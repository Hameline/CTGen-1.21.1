package dev.tocraft.ctgen.roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

public record Road(
        String type,
        List<Waypoint> waypoints,
        int transition,
        int yLevel,
        float minZoom,
        float maxZoom
) {
    public static final Codec<Road> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("type").forGetter(Road::type),
            Codec.list(Waypoint.CODEC).fieldOf("waypoints").forGetter(Road::waypoints),
            Codec.INT.optionalFieldOf("transition", 4).forGetter(Road::transition),
            Codec.INT.optionalFieldOf("y_level", 64).forGetter(Road::yLevel),
            Codec.FLOAT.optionalFieldOf("min_zoom", 0.0f).forGetter(Road::minZoom),
            Codec.FLOAT.optionalFieldOf("max_zoom", -1f).forGetter(Road::maxZoom)
    ).apply(instance, instance.stable(Road::new)));
}