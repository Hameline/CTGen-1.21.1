package dev.tocraft.ctgen.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.util.Codecs;

public record MapWaypoint(
        String name,
        int x,
        int z,
        int innerColor,
        int outerColor,
        float minZoom,
        float maxZoom,
        float textMinZoom,
        float textMaxZoom
) {
    public static final Codec<MapWaypoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(MapWaypoint::name),
            Codec.INT.fieldOf("x").forGetter(MapWaypoint::x),
            Codec.INT.fieldOf("z").forGetter(MapWaypoint::z),
            Codecs.COLOR.fieldOf("inner_color").forGetter(MapWaypoint::innerColor),
            Codecs.COLOR.fieldOf("outer_color").forGetter(MapWaypoint::outerColor),
            Codec.FLOAT.optionalFieldOf("min_zoom", 0.5f).forGetter(MapWaypoint::minZoom),
            Codec.FLOAT.optionalFieldOf("max_zoom", -1f).forGetter(MapWaypoint::maxZoom),
            Codec.FLOAT.optionalFieldOf("text_min_zoom", 0.5f).forGetter(MapWaypoint::textMinZoom),
            Codec.FLOAT.optionalFieldOf("text_max_zoom", -1f).forGetter(MapWaypoint::textMaxZoom)
    ).apply(instance, instance.stable(MapWaypoint::new)));
}