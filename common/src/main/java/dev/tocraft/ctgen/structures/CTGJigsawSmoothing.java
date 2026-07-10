package dev.tocraft.ctgen.structures;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class CTGJigsawSmoothing {

    private static Map<ResourceLocation, CTGJigsawSmoothingConfig> CONFIGS = new HashMap<>();
    private static final Map<Structure, ResourceLocation> STRUCTURE_ID_LOOKUP = new HashMap<>();
    private static final Map<ResourceLocation, Structure> STRUCTURE_ID_REVERSE_LOOKUP = new HashMap<>();

    public record StructurePlacement(BoundingBox boundingBox, int startPieceY) {}

    private static final Map<ResourceLocation, List<StructurePlacement>> PLACED_STRUCTURE_BOXES = new ConcurrentHashMap<>();
    private static final Set<Long> SMOOTHED_CHUNKS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public record CTGJigsawSmoothingConfig(int transitionWidth, int yOffset) {}

    public static void setConfigs(Map<ResourceLocation, CTGJigsawSmoothingConfig> configs) {
        CONFIGS = configs;
    }

    public static void registerStructureId(Structure structure, ResourceLocation id) {
        STRUCTURE_ID_LOOKUP.put(structure, id);
        STRUCTURE_ID_REVERSE_LOOKUP.put(id, structure);
    }

    public static boolean isRegisteredForSmoothing(Structure structure) {
        ResourceLocation id = STRUCTURE_ID_LOOKUP.get(structure);
        return id != null && CONFIGS.containsKey(id);
    }

    @Nullable
    public static ResourceLocation getStructureId(Structure structure) {
        return STRUCTURE_ID_LOOKUP.get(structure);
    }

    public static int getLookupSize() {
        return STRUCTURE_ID_LOOKUP.size();
    }

    public static void clearStructureBoxCache() {
        PLACED_STRUCTURE_BOXES.clear();
        SMOOTHED_CHUNKS.clear();
    }

    public static void smoothChunkAroundStructures(
            @NotNull ChunkAccess chunk,
            @NotNull StructureManager structureManager
    ) {
        if (CONFIGS.isEmpty()) return;

        long chunkKey = net.minecraft.world.level.ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z);
        if (!SMOOTHED_CHUNKS.add(chunkKey)) return;

        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        // step 1 — query current chunk and cache structure placements
        try {
            List<StructureStart> starts = structureManager.startsForStructure(
                    chunk.getPos(),
                    structure -> STRUCTURE_ID_LOOKUP.containsKey(structure)
                            && CONFIGS.containsKey(STRUCTURE_ID_LOOKUP.get(structure))
            );

            for (StructureStart start : starts) {
                Structure structure = start.getStructure();
                ResourceLocation id = STRUCTURE_ID_LOOKUP.get(structure);
                if (id == null) continue;
                BoundingBox box = start.getBoundingBox();

                // use start piece Y as reference — more accurate than overall bounding box minY
                // for jigsaw structures the start piece is the center/anchor piece
                int startPieceY = box.minY();
                if (!start.getPieces().isEmpty()) {
                    startPieceY = start.getPieces().get(0).getBoundingBox().minY();
                }

                List<StructurePlacement> placements = PLACED_STRUCTURE_BOXES
                        .computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
                StructurePlacement placement = new StructurePlacement(box, startPieceY);
                if (!placements.contains(placement)) {
                    placements.add(placement);
                }
            }
        } catch (Exception e) {
            // structure references not available at this generation stage — skip
        }

        // step 2 — check ALL cached placements for ALL registered structures
        for (Map.Entry<ResourceLocation, CTGJigsawSmoothingConfig> entry : CONFIGS.entrySet()) {
            ResourceLocation id = entry.getKey();
            CTGJigsawSmoothingConfig config = entry.getValue();
            List<StructurePlacement> placements = PLACED_STRUCTURE_BOXES.get(id);
            if (placements == null) continue;

            int transitionWidth = config.transitionWidth();

            for (StructurePlacement placement : placements) {
                BoundingBox box = placement.boundingBox();
                int expandedMinX = box.minX() - transitionWidth;
                int expandedMaxX = box.maxX() + transitionWidth;
                int expandedMinZ = box.minZ() - transitionWidth;
                int expandedMaxZ = box.maxZ() + transitionWidth;

                if (chunkMaxX < expandedMinX || chunkMinX > expandedMaxX ||
                        chunkMaxZ < expandedMinZ || chunkMinZ > expandedMaxZ) continue;

                smoothAroundStructure(chunk, box, placement.startPieceY(), config);
            }
        }
    }

    public static void smoothAroundStructure(
            @NotNull ChunkAccess chunk,
            @NotNull BoundingBox structureBox,
            int startPieceY,
            @NotNull CTGJigsawSmoothingConfig config
    ) {
        int transitionWidth = config.transitionWidth();

        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        int footprintMinX = structureBox.minX();
        int footprintMaxX = structureBox.maxX() + 1;
        int footprintMinZ = structureBox.minZ();
        int footprintMaxZ = structureBox.maxZ() + 1;

        // use start piece Y corrected by yOffset as reference for smoothing
        int placeY = startPieceY - config.yOffset();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // pass 1 — fill under structure and clear above it
        for (int x = Math.max(chunkMinX, footprintMinX);
             x <= Math.min(chunkMaxX, footprintMaxX - 1); x++) {
            for (int z = Math.max(chunkMinZ, footprintMinZ);
                 z <= Math.min(chunkMaxZ, footprintMaxZ - 1); z++) {

                int localX = x - chunkMinX;
                int localZ = z - chunkMinZ;
                int naturalY = getNaturalHeight(chunk, localX, localZ);

                // fill downward — no floating structures
                int fillBottom = Math.max(placeY - 15, chunk.getMinBuildHeight());
                for (int y = placeY - 1; y >= fillBottom; y--) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) {
                        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
                    } else {
                        break;
                    }
                }

                // clear upward — no terrain clipping through structure
                for (int y = naturalY; y >= placeY; y--) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!state.isAir()) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }

        // pass 2 — smooth transition zone around footprint
        for (int x = Math.max(chunkMinX, footprintMinX - transitionWidth);
             x <= Math.min(chunkMaxX, footprintMaxX + transitionWidth); x++) {
            for (int z = Math.max(chunkMinZ, footprintMinZ - transitionWidth);
                 z <= Math.min(chunkMaxZ, footprintMaxZ + transitionWidth); z++) {

                int localX = x - chunkMinX;
                int localZ = z - chunkMinZ;

                boolean insideFootprint = x >= footprintMinX && x < footprintMaxX
                        && z >= footprintMinZ && z < footprintMaxZ;

                if (insideFootprint) continue;

                double distFromFootprint = distanceToRect(x, z,
                        footprintMinX, footprintMinZ, footprintMaxX, footprintMaxZ);
                if (distFromFootprint >= transitionWidth) continue;

                int naturalY = getNaturalHeight(chunk, localX, localZ);

                // skip columns with trees
                if (hasTreeAbove(chunk, localX, localZ, naturalY - 1)) continue;

                double t = distFromFootprint / transitionWidth;
                double smoothT = t * t * t * (t * (t * 6 - 15) + 10);
                int targetY = (int) Math.round(placeY + (naturalY - placeY) * smoothT);

                if (targetY < naturalY) {
                    for (int y = naturalY; y > targetY; y--) {
                        pos.set(x, y, z);
                        BlockState state = chunk.getBlockState(pos);
                        if (!state.isAir()) {
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                        }
                    }
                    pos.set(x, targetY, z);
                    BlockState top = chunk.getBlockState(pos);
                    if (!top.isAir()) {
                        chunk.setBlockState(pos, Blocks.GRASS_BLOCK.defaultBlockState(), false);
                    }
                } else if (targetY > naturalY) {
                    for (int y = naturalY + 1; y < naturalY + 4; y++) {
                        pos.set(x, y, z);
                        BlockState state = chunk.getBlockState(pos);
                        if (isVegetation(state)) {
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                        } else if (!state.isAir()) {
                            break;
                        }
                    }
                    pos.set(x, naturalY, z);
                    BlockState existingTop = chunk.getBlockState(pos);
                    if (existingTop.is(Blocks.GRASS_BLOCK)) {
                        chunk.setBlockState(pos, Blocks.DIRT.defaultBlockState(), false);
                    }
                    for (int y = naturalY + 1; y <= targetY; y++) {
                        pos.set(x, y, z);
                        if (y == targetY) {
                            chunk.setBlockState(pos, Blocks.GRASS_BLOCK.defaultBlockState(), false);
                        } else {
                            chunk.setBlockState(pos, Blocks.DIRT.defaultBlockState(), false);
                        }
                    }
                }

                // final safety pass — ensure top non-air non-vegetation block is grass
                int checkStart = Math.max(naturalY, targetY) + 2;
                for (int y = checkStart; y >= chunk.getMinBuildHeight(); y--) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (isVegetation(state)) continue;
                    if (!state.isAir()) {
                        if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)) {
                            chunk.setBlockState(pos, Blocks.GRASS_BLOCK.defaultBlockState(), false);
                        }
                        break;
                    }
                }
            }
        }
    }

    private static boolean hasTreeAbove(@NotNull ChunkAccess chunk, int localX, int localZ, int fromY) {
        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int checkTo = Math.min(fromY + 32, chunk.getMaxBuildHeight() - 1);
        for (int y = fromY; y <= checkTo; y++) {
            pos.set(worldX, y, worldZ);
            if (chunk.getBlockState(pos).is(BlockTags.LOGS)) return true;
        }
        return false;
    }

    private static double distanceToRect(int x, int z, int minX, int minZ, int maxX, int maxZ) {
        double dx = Math.max(Math.max(minX - x, 0), x - maxX + 1);
        double dz = Math.max(Math.max(minZ - z, 0), z - maxZ + 1);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean isVegetation(@NotNull BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.VINE);
    }

    private static int getNaturalHeight(@NotNull ChunkAccess chunk, int localX, int localZ) {
        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int startY = Math.min(256, chunk.getMaxBuildHeight() - 1);
        for (int y = startY; y >= chunk.getMinBuildHeight(); y--) {
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && !isVegetation(state)) return y;
        }
        return chunk.getMinBuildHeight();
    }
}