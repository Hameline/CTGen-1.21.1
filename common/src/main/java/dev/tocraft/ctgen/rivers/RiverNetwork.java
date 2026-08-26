package dev.tocraft.ctgen.rivers;

import dev.tocraft.ctgen.roads.Waypoint;

import java.util.List;
import java.util.Map;

public record RiverNetwork(
        List<River> rivers,
        Map<String, River> riversByName
) {
    private static volatile int[] GLOBAL_BOUNDS = null;

    public boolean isInRiverInfluenceZone(double blockX, double blockZ) {
        if (rivers.isEmpty()) return false;
        int[] bounds = getOrComputeGlobalBounds();
        return blockX >= bounds[0] && blockX <= bounds[1]
                && blockZ >= bounds[2] && blockZ <= bounds[3];
    }

    private int[] getOrComputeGlobalBounds() {
        if (GLOBAL_BOUNDS != null) return GLOBAL_BOUNDS;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (River river : rivers) {
            int influence = (int)(river.type().width() * river.type().transitionMultiplier() * 1.25) + 50;
            for (Waypoint wp : river.waypoints()) {
                minX = Math.min(minX, (int) wp.x() - influence);
                maxX = Math.max(maxX, (int) wp.x() + influence);
                minZ = Math.min(minZ, (int) wp.z() - influence);
                maxZ = Math.max(maxZ, (int) wp.z() + influence);
            }
        }
        GLOBAL_BOUNDS = new int[]{minX, maxX, minZ, maxZ};
        return GLOBAL_BOUNDS;
    }

    public static void clearBoundsCache() {
        GLOBAL_BOUNDS = null;
    }

    public double getTerrainModifierAt(double blockX, double blockZ, int seaLevel) {
        double maxModifier = 0;

        for (River river : rivers) {
            double halfWidth = river.type().width() / 2.0;
            double transitionWidth = halfWidth * 3.0;

            // cheap per-river bounding-box reject before touching the (still non-trivial)
            // spatial-index lookup — skips rivers that can't possibly matter here at all
            if (!RiverGenerator.isNearRiver(river, blockX, blockZ, transitionWidth)) continue;

            double dist = RiverGenerator.distanceToRiver(river, blockX, blockZ);
            if (dist >= transitionWidth) continue;

            if (dist < halfWidth) {
                double normalizedDist = dist / halfWidth;
                double depthFraction = 1.0 - (normalizedDist * normalizedDist);
                maxModifier = Math.max(maxModifier, river.type().depth() * depthFraction);
            } else {
                double transitionT = (dist - halfWidth) / (transitionWidth - halfWidth);
                double smoothT = smoothStep(transitionT);
                double depthFraction = 1.0 - smoothT;
                maxModifier = Math.max(maxModifier, river.type().depth() * depthFraction);
            }
        }

        return maxModifier;
    }

    public boolean isVisibleRiverAt(double blockX, double blockZ) {
        for (River river : rivers) {
            if (!river.type().visibleOnMap()) continue;
            double halfWidth = river.type().width() / 2.0;
            if (!RiverGenerator.isNearRiver(river, blockX, blockZ, halfWidth)) continue;
            if (RiverGenerator.distanceToRiver(river, blockX, blockZ) < halfWidth) return true;
        }
        return false;
    }

    private double smoothStep(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
