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
    private double cliffChance = Zone.DEFAULT_CLIFF_CHANCE;
    private int cliffMinHeight = Zone.DEFAULT_CLIFF_MIN_HEIGHT;
    private double cliffJaggedness = Zone.DEFAULT_CLIFF_JAGGEDNESS;
    private boolean isMountain = Zone.DEFAULT_IS_MOUNTAIN;

    public ZoneBuilder addBiome(Holder<Biome> biome, double weight) {
        this.biomes.add(new Zone.BiomeEntry(biome, weight));
        return this;
    }

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

    public ZoneBuilder setCliffChance(double cliffChance) {
        this.cliffChance = cliffChance;
        return this;
    }

    public ZoneBuilder setCliffMinHeight(int cliffMinHeight) {
        this.cliffMinHeight = cliffMinHeight;
        return this;
    }

    public ZoneBuilder setCliffJaggedness(double cliffJaggedness) {
        this.cliffJaggedness = cliffJaggedness;
        return this;
    }

    public ZoneBuilder setMountain(boolean isMountain) {
        this.isMountain = isMountain;
        return this;
    }

    public Zone build() {
        return new Zone(biomes, color, terrainModifier, pixelWeight, blobScale,
                cliffChance, cliffMinHeight, cliffJaggedness, isMountain);
    }
}