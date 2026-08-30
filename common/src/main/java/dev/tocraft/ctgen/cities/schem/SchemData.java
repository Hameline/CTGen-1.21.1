package dev.tocraft.ctgen.cities.schem;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A fully-parsed Sponge Schematic (v2 or v3), still in the file's own native layout —
 * an intermediate form consumed exactly once by {@link SchemCompiler} to build the
 * random-access on-disk cache ({@link SchemCache}) that chunk generation actually reads from.
 * <p>
 * {@link #blockData} is the raw varint-packed palette-index stream as stored in the file —
 * {@code width * height * length} entries once decoded, in Y-major/Z/X order (index into the
 * decoded stream is {@code (y * length + z) * width + x}), which is why it can only be consumed
 * sequentially from the start; there is no random access into it directly.
 */
public record SchemData(
        int version,
        int width,
        int height,
        int length,
        int offsetX,
        int offsetY,
        int offsetZ,
        BlockState[] palette,
        String[] paletteStrings,
        byte[] blockData,
        List<BlockEntityEntry> blockEntities,
        List<EntityEntry> entities
) {
}
