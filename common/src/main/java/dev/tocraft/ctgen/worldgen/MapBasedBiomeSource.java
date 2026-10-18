package dev.tocraft.ctgen.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.CTerrainGeneration;
import dev.tocraft.ctgen.rivers.RiverGenerator;
import dev.tocraft.ctgen.rivers.RiverNetworkLoader;
import dev.tocraft.ctgen.underground.UndergroundBiomeLoader;
import dev.tocraft.ctgen.worldgen.terrain.CTGenTerrain;
import it.unimi.dsi.fastutil.HashCommon;
import dev.tocraft.ctgen.zone.Zone;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class MapBasedBiomeSource extends BiomeSource {
    public static final ResourceLocation ID = CTerrainGeneration.id("map_based_biome_source");
    public static final MapCodec<MapBasedBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) -> instance.group(
            MapSettings.CODEC.fieldOf("settings").forGetter(o -> o.settings)
    ).apply(instance, instance.stable(MapBasedBiomeSource::new)));

    final MapSettings settings;

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

        Stream<Holder<Biome>> undergroundBiomes = UndergroundBiomeLoader.getSettings()
                .map(s -> s.zones().stream()
                        .flatMap(zone -> zone.biomes().stream())
                        .map(entry -> entry.biome()))
                .orElse(Stream.empty());

        Stream<Holder<Biome>> riverBiomes = RiverNetworkLoader.getNetwork()
                .map(network -> network.rivers().stream()
                        .flatMap(river -> river.type().biome().stream()))
                .orElse(Stream.empty());

        return Stream.concat(Stream.concat(surfaceBiomes, undergroundBiomes), riverBiomes).distinct();
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
            return UndergroundBiomeLoader.getSettings()
                    .map(s -> {
                        int surfaceColor = getSurfaceColor(pX, pZ);
                        return s.getBiome(surfaceColor, blockX, blockY, blockZ, this::getUndergroundNoise);
                    })
                    .filter(biome -> biome != null)
                    .orElseGet(() -> settings.getBiome(blockX, blockZ));
        }

        // the surface biome doesn't depend on y, but is asked for once per 4 blocks of height
        long key = ((long) pX << 32) | (pZ & 0xFFFFFFFFL);
        int slot = (int) HashCommon.mix(key) & (SURFACE_CACHE_SIZE - 1);
        int epoch = CTGenTerrain.epoch();
        SurfaceBiome cached = surfaceCache[slot];
        if (cached != null && cached.key == key && cached.epoch == epoch) {
            return cached.biome;
        }
        Holder<Biome> biome = computeSurfaceBiome(blockX, blockZ);
        surfaceCache[slot] = new SurfaceBiome(key, epoch, biome);
        return biome;
    }

    private record SurfaceBiome(long key, int epoch, Holder<Biome> biome) {
    }

    private static final int SURFACE_CACHE_SIZE = 4096;
    // direct-mapped, immutable entries: safe to share between worldgen threads without locking
    private final SurfaceBiome[] surfaceCache = new SurfaceBiome[SURFACE_CACHE_SIZE];

    private Holder<Biome> computeSurfaceBiome(int blockX, int blockZ) {
        // check if inside a river using meander-displaced spline points
        var riverNetwork = RiverNetworkLoader.getNetwork();
        if (riverNetwork.isPresent()) {
            for (var river : riverNetwork.get().rivers()) {
                if (river.type().biome().isEmpty()) continue;
                double halfWidth = river.type().width() / 2.0;
                if (!RiverGenerator.isNearRiver(river, blockX, blockZ, halfWidth)) continue;
                if (RiverGenerator.distanceToRiver(river, blockX, blockZ, halfWidth) < halfWidth) {
                    return river.type().biome().get();
                }
            }
        }

        return settings.getBiome(blockX, blockZ);
    }

    private int getSurfaceColor(int noiseX, int noiseZ) {
        int pixelX = settings.xOffset(noiseX);
        int pixelY = settings.yOffset(noiseZ);
        java.awt.image.BufferedImage map = settings.getMapImage();
        if (map != null && pixelX >= 0 && pixelX < map.getWidth() && pixelY >= 0 && pixelY < map.getHeight()) {
            return map.getRGB(pixelX, pixelY);
        }
        return 0;
    }
}