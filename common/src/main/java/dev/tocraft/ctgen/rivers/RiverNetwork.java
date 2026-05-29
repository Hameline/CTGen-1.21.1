package dev.tocraft.ctgen.rivers;

import java.util.List;
import java.util.Map;

public record RiverNetwork(
        List<River> rivers,
        Map<String, River> riversByName
) {
    /**
     * Returns the terrain height modifier for a block position.
     * Returns empty if not near any river.
     * If near a river, returns the Y level the terrain should be carved to.
     */
    public java.util.OptionalDouble getRiverDepthAt(double blockX, double blockZ, int seaLevel) {
        double bestCarveY = Double.MAX_VALUE;
        boolean nearRiver = false;

        for (River river : rivers) {
            double dist = river.distanceTo(blockX, blockZ);
            double halfWidth = river.type().width() / 2.0;
            double transitionWidth = halfWidth * 3.0;

            if (dist < transitionWidth) {
                nearRiver = true;

                if (dist < halfWidth) {
                    // inside river — full U-shape depth
                    double normalizedDist = dist / halfWidth;
                    // U-shape: depth is max at center, 0 at edge
                    double depthFraction = 1.0 - (normalizedDist * normalizedDist);
                    double carveY = seaLevel - (river.type().depth() * depthFraction);
                    bestCarveY = Math.min(bestCarveY, carveY);
                } else {
                    // transition zone — smoothstep blend from river edge to terrain
                    double transitionT = (dist - halfWidth) / (transitionWidth - halfWidth);
                    double smoothT = smoothStep(transitionT);
                    // at dist=halfWidth: smoothT=0 → river depth
                    // at dist=transitionWidth: smoothT=1 → terrain level (no carve)
                    // we just carve shallower the further we are
                    double depthFraction = (1.0 - smoothT) * 0.0; // edge depth = 0
                    double carveY = seaLevel - (river.type().depth() * depthFraction);
                    // only carve if we're adding actual depth
                    if (carveY < seaLevel) {
                        bestCarveY = Math.min(bestCarveY, carveY);
                    }
                }
            }
        }

        return nearRiver && bestCarveY < Double.MAX_VALUE
                ? java.util.OptionalDouble.of(bestCarveY)
                : java.util.OptionalDouble.empty();
    }

    /**
     * Returns the height modifier for terrain near a river.
     * This is used to blend terrain smoothly into the river.
     * Returns the amount to subtract from terrain height, or 0 if not near a river.
     */
    public double getTerrainModifierAt(double blockX, double blockZ, int seaLevel) {
        double maxModifier = 0;

        for (River river : rivers) {
            double dist = river.distanceTo(blockX, blockZ);
            double halfWidth = river.type().width() / 2.0;
            double transitionWidth = halfWidth * 3.0;

            if (dist >= transitionWidth) continue;

            if (dist < halfWidth) {
                // inside river — full depth carve
                double normalizedDist = dist / halfWidth;
                double depthFraction = 1.0 - (normalizedDist * normalizedDist);
                maxModifier = Math.max(maxModifier, river.type().depth() * depthFraction);
            } else {
                // transition zone
                double transitionT = (dist - halfWidth) / (transitionWidth - halfWidth);
                double smoothT = smoothStep(transitionT);
                double depthFraction = 1.0 - smoothT;
                maxModifier = Math.max(maxModifier, river.type().depth() * depthFraction);
            }
        }

        return maxModifier;
    }

    /**
     * Returns true if this position is close enough to a river to be visible as a map dot.
     */
    public boolean isVisibleRiverAt(double blockX, double blockZ) {
        for (River river : rivers) {
            if (!river.type().visibleOnMap()) continue;
            double dist = river.distanceTo(blockX, blockZ);
            if (dist < river.type().width() / 2.0) return true;
        }
        return false;
    }

    private double smoothStep(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}