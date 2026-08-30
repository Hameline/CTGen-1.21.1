package dev.tocraft.ctgen.cities.schem;

import net.minecraft.nbt.CompoundTag;

/**
 * One entity from a v3 schematic (mobs, item frames, etc. — distinct from
 * {@link BlockEntityEntry}), in schem-local block coordinates. {@link #nbt} is a ready-to-load
 * entity tag (has {@code id} set to the entity type's registry name).
 */
public record EntityEntry(double x, double y, double z, CompoundTag nbt) {
}
