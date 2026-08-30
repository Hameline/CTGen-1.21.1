package dev.tocraft.ctgen.roads;

import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RoadGenerator {

    private static final int MAX_CACHE_SIZE = 100;

    private static final Map<String, List<int[]>> ROAD_POINT_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<int[]>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private static final SimplexNoise HEIGHT_NOISE = new SimplexNoise(new LegacyRandomSource(555666777L));
    private static final double HEIGHT_FREQUENCY = 0.00015;
    private static final double HEIGHT_AMPLITUDE = 5.0;

    public static void generateRoads(@NotNull ChunkAccess chunk, @NotNull RoadNetwork network) {
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        for (Road road : network.roads()) {
            RoadType roadType = network.roadTypes().get(road.type());
            if (roadType == null) continue;

            List<Waypoint> waypoints = road.waypoints();
            if (waypoints.size() < 2) continue;

            for (int i = 0; i < waypoints.size() - 1; i++) {
                Waypoint from = waypoints.get(i);
                Waypoint to = waypoints.get(i + 1);

                int minX = Math.min(from.x(), to.x()) - roadType.width() - road.transition() - 50;
                int maxX = Math.max(from.x(), to.x()) + roadType.width() + road.transition() + 50;
                int minZ = Math.min(from.z(), to.z()) - roadType.width() - road.transition() - 50;
                int maxZ = Math.max(from.z(), to.z()) + roadType.width() + road.transition() + 50;

                if (maxX < chunkMinX || minX > chunkMaxX || maxZ < chunkMinZ || minZ > chunkMaxZ) {
                    continue;
                }

                generateSegment(chunk, road, roadType, from, to, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
            }
        }
    }

    public static void clearCaches() {
        ROAD_POINT_CACHE.clear();
    }

    private static int getRoadY(int baseY, int worldX, int worldZ) {
        double noise = HEIGHT_NOISE.getValue(worldX * HEIGHT_FREQUENCY, worldZ * HEIGHT_FREQUENCY);
        return baseY + (int) Math.round(noise * HEIGHT_AMPLITUDE);
    }

    private static List<int[]> getOrComputeRoadPoints(@NotNull Waypoint from, @NotNull Waypoint to, int yLevel) {
        String key = from.x() + "," + from.z() + "," + to.x() + "," + to.z();
        if (ROAD_POINT_CACHE.containsKey(key)) {
            return ROAD_POINT_CACHE.get(key);
        }

        double dist = Math.sqrt(Math.pow(to.x() - from.x(), 2) + Math.pow(to.z() - from.z(), 2));
        int steps = Math.max(64, (int) dist);

        List<int[]> points = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double t = (double) step / steps;
            double[] pos = getBezierPoint(from, to, t);
            int roadX = (int) Math.round(pos[0]);
            int roadZ = (int) Math.round(pos[1]);
            int roadY = getRoadY(yLevel, roadX, roadZ);
            points.add(new int[]{roadX, roadY, roadZ});
        }

        ROAD_POINT_CACHE.put(key, points);
        return points;
    }

    private static void generateSegment(
            @NotNull ChunkAccess chunk,
            @NotNull Road road,
            @NotNull RoadType roadType,
            @NotNull Waypoint from,
            @NotNull Waypoint to,
            int chunkMinX,
            int chunkMinZ,
            int chunkMaxX,
            int chunkMaxZ
    ) {
        List<int[]> allRoadPoints = getOrComputeRoadPoints(from, to, road.yLevel());
        int halfWidth = roadType.width() / 2;
        int searchRadius = halfWidth + road.transition();

        List<int[]> nearbyPoints = new ArrayList<>();
        for (int[] point : allRoadPoints) {
            if (point[0] >= chunkMinX - searchRadius && point[0] <= chunkMaxX + searchRadius &&
                    point[2] >= chunkMinZ - searchRadius && point[2] <= chunkMaxZ + searchRadius) {
                nearbyPoints.add(point);
            }
        }

        if (nearbyPoints.isEmpty()) return;

        int[][] placedRoadY = new int[16][16];
        boolean[][] isRoadBlock = new boolean[16][16];

        // cache natural height per column
        int[][] naturalHeightCache = new int[16][16];
        boolean[][] naturalHeightComputed = new boolean[16][16];

        for (int x = chunkMinX; x <= chunkMaxX; x++) {
            for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                int[] closest = findClosest(nearbyPoints, x, z);
                if (closest == null) continue;

                double closestDist = dist(x, z, closest[0], closest[2]);
                int localX = x - chunkMinX;
                int localZ = z - chunkMinZ;
                int roadY = closest[1];

                if (closestDist <= halfWidth) {
                    placeRoadSurface(chunk, localX, localZ, roadY, roadType);
                    placedRoadY[localX][localZ] = roadY;
                    isRoadBlock[localX][localZ] = true;
                    naturalHeightCache[localX][localZ] = roadY;
                    naturalHeightComputed[localX][localZ] = true;
                } else if (closestDist <= halfWidth + road.transition()) {
                    if (!naturalHeightComputed[localX][localZ]) {
                        naturalHeightCache[localX][localZ] = getNaturalHeight(chunk, localX, localZ);
                        naturalHeightComputed[localX][localZ] = true;
                    }
                    int naturalY = naturalHeightCache[localX][localZ];
                    placeTransition(chunk, localX, localZ, roadY, road.transition(), closestDist - halfWidth);
                    double t = (closestDist - halfWidth) / road.transition();
                    t = t * t * (3 - 2 * t);
                    placedRoadY[localX][localZ] = (int) Math.round(roadY + (naturalY - roadY) * t);
                }
            }
        }

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                if (!isRoadBlock[localX][localZ]) continue;

                int myY = placedRoadY[localX][localZ];
                int worldX = chunk.getPos().getMinBlockX() + localX;
                int worldZ = chunk.getPos().getMinBlockZ() + localZ;

                int slabIndex = (int) (hashCoord(worldX + 7919, worldZ + 6271) % roadType.slabs().size());
                Block slab = roadType.slabs().get(slabIndex);

                if (!slab.defaultBlockState().hasProperty(SlabBlock.TYPE)) continue;

                MutableBlockPos pos = new MutableBlockPos();

                int[][] neighbors = {
                        {localX - 1, localZ, worldX - 1, worldZ},
                        {localX + 1, localZ, worldX + 1, worldZ},
                        {localX, localZ - 1, worldX, worldZ - 1},
                        {localX, localZ + 1, worldX, worldZ + 1}
                };

                for (int[] neighbor : neighbors) {
                    int nx = neighbor[0];
                    int nz = neighbor[1];
                    int nwx = neighbor[2];
                    int nwz = neighbor[3];

                    int neighborY;

                    if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) {
                        if (placedRoadY[nx][nz] == 0) continue;
                        neighborY = placedRoadY[nx][nz];
                    } else {
                        int[] closestOutside = findClosest(allRoadPoints, nwx, nwz);
                        if (closestOutside == null) continue;
                        double outsideDist = dist(nwx, nwz, closestOutside[0], closestOutside[2]);
                        if (outsideDist > halfWidth + road.transition()) continue;
                        neighborY = closestOutside[1];
                    }

                    if (neighborY - myY == 1) {
                        pos.set(worldX, neighborY, worldZ);
                        chunk.setBlockState(pos, slab.defaultBlockState()
                                .setValue(SlabBlock.TYPE, SlabType.BOTTOM), false);
                        break;
                    } else if (myY - neighborY == 1) {
                        pos.set(worldX, myY, worldZ);
                        chunk.setBlockState(pos, slab.defaultBlockState()
                                .setValue(SlabBlock.TYPE, SlabType.BOTTOM), false);
                        break;
                    }
                }
            }
        }
    }

    private static void placeRoadSurface(
            @NotNull ChunkAccess chunk,
            int localX,
            int localZ,
            int roadY,
            @NotNull RoadType roadType
    ) {
        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;

        int blockIndex = hashCoordBounded(worldX, worldZ, roadType.blocks().size());
        Block block = roadType.blocks().get(blockIndex);

        MutableBlockPos pos = new MutableBlockPos();
        int naturalY = getNaturalHeight(chunk, localX, localZ);

        if (roadY >= naturalY) {
            for (int y = naturalY + 1; y <= roadY; y++) {
                pos.set(worldX, y, worldZ);
                chunk.setBlockState(pos, block.defaultBlockState(), false);
            }
        } else {
            for (int y = roadY + 1; y <= naturalY; y++) {
                pos.set(worldX, y, worldZ);
                BlockState state = chunk.getBlockState(pos);
                if (!state.isAir() && !isTreeBlock(state)) {
                    chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                }
            }
        }

        for (int y = roadY - 1; y >= chunk.getMinBuildHeight(); y--) {
            pos.set(worldX, y, worldZ);
            if (!chunk.getBlockState(pos).isAir()) break;
            chunk.setBlockState(pos, block.defaultBlockState(), false);
        }

        pos.set(worldX, roadY, worldZ);
        chunk.setBlockState(pos, block.defaultBlockState(), false);

        for (int y = roadY + 1; y < chunk.getMaxBuildHeight(); y++) {
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) break;
            if (!isTreeBlock(state)) {
                chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
            } else {
                break;
            }
        }
    }

    private static void placeTransition(
            @NotNull ChunkAccess chunk,
            int localX,
            int localZ,
            int roadY,
            int transitionWidth,
            double distFromRoadEdge
    ) {
        double t = distFromRoadEdge / transitionWidth;
        t = t * t * (3 - 2 * t);

        int naturalY = getNaturalHeight(chunk, localX, localZ);
        int targetY = (int) Math.round(roadY + (naturalY - roadY) * t);

        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;
        MutableBlockPos pos = new MutableBlockPos();

        if (targetY < naturalY) {
            for (int y = targetY + 1; y <= naturalY; y++) {
                pos.set(worldX, y, worldZ);
                BlockState state = chunk.getBlockState(pos);
                if (!state.isAir() && !isTreeBlock(state)) {
                    chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                }
            }
        } else if (targetY > naturalY) {
            for (int y = naturalY + 1; y <= targetY; y++) {
                pos.set(worldX, y, worldZ);
                chunk.setBlockState(pos, Blocks.GRASS_BLOCK.defaultBlockState(), false);
            }
        }
    }

    private static int getNaturalHeight(@NotNull ChunkAccess chunk, int localX, int localZ) {
        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;
        MutableBlockPos pos = new MutableBlockPos();
        // scan from the true top of the world, not a fixed 256 cap — that assumed vanilla's old
        // ~320 ceiling and would silently ignore terrain (e.g. a tall mountain) built above it.
        for (int y = chunk.getMaxBuildHeight() - 1; y >= chunk.getMinBuildHeight(); y--) {
            pos.set(worldX, y, worldZ);
            if (!chunk.getBlockState(pos).isAir()) {
                return y;
            }
        }
        return chunk.getMinBuildHeight();
    }

    @Nullable
    private static int[] findClosest(@NotNull List<int[]> points, int x, int z) {
        int[] closest = null;
        double closestDist = Double.MAX_VALUE;
        for (int[] point : points) {
            double d = dist(x, z, point[0], point[2]);
            if (d < closestDist) {
                closestDist = d;
                closest = point;
            }
        }
        return closest;
    }

    private static double dist(int x1, int z1, int x2, int z2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(z1 - z2, 2));
    }

    private static double[] getBezierPoint(@NotNull Waypoint from, @NotNull Waypoint to, double t) {
        double midX = (from.x() + to.x()) / 2.0;
        double midZ = (from.z() + to.z()) / 2.0;

        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        double perpX = -dz / len;
        double perpZ = dx / len;

        double controlX = midX + perpX * len * to.curve();
        double controlZ = midZ + perpZ * len * to.curve();

        double x = (1 - t) * (1 - t) * from.x() + 2 * (1 - t) * t * controlX + t * t * to.x();
        double z = (1 - t) * (1 - t) * from.z() + 2 * (1 - t) * t * controlZ + t * t * to.z();

        return new double[]{x, z};
    }

    private static boolean isTreeBlock(@NotNull BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }

    private static int hashCoordBounded(int x, int z, int bound) {
        long h = x * 0x9e3779b97f4a7c15L ^ z * 0x6c62272e07bb0142L;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        h = h ^ (h >>> 31);
        return (int) (Math.abs(h) % bound);
    }

    private static long hashCoord(int x, int z) {
        long h = x * 0x9e3779b97f4a7c15L ^ z * 0x6c62272e07bb0142L;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        h = h ^ (h >>> 31);
        return Math.abs(h);
    }
}