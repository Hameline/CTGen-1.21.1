package dev.tocraft.ctgen.rivers;

import java.util.List;
import java.util.Map;

public record RiverNetwork(
        List<River> rivers,
        Map<String, River> riversByName
) {
    public boolean isVisibleRiverAt(double blockX, double blockZ) {
        for (River river : rivers) {
            if (!river.type().visibleOnMap()) continue;
            double halfWidth = river.type().width() / 2.0;
            if (!RiverGenerator.isNearRiver(river, blockX, blockZ, halfWidth)) continue;
            if (RiverGenerator.distanceToRiver(river, blockX, blockZ, halfWidth) < halfWidth) return true;
        }
        return false;
    }
}
