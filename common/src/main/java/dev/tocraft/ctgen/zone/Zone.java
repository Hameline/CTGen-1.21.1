package dev.tocraft.ctgen.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.util.Codecs;
import dev.tocraft.ctgen.xtend.CTRegistries;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

public record Zone(
        List<BiomeEntry> biomes,
        int color,
        double terrainModifier,
        double pixelWeight,
        int blobScale,
        double cliffChance,
        int cliffMinHeight,
        double cliffJaggedness,
        boolean isMountain
) {
    public static final double DEFAULT_TERRAIN_MODIFIER = 4;
    public static final double DEFAULT_PIXEL_WEIGHT = 1;
    public static final int DEFAULT_BLOB_SCALE = 2000;
    public static final double DEFAULT_CLIFF_CHANCE = 0.0;
    public static final int DEFAULT_CLIFF_MIN_HEIGHT = 0;
    public static final double DEFAULT_CLIFF_JAGGEDNESS = 0.3;
    public static final boolean DEFAULT_IS_MOUNTAIN = false;

    public record BiomeEntry(Holder<Biome> biome, double weight) {
        public static final Codec<BiomeEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Biome.CODEC.fieldOf("biome").forGetter(BiomeEntry::biome),
                Codec.DOUBLE.fieldOf("weight").forGetter(BiomeEntry::weight)
        ).apply(instance, instance.stable(BiomeEntry::new)));
    }

    private static final Codec<Zone> MODERN_CODEC = RecordCodecBuilder.create((instance) -> instance.group(
            Codec.list(BiomeEntry.CODEC).fieldOf("biomes").forGetter(Zone::biomes),
            Codecs.COLOR.fieldOf("color").forGetter(Zone::color),
            Codec.DOUBLE.optionalFieldOf("terrain_modifier", DEFAULT_TERRAIN_MODIFIER).forGetter(Zone::terrainModifier),
            Codec.DOUBLE.optionalFieldOf("pixel_weight", DEFAULT_PIXEL_WEIGHT).forGetter(Zone::pixelWeight),
            Codec.INT.optionalFieldOf("blob_scale", DEFAULT_BLOB_SCALE).forGetter(Zone::blobScale),
            Codec.DOUBLE.optionalFieldOf("cliff_chance", DEFAULT_CLIFF_CHANCE).forGetter(Zone::cliffChance),
            Codec.INT.optionalFieldOf("cliff_min_height", DEFAULT_CLIFF_MIN_HEIGHT).forGetter(Zone::cliffMinHeight),
            Codec.DOUBLE.optionalFieldOf("cliff_jaggedness", DEFAULT_CLIFF_JAGGEDNESS).forGetter(Zone::cliffJaggedness),
            Codec.BOOL.optionalFieldOf("is_mountain", DEFAULT_IS_MOUNTAIN).forGetter(Zone::isMountain)
    ).apply(instance, instance.stable(Zone::new)));

    private static final Codec<Zone> LEGACY_CODEC = RecordCodecBuilder.create((instance) -> instance.group(
            Biome.CODEC.fieldOf("biome").forGetter(zone -> zone.biomes().get(0).biome()),
            Codecs.COLOR.fieldOf("color").forGetter(Zone::color),
            Codec.DOUBLE.optionalFieldOf("terrain_modifier", DEFAULT_TERRAIN_MODIFIER).forGetter(Zone::terrainModifier),
            Codec.DOUBLE.optionalFieldOf("pixel_weight", DEFAULT_PIXEL_WEIGHT).forGetter(Zone::pixelWeight),
            Codec.INT.optionalFieldOf("blob_scale", DEFAULT_BLOB_SCALE).forGetter(Zone::blobScale)
    ).apply(instance, (biome, color, terrainModifier, pixelWeight, blobScale) ->
            new Zone(List.of(new BiomeEntry(biome, 1.0)), color, terrainModifier, pixelWeight, blobScale,
                    DEFAULT_CLIFF_CHANCE, DEFAULT_CLIFF_MIN_HEIGHT, DEFAULT_CLIFF_JAGGEDNESS, DEFAULT_IS_MOUNTAIN)));

    public static final Codec<Zone> DIRECT_CODEC = Codec.withAlternative(MODERN_CODEC, LEGACY_CODEC);

    public static RegistryFileCodec<Zone> CODEC = RegistryFileCodec.create(CTRegistries.ZONES_KEY, DIRECT_CODEC);

    public Holder<Biome> getBiomeForNoise(double noise) {
        if (biomes.isEmpty()) return null;
        if (biomes.size() == 1) return biomes.get(0).biome();

        double normalized = (noise + 1.0) / 2.0;
        double totalWeight = biomes.stream().mapToDouble(BiomeEntry::weight).sum();

        double cumulative = 0;
        for (BiomeEntry entry : biomes) {
            cumulative += entry.weight() / totalWeight;
            if (normalized <= cumulative) {
                return entry.biome();
            }
        }

        return biomes.get(biomes.size() - 1).biome();
    }

    public Holder<Biome> getPrimaryBiome() {
        return biomes.stream()
                .max((a, b) -> Double.compare(a.weight(), b.weight()))
                .map(BiomeEntry::biome)
                .orElse(null);
    }
}