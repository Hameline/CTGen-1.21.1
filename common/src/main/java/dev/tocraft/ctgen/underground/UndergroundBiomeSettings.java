package dev.tocraft.ctgen.underground;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record UndergroundBiomeSettings(List<UndergroundZone> zones) {
    public static final Codec<UndergroundBiomeSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(UndergroundZone.CODEC).fieldOf("underground_biomes").forGetter(UndergroundBiomeSettings::zones)
    ).apply(instance, instance.stable(UndergroundBiomeSettings::new)));

    @Nullable
    public Holder<Biome> getBiome(int surfaceColor, int blockX, int blockY, int blockZ, java.util.function.Function<Integer, net.minecraft.world.level.levelgen.synth.SimplexNoise> noiseSupplier) {
        for (UndergroundZone zone : zones) {
            if (zone.appliesAt(surfaceColor, blockY)) {
                double frequency = 1.0 / zone.blobScale();
                net.minecraft.world.level.levelgen.synth.SimplexNoise noise = noiseSupplier.apply(zone.blobScale());

                // same anti-elongation technique as surface blobs
                double angle1 = blockX * frequency;
                double angle2 = blockZ * frequency;
                double rotX = (blockX + blockZ) * frequency * 0.7071;
                double rotZ = (blockZ - blockX) * frequency * 0.7071;
                double noise1 = noise.getValue(angle1, angle2);
                double noise2 = noise.getValue(rotX + 100, rotZ + 100);
                double combined = (noise1 + noise2) * 0.5;

                Holder<Biome> biome = zone.getBiomeForNoise(combined);
                if (biome != null) return biome;
            }
        }
        return null;
    }
}