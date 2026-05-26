package dev.tocraft.ctgen.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.data.BiomeImageRegistry;
import dev.tocraft.ctgen.data.HeightImageRegistry;
import dev.tocraft.ctgen.rivers.RiverNetworkLoader;
import dev.tocraft.ctgen.util.Noise;
import dev.tocraft.ctgen.zone.Zone;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class MapSettings {
    public static final Codec<MapSettings> CODEC = RecordCodecBuilder.create((instance) -> instance.group(
            ResourceLocation.CODEC.fieldOf("map_id").forGetter(o -> o.mapId),
            Codec.list(Zone.CODEC).fieldOf("zones").forGetter(o -> o.zones),
            Zone.CODEC.fieldOf("default_map_biome").forGetter(o -> o.defaultBiome),
            Codec.INT.optionalFieldOf("transition", 16).forGetter(o -> o.transition),
            Codec.INT.optionalFieldOf("scale", 1).forGetter(o -> o.scale),
            Codec.DOUBLE.optionalFieldOf("border_noise", 0.0).forGetter(o -> o.borderNoise),
            Codec.INT.optionalFieldOf("spawn_pixel_x").forGetter(o -> o.spawnX),
            Codec.INT.optionalFieldOf("spawn_pixel_y").forGetter(o -> o.spawnY),
            NoiseGeneratorSettings.CODEC.fieldOf("noise_gen_settings").forGetter(o -> o.noiseGenSettings),
            Codec.list(ResourceLocation.CODEC).optionalFieldOf("disabled_structure_sets", List.of()).forGetter(o -> o.disabledStructureSets),
            Codec.list(ResourceLocation.CODEC).optionalFieldOf("disabled_features", List.of()).forGetter(o -> o.disabledFeatures)
    ).apply(instance, instance.stable(MapSettings::new)));

    private final ResourceLocation mapId;
    final List<Holder<Zone>> zones;
    private final Holder<Zone> defaultBiome;
    final int transition;
    private final int scale;
    private final double borderNoise;
    private final Supplier<BufferedImage> mapImage;
    private final Supplier<BufferedImage> heightmap;
    final Optional<Integer> spawnX;
    final Optional<Integer> spawnY;
    public final Holder<NoiseGeneratorSettings> noiseGenSettings;

    private final List<ResourceLocation> disabledStructureSets;
    private final List<ResourceLocation> disabledFeatures;

    // border noise — domain warping for biome borders
    private final SimplexNoise warpX;
    private final SimplexNoise warpZ;
    private final SimplexNoise noiseX;
    private final SimplexNoise noiseZ;



    // blob noise — per zone biome selection, different seed from border noise
    // cached per zone blob scale so each zone gets its own noise space
    private final Map<Integer, SimplexNoise> blobNoiseCache = new ConcurrentHashMap<>();

    @ApiStatus.Internal
    public MapSettings(ResourceLocation mapId, List<Holder<Zone>> zones, Holder<Zone> defaultBiome, int transition, int scale, double borderNoise, @NotNull Optional<Integer> spawnX, @NotNull Optional<Integer> spawnY, Holder<NoiseGeneratorSettings> noiseGenSettings, List<ResourceLocation> disabledStructureSets, List<ResourceLocation> disabledFeatures) {
        this.mapId = mapId;
        this.zones = zones;
        this.defaultBiome = defaultBiome;
        this.transition = transition;
        this.scale = Math.max(1, scale);
        this.borderNoise = Math.max(0.0, borderNoise);
        this.mapImage = () -> BiomeImageRegistry.getById(mapId);
        this.heightmap = () -> HeightImageRegistry.getById(mapId);
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.noiseGenSettings = noiseGenSettings;
        this.disabledStructureSets = disabledStructureSets;
        this.disabledFeatures = disabledFeatures;

        this.warpX  = new SimplexNoise(new LegacyRandomSource(111111111L));
        this.warpZ  = new SimplexNoise(new LegacyRandomSource(222222222L));
        this.noiseX = new SimplexNoise(new LegacyRandomSource(333333333L));
        this.noiseZ = new SimplexNoise(new LegacyRandomSource(444444444L));
    }

    public List<ResourceLocation> getDisabledStructureSets() {
        return disabledStructureSets;
    }

    public List<ResourceLocation> getDisabledFeatures() {
        return disabledFeatures;
    }

    /**
     * Gets the blob noise instance for a given blob scale.
     * Each unique blob scale gets its own noise instance with a different seed
     * so zones with different scales don't correlate.
     * Blob noise uses completely different seeds from border noise.
     */
    private SimplexNoise getBlobNoise(int blobScale) {
        return blobNoiseCache.computeIfAbsent(blobScale,
                s -> new SimplexNoise(new LegacyRandomSource(987654321L + s * 31337L)));
    }

    private int[] distortCoords(int blockX, int blockZ) {
        if (borderNoise <= 0.0) {
            return new int[]{blockX, blockZ};
        }

        double pixelX = (double) blockX / (scale * 4.0);
        double pixelZ = (double) blockZ / (scale * 4.0);

        double maxDistortion = scale * 4.0 * borderNoise;

        double warpFreq = 0.2;
        double warpStrength = 1.5;
        double wx = warpX.getValue(pixelX * warpFreq + 5.2, pixelZ * warpFreq + 1.3) * warpStrength;
        double wz = warpZ.getValue(pixelX * warpFreq + 8.3, pixelZ * warpFreq + 2.8) * warpStrength;

        double warpedX = pixelX + wx;
        double warpedZ = pixelZ + wz;

        double distortX = noiseX.getValue(warpedX * 0.15, warpedZ * 0.15) * 0.55;
        double distortZ = noiseZ.getValue(warpedX * 0.15, warpedZ * 0.15) * 0.55;

        distortX += noiseX.getValue(warpedX * 0.4 + 31.7, warpedZ * 0.4 + 71.3) * 0.3;
        distortZ += noiseZ.getValue(warpedX * 0.4 + 31.7, warpedZ * 0.4 + 71.3) * 0.3;

        distortX += noiseX.getValue(warpedX * 1.1 + 57.4, warpedZ * 1.1 + 23.9) * 0.15;
        distortZ += noiseZ.getValue(warpedX * 1.1 + 57.4, warpedZ * 1.1 + 23.9) * 0.15;

        return new int[]{
                (int) (blockX + distortX * maxDistortion),
                (int) (blockZ + distortZ * maxDistortion)
        };
    }

    /**
     * Gets the zone holder at noise coordinates.
     * Applies border distortion before sampling the map image.
     */
    @NotNull
    public Holder<Zone> getZone(int pX, int pY) {
        int blockX = pX * 4;
        int blockZ = pY * 4;

        int[] distorted = distortCoords(blockX, blockZ);

        int x = xOffset(distorted[0] >> 2);
        int y = yOffset(distorted[1] >> 2);

        if (isPixelInBiomeMap(x, y)) {
            Holder<Zone> zone = getByColor(mapImage.get().getRGB(x, y));
            return zone != null ? zone : defaultBiome;
        } else {
            return defaultBiome;
        }
    }

    // the noise router already knows which biomes can generate underground
// via its density functions — we just need to declare the common ones
    public Stream<Holder<Biome>> getUndergroundBiomes(HolderGetter<Biome> biomeGetter) {
        return Stream.of(
                Biomes.DRIPSTONE_CAVES,
                Biomes.LUSH_CAVES,
                Biomes.DEEP_DARK
        ).map(biomeGetter::getOrThrow);
    }

    /**
     * Gets the actual biome at a block position, applying blob noise within the zone.
     * This is what MapBasedBiomeSource calls.
     */
    public @NotNull Holder<net.minecraft.world.level.biome.Biome> getBiome(int blockX, int blockZ) {
        Holder<Zone> zoneHolder = getZone(blockX >> 2, blockZ >> 2);
        Zone zone = zoneHolder.value();

        if (zone.biomes().size() == 1) {
            return zone.biomes().get(0).biome();
        }

        double frequency = 1.0 / zone.blobScale();
        SimplexNoise blobNoise = getBlobNoise(zone.blobScale());

        // sample at two rotated angles and average them
        // this breaks the directional bias of simplex noise
        // making blobs more circular and compact
        double angle1 = blockX * frequency;
        double angle2 = blockZ * frequency;

        // rotate 45 degrees for second sample to break elongation
        double rotX = (blockX + blockZ) * frequency * 0.7071;
        double rotZ = (blockZ - blockX) * frequency * 0.7071;

        double noise1 = blobNoise.getValue(angle1, angle2);
        double noise2 = blobNoise.getValue(rotX + 100, rotZ + 100);

        // combine both samples — average breaks elongation
        double noise = (noise1 + noise2) * 0.5;

        Holder<net.minecraft.world.level.biome.Biome> biome = zone.getBiomeForNoise(noise);
        return biome != null ? biome : zone.biomes().get(0).biome();
    }

    public double getHeight(SimplexNoise noise, int pX, int pY) {
        double genHeight = getTransitionedHeight(pX, pY);
        double addHeight = Noise.DEFAULT.getPerlin(noise, pX, pY) * getTransitionedModifier(pX, pY);
        double baseHeight = genHeight + addHeight;

        // apply river carving
        int blockX = pX * 4;
        int blockZ = pY * 4;
        double riverModifier = RiverNetworkLoader.getNetwork()
                .map(network -> network.getTerrainModifierAt(blockX, blockZ, 63))
                .orElse(0.0);

        if (riverModifier > 0) {
            // carve down — river modifier is how many blocks to subtract
            return baseHeight - riverModifier;
        }

        return baseHeight;
    }

    public double getTransitionedModifier(int x, int y) {
        int scaledTransition = transition * scale;

        int baseX = (x / scaledTransition) * scaledTransition;
        int baseY = (y / scaledTransition) * scaledTransition;

        if (x < 0) baseX -= scaledTransition;
        if (y < 0) baseY -= scaledTransition;

        double th00 = getZone(baseX >> 2, baseY >> 2).value().terrainModifier();
        double th10 = getZone((baseX + scaledTransition) >> 2, baseY >> 2).value().terrainModifier();
        double th01 = getZone(baseX >> 2, (baseY + scaledTransition) >> 2).value().terrainModifier();
        double th11 = getZone((baseX + scaledTransition) >> 2, (baseY + scaledTransition) >> 2).value().terrainModifier();

        double xPercent = Math.abs((double) (x - baseX) / scaledTransition);
        double yPercent = Math.abs((double) (y - baseY) / scaledTransition);

        return (th00 * (1 - xPercent) * (1 - yPercent)) +
                (th10 * xPercent * (1 - yPercent)) +
                (th01 * (1 - xPercent) * yPercent) +
                (th11 * xPercent * yPercent);
    }

    public double getTransitionedHeight(int x, int y) {
        int scaledTransition = transition * scale;

        int baseX = (x / scaledTransition) * scaledTransition;
        int baseY = (y / scaledTransition) * scaledTransition;

        if (x < 0) baseX -= scaledTransition;
        if (y < 0) baseY -= scaledTransition;

        int h00 = getRedHeight(baseX >> 2, baseY >> 2);
        int h10 = getRedHeight((baseX + scaledTransition) >> 2, baseY >> 2);
        int h01 = getRedHeight(baseX >> 2, (baseY + scaledTransition) >> 2);
        int h11 = getRedHeight((baseX + scaledTransition) >> 2, (baseY + scaledTransition) >> 2);

        double xPercent = Math.abs((double) (x - baseX) / scaledTransition);
        double yPercent = Math.abs((double) (y - baseY) / scaledTransition);

        xPercent = smoothStep(xPercent);
        yPercent = smoothStep(yPercent);

        return (h00 * (1 - xPercent) * (1 - yPercent)) +
                (h10 * xPercent * (1 - yPercent)) +
                (h01 * (1 - xPercent) * yPercent) +
                (h11 * xPercent * yPercent);
    }

    public int getRedHeight(int pX, int pY) {
        int blockX = pX * 4;
        int blockZ = pY * 4;

        int[] distorted = distortCoords(blockX, blockZ);

        int x = xOffset(distorted[0] >> 2);
        int y = yOffset(distorted[1] >> 2);

        if (isPixelInHeightmap(x, y)) {
            return heightmap.get().getRGB(x, y) >> 16 & 0xFF;
        }
        return heightmap.get().getRGB(0, 0) >> 16 & 0xFF;
    }

    private double smoothStep(double t) {
        return t * t * (3 - 2 * t);
    }

    private boolean isPixelInHeightmap(int x, int y) {
        return x >= 0 && y >= 0 && x < heightmap.get().getWidth() && y < heightmap.get().getHeight();
    }

    private boolean isPixelInBiomeMap(int x, int y) {
        return x >= 0 && y >= 0 && x < mapImage.get().getWidth() && y < mapImage.get().getHeight();
    }

    public int xOffset(int x) {
        return (x / scale) + spawnX.orElseGet(() -> mapImage.get().getWidth() / 2);
    }

    public int yOffset(int y) {
        return (y / scale) + spawnY.orElseGet(() -> mapImage.get().getHeight() / 2);
    }

    public int blockToPixelX(int blockX) {
        return xOffset(blockX >> 2);
    }

    public int blockToPixelY(int blockZ) {
        return yOffset(blockZ >> 2);
    }

    public int pixelToBlockX(int pixelX) {
        return (pixelX - spawnX.orElseGet(() -> mapImage.get().getWidth() / 2)) * scale * 4;
    }

    public int pixelToBlockZ(int pixelY) {
        return (pixelY - spawnY.orElseGet(() -> mapImage.get().getHeight() / 2)) * scale * 4;
    }

    public int getScale() {
        return scale;
    }

    public double getBorderNoise() {
        return borderNoise;
    }

    @Nullable
    private Holder<Zone> getByColor(int color) {
        return zones.stream().filter(zone -> zone.value().color() == color).findAny().orElse(null);
    }

    @ApiStatus.Internal
    public BufferedImage getMapImage() {
        return mapImage.get();
    }

    public ResourceLocation getMapId() {
        return mapId;
    }

    @ApiStatus.Internal
    public int getMapWidth() {
        return mapImage.get().getWidth();
    }

    @ApiStatus.Internal
    public int getMapHeight() {
        return mapImage.get().getHeight();
    }
}