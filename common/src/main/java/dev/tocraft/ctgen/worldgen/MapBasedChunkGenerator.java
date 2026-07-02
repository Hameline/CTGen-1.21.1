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

        // structure smoothing — runs before features so no vegetation conflicts
        CTGJigsawSmoothing.smoothChunkAroundStructures(chunk, structures);
    }

    @Override
    public void applyBiomeDecoration(
            @NotNull WorldGenLevel level,
            @NotNull ChunkAccess chunk,
            @NotNull StructureManager structureManager
    ) {
        // structure smoothing runs before vanilla decoration
        // at this point structures are placed so bounding boxes are available
        // but features (trees/grass) have not been placed yet so no vegetation conflicts
        CTGJigsawSmoothing.smoothChunkAroundStructures(chunk, structureManager);

        // delegate to vanilla for biome decoration (features, plants, etc.)
        delegate.applyBiomeDecoration(level, chunk, structureManager);
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
            // vanilla structure placement reads WORLD_SURFACE_WG to determine spawn height
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