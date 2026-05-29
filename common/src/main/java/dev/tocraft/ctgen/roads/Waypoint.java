package dev.tocraft.ctgen.roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record Waypoint(int x, int z, float curve) {
    public static final Codec<Waypoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(Waypoint::x),
            Codec.INT.fieldOf("z").forGetter(Waypoint::z),
            Codec.FLOAT.optionalFieldOf("curve", 0.0f).forGetter(Waypoint::curve)
    ).apply(instance, Waypoint::new));
}