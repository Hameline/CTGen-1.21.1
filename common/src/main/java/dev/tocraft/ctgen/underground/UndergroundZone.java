package dev.tocraft.ctgen.underground;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.util.Codecs;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record UndergroundZone(
        List<Integer> surfaceColors,
        int depthMin,
        int depthMax,
        List<UndergroundBiomeEntry> biomes,
        int blobScale
) {
    public static final Codec<UndergroundZone> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(Codecs.COLOR).fieldOf("surface_colors").forGetter(UndergroundZone::surfaceColors),
            Codec.INT.fieldOf("depth_min").forGetter(UndergroundZone::depthMin),
            Codec.INT.fieldOf("depth_max").forGetter(UndergroundZone::depthMax),
            Codec.list(UndergroundBiomeEntry.CODEC).fieldOf("biomes").forGetter(UndergroundZone::biomes),
            Codec.INT.optionalFieldOf("blob_scale", 1000).forGetter(UndergroundZone::blobScale)
    ).apply(instance, instance.stable(UndergroundZone::new)));

    /**
     * Returns the biome for a given noise value between -1 and 1.
     */
    @Nullable
    public Holder<Biome> getBiomeForNoise(double noise) {
        if (biomes.isEmpty()) return null;
        if (biomes.size() == 1) return biomes.get(0).biome();

        double normalized = (noise + 1.0) / 2.0;
        double totalWeight = biomes.stream().mapToDouble(UndergroundBiomeEntry::weight).sum();

        double cumulative = 0;
        for (UndergroundBiomeEntry entry : biomes) {
            cumulative += entry.weight() / totalWeight;
            if (normalized <= cumulative) {
                return entry.biome();
            }
        }

        return biomes.get(biomes.size() - 1).biome();
    }

    /**
     * Returns true if this zone applies at the given surface color and Y level.
     */
    public boolean appliesAt(int surfaceColor, int blockY) {
        return blockY >= depthMin && blockY <= depthMax &&
                surfaceColors.contains(surfaceColor);
    }
}