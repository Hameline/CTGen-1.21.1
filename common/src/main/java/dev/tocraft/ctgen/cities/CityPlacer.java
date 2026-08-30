package dev.tocraft.ctgen.cities;

import com.mojang.logging.LogUtils;
import dev.tocraft.ctgen.cities.schem.BlockEntityEntry;
import dev.tocraft.ctgen.cities.schem.EntityEntry;
import dev.tocraft.ctgen.cities.schem.SchemCache;
import dev.tocraft.ctgen.cities.schem.SchemCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/**
 * Owns every configured city's compiled-schematic state and does the actual per-chunk placement,
 * called from {@link dev.tocraft.ctgen.worldgen.MapBasedChunkGenerator}. Mirrors the shape of
 * {@link dev.tocraft.ctgen.structures.CTGJigsawSmoothing}'s placement-cache-with-AABB-reject —
 * a handful of large placements, so a flat list with a cheap bounding-box check per chunk is
 * enough; no need for {@link dev.tocraft.ctgen.rivers.RiverGenerator}'s bucketed spatial grid,
 * which exists for many small scattered features instead.
 * <p>
 * A city only becomes placeable once its schematic has been compiled/mapped — that happens
 * asynchronously (see {@link #startCompile}) and can take a while the very first time a given
 * schematic is seen. A chunk inside a city's footprint that generates before that finishes falls
 * back to ordinary terrain generation; this is a known limitation of compiling lazily rather than
 * blocking world startup on it (see the {@code /ctgen cities status} command to check readiness).
 */
