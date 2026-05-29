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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WallGenerator {

    private static final Map<WallType, List<int[]>> WALL_POINT_CACHE = new HashMap<>();

    // hardcoded cross section of 15 blocks from left to right:
    // block 0:       left battlement
    // block 1,2,3:   left trench
    // block 4,5:     left snow wall
    // block 6,7,8:   center trench (block 7 = exact center)
    // block 9,10:    right snow wall
    // block 11,12,13: right trench
    // block 14:      right battlement
    //
    // intDist = distance from center of wall (intDist=0 is center block 7)
    // intDist 0 = block 7 (center trench)
    // intDist 1 = block 6 or 8 (outer center trench)
    // intDist 2 = block 5 or 9 (snow wall inner)
    // intDist 3 = block 4 or 10 (snow wall outer)
    // intDist 4 = block 3 or 11 (trench inner)
    // intDist 5 = block 2 or 12 (trench middle)
    // intDist 6 = block 1 or 13 (trench outer)
    // intDist 7 = block 0 or 14 (battlement)

    public static void generateWalls(@NotNull ChunkAccess chunk, @NotNull WallNetwork network) {
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

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

            generateWall(chunk, wall, nearbyPoints, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        }
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

        int samples = Math.max(64, (int) totalDist);
        List<int[]> points = new ArrayList<>(samples + 1);

        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double[] p = evaluateSpline(wps, t);
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
        // both walls use the same seed so gaps appear on both sides simultaneously
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
        // battlement starts 1 block lower than the inner path floor
        int battlementBase = floorY - 1;

        // solid battlement blocks 1-2 high above battlementBase
        int solidHeight = 1 + jaggedHeight(x * 7 + z * 13, z * 3 + x * 11, 1, 0xB4771E3CL);
        for (int y = battlementBase + 1; y <= battlementBase + solidHeight; y++) {
            pos.set(x, y, z);
            Block block = b.battlementBlocks().isEmpty() ? Blocks.PACKED_ICE
                    : b.battlementBlocks().get(hashCoordBounded(x + y * 31, z + y * 17, b.battlementBlocks().size()));
            chunk.setBlockState(pos, block.defaultBlockState(), false);
        }

        int topY = battlementBase + solidHeight + 1;

        // 80% slab or stair on top — no snow on these
        // 20% solid block on top — snow layer placed above it
        int topRoll = hashCoordBounded(x + 1000, z + 2000, 10);
        if (topRoll < 8) {
            int slabOrStair = hashCoordBounded(x + 3000, z + 4000, 2);
            if (slabOrStair == 0 && !b.battlementSlabs().isEmpty()) {
                Block slab = b.battlementSlabs().get(hashCoordBounded(x, z, b.battlementSlabs().size()));
                if (slab.defaultBlockState().hasProperty(SlabBlock.TYPE)) {
                    pos.set(x, topY, z);
                    chunk.setBlockState(pos, slab.defaultBlockState()
                            .setValue(SlabBlock.TYPE, SlabType.BOTTOM), false);
                    // no snow on slabs
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
                    // no snow on stairs
                }
            }
        } else {
            // solid block on top
            Block block = b.battlementBlocks().isEmpty() ? Blocks.PACKED_ICE
                    : b.battlementBlocks().get(hashCoordBounded(x + topY * 31, z + topY * 17, b.battlementBlocks().size()));
            pos.set(x, topY, z);
            chunk.setBlockState(pos, block.defaultBlockState(), false);

            // snow layer on top of solid block — random 1-4 layers
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
            int chunkMaxZ
    ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseHalfWidth = wall.baseWidth() / 2;

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

                int surfaceY = getNaturalHeight(chunk, localX, localZ);
                int currentY = surfaceY + 1;

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

                // battlement generation
                // hardcoded 15-block cross section by intDist from center:
                // intDist 7     → battlement (1 block each side) — no floor
                // intDist 4,5,6 → outer trench (3 blocks each side) — floor + snow on top
                // intDist 2,3   → snow wall (2 blocks each side) — floor + snow wall
                // intDist 0,1   → center trench (3 blocks total) — floor + snow on top
                if (wall.battlement().isPresent() && intDist <= 7) {
                    WallBattlement b = wall.battlement().get();

                    int outerEdgeY = surfaceY + 1;
                    for (WallSection s : wall.sections()) outerEdgeY += s.height();

                    int floorY = outerEdgeY;
                    int wallCoord = x + z;

                    if (intDist == 7) {
                        // battlement — rises 1 block lower than inner path, no floor
                        placeBattlementWall(chunk, b, nearbyPoints, x, z, floorY, pos);

                    } else {
                        // central 13 blocks — place floor from currentY up to outerEdgeY
                        Block floorBlock = b.floorBlocks().isEmpty() ? Blocks.GRAVEL
                                : b.floorBlocks().get(hashCoordBounded(x, z, b.floorBlocks().size()));
                        for (int y = currentY; y <= outerEdgeY; y++) {
                            pos.set(x, y, z);
                            chunk.setBlockState(pos, floorBlock.defaultBlockState(), false);
                        }

                        boolean placedSomethingAboveFloor = false;

                        if (intDist >= 4) {
                            // outer trench — intDist 4, 5, 6 — open air above floor
                            // intDist 4 is adjacent to outer snow wall — check bulge
                            if (intDist == 4 && isSnowWallPresent(wallCoord, true)
                                    && isSnowWallBulging(wallCoord, true)) {
                                placeSnowWallColumn(chunk, x, z, floorY + 1, wallCoord, true, pos);
                                placedSomethingAboveFloor = true;
                            }

                        } else if (intDist >= 2) {
                            // snow wall — intDist 2, 3
                            // intDist 3 = outer snow wall, intDist 2 = inner snow wall
                            // when break present nothing placed above floor — gap goes all the way through
                            boolean isOuter = intDist == 3;
                            if (isSnowWallPresent(wallCoord, isOuter)) {
                                placeSnowWallColumn(chunk, x, z, floorY + 1, wallCoord, isOuter, pos);
                                placedSomethingAboveFloor = true;
                            }

                        } else {
                            // center trench — intDist 0, 1 — open air above floor
                            // intDist 1 is adjacent to inner snow wall — check bulge
                            if (intDist == 1 && isSnowWallPresent(wallCoord, false)
                                    && isSnowWallBulging(wallCoord, false)) {
                                placeSnowWallColumn(chunk, x, z, floorY + 1, wallCoord, false, pos);
                                placedSomethingAboveFloor = true;
                            }
                        }

                        // place snow layer on floor where nothing is above it
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
        for (int y = chunk.getMaxY() - 1; y >= chunk.getMinY(); y--) {
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                return y;
            }
        }
        return chunk.getMinY();
    }
}