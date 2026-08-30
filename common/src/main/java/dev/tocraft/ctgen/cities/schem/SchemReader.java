package dev.tocraft.ctgen.cities.schem;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Sponge Schematic (v2 or v3) file into a {@link SchemData}. This is intentionally
 * the ONLY place in CTGen that touches the raw {@code .schem} NBT — everything downstream
 * ({@link SchemCompiler}, {@link SchemCache}) works off the already-parsed form.
 * <p>
 * <b>Needs validation against a real {@code .schem} fixture</b> (both v2 and v3) before being
 * trusted against a large production file — the exact key layout of the {@code BlockEntities}/
 * {@code Entities} compounds below (in particular whether extra NBT sits under a nested
 * {@code Data} key or inline as sibling fields) is written from the published Sponge Schematic
 * Specification, not verified against a real reference file.
 */
public final class SchemReader {
    private SchemReader() {
    }

    public static SchemData read(Path schemFile) throws IOException {
        try (InputStream in = Files.newInputStream(schemFile)) {
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            return read(root);
        }
    }

    private static SchemData read(CompoundTag root) {
        int version = root.getInt("Version");

        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;

        int offsetX = 0, offsetY = 0, offsetZ = 0;
        if (root.contains("Offset")) {
            int[] offset = root.getIntArray("Offset");
            if (offset.length == 3) {
                offsetX = offset[0];
                offsetY = offset[1];
                offsetZ = offset[2];
            }
        }

        CompoundTag palette;
        byte[] blockData;
        List<BlockEntityEntry> blockEntities;

        if (version >= 3) {
            CompoundTag blocks = root.getCompound("Blocks");
            palette = blocks.getCompound("Palette");
            blockData = blocks.getByteArray("Data");
            blockEntities = readBlockEntities(blocks.getList("BlockEntities", Tag.TAG_COMPOUND));
        } else {
            palette = root.getCompound("Palette");
            blockData = root.getByteArray("BlockData");
            blockEntities = readBlockEntities(root.getList("BlockEntities", Tag.TAG_COMPOUND));
        }

        String[] paletteStrings = resolvePaletteStrings(palette);
        BlockState[] paletteStates = new BlockState[paletteStrings.length];
        for (int i = 0; i < paletteStrings.length; i++) {
            if (paletteStrings[i] != null) {
                paletteStates[i] = parseBlockState(paletteStrings[i]);
            }
        }
        List<EntityEntry> entities = readEntities(root.getList("Entities", Tag.TAG_COMPOUND));

        return new SchemData(version, width, height, length, offsetX, offsetY, offsetZ,
                paletteStates, paletteStrings, blockData, blockEntities, entities);
    }

    private static String[] resolvePaletteStrings(CompoundTag palette) {
        int size = palette.size();
        String[] strings = new String[size];
        for (String key : palette.getAllKeys()) {
            int id = palette.getInt(key);
            if (id < 0 || id >= size) {
                // sparse/non-contiguous ids are not expected per spec, but don't crash on one
                LogUtils.getLogger().warn("City schematic palette entry {} has out-of-range id {} (palette size {})", key, id, size);
                continue;
            }
            strings[id] = key;
        }
        return strings;
    }

    static BlockState parseBlockState(String blockStateString) {
        try {
            StringReader reader = new StringReader(blockStateString);
            BlockStateParser.BlockResult result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), reader, false);
            return result.blockState();
        } catch (CommandSyntaxException e) {
            LogUtils.getLogger().error("Failed to parse city schematic palette entry '{}', substituting air", blockStateString, e);
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
    }

    private static List<BlockEntityEntry> readBlockEntities(ListTag list) {
        List<BlockEntityEntry> result = new ArrayList<>(list.size());
        for (Tag t : list) {
            if (!(t instanceof CompoundTag entry)) continue;
            int[] pos = entry.getIntArray("Pos");
            if (pos.length != 3) continue;
            String id = entry.getString("Id");

            CompoundTag data = entry.contains("Data", Tag.TAG_COMPOUND) ? entry.getCompound("Data").copy() : entry.copy();
            data.remove("Pos");
            data.remove("Id");
            data.putString("id", id);

            result.add(new BlockEntityEntry(pos[0], pos[1], pos[2], data));
        }
        return result;
    }

    private static List<EntityEntry> readEntities(ListTag list) {
        List<EntityEntry> result = new ArrayList<>(list.size());
        for (Tag t : list) {
            if (!(t instanceof CompoundTag entry)) continue;
            String id = entry.getString("Id");

            double[] pos = readDoubleList(entry.getList("Pos", Tag.TAG_DOUBLE));
            if (pos.length != 3) continue;

            CompoundTag data = entry.contains("Data", Tag.TAG_COMPOUND) ? entry.getCompound("Data").copy() : entry.copy();
            data.remove("Pos");
            data.remove("Id");
            data.putString("id", id);

            result.add(new EntityEntry(pos[0], pos[1], pos[2], data));
        }
        return result;
    }

    private static double[] readDoubleList(ListTag list) {
        double[] result = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof DoubleTag d) {
                result[i] = d.getAsDouble();
            }
        }
        return result;
    }
}