public final class CityPlacer {
    private static final ExecutorService COMPILE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CTGen City Compiler");
        t.setDaemon(true);
        return t;
    });

    /** Finds each city's {@code .schem} resource — same directory/id as its JSON, {@code .schem} extension. */
    private static final FileToIdConverter SCHEM_FILES = new FileToIdConverter(CitySpawnLoader.DIRECTORY, ".schem");

    /**
     * CTGen's own private cache directory — entirely internal, not something a consumer mod
     * configures. Relative to the game/run directory, same as every other relative path this mod
     * resolves. Holds one compiled {@code .cgcache} per city (the actual mmap-able random-access
     * data) plus short-lived {@code .staged} files while a schematic resource is being extracted.
     */
    private static final Path CACHE_DIR = Path.of("ctgen_cache", "cities");

    private static final class Instance {
        final ResourceLocation id;
        final CitySpawnEntry entry;
        volatile SchemCache cache;
        volatile BoundingBox worldBox;
        volatile Map<Long, List<BlockPos>> blockEntityChunks = Map.of();
        volatile Map<Long, List<EntityEntry>> entityChunks = Map.of();
        volatile Map<BlockPos, CompoundTag> blockEntityNbt = Map.of();

        Instance(ResourceLocation id, CitySpawnEntry entry) {
            this.id = id;
            this.entry = entry;
        }
    }

    private static volatile Map<ResourceLocation, Instance> INSTANCES = Map.of();
    private static final Set<Long> ALREADY_PLACED_CHUNKS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private CityPlacer() {
    }

    /**
     * Called from {@link CitySpawnLoader#apply}. For every configured city, looks up its
     * {@code .schem} datapack resource (same id as the JSON, {@code .schem} extension — see
     * {@link #SCHEM_FILES}) and opens it <b>synchronously</b>, since {@code resourceManager} is
     * only guaranteed valid for the duration of this call — but only opens it; the actual
     * extraction, digesting and compiling all happen off-thread in {@link #startCompile}, so
     * reload doesn't block the main thread on a potentially large copy.
     */
    public static void onCitiesReloaded(Map<ResourceLocation, CitySpawnEntry> cities, ResourceManager resourceManager) {
        Map<ResourceLocation, Instance> old = INSTANCES;
        Map<ResourceLocation, Instance> updated = new HashMap<>();

        for (Map.Entry<ResourceLocation, CitySpawnEntry> e : cities.entrySet()) {
            ResourceLocation id = e.getKey();
            ResourceLocation schemId = SCHEM_FILES.idToFile(id);
            Optional<Resource> resource = resourceManager.getResource(schemId);
            if (resource.isEmpty()) {
                LogUtils.getLogger().error("City spawn {} has no matching schematic — expected a .schem resource at {}", id, schemId);
                continue;
            }

            InputStream schemStream;
            try {
                schemStream = resource.get().open();
            } catch (IOException ex) {
                LogUtils.getLogger().error("Failed to open city schematic resource for {}", id, ex);
                continue;
            }

            Instance instance = new Instance(id, e.getValue());
            updated.put(id, instance);
            startCompile(instance, schemStream);
        }

        INSTANCES = updated;

        for (Instance instance : old.values()) {
            closeQuietly(instance);
        }
    }

    /**
     * Frees every open schematic cache. Called on server stop, mirroring
     * {@code RiverGenerator.clearCaches()}/{@code CTGJigsawSmoothing.clearStructureBoxCache()}
     * so mapped file handles don't leak between worlds.
     */
    public static void clearAll() {
        Map<ResourceLocation, Instance> old = INSTANCES;
        INSTANCES = Map.of();
        ALREADY_PLACED_CHUNKS.clear();
        for (Instance instance : old.values()) {
            closeQuietly(instance);
        }
    }

    private static void startCompile(Instance instance, InputStream schemStream) {
        COMPILE_EXECUTOR.execute(() -> {
            Path stagingFile = CACHE_DIR.resolve(stagingFileName(instance.id));
            try {
                long digest = extractToStaging(schemStream, stagingFile);

                Path cacheFile = SchemCompiler.compileIfNeeded(stagingFile, digest, instance.id, CACHE_DIR);
                SchemCache cache = SchemCache.open(cacheFile);

                int minX = instance.entry.x() + cache.offsetX();
                int minY = instance.entry.y() + cache.offsetY();
                int minZ = instance.entry.z() + cache.offsetZ();
                BoundingBox box = new BoundingBox(
                        minX, minY, minZ,
                        minX + cache.width() - 1, minY + cache.height() - 1, minZ + cache.length() - 1
                );

                indexBlockEntities(instance, cache, minX, minY, minZ);
                indexEntities(instance, cache, minX, minY, minZ);

                instance.cache = cache;
                instance.worldBox = box; // published last — readers only see a fully-indexed instance
                LogUtils.getLogger().info("City {} ready — world bounds {} to {}",
                        instance.id, box.getCenter(), box);
            } catch (IOException e) {
                LogUtils.getLogger().error("Failed to compile/open city schematic {}", instance.id, e);
            } catch (Exception e) {
                LogUtils.getLogger().error("Unexpected error preparing city schematic {}", instance.id, e);
            } finally {
                try {
                    Files.deleteIfExists(stagingFile);
                } catch (IOException ignored) {
                    // harmless — next reload overwrites it anyway
                }
            }
        });
    }

    private static String stagingFileName(ResourceLocation id) {
        return id.getNamespace() + "_" + id.getPath().replace('/', '_') + ".staged";
    }

    /**
     * Copies the schematic resource out to a real file (needed because {@link SchemCompiler}
     * ultimately mmaps its output, which only works against actual filesystem files — bytes still
     * sitting inside the mod jar can't be mapped directly) while computing a CRC32 of its content
     * as a cheap byproduct of the copy it already has to do. That digest is what lets
     * {@link SchemCompiler#compileIfNeeded} skip recompiling on every single reload — only the
     * (much cheaper) copy-and-digest happens unconditionally.
     */
    private static long extractToStaging(InputStream schemStream, Path stagingFile) throws IOException {
        Files.createDirectories(CACHE_DIR);
        CRC32 crc = new CRC32();
        try (InputStream in = schemStream;
             CheckedInputStream checked = new CheckedInputStream(in, crc);
             OutputStream out = Files.newOutputStream(stagingFile)) {
            checked.transferTo(out);
        }
        return crc.getValue();
    }

    private static void indexBlockEntities(Instance instance, SchemCache cache, int minX, int minY, int minZ) {
        Map<Long, List<BlockPos>> byChunk = new HashMap<>();
        Map<BlockPos, CompoundTag> nbtByPos = new HashMap<>();
        for (BlockEntityEntry be : cache.blockEntities()) {
            BlockPos worldPos = new BlockPos(minX + be.x(), minY + be.y(), minZ + be.z());
            long key = ChunkPos.asLong(worldPos.getX() >> 4, worldPos.getZ() >> 4);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(worldPos);
            nbtByPos.put(worldPos, be.nbt());
        }
        instance.blockEntityChunks = byChunk;
        instance.blockEntityNbt = nbtByPos;
    }

    private static void indexEntities(Instance instance, SchemCache cache, int minX, int minY, int minZ) {
        Map<Long, List<EntityEntry>> byChunk = new HashMap<>();
        for (EntityEntry e : cache.entities()) {
            double worldX = minX + e.x();
            double worldY = minY + e.y();
            double worldZ = minZ + e.z();
            long key = ChunkPos.asLong((int) Math.floor(worldX) >> 4, (int) Math.floor(worldZ) >> 4);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new EntityEntry(worldX, worldY, worldZ, e.nbt()));
        }
        instance.entityChunks = byChunk;
    }

    private static void closeQuietly(Instance instance) {
        SchemCache cache = instance.cache;
        if (cache != null) {
            try {
                cache.close();
            } catch (IOException e) {
                LogUtils.getLogger().error("Failed to close city schematic cache {}", instance.id, e);
            }
        }
    }

    /**
     * Whether the given city's schematic has finished compiling/mapping and is actively being
     * placed. Used by {@code /ctgen cities status}.
     */
    public static boolean isReady(ResourceLocation id) {
        Instance instance = INSTANCES.get(id);
        return instance != null && instance.cache != null && instance.worldBox != null;
    }

    /**
     * Cheap check used by every other CTGen per-column generation pass (surface rules, roads,
     * rivers, walls, cave entrances, jigsaw smoothing) to skip columns that belong to a city —
     * hand-placed city blocks shouldn't be touched by anything terrain-specific.
     */
    public static boolean isInsideCity(int worldX, int worldZ) {
        for (Instance instance : INSTANCES.values()) {
            BoundingBox box = instance.worldBox;
            if (box != null && worldX >= box.minX() && worldX <= box.maxX() && worldZ >= box.minZ() && worldZ <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places raw blocks and block entities for every ready city overlapping this chunk. Called
     * once from {@code MapBasedChunkGenerator.fillFromNoise} — substitutes for normal terrain
     * fill in the overlapping columns entirely (no default-block/fluid fill underneath).
     */
    public static void placeChunk(ChunkAccess chunk) {
        forEachOverlappingCity(chunk, (instance, cache, box) -> {
            placeBlocks(chunk, cache, box);
            placeBlockEntities(chunk, instance);
        });
    }

    /**
     * Re-stamps just the raw blocks (no block-entity work — they're already attached from
     * {@link #placeChunk}, and re-writing the identical block state doesn't disturb them).
     * Called once more at the very end of {@code MapBasedChunkGenerator.buildSurface}, after
     * every other CTGen pass (surface rules, roads/rivers/walls, jigsaw smoothing, cave
     * entrances) has run, so a city's footprint is always what's actually there at the end —
     * simpler and more robust than threading an {@code isInsideCity} guard through each of those
     * passes individually, and correctly undoes anything that reached into the footprint from a
     * neighboring chunk (e.g. a cave tunnel carved in from outside the city).
     */
    public static void restampBlocks(ChunkAccess chunk) {
        forEachOverlappingCity(chunk, (instance, cache, box) -> placeBlocks(chunk, cache, box));
    }

    @FunctionalInterface
    private interface CityAction {
        void run(Instance instance, SchemCache cache, BoundingBox box);
    }

    private static void forEachOverlappingCity(ChunkAccess chunk, CityAction action) {
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX(), chunkMinZ = pos.getMinBlockZ();

        for (Instance instance : INSTANCES.values()) {
            SchemCache cache = instance.cache;
            BoundingBox box = instance.worldBox;
            if (cache == null || box == null) continue;

            if (chunkMinX + 15 < box.minX() || chunkMinX > box.maxX()
                    || chunkMinZ + 15 < box.minZ() || chunkMinZ > box.maxZ()) continue;

            action.run(instance, cache, box);
        }
    }

    private static void placeBlocks(ChunkAccess chunk, SchemCache cache, BoundingBox box) {
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX(), chunkMinZ = pos.getMinBlockZ();

        int fromX = Math.max(chunkMinX, box.minX());
        int toX = Math.min(chunkMinX + 15, box.maxX());
        int fromZ = Math.max(chunkMinZ, box.minZ());
        int toZ = Math.min(chunkMinZ + 15, box.maxZ());

        int fromY = Math.max(chunk.getMinBuildHeight(), box.minY());
        int toY = Math.min(chunk.getMaxBuildHeight() - 1, box.maxY());

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int worldX = fromX; worldX <= toX; worldX++) {
            int localX = worldX - box.minX();
            for (int worldZ = fromZ; worldZ <= toZ; worldZ++) {
                int localZ = worldZ - box.minZ();
                for (int worldY = fromY; worldY <= toY; worldY++) {
                    BlockState state = cache.getBlock(localX, worldY - box.minY(), localZ);
                    if (state == null) continue;
                    mpos.set(worldX, worldY, worldZ);
                    chunk.setBlockState(mpos, state, false);
                }
            }
        }
    }

    private static void placeBlockEntities(ChunkAccess chunk, Instance instance) {
        long key = ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z);
        List<BlockPos> positions = instance.blockEntityChunks.getOrDefault(key, List.of());
        for (BlockPos worldPos : positions) {
            CompoundTag nbt = instance.blockEntityNbt.get(worldPos);
            if (nbt == null) continue;
            BlockState state = chunk.getBlockState(worldPos);
            BlockEntity be = BlockEntity.loadStatic(worldPos, state, nbt, null);
            if (be != null) {
                chunk.setBlockEntity(be);
            }
        }
    }

    /**
     * Places entities (mobs, item frames, etc. — v3 schematics only) for every ready city
     * overlapping this chunk. Called from {@code MapBasedChunkGenerator.buildSurface}, which is
     * the earliest generation stage CTGen hooks that has access to a live {@link WorldGenRegion}
     * (needed for {@code addFreshEntity} — unlike raw block placement, entities can't be written
     * through {@link ChunkAccess} alone). One placement attempt per chunk, guarded so a chunk
     * already fully generated (or reloaded) is never re-populated.
     * <p>
     * <b>Least-precedented part of this feature</b> — flagged in the design plan as needing a
     * short spike/validation pass against a real schematic fixture before being trusted; a
     * failure here is caught and logged per-entity rather than aborting the chunk.
     */
    public static void placeEntities(WorldGenRegion region, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        long chunkKey = ChunkPos.asLong(pos.x, pos.z);
        if (!ALREADY_PLACED_CHUNKS.add(chunkKey)) return;

        for (Instance instance : INSTANCES.values()) {
            BoundingBox box = instance.worldBox;
            if (box == null) continue;
            if (pos.getMinBlockX() + 15 < box.minX() || pos.getMinBlockX() > box.maxX()
                    || pos.getMinBlockZ() + 15 < box.minZ() || pos.getMinBlockZ() > box.maxZ()) continue;

            for (EntityEntry entry : instance.entityChunks.getOrDefault(chunkKey, List.of())) {
                try {
                    EntityType.loadEntityRecursive(entry.nbt(), region.getLevel(), entity -> {
                        entity.moveTo(entry.x(), entry.y(), entry.z(), entity.getYRot(), entity.getXRot());
                        region.addFreshEntity(entity);
                        return entity;
                    });
                } catch (Exception e) {
                    LogUtils.getLogger().error("Failed to place city entity for {} in chunk {}", instance.id, pos, e);
                }
            }
        }
    }
}
