package dev.tocraft.ctgen.rivers;

import dev.tocraft.ctgen.roads.RoadNetworkLoader;
import dev.tocraft.ctgen.roads.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RiverGenerator {

    // per-river caches (full spline / spatial index / bounds / fords) — sized generously so a
    // large network with many interconnected rivers doesn't evict and force an O(river length)
    // spline recompute for a river that's still in active use
    private static final int MAX_CACHE_SIZE = 500;
    private static final int SPATIAL_BUCKET_SIZE = 256;

    // per-chunk spline point cache keyed by (river hashCode << 32 | chunkKey)
    // avoids computing the full river spline on first load — only computes what each chunk needs
    private static final Map<Long, List<int[]>> CHUNK_POINT_CACHE = new java.util.LinkedHashMap<>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<int[]>> eldest) {
            return size() > 4000;
        }
    };

    // full spline cache — only populated after all chunks have been visited, used for spatial index
    private static final Map<River, List<int[]>> RIVER_POINT_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<River, List<int[]>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    // spatial index cache — built lazily from RIVER_POINT_CACHE
    private static final Map<River, Map<Long, List<int[]>>> SPATIAL_INDEX_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<River, Map<Long, List<int[]>>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    // bounding box cache per river
    private static final Map<River, int[]> BOUNDS_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<River, int[]> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private static final Map<River, List<double[]>> FORD_SEGMENT_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<River, List<double[]>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private static final SimplexNoise MEANDER_NOISE_X = new SimplexNoise(new LegacyRandomSource(0xDEADBEEFL));
    private static final SimplexNoise MEANDER_NOISE_Z = new SimplexNoise(new LegacyRandomSource(0xCAFEBABEL));
    private static final double MEANDER_BASE_FREQUENCY = 0.005;
    private static final double MEANDER_BASE_AMPLITUDE = 80.0;

    private static final SimplexNoise WIDTH_NOISE = new SimplexNoise(new LegacyRandomSource(0xABCDEF01L));
    private static final SimplexNoise DEPTH_NOISE = new SimplexNoise(new LegacyRandomSource(0x12345678L));
    private static final SimplexNoise BED_NOISE = new SimplexNoise(new LegacyRandomSource(0xFEDCBA98L));

    private static final double WIDTH_FREQUENCY = 0.001;
    private static final double DEPTH_FREQUENCY = 0.002;
    private static final double BED_FREQUENCY = 0.02;

    public static void generateRivers(@NotNull ChunkAccess chunk, @NotNull RiverNetwork network) {
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        for (River river : network.rivers()) {
            if (river.waypoints().size() < 2) continue;

            int influence = (int)(river.type().width() * river.type().transitionMultiplier() * 1.25) + 50;

            // fast waypoint-based bounding box reject — uses raw waypoints, no spline needed
            if (!isChunkNearWaypoints(river, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, influence)) continue;

            // fetch only the spline points relevant to this chunk from the spatial index —
            // the full (meandered) spline is still computed once per river, not once per chunk
            List<int[]> nearbyPoints = getOrComputeChunkPoints(river, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, influence);
            if (nearbyPoints.isEmpty()) continue;

            // ford segments still need the full spline — but computed lazily on first ford chunk
            List<int[]> allSplinePoints = getOrComputeSplinePoints(river);
            List<double[]> fordSegments = getOrComputeFordSegments(river, allSplinePoints);

            generateRiver(chunk, river, nearbyPoints, fordSegments, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        }
    }

    // fast reject using raw waypoints only — avoids spline computation for distant chunks
    private static boolean isChunkNearWaypoints(River river,
                                                int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, int influence) {
        List<Waypoint> wps = river.waypoints();
        for (int i = 0; i < wps.size() - 1; i++) {
            double ax = wps.get(i).x(), az = wps.get(i).z();
            double bx = wps.get(i+1).x(), bz = wps.get(i+1).z();
            double segMinX = Math.min(ax, bx) - influence;
            double segMaxX = Math.max(ax, bx) + influence;
            double segMinZ = Math.min(az, bz) - influence;
            double segMaxZ = Math.max(az, bz) + influence;
            if (chunkMaxX >= segMinX && chunkMinX <= segMaxX &&
                    chunkMaxZ >= segMinZ && chunkMinZ <= segMaxZ) return true;
        }
        return false;
    }

    // fetch spline points only for the portion of the river near this chunk, via the
    // per-river spatial index. The full spline (with meander noise applied) is only ever
    // walked once per river — here we just bucket-lookup the slice that matters for this chunk,
    // instead of re-evaluating the whole river's spline on every chunk it happens to pass through.
    private static List<int[]> getOrComputeChunkPoints(River river,
                                                       int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, int influence) {
        long cacheKey = chunkPointKey(river, chunkMinX >> 4, chunkMinZ >> 4);
        List<int[]> cached = CHUNK_POINT_CACHE.get(cacheKey);
        if (cached != null) return cached;

        int searchRadius = influence + 32;
        Map<Long, List<int[]>> index = getOrComputeSpatialIndex(river);

        int minBX = Math.floorDiv(chunkMinX - searchRadius, SPATIAL_BUCKET_SIZE);
        int maxBX = Math.floorDiv(chunkMaxX + searchRadius, SPATIAL_BUCKET_SIZE);
        int minBZ = Math.floorDiv(chunkMinZ - searchRadius, SPATIAL_BUCKET_SIZE);
        int maxBZ = Math.floorDiv(chunkMaxZ + searchRadius, SPATIAL_BUCKET_SIZE);

        List<int[]> points = new ArrayList<>();
        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int bz = minBZ; bz <= maxBZ; bz++) {
                List<int[]> bucket = index.get(bucketKey(bx, bz));
                if (bucket == null) continue;
                for (int[] pt : bucket) {
                    if (pt[0] >= chunkMinX - searchRadius && pt[0] <= chunkMaxX + searchRadius &&
                            pt[1] >= chunkMinZ - searchRadius && pt[1] <= chunkMaxZ + searchRadius) {
                        points.add(pt);
                    }
                }
            }
        }

        CHUNK_POINT_CACHE.put(cacheKey, points);
        return points;
    }

    private static long chunkPointKey(River river, int chunkX, int chunkZ) {
        // combine river identity with chunk coords into a single long key
        return ((long)(System.identityHashCode(river) & 0xFFFF) << 48)
                | ((long)(chunkX & 0xFFFFFF) << 24)
                | (chunkZ & 0xFFFFFF);
    }

    public static double distanceToRiver(River river, double blockX, double blockZ) {
        Map<Long, List<int[]>> index = getOrComputeSpatialIndex(river);
        int bucketX = (int) Math.floor(blockX / SPATIAL_BUCKET_SIZE);
        int bucketZ = (int) Math.floor(blockZ / SPATIAL_BUCKET_SIZE);
        double minDist = Double.MAX_VALUE;
        for (int bx = bucketX - 1; bx <= bucketX + 1; bx++) {
            for (int bz = bucketZ - 1; bz <= bucketZ + 1; bz++) {
                List<int[]> bucket = index.get(bucketKey(bx, bz));
                if (bucket == null) continue;
                for (int[] pt : bucket) {
                    double dx = blockX - pt[0];
                    double dz = blockZ - pt[1];
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist < minDist) minDist = dist;
                }
            }
        }
        return minDist;
    }

    private static long bucketKey(int bx, int bz) {
        return ((long)(bx & 0xFFFFFFFFL) << 32) | (bz & 0xFFFFFFFFL);
    }

    private static synchronized Map<Long, List<int[]>> getOrComputeSpatialIndex(River river) {
        if (SPATIAL_INDEX_CACHE.containsKey(river)) return SPATIAL_INDEX_CACHE.get(river);
        List<int[]> points = getOrComputeSplinePoints(river);
        Map<Long, List<int[]>> index = new HashMap<>(points.size() / 4 + 1);
        for (int[] pt : points) {
            int bx = Math.floorDiv(pt[0], SPATIAL_BUCKET_SIZE);
            int bz = Math.floorDiv(pt[1], SPATIAL_BUCKET_SIZE);
            index.computeIfAbsent(bucketKey(bx, bz), k -> new ArrayList<>()).add(pt);
        }
        SPATIAL_INDEX_CACHE.put(river, index);
        return index;
    }

    // cheap per-river bounding-box reject, used by RiverNetwork before it bothers with the
    // (still much cheaper than before, but non-zero) spatial-index lookup in distanceToRiver
    public static boolean isNearRiver(River river, double blockX, double blockZ, double margin) {
        int[] b = getOrComputeBounds(river, (int) Math.ceil(margin));
        return blockX >= b[0] && blockX <= b[1] && blockZ >= b[2] && blockZ <= b[3];
    }

    private static int[] getOrComputeBounds(River river, int influence) {
        if (BOUNDS_CACHE.containsKey(river)) {
            int[] b = BOUNDS_CACHE.get(river);
            return new int[]{b[0] - influence, b[1] + influence, b[2] - influence, b[3] + influence};
        }
        List<int[]> points = getOrComputeSplinePoints(river);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        Waypoint firstWp = river.waypoints().get(0);
        minX = Math.min(minX, (int) firstWp.x()); maxX = Math.max(maxX, (int) firstWp.x());
        minZ = Math.min(minZ, (int) firstWp.z()); maxZ = Math.max(maxZ, (int) firstWp.z());
        for (int[] pt : points) {
            if (pt[0] < minX) minX = pt[0]; if (pt[0] > maxX) maxX = pt[0];
            if (pt[1] < minZ) minZ = pt[1]; if (pt[1] > maxZ) maxZ = pt[1];
        }
        BOUNDS_CACHE.put(river, new int[]{minX, maxX, minZ, maxZ});
        return new int[]{minX - influence, maxX + influence, minZ - influence, maxZ + influence};
    }

    public static void clearCaches() {
        CHUNK_POINT_CACHE.clear();
        RIVER_POINT_CACHE.clear();
        SPATIAL_INDEX_CACHE.clear();
        BOUNDS_CACHE.clear();
        FORD_SEGMENT_CACHE.clear();
        RiverNetwork.clearBoundsCache();
    }

    private static List<int[]> getOrComputeSplinePoints(River river) {
        if (RIVER_POINT_CACHE.containsKey(river)) {
            return RIVER_POINT_CACHE.get(river);
        }

        double totalDist = 0;
        List<Waypoint> wps = river.waypoints();
        for (int i = 0; i < wps.size() - 1; i++) {
            double dx = wps.get(i + 1).x() - wps.get(i).x();
            double dz = wps.get(i + 1).z() - wps.get(i).z();
            totalDist += Math.sqrt(dx * dx + dz * dz);
        }

        double meanderStrength = river.type().meanderStrength();
        int samples = Math.max(64, (int) totalDist);
        List<int[]> points = new ArrayList<>(samples + 1);

        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            double[] pos = river.evaluateSpline(t);
            double x = pos[0];
            double z = pos[1];

            boolean isEndpoint = (i == samples);

            if (!isEndpoint) {
                // fade meander in over the first 15% so the start connects cleanly
                // use smoothstep so the transition is gradual rather than linear
                double fadeIn = Math.min(1.0, t / 0.15);
                double fadeOut = river.connectsTo().isEmpty() ? 1.0 : Math.min(1.0, (1.0 - t) / 0.15);
                double rawFade = Math.min(fadeIn, fadeOut);
                double meanderFade = rawFade * rawFade * (3 - 2 * rawFade);

                if (meanderStrength > 0) {
                    double tAhead = Math.min(1.0, t + 1.0 / samples);
                    double[] posAhead = river.evaluateSpline(tAhead);
                    double dirX = posAhead[0] - x;
                    double dirZ = posAhead[1] - z;
                    double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
                    if (len > 0) {
                        double perpX = -dirZ / len;
                        double perpZ = dirX / len;
                        double frequency = 0.0005 + meanderStrength * 0.003;
                        double amplitude = river.type().width() * (2.0 + meanderStrength * 8.0);
                        double pathDist = t * totalDist;
                        double displacement = MEANDER_NOISE_X.getValue(pathDist * frequency, 0) * amplitude * meanderFade;
                        x += perpX * displacement;
                        z += perpZ * displacement;
                    }
                }

                {
                    double frequency = MEANDER_BASE_FREQUENCY * (1.0 + 0.1 * 3.0);
                    double amplitude = MEANDER_BASE_AMPLITUDE * 0.1;
                    double dispX = (MEANDER_NOISE_X.getValue(x * frequency, z * frequency) * amplitude
                            + MEANDER_NOISE_X.getValue(x * frequency * 2.5 + 100, z * frequency * 2.5 + 100) * amplitude * 0.4) * meanderFade;
                    double dispZ = (MEANDER_NOISE_Z.getValue(x * frequency + 200, z * frequency + 200) * amplitude
                            + MEANDER_NOISE_Z.getValue(x * frequency * 2.5 + 300, z * frequency * 2.5 + 300) * amplitude * 0.4) * meanderFade;
                    x += dispX;
                    z += dispZ;
                }
            }

            points.add(new int[]{(int) Math.round(x), (int) Math.round(z)});
        }

        RIVER_POINT_CACHE.put(river, points);
        return points;
    }

    private static List<double[]> getOrComputeFordSegments(River river, List<int[]> allSplinePoints) {
        if (FORD_SEGMENT_CACHE.containsKey(river)) {
            return FORD_SEGMENT_CACHE.get(river);
        }

        List<double[]> fordSegments = new ArrayList<>();
        double halfWidth = river.type().width() / 2.0;

        RoadNetworkLoader.getNetwork().ifPresent(network -> {
            for (var road : network.roads()) {
                var roadType = network.roadTypes().get(road.type());
                if (roadType == null) continue;

                List<Waypoint> wps = road.waypoints();
                for (int i = 0; i < wps.size() - 1; i++) {
                    Waypoint from = wps.get(i);
                    Waypoint to = wps.get(i + 1);

                    double segDist = Math.sqrt(
                            Math.pow(to.x() - from.x(), 2) +
                                    Math.pow(to.z() - from.z(), 2));
                    int steps = Math.max(8, (int) segDist / 4);

                    double[] prevPoint = null;

                    for (int s = 0; s <= steps; s++) {
                        double t = (double) s / steps;

                        double midX = (from.x() + to.x()) / 2.0;
                        double midZ = (from.z() + to.z()) / 2.0;
                        double dx = to.x() - from.x();
                        double dz = to.z() - from.z();
                        double len = Math.sqrt(dx * dx + dz * dz);
                        double perpX = -dz / len;
                        double perpZ = dx / len;
                        double controlX = midX + perpX * len * to.curve();
                        double controlZ = midZ + perpZ * len * to.curve();
                        double rx = (1-t)*(1-t)*from.x() + 2*(1-t)*t*controlX + t*t*to.x();
                        double rz = (1-t)*(1-t)*from.z() + 2*(1-t)*t*controlZ + t*t*to.z();

                        double minDist = Double.MAX_VALUE;
                        for (int[] pt : allSplinePoints) {
                            double pdx = rx - pt[0];
                            double pdz = rz - pt[1];
                            double d = Math.sqrt(pdx * pdx + pdz * pdz);
                            if (d < minDist) minDist = d;
                        }

                        if (minDist < halfWidth && prevPoint != null) {
                            fordSegments.add(new double[]{prevPoint[0], prevPoint[1], rx, rz});
                        }

                        prevPoint = new double[]{rx, rz};
                    }
                }
            }
        });

        FORD_SEGMENT_CACHE.put(river, fordSegments);
        return fordSegments;
    }

    private static double distToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq == 0) {
            double ex = px - ax;
            double ez = pz - az;
            return Math.sqrt(ex * ex + ez * ez);
        }
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / lenSq));
        double projX = ax + t * dx;
        double projZ = az + t * dz;
        double fx = px - projX;
        double fz = pz - projZ;
        return Math.sqrt(fx * fx + fz * fz);
    }

    private static double getWidthMultiplier(double x, double z) {
        double noise = WIDTH_NOISE.getValue(x * WIDTH_FREQUENCY, z * WIDTH_FREQUENCY);
        return 1.0 + noise * 0.10;
    }

    private static double getDepthVariation(double x, double z) {
        double noise = DEPTH_NOISE.getValue(x * DEPTH_FREQUENCY, z * DEPTH_FREQUENCY);
        return 0.6 + (noise * 0.5 + 0.5) * 0.4;
    }

    private static Block getBedBlock(int x, int z, List<Block> bedBlocks) {
        if (bedBlocks.isEmpty()) return Blocks.GRAVEL;
        if (bedBlocks.size() == 1) return bedBlocks.get(0);
        double noise = BED_NOISE.getValue(x * BED_FREQUENCY, z * BED_FREQUENCY);
        double normalized = (noise + 1.0) / 2.0;
        int index = (int) Math.floor(normalized * bedBlocks.size());
        index = Math.max(0, Math.min(bedBlocks.size() - 1, index));
        return bedBlocks.get(index);
    }

    private static void generateRiver(
            @NotNull ChunkAccess chunk,
            @NotNull River river,
            @NotNull List<int[]> nearbyPoints,
            @NotNull List<double[]> fordSegments,
            int chunkMinX,
            int chunkMinZ,
            int chunkMaxX,
            int chunkMaxZ
    ) {
        int seaLevel = 62;
        double baseHalfWidth = river.type().width() / 2.0;
        double transitionMultiplier = river.type().transitionMultiplier();
        int maxDepth = river.type().depth();
        List<Block> bedBlocks = river.type().bedBlocks();
        double fordCorridorWidth = baseHalfWidth * 12.0;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int[][] naturalHeightCache = new int[16][16];
        boolean[][] naturalHeightComputed = new boolean[16][16];

        for (int x = chunkMinX; x <= chunkMaxX; x++) {
            for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                int localX = x - chunkMinX;
                int localZ = z - chunkMinZ;

                double minDist = Double.MAX_VALUE;
                for (int[] pt : nearbyPoints) {
                    double dx = x - pt[0];
                    double dz = z - pt[1];
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d < minDist) minDist = d;
                }

                double widthMult = getWidthMultiplier(x, z);
                double halfWidth = baseHalfWidth * widthMult;
                double transitionWidth = halfWidth * transitionMultiplier;

                if (minDist >= transitionWidth) continue;

                if (!naturalHeightComputed[localX][localZ]) {
                    naturalHeightCache[localX][localZ] = getNaturalHeight(chunk, localX, localZ);
                    naturalHeightComputed[localX][localZ] = true;
                }
                int naturalY = naturalHeightCache[localX][localZ];

                if (minDist <= halfWidth) {
                    double normalizedDist = minDist / halfWidth;
                    double depthFraction = (1.0 - (normalizedDist * normalizedDist)) * getDepthVariation(x, z);
                    int targetY = (int) Math.floor(seaLevel - maxDepth * depthFraction);
                    targetY = Math.max(targetY, chunk.getMinBuildHeight() + 1);

                    for (int y = naturalY; y > targetY; y--) {
                        pos.set(x, y, z);
                        BlockState state = chunk.getBlockState(pos);
                        if (!state.isAir() && !isTreeBlock(state)) {
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                        }
                    }

                    if (!bedBlocks.isEmpty()) {
                        Block bedBlock = getBedBlock(x, z, bedBlocks);
                        pos.set(x, targetY, z);
                        BlockState atTarget = chunk.getBlockState(pos);
                        if (!atTarget.isAir() && !atTarget.is(Blocks.WATER)) {
                            chunk.setBlockState(pos, bedBlock.defaultBlockState(), false);
                        }
                    }

                    double minFordDist = Double.MAX_VALUE;
                    for (double[] seg : fordSegments) {
                        double d = distToSegment(x, z, seg[0], seg[1], seg[2], seg[3]);
                        if (d < minFordDist) minFordDist = d;
                    }

                    boolean isFord = minFordDist < fordCorridorWidth;

                    if (isFord) {
                        double fordT = minFordDist / fordCorridorWidth;
                        double fordInfluence = 1.0 - smoothStep(fordT);
                        int fordTopY = (int) Math.round(targetY + fordInfluence * ((seaLevel - 1) - targetY));
                        fordTopY = Math.min(fordTopY, seaLevel - 1);

                        if (fordTopY > targetY) {
                            Block bedBlock = bedBlocks.isEmpty() ? Blocks.GRAVEL : getBedBlock(x, z, bedBlocks);
                            for (int y = targetY + 1; y <= fordTopY; y++) {
                                pos.set(x, y, z);
                                chunk.setBlockState(pos, bedBlock.defaultBlockState(), false);
                            }
                            for (int y = fordTopY + 1; y <= seaLevel; y++) {
                                pos.set(x, y, z);
                                chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                            }
                        } else {
                            for (int y = targetY + 1; y <= seaLevel; y++) {
                                pos.set(x, y, z);
                                BlockState state = chunk.getBlockState(pos);
                                if (state.isAir() || state.is(Blocks.WATER)) {
                                    chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                                }
                            }
                        }
                    } else {
                        for (int y = targetY + 1; y <= seaLevel; y++) {
                            pos.set(x, y, z);
                            BlockState state = chunk.getBlockState(pos);
                            if (state.isAir() || state.is(Blocks.WATER)) {
                                chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                            }
                        }
                    }

                } else {
                    double transitionT = (minDist - halfWidth) / (transitionWidth - halfWidth);
                    double smoothT = smoothStep(transitionT);
                    int targetY = (int) Math.round(seaLevel + (naturalY - seaLevel) * smoothT);
                    BlockState surfaceBlock = getSurfaceBlock(chunk, localX, localZ, naturalY);

                    if (targetY < naturalY) {
                        for (int y = naturalY; y > targetY; y--) {
                            pos.set(x, y, z);
                            BlockState state = chunk.getBlockState(pos);
                            if (!state.isAir() && !isTreeBlock(state)) {
                                chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                            }
                        }
                        pos.set(x, targetY, z);
                        BlockState currentTop = chunk.getBlockState(pos);
                        if (!currentTop.isAir() && !isTreeBlock(currentTop)) {
                            chunk.setBlockState(pos, surfaceBlock, false);
                        }
                    } else if (targetY > naturalY) {
                        for (int y = naturalY + 1; y <= targetY; y++) {
                            pos.set(x, y, z);
                            chunk.setBlockState(pos, surfaceBlock, false);
                        }
                    }
                }
            }
        }
    }

    private static BlockState getSurfaceBlock(@NotNull ChunkAccess chunk, int localX, int localZ, int naturalY) {
        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = naturalY; y >= naturalY - 3; y--) {
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && !isTreeBlock(state) && !state.is(Blocks.WATER)) {
                return state;
            }
        }
        return Blocks.DIRT.defaultBlockState();
    }

    private static int getNaturalHeight(@NotNull ChunkAccess chunk, int localX, int localZ) {
        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int startY = Math.min(256, chunk.getMaxBuildHeight() - 1);
        for (int y = startY; y >= chunk.getMinBuildHeight(); y--) {
            pos.set(worldX, y, worldZ);
            if (!chunk.getBlockState(pos).isAir()) return y;
        }
        return chunk.getMinBuildHeight();
    }

    private static boolean isTreeBlock(@NotNull BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }

    private static double smoothStep(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}