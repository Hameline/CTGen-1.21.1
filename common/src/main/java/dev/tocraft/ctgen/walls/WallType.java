package dev.tocraft.ctgen.walls;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.roads.Waypoint;

import java.util.List;
import java.util.Optional;

public record WallType(
        int baseWidth,
        List<WallSection> sections,
        List<Waypoint> waypoints,
        Optional<WallBattlement> battlement
) {
    public static final Codec<WallType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("base_width", 48).forGetter(WallType::baseWidth),
            Codec.list(WallSection.CODEC).fieldOf("sections").forGetter(WallType::sections),
            Codec.list(Waypoint.CODEC).fieldOf("waypoints").forGetter(WallType::waypoints),
            WallBattlement.CODEC.optionalFieldOf("battlement").forGetter(WallType::battlement)
    ).apply(instance, instance.stable(WallType::new)));
}