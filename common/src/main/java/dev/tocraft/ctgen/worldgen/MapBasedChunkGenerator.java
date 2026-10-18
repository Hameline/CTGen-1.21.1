package dev.tocraft.ctgen.worldgen;

import com.google.common.collect.Sets;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.CTerrainGeneration;
import dev.tocraft.ctgen.cities.CityPlacer;
import dev.tocraft.ctgen.data.SurfaceBuilderAccess;
import dev.tocraft.ctgen.rivers.RiverGenerator;
import dev.tocraft.ctgen.roads.RoadGenerator;
import dev.tocraft.ctgen.roads.RoadNetworkLoader;
import dev.tocraft.ctgen.structures.CTGJigsawSmoothing;
import dev.tocraft.ctgen.walls.WallGenerator;
import dev.tocraft.ctgen.walls.WallNetworkLoader;
import dev.tocraft.ctgen.worldgen.terrain.CTGenDensity;
import dev.tocraft.ctgen.worldgen.terrain.CTGenTerrain;
import dev.tocraft.ctgen.worldgen.terrain.CarveGuard;
import dev.tocraft.ctgen.zone.Zone;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class MapBasedChunkGenerator extends ChunkGenerator {
    public static final ResourceLocation ID = CTerrainGeneration.id("map_based_chunk_generator");
    public static final MapCodec<MapBasedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec((instance) -> instance.group(
            MapSettings.CODEC.fieldOf("settings").forGetter(MapBasedChunkGenerator::getSettings)
    ).apply(instance, instance.stable(MapBasedChunkGenerator::of)));

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // only used for vanilla's carvers (which need nothing but the biome source and a NoiseChunk,
    // which this generator creates itself) - terrain, caves and aquifers come from fillFromNoise
    private final NoiseBasedChunkGenerator delegate;

    protected final MapBasedBiomeSource biomeSource;
    private volatile SimplexNoise noise = null;

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

    /**
     * The {@link RandomState} the server must use for this generator (installed by ChunkMapMixin):
     * the configured noise settings with CTGen's router ({@link CTGenDensity}) - vanilla's own
     * caves, aquifers and ore veins, shaped around CTGen's map terrain.
     */
    public RandomState createRandomState(RegistryAccess registryAccess, long seed) {
        NoiseGeneratorSettings settings = getNoiseGenSettings();
        HolderGetter<NormalNoise.NoiseParameters> noises = registryAccess.lookupOrThrow(Registries.NOISE);
        // the CTGen height noise only depends on the seed, not the router
        setNoise(RandomState.create(settings, noises, seed));

        NoiseRouter router = CTGenDensity.createRouter(settings.noiseRouter(), terrain(), settings.noiseSettings().minY(),
                registryAccess.lookupOrThrow(Registries.DENSITY_FUNCTION), noises);
        NoiseGeneratorSettings withRouter = new NoiseGeneratorSettings(
                settings.noiseSettings(),
                settings.defaultBlock(),
                settings.defaultFluid(),
                router,
                settings.surfaceRule(),
                settings.spawnTarget(),
                settings.seaLevel(),
                settings.disableMobGeneration(),
                settings.aquifersEnabled(),
                settings.oreVeinsEnabled(),
                settings.useLegacyRandomSource()
        );
        return RandomState.create(withRouter, noises, seed);
    }

    /** CTGen's ground, cached per column - see {@link CTGenTerrain}. */
    @ApiStatus.Internal
    public CTGenTerrain terrain() {
        return getSettings().terrain(noise);
    }

    @Override
    public void applyCarvers(WorldGenRegion chunkRegion, long seed, RandomState noiseConfig, BiomeManager biomeAccess, StructureManager structureAccessor, ChunkAccess chunk, Carving carving) {
        setNoise(noiseConfig);

        NoiseGeneratorSettings chunkGeneratorSettings = this.getNoiseGenSettings();
        Blender blender = Blender.of(chunkRegion);
        chunk.getOrCreateNoiseChunk(chunk2 -> createChunkNoiseSampler(chunkGeneratorSettings, chunk2, structureAccessor, blender, noiseConfig));

        // vanilla's cave/canyon carvers, kept out of the crust under rivers/lakes/coasts and walls
        CarveGuard.begin(terrain().tile(chunk.getPos().x, chunk.getPos().z));
        try {
            delegate.applyCarvers(chunkRegion, seed, noiseConfig, biomeAccess, structureAccessor, chunk, carving);
        } finally {
            CarveGuard.end();
        }
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState noiseConfig, ChunkAccess chunk) {
        setNoise(noiseConfig);
        if (SharedConstants.debugVoidTerrain(chunk.getPos())) {
            return;
        }
        CTGenTerrain terrain = terrain();
        CTGenTerrain.Tile tile = terrain.tile(chunk.getPos().x, chunk.getPos().z);

        WorldGenerationContext heightContext = new WorldGenerationContext(this, region);
        this.buildSurface(chunk, heightContext, noiseConfig, structures, region.getBiomeManager(), biomeRegistry(region), Blender.of(region));

        // apply mountain surface — stone base with snow/ice streaks
        // runs before snow layer placement so snow layers go on top of snow blocks
        applyMountainSurface(chunk, terrain);

        // place snow layers on top of CTGen-placed snow blocks
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = chunk.getPos().getMinBlockX() + x;
                int worldZ = chunk.getPos().getMinBlockZ() + z;
                if (CityPlacer.isInsideCity(worldX, worldZ)) continue;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                pos.set(worldX, top, worldZ);
                if (top + 1 < chunk.getMaxBuildHeight() && chunk.getBlockState(pos).is(Blocks.SNOW_BLOCK)) {
                    pos.set(worldX, top + 1, worldZ);
                    chunk.setBlockState(pos, Blocks.SNOW.defaultBlockState(), false);
                }
            }
        }

        // roads/rivers/walls generate after surface - the river valleys themselves are already part
        // of the terrain, this lays the beds and keeps channels clear of roads
        RoadNetworkLoader.getNetwork().ifPresent(network -> RoadGenerator.generateRoads(chunk, network));
        RiverGenerator.generateRivers(chunk, tile, terrain.waterTop());
        WallNetworkLoader.getNetwork().ifPresent(network -> WallGenerator.generateWalls(chunk, network));

        // structure smoothing — pass the CTGen ground height so it can measure a structure's whole
        // footprint (which may span many chunks) up front, regardless of which chunk happens to
        // generate first
        CTGJigsawSmoothing.smoothChunkAroundStructures(chunk, structures, (x, z) -> terrain.floor(x, z));

        // final authoritative stamp — a city's footprint always wins over anything the passes
        // above may have touched (roads/rivers/walls, jigsaw smoothing), then places its entities
        // now that a live WorldGenRegion is available (unlike fillFromNoise, which only has the raw
        // ChunkAccess)
        CityPlacer.restampBlocks(chunk);
        CityPlacer.placeEntities(region, chunk);
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
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        int lavaBelow = Math.min(-54, settings.seaLevel());
        return (x, y, z) -> y < lavaBelow ? lava : sea;
    }

    /**
     * Vanilla's {@code NoiseBasedChunkGenerator.doFill}, run directly on CTGen's router.
     * <p>
     * Used to be: let the vanilla delegate generate a whole vanilla world column (blocking on its
     * future from inside another background task), then scan every column and overwrite it with
     * stone/air/water by CTGen's height, recomputing that height twice per column. Now there is
     * one pass, and it skips what can't produce anything: cells entirely above both the ground
     * and the sea are never evaluated, and blocks above the ground are air/sea water without
     * asking the density functions or the aquifer.
     */
    @Override
    public @NotNull CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState noiseConfig, StructureManager structureAccessor, @NotNull ChunkAccess chunk) {
        setNoise(noiseConfig);

        NoiseSettings noiseSettings = getNoiseGenSettings().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int minY = noiseSettings.minY();
        int cellMinY = Mth.floorDiv(minY, noiseSettings.getCellHeight());
        int cellCountY = Mth.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());
        if (cellCountY <= 0) {
            return CompletableFuture.completedFuture(chunk);
        }

        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("wgen_fill_noise", () -> {
            int topSection = chunk.getSectionIndex(cellCountY * noiseSettings.getCellHeight() - 1 + minY);
            int bottomSection = chunk.getSectionIndex(minY);
            Set<LevelChunkSection> sections = Sets.newHashSet();
            for (int i = topSection; i >= bottomSection; i--) {
                LevelChunkSection section = chunk.getSection(i);
                section.acquire();
                sections.add(section);
            }

            try {
                doFill(blender, structureAccessor, noiseConfig, chunk, cellMinY, cellCountY);
            } finally {
                for (LevelChunkSection section : sections) {
                    section.release();
                }
            }

            // stamp in city blocks (and their block entities) for every column of every city
            // overlapping this chunk — runs after the fill so it always has the final say over
            // its footprint
            CityPlacer.placeChunk(chunk);
            return chunk;
        }), Util.backgroundExecutor());
    }

    private void doFill(Blender blender, StructureManager structures, RandomState randomState, ChunkAccess chunk, int cellMinY, int cellCountY) {
        NoiseGeneratorSettings settings = getNoiseGenSettings();
        CTGenTerrain terrain = terrain();
        ChunkPos chunkPos = chunk.getPos();
        CTGenTerrain.Tile tile = terrain.tile(chunkPos.x, chunkPos.z);
        // the interpolation's last row/column of cell corners lies in these chunks
        terrain.tile(chunkPos.x + 1, chunkPos.z);
        terrain.tile(chunkPos.x, chunkPos.z + 1);
        terrain.tile(chunkPos.x + 1, chunkPos.z + 1);

        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> createChunkNoiseSampler(settings, c, structures, blender, randomState));
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int seaLevel = settings.seaLevel();
        BlockState defaultBlock = settings.defaultBlock();
        BlockState seaFluid = settings.defaultFluid();
        Aquifer aquifer = noiseChunk.aquifer();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        noiseChunk.initializeForFirstCellX();
        int cellWidth = noiseChunk.cellWidth();
        int cellHeight = noiseChunk.cellHeight();
        int cellCountXZ = 16 / cellWidth;

        for (int cellX = 0; cellX < cellCountXZ; cellX++) {
            noiseChunk.advanceCellX(cellX);

            for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
                // highest ground and highest ground-or-sea of this cell's columns
                int maxFloor = Integer.MIN_VALUE;
                for (int dx = 0; dx < cellWidth; dx++) {
                    for (int dz = 0; dz < cellWidth; dz++) {
                        maxFloor = Math.max(maxFloor, tile.floor(cellX * cellWidth + dx, cellZ * cellWidth + dz));
                    }
                }
                int maxFilled = Math.max(maxFloor, seaLevel);

                int sectionIndex = chunk.getSectionsCount() - 1;
                LevelChunkSection section = chunk.getSection(sectionIndex);

                for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                    int cellBottom = (cellMinY + cellY) * cellHeight;
                    // nothing but air: leave the (empty) section alone
                    if (cellBottom >= maxFilled) continue;
                    // ground in this cell: needs the density functions (caves, aquifer, ores)
                    boolean underground = cellBottom < maxFloor;
                    if (underground) {
                        noiseChunk.selectCellYZ(cellY, cellZ);
                    }

                    for (int yInCell = cellHeight - 1; yInCell >= 0; yInCell--) {
                        int y = cellBottom + yInCell;
                        int newSectionIndex = chunk.getSectionIndex(y);
                        if (sectionIndex != newSectionIndex) {
                            sectionIndex = newSectionIndex;
                            section = chunk.getSection(newSectionIndex);
                        }
                        if (underground) {
                            noiseChunk.updateForY(y, (double) yInCell / cellHeight);
                        }

                        for (int xInCell = 0; xInCell < cellWidth; xInCell++) {
                            int x = minX + cellX * cellWidth + xInCell;
                            int localX = x & 15;
                            if (underground) {
                                noiseChunk.updateForX(x, (double) xInCell / cellWidth);
                            }

                            for (int zInCell = 0; zInCell < cellWidth; zInCell++) {
                                int z = minZ + cellZ * cellWidth + zInCell;
                                int localZ = z & 15;
                                if (underground) {
                                    noiseChunk.updateForZ(z, (double) zInCell / cellWidth);
                                }

                                BlockState state;
                                boolean fromAquifer = false;
                                if (y >= tile.floor(localX, localZ)) {
                                    // above CTGen's ground: open sea/river/lake water up to sea level
                                    if (y >= seaLevel) continue;
                                    state = seaFluid;
                                } else {
                                    state = noiseChunk.getInterpolatedState();
                                    if (state == null) {
                                        state = defaultBlock;
                                    }
                                    fromAquifer = true;
                                }

                                if (state != AIR) {
                                    section.setBlockState(localX, y & 15, localZ, state, false);
                                    oceanFloor.update(localX, y, localZ, state);
                                    worldSurface.update(localX, y, localZ, state);
                                    if (fromAquifer && aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                                        pos.set(x, y, z);
                                        chunk.markPosForPostprocessing(pos);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            noiseChunk.swapSlices();
        }

        noiseChunk.stopInterpolation();
    }

    private void applyMountainSurface(@NotNull ChunkAccess chunk, @NotNull CTGenTerrain terrain) {
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

                if (CityPlacer.isInsideCity(worldX, worldZ)) continue;

                Zone zone = getSettings().getZone(worldX >> 2, worldZ >> 2).value();
                if (!zone.isMountain()) continue;

                // top non-air block, straight from the live worldgen heightmap
                int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                pos.set(worldX, surfaceY, worldZ);

                if (surfaceY <= chunk.getMinBuildHeight()) continue;

                var surfaceState = chunk.getBlockState(pos);
                if (surfaceState.isAir()) continue;


                /*
                 * Height samples
                 */
                double hCenter = terrain.height(worldX, worldZ);

                double hN1 = terrain.height(worldX, worldZ - 1);
                double hS1 = terrain.height(worldX, worldZ + 1);
                double hE1 = terrain.height(worldX + 1, worldZ);
                double hW1 = terrain.height(worldX - 1, worldZ);

                double hN4 = terrain.height(worldX, worldZ - 4);
                double hS4 = terrain.height(worldX, worldZ + 4);
                double hE4 = terrain.height(worldX + 4, worldZ);
                double hW4 = terrain.height(worldX - 4, worldZ);

                double hN8 = terrain.height(worldX, worldZ - 8);
                double hS8 = terrain.height(worldX, worldZ + 8);
                double hE8 = terrain.height(worldX + 8, worldZ);
                double hW8 = terrain.height(worldX - 8, worldZ);

                double hNE8 = terrain.height(worldX + 6, worldZ - 6);
                double hNW8 = terrain.height(worldX - 6, worldZ - 6);
                double hSE8 = terrain.height(worldX + 6, worldZ + 6);
                double hSW8 = terrain.height(worldX - 6, worldZ + 6);


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


                double blobBottomH = terrain.height(
                        (int)(worldX - 15 * slopeDirX),
                        (int)(worldZ - 15 * slopeDirZ)
                );

                double blobTopH = terrain.height(
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


    /**
     * Same meaning as vanilla's: the first free y above the column, counting sea water as
     * occupied except for the ocean floor heightmaps. Answered from the terrain cache, so
     * structure placement (which asks this for every jigsaw piece) costs a lookup, not a column of
     * density functions.
     */
    @Override
    public int getBaseHeight(int pX, int pZ, @NotNull Heightmap.Types pType, @NotNull LevelHeightAccessor pLevel, @NotNull RandomState pRandom) {
        setNoise(pRandom);
        int floor = terrain().floor(pX, pZ);
        return pType == Heightmap.Types.OCEAN_FLOOR || pType == Heightmap.Types.OCEAN_FLOOR_WG
                ? floor
                : Math.max(floor, getSeaLevel());
    }

    @Override
    public @NotNull NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor world, RandomState noiseConfig) {
        setNoise(noiseConfig);
        int floor = terrain().floor(x, z);
        int minY = Math.max(getMinY(), world.getMinBuildHeight());
        int top = Math.min(Math.max(floor, getSeaLevel()), world.getMaxBuildHeight());
        BlockState stone = getNoiseGenSettings().defaultBlock();
        BlockState water = getNoiseGenSettings().defaultFluid();
        BlockState[] states = new BlockState[Math.max(0, top - minY)];
        for (int i = 0; i < states.length; i++) {
            states[i] = minY + i < floor ? stone : water;
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(@NotNull List<String> pInfo, @NotNull RandomState pRandom, @NotNull BlockPos pPos) {
        setNoise(pRandom);
        pInfo.add("Pixel Pos: X: " + getSettings().xOffset(pPos.getX() >> 2) + " Y: " + getSettings().yOffset(pPos.getZ() >> 2));
        getSettings().getZone(pPos.getX() >> 2, pPos.getZ() >> 2).unwrapKey().ifPresent(zoneResourceKey -> pInfo.add("Zone: " + zoneResourceKey.location()));
        pInfo.add("Pixel Height: " + getSettings().getRedHeight(pPos.getX() >> 2, pPos.getZ() >> 2));
        pInfo.add("CTGen Ground: " + terrain().floor(pPos.getX(), pPos.getZ()));
    }

    // structures go through this generator (not the vanilla delegate), so their heights come from
    // getBaseHeight/getBaseColumn above - CTGen's ground - instead of vanilla's own terrain noise
    @Override
    public void createStructures(
            @NotNull RegistryAccess registryAccess,
            @NotNull ChunkGeneratorStructureState structureState,
            @NotNull StructureManager structureManager,
            @NotNull ChunkAccess chunk,
            @NotNull StructureTemplateManager structureTemplateManager
    ) {
        super.createStructures(registryAccess, structureState, structureManager, chunk, structureTemplateManager);
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

    // called from every generation entry point - lock-free once set
    public void setNoise(RandomState randomState) {
        if (noise == null) {
            synchronized (this) {
                if (noise == null) {
                    RandomSource randomSource = randomState.getOrCreateRandomFactory(CTerrainGeneration.id("generator")).at(0, 0, 0);
                    noise = new SimplexNoise(randomSource);
                }
            }
        }
    }
}
