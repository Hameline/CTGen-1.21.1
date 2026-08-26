package dev.tocraft.ctgen.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.CTerrainGeneration;
import dev.tocraft.ctgen.data.SurfaceBuilderAccess;
import dev.tocraft.ctgen.rivers.RiverGenerator;
import dev.tocraft.ctgen.rivers.RiverNetworkLoader;
import dev.tocraft.ctgen.roads.RoadGenerator;
import dev.tocraft.ctgen.roads.RoadNetworkLoader;
import dev.tocraft.ctgen.structures.CTGJigsawSmoothing;
import dev.tocraft.ctgen.walls.WallGenerator;
import dev.tocraft.ctgen.walls.WallNetworkLoader;
import dev.tocraft.ctgen.zone.Zone;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class MapBasedChunkGenerator extends ChunkGenerator {
    public static final ResourceLocation ID = CTerrainGeneration.id("map_based_chunk_generator");
    public static final MapCodec<MapBasedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec((instance) -> instance.group(
            MapSettings.CODEC.fieldOf("settings").forGetter(MapBasedChunkGenerator::getSettings)
    ).apply(instance, instance.stable(MapBasedChunkGenerator::of)));

    private final NoiseBasedChunkGenerator delegate;

    protected final MapBasedBiomeSource biomeSource;
    private SimplexNoise noise = null;

    private MapBasedChunkGenerator(MapBasedBiomeSource biomeSource) {
        super(biomeSource);
        this.biomeSource = biomeSource;
        this.delegate = new NoiseBasedChunkGenerator(biomeSource, getSettings().noiseGenSettings);
    }

    public static @NotNull MapBasedChunkGenerator of(MapSettings settings) {
        MapBasedBiomeSource biomeSource = new MapBasedBiomeSource(settings);
        DisabledStructureRegistry.setDisabledStructureSets(settings.getDisabledStructureSets());
        DisabledStructureRegistry.setDisabledFeatures(settings.getDisabledFeatures());
        return new MapBasedChunkGenerator(biomeSource);
    }

    @Override
    protected @NotNull MapCodec<MapBasedChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public void applyCarvers(WorldGenRegion chunkRegion, long seed, RandomState noiseConfig, BiomeManager biomeAccess, StructureManager structureAccessor, ChunkAccess chunk, Carving carving) {
        setNoise(noiseConfig);

        NoiseGeneratorSettings chunkGeneratorSettings = this.getNoiseGenSettings();
        Blender blender = Blender.of(chunkRegion);
        chunk.getOrCreateNoiseChunk(chunk2 ->
                NoiseChunk.forChunk(
                        chunk2,
                        noiseConfig,
                        Beardifier.forStructuresInChunk(structureAccessor, chunk2.getPos()),
                        chunkGeneratorSettings,
                        createFluidPicker(chunkGeneratorSettings),
                        blender
                )
        );

        delegate.applyCarvers(chunkRegion, seed, noiseConfig, biomeAccess, structureAccessor, chunk, carving);
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState noiseConfig, ChunkAccess chunk) {
        setNoise(noiseConfig);
        if (SharedConstants.debugVoidTerrain(chunk.getPos())) {
            return;
        }
        WorldGenerationContext heightContext = new WorldGenerationContext(this, region);
        this.buildSurface(chunk, heightContext, noiseConfig, structures, region.getBiomeManager(), biomeRegistry(region), Blender.of(region));

        // apply mountain surface — stone base with snow/ice streaks
        // runs before snow layer placement so snow layers go on top of snow blocks
        applyMountainSurface(chunk);

        // place snow layers on top of CTGen-placed snow blocks
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = chunk.getPos().getMinBlockX() + x;
                int worldZ = chunk.getPos().getMinBlockZ() + z;
                for (int y = chunk.getMaxBuildHeight() - 1; y >= chunk.getMinBuildHeight(); y--) {
                    pos.set(worldX, y, worldZ);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (state.is(Blocks.SNOW_BLOCK)) {
                        above.set(worldX, y + 1, worldZ);
                        if (chunk.getBlockState(above).isAir()) {
                            chunk.setBlockState(above, Blocks.SNOW.defaultBlockState(), false);
                        }
                    }
                    break;
                }
            }
        }

        // roads/rivers/walls generate after surface
        RoadNetworkLoader.getNetwork().ifPresent(network -> RoadGenerator.generateRoads(chunk, network));
        RiverNetworkLoader.getNetwork().ifPresent(network -> RiverGenerator.generateRivers(chunk, network));
        WallNetworkLoader.getNetwork().ifPresent(network -> WallGenerator.generateWalls(chunk, network));

        // structure smoothing — pass the CTGen density-level height so it can measure a
        // structure's whole footprint (which may span many chunks) up front, regardless of
        // which chunk happens to generate first
        CTGJigsawSmoothing.smoothChunkAroundStructures(chunk, structures,
                (x, z) -> getSettings().getHeight(noise, x, z));

        // cave entrances — carved last, cuts through everything including mountain features
        applyCaveEntrances(chunk, chunk.getPos());
    }

    private void buildSurface(ChunkAccess chunk, WorldGenerationContext heightContext, RandomState noiseConfig, StructureManager structureAccessor, BiomeManager biomeAccess, Registry<Biome> biomeRegistry, Blender blender) {
        NoiseGeneratorSettings chunkGeneratorSettings = this.getNoiseGenSettings();
        NoiseChunk chunkNoiseSampler = chunk.getOrCreateNoiseChunk(chunk3 -> this.createChunkNoiseSampler(chunkGeneratorSettings, chunk3, structureAccessor, blender, noiseConfig));
        ((SurfaceBuilderAccess) noiseConfig.surfaceSystem()).ctgen$buildSurface(noiseConfig, biomeAccess, biomeRegistry, chunkGeneratorSettings.useLegacyRandomSource(), heightContext, chunk, chunkNoiseSampler, chunkGeneratorSettings.surfaceRule(), this::getSettings, () -> this.noise);
    }

    @SuppressWarnings("unchecked")
    private static Registry<Biome> biomeRegistry(WorldGenRegion region) {
        Object current = region.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof Registry<?> registry) {
                return (Registry<Biome>) registry;
            }
            try {
                var method = current.getClass().getDeclaredMethod("parent");
                method.setAccessible(true);
                current = method.invoke(current);
                continue;
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                var field = current.getClass().getDeclaredField("this$0");
                field.setAccessible(true);
                current = field.get(current);
                continue;
            } catch (ReflectiveOperationException ignored) {
            }
            break;
        }
        throw new IllegalStateException("Biome lookup is not a Registry: " + region.getLevel().registryAccess().lookupOrThrow(Registries.BIOME).getClass().getName());
    }

    private NoiseChunk createChunkNoiseSampler(NoiseGeneratorSettings settings, ChunkAccess chunk, StructureManager world, Blender blender, RandomState noiseConfig) {
        return NoiseChunk.forChunk(chunk, noiseConfig, Beardifier.forStructuresInChunk(world, chunk.getPos()), settings, this.createFluidPicker(settings), blender);
    }

    private Aquifer.@NotNull FluidPicker createFluidPicker(@NotNull NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus fluidLevel = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int i = settings.seaLevel();
        return (x, y, z) -> {
            if (y < Math.min(-54, i)) {
                return fluidLevel;
            }
            return new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        };
    }

    @Override
    public @NotNull CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState noiseConfig, StructureManager structureAccessor, @NotNull ChunkAccess chunk) {
        setNoise(noiseConfig);

        NoiseSettings generationShapeConfig = getNoiseGenSettings().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int k = Mth.floorDiv(generationShapeConfig.height(), generationShapeConfig.noiseSizeVertical());
        if (k <= 0) {
            return CompletableFuture.completedFuture(chunk);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                delegate.fillFromNoise(blender, noiseConfig, structureAccessor, chunk).get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            BlockState defaultBlock = biomeSource.settings.noiseGenSettings.value().defaultBlock();
            BlockState defaultFluid = biomeSource.settings.noiseGenSettings.value().defaultFluid();
            ChunkPos chunkPos = chunk.getPos();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int xOff = chunk.getPos().getBlockX(x);
                    int zOff = chunk.getPos().getBlockZ(z);

                    double surfaceHeight = getSettings().getHeight(noise, xOff, zOff);
                    int floorHeight = (int) Math.floor(surfaceHeight);
                    int worldX = chunkPos.getMinBlockX() + x;
                    int worldZ = chunkPos.getMinBlockZ() + z;

                    int delegateTop = chunk.getMinBuildHeight();
                    for (int y = floorHeight; y >= chunk.getMinBuildHeight(); y--) {
                        pos.set(worldX, y, worldZ);
                        BlockState state = chunk.getBlockState(pos);
                        if (!state.isAir() && !state.is(Blocks.WATER)) {
                            delegateTop = y;
                            break;
                        }
                    }

                    for (int y = delegateTop + 1; y < floorHeight; y++) {
                        pos.set(worldX, y, worldZ);
                        chunk.setBlockState(pos, defaultBlock, false);
                    }

                    for (int y = floorHeight; y < chunk.getMaxBuildHeight(); y++) {
                        pos.set(worldX, y, worldZ);
                        BlockState state = chunk.getBlockState(pos);
                        if (!state.isAir()) {
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                        }
                    }

                    for (int y = floorHeight; y < getSeaLevel(); y++) {
                        pos.set(worldX, y, worldZ);
                        chunk.setBlockState(pos, defaultFluid, false);
                    }
                }
            }

            // update all heightmaps to reflect CTGen's actual surface height
            Heightmap heightmapWS = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            Heightmap heightmapOS = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
            Heightmap heightmapWSC = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
            Heightmap heightmapOSC = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR);

            BlockPos.MutableBlockPos heightPos = new BlockPos.MutableBlockPos();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = chunkPos.getMinBlockX() + x;
                    int worldZ = chunkPos.getMinBlockZ() + z;

                    double surfaceHeight = getSettings().getHeight(noise, worldX, worldZ);
                    int floorHeight = (int) Math.floor(surfaceHeight);
                    int actualSurface = Math.max(floorHeight, getSeaLevel());

                    for (int y = actualSurface; y >= chunk.getMinBuildHeight(); y--) {
                        heightPos.set(worldX, y, worldZ);
                        BlockState state = chunk.getBlockState(heightPos);
                        if (!state.isAir()) {
                            heightmapWS.update(x, y, z, state);
                            heightmapOS.update(x, y, z, state);
                            heightmapWSC.update(x, y, z, state);
                            heightmapOSC.update(x, y, z, state);
                            break;
                        }
                    }
                }
            }

            return chunk;
        }, Util.backgroundExecutor());
    }

    private @NotNull ChunkAccess fill(@NotNull ChunkAccess chunk) {
        BlockState defaultBlock = biomeSource.settings.noiseGenSettings.value().defaultBlock();
        BlockState defaultFluid = biomeSource.settings.noiseGenSettings.value().defaultFluid();
        BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();

        ChunkPos chunkPos = chunk.getPos();
        int minY = getNoiseGenSettings().noiseSettings().minY();
        int seaLevel = getSeaLevel();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int xOff = chunk.getPos().getBlockX(x);
                int zOff = chunk.getPos().getBlockZ(z);

                double surfaceHeight = getSettings().getHeight(noise, xOff, zOff);
                int floorHeight = (int) Math.floor(surfaceHeight);

                for (int y = minY; y < floorHeight; y++) {
                    BlockPos pos = chunkPos.getBlockAt(x, y, z);

                    if (y <= minY + 4) {
                        if (y == minY || (y <= minY + 4 && hashCoord(x + chunkPos.getMinBlockX(), y, z + chunkPos.getMinBlockZ()) % (y - minY + 1) == 0)) {
                            chunk.setBlockState(pos, bedrock, false);
                            continue;
                        }
                    }

                    if (y < 0) {
                        chunk.setBlockState(pos, deepslate, false);
                    } else if (y < 8) {
                        long hash = hashCoord(x + chunkPos.getMinBlockX(), y, z + chunkPos.getMinBlockZ());
                        if (hash % 8 < (8 - y)) {
                            chunk.setBlockState(pos, deepslate, false);
                        } else {
                            chunk.setBlockState(pos, defaultBlock, false);
                        }
                    } else {
                        chunk.setBlockState(pos, defaultBlock, false);
                    }
                }

                for (int y = floorHeight; y < seaLevel; y++) {
                    BlockPos pos = chunkPos.getBlockAt(x, y, z);
                    chunk.setBlockState(pos, defaultFluid, false);
                }
            }
        }

        return chunk;
    }

    private void applyMountainSurface(@NotNull ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();

        final int SNOW_START_Y = 90;
        final int ICE_START_Y = 160;
        final int POWDER_SNOW_START_Y = 170;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {

                int worldX = chunkPos.getMinBlockX() + x;
                int worldZ = chunkPos.getMinBlockZ() + z;

                Zone zone = getSettings().getZone(worldX >> 2, worldZ >> 2).value();
                if (!zone.isMountain()) continue;

                int surfaceY = chunk.getMaxBuildHeight() - 1;
                pos.set(worldX, surfaceY, worldZ);

                while (surfaceY > chunk.getMinBuildHeight()
                        && chunk.getBlockState(pos).isAir()) {
                    surfaceY--;
                    pos.set(worldX, surfaceY, worldZ);
                }

                if (surfaceY <= chunk.getMinBuildHeight()) continue;

                var surfaceState = chunk.getBlockState(pos);
                if (surfaceState.isAir()) continue;


                /*
                 * Height samples
                 */
                double hCenter = getSettings().getHeight(noise, worldX, worldZ);

                double hN1 = getSettings().getHeight(noise, worldX, worldZ - 1);
                double hS1 = getSettings().getHeight(noise, worldX, worldZ + 1);
                double hE1 = getSettings().getHeight(noise, worldX + 1, worldZ);
                double hW1 = getSettings().getHeight(noise, worldX - 1, worldZ);

                double hN4 = getSettings().getHeight(noise, worldX, worldZ - 4);
                double hS4 = getSettings().getHeight(noise, worldX, worldZ + 4);
                double hE4 = getSettings().getHeight(noise, worldX + 4, worldZ);
                double hW4 = getSettings().getHeight(noise, worldX - 4, worldZ);

                double hN8 = getSettings().getHeight(noise, worldX, worldZ - 8);
                double hS8 = getSettings().getHeight(noise, worldX, worldZ + 8);
                double hE8 = getSettings().getHeight(noise, worldX + 8, worldZ);
                double hW8 = getSettings().getHeight(noise, worldX - 8, worldZ);

                double hNE8 = getSettings().getHeight(noise, worldX + 6, worldZ - 6);
                double hNW8 = getSettings().getHeight(noise, worldX - 6, worldZ - 6);
                double hSE8 = getSettings().getHeight(noise, worldX + 6, worldZ + 6);
                double hSW8 = getSettings().getHeight(noise, worldX - 6, worldZ + 6);


                double localSlope = Math.max(
                        Math.abs(hN1 - hS1),
                        Math.abs(hE1 - hW1)
                );

                double broadSlope = Math.max(
                        Math.abs(hN4 - hS4) / 4.0,
                        Math.abs(hE4 - hW4) / 4.0
                );


                /*
                 * Slope direction
                 */
                double slopeDirX = hE1 - hW1;
                double slopeDirZ = hS1 - hN1;

                double slopeMag = Math.sqrt(
                        slopeDirX * slopeDirX +
                                slopeDirZ * slopeDirZ
                );

                if (slopeMag > 0.001) {
                    slopeDirX /= slopeMag;
                    slopeDirZ /= slopeMag;
                }


                double alongSlope =
                        worldX * slopeDirX +
                                worldZ * slopeDirZ;

                double acrossSlope =
                        worldX * (-slopeDirZ) +
                                worldZ * slopeDirX;


                /*
                 * Glacier blob noise
                 */
                double stoneNoise =
                        Math.sin(alongSlope * 0.008)
                                * Math.cos(acrossSlope * 0.04);

                double normalizedStone =
                        (stoneNoise + 1.0) * 0.5;


                /*
                 * Blob type
                 *
                 * 0.0 - 0.55 stone
                 * 0.55 - 0.8 ice cap blobs
                 * 0.8 - 1.0 glacier blobs
                 */
                double streakType =
                        (Math.sin(acrossSlope * 0.005 + 31.4) + 1.0) * 0.5;


                double blobBottomH = getSettings().getHeight(
                        noise,
                        (int)(worldX - 15 * slopeDirX),
                        (int)(worldZ - 15 * slopeDirZ)
                );

                double blobTopH = getSettings().getHeight(
                        noise,
                        (int)(worldX + 15 * slopeDirX),
                        (int)(worldZ + 15 * slopeDirZ)
                );


                int blobHeight =
                        (int)Math.abs(blobTopH - blobBottomH);


                int splitY =
                        (int)(Math.min(blobTopH, blobBottomH)
                                + blobHeight * 0.45);


                long jag = hashCoord(
                        (int)(acrossSlope * 10),
                        0,
                        0
                );

                splitY += (int)((jag % 11) - 5);
                /*
                 * Terrain features
                 */
                double minNeighbor1 = Math.min(
                        Math.min(hN1, hS1),
                        Math.min(hE1, hW1)
                );

                double maxNeighbor1 = Math.max(
                        Math.max(hN1, hS1),
                        Math.max(hE1, hW1)
                );

                double minNeighbor4 = Math.min(
                        Math.min(hN4, hS4),
                        Math.min(hE4, hW4)
                );

                double maxNeighbor8 = Math.max(
                        Math.max(
                                Math.max(hN8, hS8),
                                Math.max(hE8, hW8)
                        ),
                        Math.max(
                                Math.max(hNE8, hNW8),
                                Math.max(hSE8, hSW8)
                        )
                );


                boolean isPeak =
                        hCenter >= maxNeighbor8 - 1.0;

                boolean isRidge =
                        hCenter >= maxNeighbor1 - 0.5;


                boolean isTrueGully =
                        hCenter < minNeighbor1 - 0.3
                                && hCenter < minNeighbor4 + 1.0
                                && !isPeak
                                && !isRidge;


                replaceSubsurfaceDirt(
                        chunk,
                        below,
                        worldX,
                        worldZ,
                        surfaceY
                );


                /*
                 * Low mountains remain rock
                 */
                if (surfaceY < SNOW_START_Y) {

                    if (!surfaceState.is(Blocks.STONE)
                            && !surfaceState.is(Blocks.DEEPSLATE)) {

                        chunk.setBlockState(
                                pos,
                                Blocks.STONE.defaultBlockState(),
                                false
                        );
                    }

                    continue;
                }



                /*
                 * Cliff faces
                 *
                 * More glacier exposure than before
                 */
                if (localSlope > 3.0) {

                    if (surfaceY >= ICE_START_Y
                            && streakType >= 0.45) {

                        chunk.setBlockState(
                                pos,
                                Blocks.PACKED_ICE.defaultBlockState(),
                                false
                        );

                    } else {

                        if (!surfaceState.is(Blocks.STONE)
                                && !surfaceState.is(Blocks.DEEPSLATE)) {

                            chunk.setBlockState(
                                    pos,
                                    Blocks.STONE.defaultBlockState(),
                                    false
                            );
                        }
                    }

                    continue;
                }



                double heightFactor =
                        Math.min(
                                1.0,
                                (surfaceY - SNOW_START_Y) / 80.0
                        );


                double snowCoverage =
                        Math.min(
                                1.0,
                                (surfaceY - SNOW_START_Y) / 40.0
                        );


                double stoneProbability =
                        1.0 - snowCoverage;



                /*
                 * Blob detection
                 */
                boolean inStreakShape = false;


                if (localSlope > 1.5) {

                    double steepSnowChance =
                            Math.min(
                                    1.0,
                                    (surfaceY - SNOW_START_Y) / 60.0
                            );


                    if (normalizedStone > steepSnowChance) {
                        inStreakShape = true;
                    }
                }


                if (!inStreakShape
                        && normalizedStone < stoneProbability * 0.4) {

                    inStreakShape = true;
                }



                /*
                 * Glacier blobs
                 */
                if (inStreakShape) {


                    /*
                     * Massive glacier blob
                     */
                    if (streakType >= 0.8
                            && surfaceY >= ICE_START_Y - 10) {

                        chunk.setBlockState(
                                pos,
                                Blocks.PACKED_ICE.defaultBlockState(),
                                false
                        );


                        /*
                         * Mixed stone/ice glacier
                         *
                         * Lower split = thicker ice
                         */
                    } else if (streakType >= 0.55
                            && surfaceY >= ICE_START_Y - 5) {


                        if (surfaceY >= splitY) {

                            chunk.setBlockState(
                                    pos,
                                    Blocks.PACKED_ICE.defaultBlockState(),
                                    false
                            );

                        } else {

                            if (!surfaceState.is(Blocks.STONE)
                                    && !surfaceState.is(Blocks.DEEPSLATE)) {

                                chunk.setBlockState(
                                        pos,
                                        Blocks.STONE.defaultBlockState(),
                                        false
                                );
                            }
                        }


                        /*
                         * Normal mountain rock
                         */
                    } else {

                        if (!surfaceState.is(Blocks.STONE)
                                && !surfaceState.is(Blocks.DEEPSLATE)) {

                            chunk.setBlockState(
                                    pos,
                                    Blocks.STONE.defaultBlockState(),
                                    false
                            );
                        }
                    }

                    continue;
                }



                /*
                 * Natural snow / glacier zones
                 */
                if (isTrueGully
                        && surfaceY >= ICE_START_Y) {


                    chunk.setBlockState(
                            pos,
                            Blocks.PACKED_ICE.defaultBlockState(),
                            false
                    );


                } else if ((isPeak || isRidge)
                        && surfaceY >= POWDER_SNOW_START_Y) {


                    chunk.setBlockState(
                            pos,
                            Blocks.POWDER_SNOW.defaultBlockState(),
                            false
                    );


                    fillSnowDepth(
                            chunk,
                            below,
                            worldX,
                            worldZ,
                            surfaceY,
                            Blocks.POWDER_SNOW.defaultBlockState()
                    );


                } else if (surfaceY >= POWDER_SNOW_START_Y) {


                    if (broadSlope < 0.5
                            && heightFactor > 0.5) {

                        chunk.setBlockState(
                                pos,
                                Blocks.POWDER_SNOW.defaultBlockState(),
                                false
                        );


                        fillSnowDepth(
                                chunk,
                                below,
                                worldX,
                                worldZ,
                                surfaceY,
                                Blocks.POWDER_SNOW.defaultBlockState()
                        );


                    } else {

                        chunk.setBlockState(
                                pos,
                                Blocks.SNOW_BLOCK.defaultBlockState(),
                                false
                        );


                        fillSnowDepth(
                                chunk,
                                below,
                                worldX,
                                worldZ,
                                surfaceY,
                                Blocks.SNOW_BLOCK.defaultBlockState()
                        );
                    }


                } else {


                    chunk.setBlockState(
                            pos,
                            Blocks.SNOW_BLOCK.defaultBlockState(),
                            false
                    );


                    fillSnowDepth(
                            chunk,
                            below,
                            worldX,
                            worldZ,
                            surfaceY,
                            Blocks.SNOW_BLOCK.defaultBlockState()
                    );
                }
            }
        }
    }

    private void applyCaveEntrances(@NotNull ChunkAccess chunk, ChunkPos chunkPos) {
        int searchRadius = 8;
        for (int cx = chunkPos.x - searchRadius; cx <= chunkPos.x + searchRadius; cx++) {
            for (int cz = chunkPos.z - searchRadius; cz <= chunkPos.z + searchRadius; cz++) {
                long chunkHash = hashCoord(cx, 0, cz);
                if (Math.abs(chunkHash) % 250 != 0) continue;

                long posHash = hashCoord(cx, 1, cz);
                int seedLocalX = (int)(Math.abs(posHash) % 16);
                int seedLocalZ = (int)(Math.abs(posHash >> 4) % 16);
                int seedWorldX = cx * 16 + seedLocalX;
                int seedWorldZ = cz * 16 + seedLocalZ;

                int surfaceY = (int) getSettings().getHeight(noise, seedWorldX, seedWorldZ);
                if (surfaceY <= chunk.getMinBuildHeight() + 20) continue;

                long depthHash = hashCoord(cx, 3, cz);
                int estimatedCaveDepth = 25 + (int)(Math.abs(depthHash) % 225);
                int caveY = surfaceY - estimatedCaveDepth;
                if (caveY <= chunk.getMinBuildHeight() + 5) continue;

                // check if the destination is a water cave by scanning blocks
                // at the estimated cave Y in our chunk if the seed column is within it
                boolean isWaterCave = false;
                int seedLocalBx = seedWorldX - chunkPos.getMinBlockX();
                int seedLocalBz = seedWorldZ - chunkPos.getMinBlockZ();
                if (seedLocalBx >= 0 && seedLocalBx < 16 && seedLocalBz >= 0 && seedLocalBz < 16) {
                    BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
                    int waterCount = 0;
                    int airCount = 0;
                    for (int dy = -3; dy <= 3; dy++) {
                        int checkY = caveY + dy;
                        if (checkY <= chunk.getMinBuildHeight() || checkY >= chunk.getMaxBuildHeight()) continue;
                        scanPos.set(seedWorldX, checkY, seedWorldZ);
                        BlockState state = chunk.getBlockState(scanPos);
                        if (state.is(Blocks.WATER)) waterCount++;
                        else if (state.isAir()) airCount++;
                    }
                    isWaterCave = waterCount > airCount;
                }

                carveTunnelToCave(chunk, chunkPos,
                        seedWorldX, seedWorldZ, surfaceY, caveY, posHash, isWaterCave);
            }
        }
    }

    private void carveTunnelToCave(@NotNull ChunkAccess chunk, ChunkPos chunkPos,
                                   int startX, int startZ, int surfaceY, int caveY,
                                   long seed, boolean isWaterCave) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int totalDepth = surfaceY - caveY;

        double cp1X = startX + (((seed >> 8) & 0xFF) - 128) * 0.5;
        double cp1Z = startZ + (((seed >> 16) & 0xFF) - 128) * 0.5;
        double cp1Y = surfaceY - totalDepth * 0.25 + (((seed >> 24) & 0x3F) - 32) * 0.5;

        double cp2X = startX + (((seed >> 32) & 0xFF) - 128) * 0.8;
        double cp2Z = startZ + (((seed >> 40) & 0xFF) - 128) * 0.8;
        double cp2Y = surfaceY - totalDepth * 0.6 + (((seed >> 48) & 0x3F) - 32) * 0.5;

        double endX = startX + (((seed >> 4) & 0xFF) - 128) * 0.6;
        double endZ = startZ + (((seed >> 6) & 0xFF) - 128) * 0.6;
        double endY = caveY -8 - (Math.abs(seed >> 2) % 12); //Cutting into the cave to make sure it connects the entrance

        int steps = Math.max(totalDepth * 3, 80);

        for (int step = 0; step <= steps; step++) {
            double t = (double) step / steps;

            double mt = 1.0 - t;
            double cx = mt*mt*mt*startX + 3*mt*mt*t*cp1X + 3*mt*t*t*cp2X + t*t*t*endX;
            double cy = mt*mt*mt*surfaceY + 3*mt*mt*t*cp1Y + 3*mt*t*t*cp2Y + t*t*t*endY;
            double cz = mt*mt*mt*startZ + 3*mt*mt*t*cp1Z + 3*mt*t*t*cp2Z + t*t*t*endZ;

            int stepChunkX = (int)Math.floor(cx / 16);
            int stepChunkZ = (int)Math.floor(cz / 16);
            if (Math.abs(stepChunkX - chunkPos.x) > 2 || Math.abs(stepChunkZ - chunkPos.z) > 2) {
                continue;
            }

            // size variation — minimum radius 2.0 (diameter 4), maximum 4.5 (diameter 9)
            // variation is gentler so size doesn't fluctuate too wildly
            double sizeNoise1 = Math.sin(t * 7.3 + seed * 0.001) * 0.2;
            double sizeNoise2 = Math.sin(t * 13.7 + seed * 0.003) * 0.15;
            double sizeNoise3 = Math.sin(t * 3.1 + seed * 0.007) * 0.15;
            double sizeBase = 0.4 + 0.6 * Math.sin(t * Math.PI);
            double sizeFactor = Math.max(0.0, Math.min(1.0,
                    sizeBase + sizeNoise1 + sizeNoise2 + sizeNoise3));

            // radius 2.0 to 4.5 — diameter 4 to 9 blocks
            double radius = 2.0 + sizeFactor * 2.5;

            // independent oval radii clamped to size limits
            double radiusX = Math.max(2.0, Math.min(4.5,
                    radius * (0.7 + 0.5 * Math.abs(Math.sin(t * 5.1 + seed * 0.002)))));
            double radiusY = Math.max(2.0, Math.min(4.5,
                    radius * (0.6 + 0.4 * Math.abs(Math.sin(t * 7.3 + seed * 0.004)))));
            double radiusZ = Math.max(2.0, Math.min(4.5,
                    radius * (0.7 + 0.5 * Math.abs(Math.sin(t * 4.7 + seed * 0.006)))));

            int rxi = (int) Math.ceil(radiusX);
            int ryi = (int) Math.ceil(radiusY);
            int rzi = (int) Math.ceil(radiusZ);

            // water caves — flood the tunnel from cave Y upward
            boolean shouldFillWater = isWaterCave && cy <= caveY + 2;

            for (int dx = -rxi; dx <= rxi; dx++) {
                for (int dy = -ryi; dy <= ryi; dy++) {
                    for (int dz = -rzi; dz <= rzi; dz++) {
                        double ellipsoid = (dx * dx) / (radiusX * radiusX)
                                + (dy * dy) / (radiusY * radiusY)
                                + (dz * dz) / (radiusZ * radiusZ);

                        double wallNoise = Math.sin(dx * 0.8 + t * 11.3) * 0.15
                                + Math.sin(dy * 1.1 + t * 7.7) * 0.15
                                + Math.sin(dz * 0.9 + t * 9.1) * 0.15;

                        if (ellipsoid + wallNoise > 1.0) continue;

                        int bx = (int)(cx + dx);
                        int by = (int)(cy + dy);
                        int bz = (int)(cz + dz);

                        int localBx = bx - chunkPos.getMinBlockX();
                        int localBz = bz - chunkPos.getMinBlockZ();
                        if (localBx < 0 || localBx >= 16 || localBz < 0 || localBz >= 16) continue;
                        if (by <= chunk.getMinBuildHeight() || by >= chunk.getMaxBuildHeight()) continue;

                        pos.set(bx, by, bz);
                        BlockState state = chunk.getBlockState(pos);
                        if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA)) {
                            if (shouldFillWater) {
                                chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                            } else {
                                chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                            }
                        }
                    }
                }
            }
        }
    }

    private void fillSnowDepth(@NotNull ChunkAccess chunk, BlockPos.MutableBlockPos below,
                               int worldX, int worldZ, int surfaceY,
                               @NotNull BlockState snowBlock) {
        // depth between 2 and 5 blocks using position hash for consistency
        long hash = hashCoord(worldX, surfaceY, worldZ);
        int depth = 2 + (int)(Math.abs(hash) % 4); // 2 to 5
        for (int d = 1; d <= depth; d++) {
            below.set(worldX, surfaceY - d, worldZ);
            var belowState = chunk.getBlockState(below);
            if (!belowState.isAir() && !belowState.is(Blocks.WATER) && !belowState.is(Blocks.LAVA)) {
                chunk.setBlockState(below, snowBlock, false);
            } else {
                break;
            }
        }
    }

    private void replaceSubsurfaceDirt(@NotNull ChunkAccess chunk, BlockPos.MutableBlockPos below,
                                       int worldX, int worldZ, int surfaceY) {
        for (int depth = 1; depth <= 4; depth++) {
            below.set(worldX, surfaceY - depth, worldZ);
            var belowState = chunk.getBlockState(below);
            if (belowState.is(Blocks.DIRT) || belowState.is(Blocks.GRASS_BLOCK)
                    || belowState.is(Blocks.COARSE_DIRT) || belowState.is(Blocks.GRAVEL)
                    || belowState.is(Blocks.SAND)) {
                chunk.setBlockState(below, Blocks.STONE.defaultBlockState(), false);
            } else {
                break;
            }
        }
    }

    private static long hashCoord(int x, int y, int z) {
        long hash = x * 1619L ^ y * 31337L ^ z * 6971L;
        hash = hash * hash * hash * 60493L;
        hash = hash * (hash * hash * 19990303L + 137L);
        return Math.abs(hash);
    }

    @Override
    public void spawnOriginalMobs(@NotNull WorldGenRegion pLevel) {
        ChunkPos chunkpos = pLevel.getCenter();
        Holder<Biome> holder = pLevel.getBiome(chunkpos.getWorldPosition().atY(pLevel.getMaxBuildHeight() - 1));
        WorldgenRandom worldgenrandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        worldgenrandom.setDecorationSeed(pLevel.getSeed(), chunkpos.getMinBlockX(), chunkpos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(pLevel, holder, chunkpos, worldgenrandom);
    }

    @Override
    public int getGenDepth() {
        return getNoiseGenSettings().noiseSettings().height();
    }

    @Override
    public int getSeaLevel() {
        return getNoiseGenSettings().seaLevel();
    }

    @Override
    public int getMinY() {
        return getNoiseGenSettings().noiseSettings().minY();
    }

    @Override
    public int getBaseHeight(int pX, int pZ, @NotNull Heightmap.Types pType, @NotNull LevelHeightAccessor pLevel, @NotNull RandomState pRandom) {
        setNoise(pRandom);
        return Math.max((int)(1 + getSettings().getHeight(noise, pX, pZ)), getSeaLevel());
    }

    @Override
    public @NotNull NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor world, RandomState noiseConfig) {
        setNoise(noiseConfig);
        int elevation = Math.max((int) (1 + getSettings().getHeight(noise, x, z)), getSeaLevel());
        int seaLevel = this.getSeaLevel();
        if (elevation < this.getMinY())
            return new NoiseColumn(world.getMinBuildHeight(), new BlockState[]{Blocks.AIR.defaultBlockState()});
        if (elevation < seaLevel) {
            return new NoiseColumn(
                    this.getMinY(),
                    Stream.concat(
                            Stream.generate(() -> this.getNoiseGenSettings().defaultBlock()).limit(elevation - this.getMinY()),
                            Stream.generate(() -> this.getNoiseGenSettings().defaultFluid()).limit(seaLevel - elevation - this.getMinY())
                    ).toArray(BlockState[]::new));
        }
        return new NoiseColumn(
                this.getMinY(),
                Stream.generate(() -> this.getNoiseGenSettings().defaultBlock()).limit(elevation - this.getMinY() + 1).toArray(BlockState[]::new)
        );
    }

    @Override
    public void addDebugScreenInfo(@NotNull List<String> pInfo, @NotNull RandomState pRandom, @NotNull BlockPos pPos) {
        setNoise(pRandom);
        pInfo.add("Pixel Pos: X: " + getSettings().xOffset(pPos.getX() >> 2) + " Y: " + getSettings().yOffset(pPos.getZ() >> 2));
        getSettings().getZone(pPos.getX() >> 2, pPos.getZ() >> 2).unwrapKey().ifPresent(zoneResourceKey -> pInfo.add("Zone: " + zoneResourceKey.location()));
        pInfo.add("Pixel Height: " + getSettings().getRedHeight(pPos.getX() >> 2, pPos.getZ() >> 2));
    }

    @Override
    public void createStructures(
            @NotNull RegistryAccess registryAccess,
            @NotNull ChunkGeneratorStructureState structureState,
            @NotNull StructureManager structureManager,
            @NotNull ChunkAccess chunk,
            @NotNull StructureTemplateManager structureTemplateManager
    ) {
        delegate.createStructures(registryAccess, structureState, structureManager, chunk, structureTemplateManager);
    }

    @Override
    public void createReferences(
            @NotNull WorldGenLevel level,
            @NotNull StructureManager structureManager,
            @NotNull ChunkAccess chunk
    ) {
        delegate.createReferences(level, structureManager, chunk);
    }

    @Override
    public @NotNull ChunkGeneratorStructureState createState(
            @NotNull HolderLookup<StructureSet> structureSetLookup,
            @NotNull RandomState randomState,
            long seed
    ) {
        // seed cliff noise with world seed so cliffs are consistent per world
        getSettings().setCliffSeed(seed);
        return delegate.createState(structureSetLookup, randomState, seed);
    }

    @Override
    @NotNull
    public MapBasedBiomeSource getBiomeSource() {
        return biomeSource;
    }

    @ApiStatus.Internal
    public MapSettings getSettings() {
        return biomeSource.settings;
    }

    @ApiStatus.Internal
    public NoiseGeneratorSettings getNoiseGenSettings() {
        return biomeSource.settings.noiseGenSettings.value();
    }

    public synchronized void setNoise(RandomState randomState) {
        if (noise == null) {
            RandomSource randomSource = randomState.getOrCreateRandomFactory(CTerrainGeneration.id("generator")).at(0, 0, 0);
            noise = new SimplexNoise(randomSource);
        }
    }
}