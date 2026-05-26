package dev.tocraft.ctgen.roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

public record Road(String type, int yLevel, int transition, List<Waypoint> waypoints) {
    public static final Codec<Road> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("type").forGetter(Road::type),
            Codec.INT.fieldOf("y_level").forGetter(Road::yLevel),
            Codec.INT.optionalFieldOf("transition", 8).forGetter(Road::transition),
            Codec.list(Waypoint.CODEC).fieldOf("waypoints").forGetter(Road::waypoints)
    ).apply(instance, Road::new));
}