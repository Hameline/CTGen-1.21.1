package dev.tocraft.ctgen.rivers;

import dev.tocraft.ctgen.roads.RoadNetworkLoader;
import dev.tocraft.ctgen.roads.Waypoint;
import dev.tocraft.ctgen.worldgen.terrain.CTGenTerrain;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * River shapes.
 * <p>
 * Rivers used to be carved as a block-editing pass after the surface rules: scan every column from
 * the top of the world, dig the channel and bank out of the already generated terrain and copy the
 * top block down. Now the same shape is computed per column by {@link #carveColumn}, which
 * {@link CTGenTerrain} folds into the terrain height itself - the noise fill generates the river
 * valley directly, surface rules dress the banks like any other terrain, and caves/aquifers/
 * structures see the river instead of the ground it replaced. All that remains as a block pass
 * ({@link #generateRivers}) is laying the river bed and cutting channels through things placed on
 * top of the terrain afterwards (roads, walls), which is what the old pass did to them as well.
 * <p>
 * Every river gets one immutable {@link RiverIndex} (meandered spline points bucketed into a
 * small grid), built once and shared lock-free by all worldgen threads.
 */
public class RiverGenerator {
    private static final int GRID_CELL = 16;

    // copy-on-write, keyed by identity: River is a record whose hashCode walks every waypoint
    private static volatile Map<River, RiverIndex> INDEXES = new IdentityHashMap<>();

    private static final SimplexNoise MEANDER_NOISE_X = new SimplexNoise(new LegacyRandomSource(0xDEADBEEFL));
    private static final SimplexNoise MEANDER_NOISE_Z = new SimplexNoise(new LegacyRandomSource(0xCAFEBABEL));
    private static final double MEANDER_BASE_FREQUENCY = 0.005;
    private static final double MEANDER_BASE_AMPLITUDE = 80.0;

    private static final SimplexNoise WIDTH_NOISE = new SimplexNoise(new LegacyRandomSource(0xABCDEF01L));
    private static final SimplexNoise DEPTH_NOISE = new SimplexNoise(new LegacyRandomSource(0x12345678L));
    private static final SimplexNoise BED_NOISE = new SimplexNoise(new LegacyRandomSource(0xFEDCBA98L));

    // bank roughness — same idea as the cave-tunnel wall noise: perturb the implicit
    // distance-to-spline value itself (before any threshold check) instead of just varying
    // the width/depth along the river. That makes the water's edge, the transition band and
    // the underwater slope all wobble together instead of tracing a perfect offset curve.
    private static final SimplexNoise BANK_NOISE = new SimplexNoise(new LegacyRandomSource(0x900D0BAAL));
    private static final double BANK_NOISE_FREQUENCY = 0.03;
    private static final double BANK_NOISE_FINE_FREQUENCY = BANK_NOISE_FREQUENCY * 2.7;

    private static final double WIDTH_FREQUENCY = 0.001;
    private static final double DEPTH_FREQUENCY = 0.002;
    private static final double BED_FREQUENCY = 0.02;

    /**
     * One terrain column while {@link #carveColumn} shapes it. Mutable and reused per thread by
     * {@link CTGenTerrain}; {@link #top} is the highest solid block.
     */
    public static final class ColumnState {
        public static final byte NONE = 0;
        /** inside a river's transition band - terrain lowered toward the water */
        public static final byte BANK = 1;
        /** inside the river itself - water down to the bed */
        public static final byte CHANNEL = 2;

        public double height;
        public int top;
        public byte kind;
        /** block for {@code [bedBottom, top]} of a channel column, or null */
        public Block bed;
        public int bedBottom;

        public void reset(double height, int top) {
            this.height = height;
            this.top = top;
            this.kind = NONE;
            this.bed = null;
            this.bedBottom = Integer.MAX_VALUE;
        }
    }

    /** Cheap reject for a whole chunk - true if any river's reach overlaps it. */
    public static boolean mayAffectChunk(@NotNull RiverNetwork network, int chunkMinX, int chunkMinZ) {
        for (River river : network.rivers()) {
            RiverIndex index = index(river);
            if (index != null && index.overlaps(chunkMinX, chunkMinZ, chunkMinX + 15, chunkMinZ + 15)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies every river to one column: lowers {@link ColumnState#top} to the channel bed or the
     * bank slope and records the bed block. Same shapes, noises and order the old block pass used
     * (later rivers win), {@code waterTop} being the highest water block (sea level - 1).
     */
    public static void carveColumn(@NotNull RiverNetwork network, int x, int z, int waterTop, int minY, @NotNull ColumnState column) {
        for (River river : network.rivers()) {
            RiverIndex index = index(river);
            if (index == null || !index.overlaps(x, z, x, z)) continue;

            double minDistSq = index.nearestDistSq(x, z, index.maxSearchDist);
            if (minDistSq >= index.maxSearchDistSq) continue;
            double minDist = Math.sqrt(minDistSq);

            RiverType type = river.type();
            double halfWidth = index.baseHalfWidth * getWidthMultiplier(x, z);
            // transition band is a multiple of the river's own (full) width, measured
            // outward from the bank — e.g. transitionMultiplier 2.0 means the fade zone
            // beyond each bank is twice as wide as the river itself.
            double transitionBandWidth = (halfWidth * 2.0) * type.transitionMultiplier();
            double transitionOuter = halfWidth + transitionBandWidth;

            // perturb the distance itself (not just the width) so the bank is a rough,
            // organic line instead of a perfect curve parallel to the spline. Scaled to this
            // river's own width so a narrow stream doesn't get torn apart by a wide river's wobble.
            double bankAmplitude = halfWidth * 0.11;
            double perturbedDist = Math.max(0.0, minDist + getBankRoughness(x, z) * bankAmplitude);
            if (perturbedDist >= transitionOuter) continue;

            if (perturbedDist <= halfWidth) {
                double normalizedDist = perturbedDist / halfWidth;
                double depthFraction = (1.0 - (normalizedDist * normalizedDist)) * getDepthVariation(x, z);
                int targetY = (int) Math.floor(waterTop - type.depth() * depthFraction);
                targetY = Math.max(targetY, minY + 1);

                Block bedBlock = getBedBlock(x, z, type.bedBlocks());
                column.kind = ColumnState.CHANNEL;
                // the bed goes on the channel floor unless that spot is already under water
                // (an earlier, deeper river or the sea floor)
                if (column.top >= targetY) {
                    column.top = targetY;
                    column.bed = bedBlock;
                    column.bedBottom = targetY;
                }

                double fordDistSq = index.fordDistSq(x, z);
                if (fordDistSq < index.fordCorridorWidth * index.fordCorridorWidth) {
                    double fordInfluence = 1.0 - smoothStep(Math.sqrt(fordDistSq) / index.fordCorridorWidth);
                    int fordTopY = Math.min((int) Math.round(targetY + fordInfluence * ((waterTop - 1) - targetY)), waterTop - 1);
                    if (fordTopY > targetY) {
                        // a shallow crossing raised out of the bed, same block as the bed
                        column.bedBottom = Math.min(column.bedBottom, targetY + 1);
                        column.top = Math.max(column.top, fordTopY);
                        column.bed = bedBlock;
                    }
                }
            } else {
                // bank: slope from the surrounding terrain down to the water line
                int naturalY = Math.max(column.top, waterTop);
                double smoothT = smoothStep((perturbedDist - halfWidth) / transitionBandWidth);
                int targetY = (int) Math.round(waterTop + (naturalY - waterTop) * smoothT);
                if (targetY < column.top) {
                    column.top = targetY;
                }
                if (column.kind == ColumnState.NONE) {
                    column.kind = ColumnState.BANK;
                }
            }
        }
    }

    /**
     * Block pass after the surface rules and roads: lays the river bed and keeps channels open
     * through anything built on the terrain in between (roads, walls) - air above the water
     * line, water below it, tree blocks left alone like before.
     */
    public static void generateRivers(@NotNull ChunkAccess chunk, @NotNull CTGenTerrain.Tile tile, int waterTop) {
        if (!tile.hasRivers()) return;

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (tile.riverKind(lx, lz) != ColumnState.CHANNEL) continue;
                int floor = tile.floor(lx, lz);
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz);

                for (int y = top; y >= floor; y--) {
                    pos.set(minX + lx, y, minZ + lz);
                    BlockState state = chunk.getBlockState(pos);
                    if (isTreeBlock(state)) continue;
                    BlockState target = y <= waterTop ? water : air;
                    if (state != target) {
                        chunk.setBlockState(pos, target, false);
                    }
                }

                Block bed = tile.riverBed(lx, lz);
                if (bed != null) {
                    BlockState bedState = bed.defaultBlockState();
                    for (int y = tile.riverBedBottom(lx, lz); y < floor; y++) {
                        pos.set(minX + lx, y, minZ + lz);
                        chunk.setBlockState(pos, bedState, false);
                    }
                }
            }
        }
    }

    /**
     * Distance from a block position to the nearest point on the river's (meandered) spline, or
     * {@link Double#MAX_VALUE} if nothing is within {@code maxDist}. Bounded searches are what
     * make this cheap enough for per-column callers like the biome source.
     */
    public static double distanceToRiver(River river, double blockX, double blockZ, double maxDist) {
        RiverIndex index = index(river);
        if (index == null) return Double.MAX_VALUE;
        double distSq = index.nearestDistSq(blockX, blockZ, maxDist);
        return distSq > maxDist * maxDist ? Double.MAX_VALUE : Math.sqrt(distSq);
    }

    /** Unbounded variant - only meant for occasional callers, it searches up to 512 blocks. */
    public static double distanceToRiver(River river, double blockX, double blockZ) {
        return distanceToRiver(river, blockX, blockZ, 512);
    }

    public static boolean isNearRiver(River river, double blockX, double blockZ, double margin) {
        RiverIndex index = index(river);
        return index != null
                && blockX >= index.minX - margin && blockX <= index.maxX + margin
                && blockZ >= index.minZ - margin && blockZ <= index.maxZ + margin;
    }

    public static void clearCaches() {
        synchronized (RiverGenerator.class) {
            INDEXES = new IdentityHashMap<>();
        }
        CTGenTerrain.invalidateAll();
    }

    private static RiverIndex index(River river) {
        RiverIndex index = INDEXES.get(river);
        if (index != null || river.waypoints().size() < 2) {
            return index;
        }
        synchronized (RiverGenerator.class) {
            index = INDEXES.get(river);
            if (index == null) {
                index = new RiverIndex(river);
                Map<River, RiverIndex> copy = new IdentityHashMap<>(INDEXES);
                copy.put(river, index);
                INDEXES = copy;
            }
            return index;
        }
    }

    private static final class RiverIndex {
        final River river;
        final double baseHalfWidth;
        final double fordCorridorWidth;
        // beyond this distance from the spline nothing of the river (bank included) can reach -
        // the transition band's outer edge, padded generously for the width/bank-roughness noise
        final double maxSearchDist;
        final double maxSearchDistSq;
        final int minX, maxX, minZ, maxZ;
        private final Long2ObjectOpenHashMap<int[]> grid;
        private volatile double[][] fords;

        RiverIndex(River river) {
            this.river = river;
            RiverType type = river.type();
            this.baseHalfWidth = type.width() / 2.0;
            this.fordCorridorWidth = baseHalfWidth * 12.0;
            this.maxSearchDist = baseHalfWidth * (1.0 + 2.0 * type.transitionMultiplier()) * 1.2 + 8;
            this.maxSearchDistSq = maxSearchDist * maxSearchDist;

            List<int[]> points = computeSplinePoints(river);
            Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int[] pt : points) {
                IntArrayList cell = cells.computeIfAbsent(cellKey(Math.floorDiv(pt[0], GRID_CELL), Math.floorDiv(pt[1], GRID_CELL)), k -> new IntArrayList(32));
                cell.add(pt[0]);
                cell.add(pt[1]);
                minX = Math.min(minX, pt[0]);
                maxX = Math.max(maxX, pt[0]);
                minZ = Math.min(minZ, pt[1]);
                maxZ = Math.max(maxZ, pt[1]);
            }
            this.grid = new Long2ObjectOpenHashMap<>(cells.size());
            cells.long2ObjectEntrySet().forEach(e -> grid.put(e.getLongKey(), e.getValue().toIntArray()));
            int margin = (int) Math.ceil(maxSearchDist);
            this.minX = minX - margin;
            this.maxX = maxX + margin;
            this.minZ = minZ - margin;
            this.maxZ = maxZ + margin;
        }

        boolean overlaps(int x0, int z0, int x1, int z1) {
            return x1 >= minX && x0 <= maxX && z1 >= minZ && z0 <= maxZ;
        }

        /**
         * Squared distance to the nearest spline point, searching rings of grid cells outward
         * and stopping as soon as no farther ring can beat the best hit. Returns
         * {@link Double#MAX_VALUE} if nothing is within {@code maxDist}.
         */
        double nearestDistSq(double x, double z, double maxDist) {
            int cx = Math.floorDiv((int) Math.floor(x), GRID_CELL);
            int cz = Math.floorDiv((int) Math.floor(z), GRID_CELL);
            int maxRing = (int) Math.ceil(maxDist / GRID_CELL) + 1;
            double best = Double.MAX_VALUE;
            for (int ring = 0; ring <= maxRing; ring++) {
                // every cell of this ring is at least (ring - 1) whole cells away
                double ringMin = (ring - 1) * (double) GRID_CELL;
                if (ringMin > 0 && ringMin * ringMin >= best) break;
                for (int dz = -ring; dz <= ring; dz++) {
                    boolean edgeRow = dz == -ring || dz == ring;
                    for (int dx = -ring; dx <= ring; dx += edgeRow ? 1 : ring * 2) {
                        int[] cell = grid.get(cellKey(cx + dx, cz + dz));
                        if (cell != null) {
                            for (int i = 0; i < cell.length; i += 2) {
                                double ddx = x - cell[i];
                                double ddz = z - cell[i + 1];
                                double d = ddx * ddx + ddz * ddz;
                                if (d < best) best = d;
                            }
                        }
                        if (ring == 0) break;
                    }
                }
            }
            return best;
        }

        double fordDistSq(double x, double z) {
            double[][] segments = fords;
            if (segments == null) {
                segments = computeFordSegments();
                fords = segments;
            }
            double min = Double.MAX_VALUE;
            for (double[] seg : segments) {
                double d = distToSegmentSq(x, z, seg[0], seg[1], seg[2], seg[3]);
                if (d < min) min = d;
            }
            return min;
        }

        // road segments that cross this river - every road sampled every ~4 blocks, kept where
        // the sample lies inside the channel
        private double[][] computeFordSegments() {
            List<double[]> fordSegments = new ArrayList<>();
            double halfWidth = baseHalfWidth;
            RoadNetworkLoader.getNetwork().ifPresent(network -> {
                for (var road : network.roads()) {
                    if (network.roadTypes().get(road.type()) == null) continue;

                    List<Waypoint> wps = road.waypoints();
                    for (int i = 0; i < wps.size() - 1; i++) {
                        Waypoint from = wps.get(i);
                        Waypoint to = wps.get(i + 1);
                        double dx = to.x() - from.x();
                        double dz = to.z() - from.z();
                        double len = Math.sqrt(dx * dx + dz * dz);
                        // segment can't reach the river at all
                        if (Math.max(from.x(), to.x()) + len < minX || Math.min(from.x(), to.x()) - len > maxX
                                || Math.max(from.z(), to.z()) + len < minZ || Math.min(from.z(), to.z()) - len > maxZ) {
                            continue;
                        }
                        int steps = Math.max(8, (int) len / 4);
                        double midX = (from.x() + to.x()) / 2.0;
                        double midZ = (from.z() + to.z()) / 2.0;
                        double perpX = -dz / len;
                        double perpZ = dx / len;
                        double controlX = midX + perpX * len * to.curve();
                        double controlZ = midZ + perpZ * len * to.curve();

                        double prevX = 0, prevZ = 0;
                        boolean hasPrev = false;
                        for (int s = 0; s <= steps; s++) {
                            double t = (double) s / steps;
                            double rx = (1 - t) * (1 - t) * from.x() + 2 * (1 - t) * t * controlX + t * t * to.x();
                            double rz = (1 - t) * (1 - t) * from.z() + 2 * (1 - t) * t * controlZ + t * t * to.z();
                            if (hasPrev && nearestDistSq(rx, rz, halfWidth) < halfWidth * halfWidth) {
                                fordSegments.add(new double[]{prevX, prevZ, rx, rz});
                            }
                            prevX = rx;
                            prevZ = rz;
                            hasPrev = true;
                        }
                    }
                }
            });
            return fordSegments.toArray(new double[0][]);
        }
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // the river's centerline: the waypoint spline sampled about once per block, displaced by the
    // meander noises
    private static List<int[]> computeSplinePoints(River river) {
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
                        // amplitude scales with width (below), so the wavelength must too, or wide
                        // rivers get the same-size bend crammed into the same along-path distance as
                        // a narrow one — i.e. a proportionally much sharper turn. Scaling frequency
                        // down (wavelength up) by width/referenceWidth keeps amplitude:wavelength,
                        // and so the bend's sharpness, roughly constant across river sizes.
                        double referenceWidth = 8.0;
                        double widthScale = referenceWidth / Math.max(1.0, river.type().width());
                        double frequency = (0.0005 + meanderStrength * 0.003) * widthScale;
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
        return points;
    }

    // squared point-to-segment distance — callers doing a nearest-of-many-segments search
    // compare this directly and only sqrt the winning minimum once.
    private static double distToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq == 0) {
            double ex = px - ax;
            double ez = pz - az;
            return ex * ex + ez * ez;
        }
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / lenSq));
        double projX = ax + t * dx;
        double projZ = az + t * dz;
        double fx = px - projX;
        double fz = pz - projZ;
        return fx * fx + fz * fz;
    }

    private static double getWidthMultiplier(double x, double z) {
        double noise = WIDTH_NOISE.getValue(x * WIDTH_FREQUENCY, z * WIDTH_FREQUENCY);
        return 1.0 + noise * 0.10;
    }

    // two octaves, same trick as the cave tunnel's summed sine wallNoise — a coarse wobble
    // plus a finer one layered on top so the bank reads as rough rather than merely offset.
    // Returned in roughly [-1, 1]; callers scale it by how much wobble they want.
    private static double getBankRoughness(double x, double z) {
        double coarse = BANK_NOISE.getValue(x * BANK_NOISE_FREQUENCY, z * BANK_NOISE_FREQUENCY);
        double fine = BANK_NOISE.getValue(x * BANK_NOISE_FINE_FREQUENCY + 500, z * BANK_NOISE_FINE_FREQUENCY + 500);
        return coarse * 0.7 + fine * 0.3;
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

    private static boolean isTreeBlock(@NotNull BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }

    private static double smoothStep(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
