package dev.tocraft.ctgen.cities.schem;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a cache file built by {@link SchemCompiler} and gives {@link dev.tocraft.ctgen.cities.CityPlacer}
 * true random access to it during chunk generation: the (large) raw block grid is memory-mapped
 * read-only so the OS page cache does the work and the JVM heap never holds the whole schematic,
 * while the (small) block-entity/entity lists are read once at open time and handed back as flat
 * lists — still in schem-local coordinates, since only the caller (which knows where this
 * particular instance is actually being placed) can turn those into world-chunk buckets.
 */
public final class SchemCache implements AutoCloseable {
    private static final int SECTION_SIZE = 16;
    private static final int BLOCKS_PER_SECTION = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final MappedByteBuffer grid;

    private final int width, height, length;
    private final int offsetX, offsetY, offsetZ;
    private final int bytesPerIndex;
    private final BlockState[] palette;
    private final int sectionsX, sectionsY, sectionsZ;

    private final List<BlockEntityEntry> blockEntities;
    private final List<EntityEntry> entities;

    private SchemCache(RandomAccessFile raf, FileChannel channel, MappedByteBuffer grid,
                        int width, int height, int length, int offsetX, int offsetY, int offsetZ,
                        int bytesPerIndex, BlockState[] palette,
                        List<BlockEntityEntry> blockEntities,
                        List<EntityEntry> entities) {
        this.raf = raf;
        this.channel = channel;
        this.grid = grid;
        this.width = width;
        this.height = height;
        this.length = length;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.bytesPerIndex = bytesPerIndex;
        this.palette = palette;
        this.sectionsX = (width + SECTION_SIZE - 1) / SECTION_SIZE;
        this.sectionsY = (height + SECTION_SIZE - 1) / SECTION_SIZE;
        this.sectionsZ = (length + SECTION_SIZE - 1) / SECTION_SIZE;
        this.blockEntities = blockEntities;
        this.entities = entities;
    }

    public static SchemCache open(Path cacheFile) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(cacheFile.toFile(), "r");
        try {
            raf.readUTF(); // magic — already validated by SchemCompiler.compileIfNeeded
            raf.readInt(); // format version
            raf.readLong(); // source hash

            int width = raf.readInt();
            int height = raf.readInt();
            int length = raf.readInt();
            int offsetX = raf.readInt();
            int offsetY = raf.readInt();
            int offsetZ = raf.readInt();
            int bytesPerIndex = raf.readByte();
            int paletteSize = raf.readInt();

            BlockState[] palette = new BlockState[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = SchemReader.parseBlockState(raf.readUTF());
            }

            long gridByteLength = raf.readLong();
            long gridStart = raf.getFilePointer();

            FileChannel channel = raf.getChannel();
            MappedByteBuffer grid = channel.map(FileChannel.MapMode.READ_ONLY, gridStart, gridByteLength);

            raf.seek(gridStart + gridByteLength);
            CompoundTag footer = NbtIo.read(raf, net.minecraft.nbt.NbtAccounter.unlimitedHeap());

            List<BlockEntityEntry> blockEntities = new ArrayList<>();
            for (Tag t : footer.getList("BlockEntities", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) t;
                blockEntities.add(new BlockEntityEntry(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"), entry.getCompound("nbt")));
            }

            List<EntityEntry> entities = new ArrayList<>();
            for (Tag t : footer.getList("Entities", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) t;
                entities.add(new EntityEntry(entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"), entry.getCompound("nbt")));
            }

            return new SchemCache(raf, channel, grid, width, height, length, offsetX, offsetY, offsetZ,
                    bytesPerIndex, palette, blockEntities, entities);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int length() {
        return length;
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    public int offsetZ() {
        return offsetZ;
    }

    /**
     * Reads the block at schem-local coordinates. Out-of-bounds coordinates return {@code null}
     * (the caller's AABB check should normally prevent this, but it's cheap to guard).
     */
    public BlockState getBlock(int localX, int localY, int localZ) {
        if (localX < 0 || localX >= width || localY < 0 || localY >= height || localZ < 0 || localZ >= length) {
            return null;
        }

        int secX = localX / SECTION_SIZE, withinX = localX % SECTION_SIZE;
        int secY = localY / SECTION_SIZE, withinY = localY % SECTION_SIZE;
        int secZ = localZ / SECTION_SIZE, withinZ = localZ % SECTION_SIZE;

        long sectionIndex = ((long) secX * sectionsZ + secZ) * sectionsY + secY;
        int withinSectionIndex = (withinY * SECTION_SIZE + withinZ) * SECTION_SIZE + withinX;
        long blockIndex = sectionIndex * BLOCKS_PER_SECTION + withinSectionIndex;
        int byteOffset = (int) (blockIndex * bytesPerIndex);

        int paletteIndex = bytesPerIndex == 2 ? (grid.getShort(byteOffset) & 0xFFFF) : grid.getInt(byteOffset);
        if (paletteIndex < 0 || paletteIndex >= palette.length) return null;
        return palette[paletteIndex];
    }

    /**
     * All block entities in the schematic, in schem-local coordinates. Small (thousands, not
     * hundreds of millions) — the caller buckets these by world chunk once, at placement-offset
     * resolution time (see {@link dev.tocraft.ctgen.cities.CityPlacer}).
     */
    public List<BlockEntityEntry> blockEntities() {
        return blockEntities;
    }

    /**
     * All entities in the schematic (v3 only — empty for v2), in schem-local coordinates.
     */
    public List<EntityEntry> entities() {
        return entities;
    }

    @Override
    public void close() throws IOException {
        channel.close();
        raf.close();
    }
}
