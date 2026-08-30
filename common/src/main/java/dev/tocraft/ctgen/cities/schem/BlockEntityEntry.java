package dev.tocraft.ctgen.cities.schem;

import net.minecraft.nbt.CompoundTag;

/**
 * One block entity from a schematic, in schem-local block coordinates (not yet offset
 * into world space — that happens at placement time). {@link #nbt} is a ready-to-load
 * block entity tag (has {@code id} set to the block entity's registry name).
 */
public record BlockEntityEntry(int x, int y, int z, CompoundTag nbt) {
}
