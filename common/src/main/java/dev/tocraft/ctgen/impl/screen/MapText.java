package dev.tocraft.ctgen.impl.screen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

public record MapText(int x,
                      int y,
                      float size,
                      float minZoom,
                      float maxZoom,
                      float fadeRange,
                      float rotation,
                      Component text
) {
    public static final Codec<MapText> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(MapText::x),
            Codec.INT.fieldOf("y").forGetter(MapText::y),
            Codec.FLOAT.optionalFieldOf("size", 50f).forGetter(MapText::size),
            Codec.FLOAT.fieldOf("min_zoom").orElse(-1f).forGetter(MapText::minZoom),
            Codec.FLOAT.fieldOf("max_zoom").orElse(-1f).forGetter(MapText::maxZoom),
            Codec.FLOAT.optionalFieldOf("fade_range", 0.5f).forGetter(MapText::fadeRange),
            Codec.FLOAT.fieldOf("rotation").orElse(0f).forGetter(MapText::rotation),
            ComponentSerialization.CODEC.fieldOf("text").forGetter(MapText::text)
    ).apply(instance, MapText::new));

    /**
     * Calculates the opacity of the text based on the current zoom level.
     * Returns a value between 0.0 (fully transparent) and 1.0 (fully opaque).
     */
    public float getOpacity(double readableZoom) {
        float opacity = 1.0f;

        // fade in near minZoom
        if (minZoom != -1f && readableZoom - minZoom < fadeRange) {
            opacity = Math.min(opacity, (float) (readableZoom - minZoom) / fadeRange);
        }

        // fade out near maxZoom
        if (maxZoom != -1f && maxZoom - readableZoom < fadeRange) {
            opacity = Math.min(opacity, (float) (maxZoom - readableZoom) / fadeRange);
        }

        return Math.max(0f, Math.min(1f, opacity));
    }
}