package dev.tocraft.ctgen.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.CTerrainGeneration;
import dev.tocraft.ctgen.underground.UndergroundBiomeLoader;
import dev.tocraft.ctgen.zone.Zone;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class MapBasedBiomeSource extends BiomeSource {
    public static final ResourceLocation ID = CTerrainGeneration.id("map_based_biome_source");
    public static final MapCodec<MapBasedBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) -> instance.group(
            MapSettings.CODEC.fieldOf("settings").forGetter(o -> o.settings)
    ).apply(instance, instance.stable(MapBasedBiomeSource::new)));

    final MapSettings settings;

    // blob noise cache for underground biomes — separate seeds from surface
    private final Map<Integer, SimplexNoise> undergroundNoiseCache = new ConcurrentHashMap<>();

    public MapBasedBiomeSource(MapSettings settings) {
        this.settings = settings;
    }

    @Override
    protected @NotNull MapCodec<MapBasedBiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected @NotNull Stream<Holder<Biome>> collectPossibleBiomes() {
        Stream<Holder<Biome>> surfaceBiomes = settings.zones.stream()
                .flatMap(zoneHolder -> zoneHolder.value().biomes().stream())
                .map(Zone.BiomeEntry::biome);

        // include underground biomes if loaded
        Stream<Holder<Biome>> undergroundBiomes = UndergroundBiomeLoader.getSettings()
                .map(s -> s.zones().stream()
                        .flatMap(zone -> zone.biomes().stream())
                        .map(entry -> entry.biome()))
                .orElse(Stream.empty());

        return Stream.concat(surfaceBiomes, undergroundBiomes).distinct();
    }

    private SimplexNoise getUndergroundNoise(int blobScale) {
        return undergroundNoiseCache.computeIfAbsent(blobScale,
                s -> new SimplexNoise(new LegacyRandomSource(112233445L + s * 77777L)));
    }

    @Override
    public @NotNull Holder<Biome> getNoiseBiome(int pX, int pY, int pZ, Climate.@NotNull Sampler pSampler) {
        int blockX = pX * 4;
        int blockY = pY * 4;
        int blockZ = pZ * 4;

        if (blockY < 0) {
            // underground — check if underground biome settings exist
            return UndergroundBiomeLoader.getSettings()
                    .map(s -> {
                        // get the surface color at this position
                        int surfaceColor = getSurfaceColor(pX, pZ);
                        return s.getBiome(surfaceColor, blockX, blockY, blockZ, this::getUndergroundNoise);
                    })
                    .filter(biome -> biome != null)
                    .orElseGet(() -> settings.getBiome(blockX, blockZ));
        }

        return settings.getBiome(blockX, blockZ);
    }

    /**
     * Gets the surface map color at noise coordinates.
     * Used to determine which underground biomes can spawn at this position.
     */
    private int getSurfaceColor(int noiseX, int noiseZ) {
        int blockX = noiseX * 4;
        int blockZ = noiseZ * 4;
        // use xOffset/yOffset to get the map pixel color
        int pixelX = settings.xOffset(noiseX);
        int pixelY = settings.yOffset(noiseZ);
        java.awt.image.BufferedImage map = settings.getMapImage();
        if (map != null && pixelX >= 0 && pixelX < map.getWidth() && pixelY >= 0 && pixelY < map.getHeight()) {
            return map.getRGB(pixelX, pixelY);
        }
        return 0;
    }
}