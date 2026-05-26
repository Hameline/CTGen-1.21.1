package dev.tocraft.ctgen.zone;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class ZoneBuilder {
    private final List<Zone.BiomeEntry> biomes = new ArrayList<>();
    private int color;
    private double terrainModifier = Zone.DEFAULT_TERRAIN_MODIFIER;
    private double pixelWeight = Zone.DEFAULT_PIXEL_WEIGHT;
    private int blobScale = Zone.DEFAULT_BLOB_SCALE;

    /**
     * Add a biome with a weight. Weights are relative — they don't need to sum to 1.
     */
    public ZoneBuilder addBiome(Holder<Biome> biome, double weight) {
        this.biomes.add(new Zone.BiomeEntry(biome, weight));
        return this;
    }

    /**
     * Convenience method for a single biome zone.
     */
    public ZoneBuilder setBiome(Holder<Biome> biome) {
        this.biomes.clear();
        this.biomes.add(new Zone.BiomeEntry(biome, 1.0));
        return this;
    }

    public ZoneBuilder setColor(int color) {
        this.color = color;
        return this;
    }

    public ZoneBuilder setColor(@NotNull Color color) {
        this.color = color.getRGB();
        return this;
    }

    public ZoneBuilder setTerrainModifier(double terrainModifier) {
        this.terrainModifier = terrainModifier;
        return this;
    }

    public ZoneBuilder setPixelWeight(double pixelWeight) {
        this.pixelWeight = pixelWeight;
        return this;
    }

    public ZoneBuilder setBlobScale(int blobScale) {
        this.blobScale = blobScale;
        return this;
    }

    public Zone build() {
        return new Zone(biomes, color, terrainModifier, pixelWeight, blobScale);
    }
}