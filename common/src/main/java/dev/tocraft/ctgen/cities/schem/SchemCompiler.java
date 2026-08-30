package dev.tocraft.ctgen.cities.schem;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Compiles a raw {@code .schem} file into CTGen's own random-access cache format, once. A
 * schem's block data is a single sequentially-decoded varint stream (see {@link SchemData}) —
 * this pass decodes it exactly once and writes it back out in <b>chunk-section-major order</b>
 * (one 16x16x16 section at a time, sections grouped by X/Z column so a whole chunk's vertical
 * column of sections is contiguous on disk) as fixed-width palette indices, so
 * {@link SchemCache} can later {@code mmap} the result and read any single chunk's blocks as one
 * contiguous slice, without ever re-parsing the source NBT or holding the whole grid on the heap.
 * <p>
 * Re-compiling is skipped whenever a cache already exists whose recorded content digest still
 * matches the source (see {@link #isCacheValid}) — the schematic itself ships as a datapack
 * resource inside the consumer mod, so unlike a plain file on disk there's no size/mtime to check
 * cheaply; the caller ({@link dev.tocraft.ctgen.cities.CityPlacer}) computes that digest while
 * extracting the resource out to a real, mmap-able file, which is a cheap byproduct of the copy
 * it already has to do. Either way this is expected to run once ever per schematic content, not
 * on every reload.
 */
public final class SchemCompiler {
    private static final String MAGIC = "CTGC";
    private static final int FORMAT_VERSION = 1;
    private static final int SECTION_SIZE = 16;
    private static final int BLOCKS_PER_SECTION = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private SchemCompiler() {
    }

    /**
     * Returns the path to a valid, up-to-date cache file for {@code id} under {@code cacheDir},
     * compiling {@code stagedSchemFile} first if no cache exists yet or {@code digest} (as
     * computed by the caller from the schematic's actual content) doesn't match what's cached.
     */
    public static Path compileIfNeeded(Path stagedSchemFile, long digest, ResourceLocation id, Path cacheDir) throws IOException {
        Files.createDirectories(cacheDir);
        Path cacheFile = cacheDir.resolve(cacheFileName(id));

        if (isCacheValid(cacheFile, digest)) {
            return cacheFile;
        }

        LogUtils.getLogger().info("Compiling city schematic {}... this only happens once per schematic", id);
        long start = System.currentTimeMillis();
        SchemData data = SchemReader.read(stagedSchemFile);
        compile(data, cacheFile, digest);
        LogUtils.getLogger().info("Compiled city schematic {} ({} x {} x {}, {} blocks) in {} ms",
                id, data.width(), data.height(), data.length(),
                (long) data.width() * data.height() * data.length(), System.currentTimeMillis() - start);
        return cacheFile;
    }

    private static String cacheFileName(ResourceLocation id) {
        return id.getNamespace() + "_" + id.getPath().replace('/', '_') + ".cgcache";
    }

    private static boolean isCacheValid(Path cacheFile, long expectedDigest) {
        if (!Files.exists(cacheFile)) return false;
        try (RandomAccessFile raf = new RandomAccessFile(cacheFile.toFile(), "r")) {
            if (!MAGIC.equals(raf.readUTF())) return false;
            if (raf.readInt() != FORMAT_VERSION) return false;
            return raf.readLong() == expectedDigest;
        } catch (IOException e) {
            return false;
        }
    }

    private static void compile(SchemData data, Path cacheFile, long digest) throws IOException {
        int width = data.width(), height = data.height(), length = data.length();
        long totalBlocks = (long) width * height * length;

        int paletteSize = data.paletteStrings().length;
        int bytesPerIndex = paletteSize > 0xFFFF ? 4 : 2;
        long gridByteLength = totalBlocks * bytesPerIndex;

        // a single mapping is capped at Integer.MAX_VALUE bytes — comfortably covers the
        // 266M-block case this was built for (~532MB at 2 bytes/index), but an even larger
        // future schematic would need the grid split across multiple mapped slabs instead
        if (gridByteLength > Integer.MAX_VALUE - 64) {
            throw new IOException("City schematic is too large for a single cache mapping (" +
                    gridByteLength + " bytes) — needs multi-slab support, not yet implemented");
        }

        int sectionsX = (width + SECTION_SIZE - 1) / SECTION_SIZE;
        int sectionsY = (height + SECTION_SIZE - 1) / SECTION_SIZE;
        int sectionsZ = (length + SECTION_SIZE - 1) / SECTION_SIZE;

        Path tmpFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        Files.deleteIfExists(tmpFile);

        try (RandomAccessFile raf = new RandomAccessFile(tmpFile.toFile(), "rw")) {
            raf.writeUTF(MAGIC);
            raf.writeInt(FORMAT_VERSION);
            raf.writeLong(digest);
            raf.writeInt(width);
            raf.writeInt(height);
            raf.writeInt(length);
            raf.writeInt(data.offsetX());
            raf.writeInt(data.offsetY());
            raf.writeInt(data.offsetZ());
            raf.writeByte(bytesPerIndex);
            raf.writeInt(paletteSize);
            for (String s : data.paletteStrings()) {
                raf.writeUTF(s != null ? s : "minecraft:air");
            }
            raf.writeLong(gridByteLength);

            long gridStart = raf.getFilePointer();
            raf.setLength(gridStart + gridByteLength);

            FileChannel channel = raf.getChannel();
            MappedByteBuffer grid = channel.map(FileChannel.MapMode.READ_WRITE, gridStart, gridByteLength);
            writeBlockGrid(grid, data, sectionsY, sectionsZ, bytesPerIndex);
            grid.force();

            raf.seek(gridStart + gridByteLength);
            NbtIo.write(buildFooter(data), raf);
        }

        Files.move(tmpFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Sequentially decodes the schem's native varint block-index stream (Y-major/Z/X order —
     * the only order it can be read in) and scatters each decoded index into its
     * chunk-section-major destination slot in the mapped output buffer.
     */
    private static void writeBlockGrid(ByteBuffer grid, SchemData data, int sectionsY, int sectionsZ, int bytesPerIndex) {
        byte[] raw = data.blockData();
        int width = data.width(), height = data.height(), length = data.length();
        int pos = 0;

        for (int y = 0; y < height; y++) {
            int secY = y / SECTION_SIZE;
            int withinY = y % SECTION_SIZE;
            for (int z = 0; z < length; z++) {
                int secZ = z / SECTION_SIZE;
                int withinZ = z % SECTION_SIZE;
                for (int x = 0; x < width; x++) {
                    int value = 0;
                    int shift = 0;
                    byte b;
                    do {
                        b = raw[pos++];
                        value |= (b & 0x7F) << shift;
                        shift += 7;
                    } while ((b & 0x80) != 0);

                    int secX = x / SECTION_SIZE;
                    int withinX = x % SECTION_SIZE;

                    long sectionIndex = ((long) secX * sectionsZ + secZ) * sectionsY + secY;
                    int withinSectionIndex = (withinY * SECTION_SIZE + withinZ) * SECTION_SIZE + withinX;
                    long blockIndex = sectionIndex * BLOCKS_PER_SECTION + withinSectionIndex;
                    int byteOffset = (int) (blockIndex * bytesPerIndex);

                    if (bytesPerIndex == 2) {
                        grid.putShort(byteOffset, (short) value);
                    } else {
                        grid.putInt(byteOffset, value);
                    }
                }
            }
        }
    }

    private static CompoundTag buildFooter(SchemData data) {
        CompoundTag footer = new CompoundTag();

        ListTag blockEntities = new ListTag();
        for (BlockEntityEntry be : data.blockEntities()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", be.x());
            entry.putInt("y", be.y());
            entry.putInt("z", be.z());
            entry.put("nbt", be.nbt());
            blockEntities.add(entry);
        }
        footer.put("BlockEntities", blockEntities);

        ListTag entities = new ListTag();
        for (EntityEntry e : data.entities()) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("x", e.x());
            entry.putDouble("y", e.y());
            entry.putDouble("z", e.z());
            entry.put("nbt", e.nbt());
            entities.add(entry);
        }
        footer.put("Entities", entities);

        return footer;
    }
}
