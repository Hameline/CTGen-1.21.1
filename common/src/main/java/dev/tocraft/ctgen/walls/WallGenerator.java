package dev.tocraft.ctgen.walls;

import dev.tocraft.ctgen.roads.Waypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WallGenerator {

    private static final int MAX_CACHE_SIZE = 50;

    private static final Map<WallType, List<int[]>> WALL_POINT_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<WallType, List<int[]>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public static void generateWalls(@NotNull ChunkAccess chunk, @NotNull WallNetwork network) {
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        // store original ground level per column before any wall generation
        // so all walls use the same base Y regardless of generation order
        int[][] groundCache = new int[16][16];
        boolean[][] groundComputed = new boolean[16][16];

        for (WallType wall : network.walls()) {
            if (wall.waypoints().size() < 2) continue;

            List<int[]> allPoints = getOrComputeWallPoints(wall);

            int maxWidth = wall.baseWidth() / 2 + 10;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int[] pt : allPoints) {
                minX = Math.min(minX, pt[0] - maxWidth);
                maxX = Math.max(maxX, pt[0] + maxWidth);
                minZ = Math.min(minZ, pt[1] - maxWidth);
                maxZ = Math.max(maxZ, pt[1] + maxWidth);
            }

            if (chunkMaxX < minX || chunkMinX > maxX || chunkMaxZ < minZ || chunkMinZ > maxZ) continue;

            List<int[]> nearbyPoints = new ArrayList<>();
            for (int[] pt : allPoints) {
                if (pt[0] >= chunkMinX - maxWidth && pt[0] <= chunkMaxX + maxWidth &&
                        pt[1] >= chunkMinZ - maxWidth && pt[1] <= chunkMaxZ + maxWidth) {
                    nearbyPoints.add(pt);
                }
            }

            if (nearbyPoints.isEmpty()) continue;

            generateWall(chunk, wall, nearbyPoints, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ,
                    groundCache, groundComputed);
        }
    }

    public static void clearCaches() {
        WALL_POINT_CACHE.clear();
    }

    private static List<int[]> getOrComputeWallPoints(WallType wall) {
        if (WALL_POINT_CACHE.containsKey(wall)) {
            return WALL_POINT_CACHE.get(wall);
        }

        List<Waypoint> wps = wall.waypoints();
        double totalDist = 0;
        for (int i = 0; i < wps.size() - 1; i++) {
            double dx = wps.get(i + 1).x() - wps.get(i).x();
            double dz = wps.get(i + 1).z() - wps.get(i).z();
            totalDist += Math.sqrt(dx * dx + dz * dz);
        }

        int samples = Math.max(64, (int) totalDist / 4);
        List<int[]> points = new ArrayList<>(samples + 1);

        for (int i = 0; i <= samples; i++) {
            double[] p;
            if (i == 0) {
                // force exact start waypoint so wall connects at junction
                p = new double[]{wps.get(0).x(), wps.get(0).z()};
            } else if (i == samples) {
                // force exact end waypoint so wall connects at junction
                p = new double[]{wps.get(wps.size() - 1).x(), wps.get(wps.size() - 1).z()};
            } else {
                double t = (double) i / samples;
                p = evaluateSpline(wps, t);
            }
            points.add(new int[]{(int) Math.round(p[0]), (int) Math.round(p[1])});
        }

        WALL_POINT_CACHE.put(wall, points);
        return points;
    }

    private static double[] evaluateSpline(List<Waypoint> pts, double t) {
        int n = pts.size();
        if (n == 1) return new double[]{pts.get(0).x(), pts.get(0).z()};

        double scaledT = t * (n - 1);
        int segment = Math.min((int) scaledT, n - 2);
        double localT = scaledT - segment;

        Waypoint p0 = pts.get(Math.max(segment - 1, 0));
        Waypoint p1 = pts.get(segment);
        Waypoint p2 = pts.get(Math.min(segment + 1, n - 1));
        Waypoint p3 = pts.get(Math.min(segment + 2, n - 1));

        double x = catmullRom(p0.x(), p1.x(), p2.x(), p3.x(), localT);
        double z = catmullRom(p0.z(), p1.z(), p2.z(), p3.z(), localT);
        return new double[]{x, z};
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        return 0.5 * ((2 * p1) +
                (-p0 + p2) * t +
                (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t +
                (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    private static int hashCoordBounded(int x, int z, int bound) {
        long h = x * 0x9e3779b97f4a7c15L ^ z * 0x6c62272e07bb0142L;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        h = h ^ (h >>> 31);
        return (int) (Math.abs(h) % bound);
    }

    private static int jaggedHeight(int x, int z, int maxExtra, long seed) {
        long h = x * 0x9e3779b97f4a7c15L ^ z * 0x6c62272e07bb0142L ^ seed;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        h = h ^ (h >>> 31);
        return (int) (Math.abs(h) % (maxExtra + 1));
    }

    private static Direction getWallDirection(List<int[]> nearbyPoints, int x, int z) {
        int[] closest = null;
        int[] secondClosest = null;
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        for (int[] pt : nearbyPoints) {
            double dx = x - pt[0];
            double dz = z - pt[1];
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < d1) {
                d2 = d1; secondClosest = closest;
                d1 = d; closest = pt;
            } else if (d < d2) {
                d2 = d; secondClosest = pt;
            }
        }
        if (closest == null || secondClosest == null) return Direction.NORTH;
        int dx = closest[0] - secondClosest[0];
        int dz = closest[1] - secondClosest[1];
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private static boolean isSnowWallPresent(int wallCoord, boolean isOuterWall) {
        int breakPeriod = 20 + hashCoordBounded(
                (int)(Math.abs(wallCoord) / 50 + 0xF0),
                0xBA, 31);
        int posInPeriod = Math.abs(wallCoord) % Math.max(1, breakPeriod);
        return posInPeriod < breakPeriod - 2;
    }

    private static boolean isSnowWallBulging(int wallCoord, boolean isOuterWall) {
        long bulgeSeed = isOuterWall ? 0xC0FFEEL : 0xFACEB00CL;
        int bulgePeriod = 15 + hashCoordBounded(
                (int)(Math.abs(wallCoord) / 25 + (int)(bulgeSeed & 0xFF)),
                (int)(bulgeSeed >> 8 & 0xFF), 11);
        int posInBulge = Math.abs(wallCoord) % Math.max(1, bulgePeriod);
        return posInBulge < 3;
    }

    private static void placeSnowWallColumn(
            @NotNull ChunkAccess chunk,
            int x, int z,
            int startY,
            int wallCoord,
            boolean isOuterWall,
            BlockPos.MutableBlockPos pos
    ) {
        long breakSeed = isOuterWall ? 0xF00BA4L : 0xDECAFBADL;
        for (int y = startY; y < startY + 2; y++) {
            pos.set(x, y, z);
            chunk.setBlockState(pos, Blocks.SNOW_BLOCK.defaultBlockState(), false);
        }
        int topLayers = 1 + jaggedHeight(x, z, 3, breakSeed + wallCoord);
        pos.set(x, startY + 2, z);
        chunk.setBlockState(pos, Blocks.SNOW.defaultBlockState()
                .setValue(SnowLayerBlock.LAYERS, topLayers), false);
    }

    private static void placeBattlementWall(
            @NotNull ChunkAccess chunk,
            @NotNull WallBattlement b,
            @NotNull List<int[]> nearbyPoints,
            int x, int z,
            int floorY,
            BlockPos.MutableBlockPos pos
    ) {
        int battlementBase = floorY - 1;

        int solidHeight = 1 + jaggedHeight(x * 7 + z * 13, z * 3 + x * 11, 1, 0xB4771E3CL);
        for (int y = battlementBase + 1; y <= battlementBase + solidHeight; y++) {
            pos.set(x, y, z);
            Block block = b.battlementBlocks().isEmpty() ? Blocks.PACKED_ICE
                    : b.battlementBlocks().get(hashCoordBounded(x + y * 31, z + y * 17, b.battlementBlocks().size()));
            chunk.setBlockState(pos, block.defaultBlockState(), false);
        }

        int topY = battlementBase + solidHeight + 1;

        int topRoll = hashCoordBounded(x + 1000, z + 2000, 10);
        if (topRoll < 8) {
            int slabOrStair = hashCoordBounded(x + 3000, z + 4000, 2);
            if (slabOrStair == 0 && !b.battlementSlabs().isEmpty()) {
                Block slab = b.battlementSlabs().get(hashCoordBounded(x, z, b.battlementSlabs().size()));
                if (slab.defaultBlockState().hasProperty(SlabBlock.TYPE)) {
                    pos.set(x, topY, z);
                    chunk.setBlockState(pos, slab.defaultBlockState()
                            .setValue(SlabBlock.TYPE, SlabType.BOTTOM), false);
                }
            } else if (!b.battlementStairs().isEmpty()) {
                Block stair = b.battlementStairs().get(hashCoordBounded(x, z, b.battlementStairs().size()));
                if (stair.defaultBlockState().hasProperty(StairBlock.FACING)) {
                    Direction wallDir = getWallDirection(nearbyPoints, x, z);
                    Direction stairFacing = hashCoordBounded(x + 5000, z + 6000, 2) == 0
                            ? wallDir : wallDir.getOpposite();
                    pos.set(x, topY, z);
                    chunk.setBlockState(pos, stair.defaultBlockState()
                            .setValue(StairBlock.FACING, stairFacing)
                            .setValue(StairBlock.HALF, Half.BOTTOM)
                            .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT), false);
                }
            }
        } else {
            Block block = b.battlementBlocks().isEmpty() ? Blocks.PACKED_ICE
                    : b.battlementBlocks().get(hashCoordBounded(x + topY * 31, z + topY * 17, b.battlementBlocks().size()));
            pos.set(x, topY, z);
            chunk.setBlockState(pos, block.defaultBlockState(), false);

            pos.set(x, topY + 1, z);
            chunk.setBlockState(pos, Blocks.SNOW.defaultBlockState()
                    .setValue(SnowLayerBlock.LAYERS, 1 + jaggedHeight(x, z, 3, 0x53AF12BCL)), false);
        }
    }

    private static void generateWall(
            @NotNull ChunkAccess chunk,
            @NotNull WallType wall,
            @NotNull List<int[]> nearbyPoints,
            int chunkMinX,
            int chunkMinZ,
            int chunkMaxX,
            int chunkMaxZ,
            int[][] groundCache,
            boolean[][] groundComputed
    ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseHalfWidth = wall.baseWidth() / 2;

        int totalSectionHeight = 0;
        for (WallSection s : wall.sections()) totalSectionHeight += s.height();

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

                int intDist = (int) Math.floor(minDist);
                if (intDist >= baseHalfWidth) continue;

                // use original ground level — computed once per column across all walls
                // so connected walls always start from the same base Y
                if (!groundComputed[localX][localZ]) {
                    groundCache[localX][localZ] = getNaturalHeight(chunk, localX, localZ);
                    groundComputed[localX][localZ] = true;
                }
                int surfaceY = groundCache[localX][localZ];
                int currentY = surfaceY + 1;

                // generate sections — freely overwrites any previously placed wall blocks
                for (int sectionIdx = 0; sectionIdx < wall.sections().size(); sectionIdx++) {
                    WallSection section = wall.sections().get(sectionIdx);

                    int sectionHalfWidth = baseHalfWidth - sectionIdx;
                    if (sectionHalfWidth <= 0) break;

                    if (intDist >= sectionHalfWidth) {
                        currentY += section.height();
                        continue;
                    }

                    int nextSectionHalfWidth = sectionHalfWidth - 1;
                    boolean hasNextSection = sectionIdx < wall.sections().size() - 1;
                    boolean isExposedLedge = hasNextSection && intDist >= nextSectionHalfWidth;

                    int iceExtra = 0;
                    if (section.jaggedIce() && isExposedLedge) {
                        iceExtra = jaggedHeight(x * 7 + z * 13, z * 3 + x * 11, 3, 0xDEADBEEFL + sectionIdx);
                    }

                    List<Block> blocks = section.blocks();
                    for (int y = currentY; y < currentY + section.height() + iceExtra; y++) {
                        pos.set(x, y, z);
                        Block block = blocks.isEmpty() ? Blocks.ICE
                                : blocks.get(hashCoordBounded(x + y * 31, z + y * 17, blocks.size()));
                        chunk.setBlockState(pos, block.defaultBlockState(), false);
                    }

                    int topY = currentY + section.height() + iceExtra;

                    if (section.snow() && isExposedLedge) {
                        int baseHeight = 2 + jaggedHeight(x / 3, z / 3, 2, 0xCAFEBABEL + sectionIdx);
                        int sharpExtra = jaggedHeight(x, z, 1, 0xABCDEF01L + sectionIdx);
                        int snowBlocks = baseHeight + sharpExtra;
                        for (int y = topY; y < topY + snowBlocks; y++) {
                            pos.set(x, y, z);
                            chunk.setBlockState(pos, Blocks.SNOW_BLOCK.defaultBlockState(), false);
                        }
                        pos.set(x, topY + snowBlocks, z);
                        chunk.setBlockState(pos, Blocks.SNOW.defaultBlockState(), false);
                    }

                    currentY += section.height();
                }

                // battlement
                if (wall.battlement().isPresent() && intDist <= 7) {
                    WallBattlement b = wall.battlement().get();

                    int outerEdgeY = surfaceY + 1 + totalSectionHeight;
                    int floorY = outerEdgeY;
                    int wallCoord = x + z;

                    if (intDist == 7) {
                        placeBattlementWall(chunk, b, nearbyPoints, x, z, floorY, pos);
                    } else {
                        Block floorBlock = b.floorBlocks().isEmpty() ? Blocks.GRAVEL
                                : b.floorBlocks().get(hashCoordBounded(x, z, b.floorBlocks().size()));
                        for (int y = currentY; y <= outerEdgeY; y++) {
                            pos.set(x, y, z);
                            chunk.setBlockState(pos, floorBlock.defaultBlockState(), false);
                        }

                        boolean placedSomethingAboveFloor = false;

                        if (intDist >= 4) {
                            if (intDist == 4 && isSnowWallPresent(wallCoord, true)
                                    && isSnowWallBulging(wallCoord, true)) {
                                placeSnowWallColumn(chunk, x, z, floorY + 1, wallCoord, true, pos);
                                placedSomethingAboveFloor = true;
                            }
                        } else if (intDist >= 2) {
                            boolean isOuter = intDist == 3;
                            if (isSnowWallPresent(wallCoord, isOuter)) {
                                placeSnowWallColumn(chunk, x, z, floorY + 1, wallCoord, isOuter, pos);
                                placedSomethingAboveFloor = true;
                            }
                        } else {
                            if (intDist == 1 && isSnowWallPresent(wallCoord, false)
                                    && isSnowWallBulging(wallCoord, false)) {
                                placeSnowWallColumn(chunk, x, z, floorY + 1, wallCoord, false, pos);
                                placedSomethingAboveFloor = true;
                            }
                        }

                        if (!placedSomethingAboveFloor) {
                            pos.set(x, floorY + 1, z);
                            chunk.setBlockState(pos, Blocks.SNOW.defaultBlockState()
                                    .setValue(SnowLayerBlock.LAYERS, 1 + jaggedHeight(x, z, 3, 0xA1B2C3D4L)), false);
                        }
                    }
                }
            }
        }
    }

    private static int getNaturalHeight(@NotNull ChunkAccess chunk, int localX, int localZ) {
        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int startY = Math.min(256, chunk.getMaxBuildHeight() - 1);
        for (int y = startY; y >= chunk.getMinBuildHeight(); y--) {
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                return y;
            }
        }
        return chunk.getMinBuildHeight();
    }
}