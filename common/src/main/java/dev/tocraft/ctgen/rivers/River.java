package dev.tocraft.ctgen.rivers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.roads.RoadNetwork;
import dev.tocraft.ctgen.roads.RoadNetworkLoader;
import dev.tocraft.ctgen.roads.Waypoint;

import java.util.List;

public record River(
        RiverType type,
        List<Waypoint> waypoints,
        List<String> connectsTo
) {
    public static final Codec<River> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RiverType.CODEC.fieldOf("type").forGetter(River::type),
            Codec.list(Waypoint.CODEC).fieldOf("waypoints").forGetter(River::waypoints),
            Codec.list(Codec.STRING).optionalFieldOf("connects_to", List.of()).forGetter(River::connectsTo)
    ).apply(instance, instance.stable(River::new)));

    public double[] evaluateSpline(double t) {
        List<Waypoint> pts = waypoints;
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

    private double catmullRom(double p0, double p1, double p2, double p3, double t) {
        return 0.5 * ((2 * p1) +
                (-p0 + p2) * t +
                (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t +
                (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    public double distanceTo(double blockX, double blockZ) {
        int samples = Math.max(waypoints.size() * 20, 40);
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double[] pos = evaluateSpline(t);
            double dx = blockX - pos[0];
            double dz = blockZ - pos[1];
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < minDist) minDist = dist;
        }
        return minDist;
    }

    /**
     * Returns true if a road from the network crosses this river near (blockX, blockZ).
     * Used to place fords.
     */
    public boolean isRoadCrossing(double blockX, double blockZ, double halfWidth) {
        return RoadNetworkLoader.getNetwork().map(network -> {
            for (var road : network.roads()) {
                var roadType = network.roadTypes().get(road.type());
                if (roadType == null) continue;
                List<Waypoint> wps = road.waypoints();
                for (int i = 0; i < wps.size() - 1; i++) {
                    Waypoint from = wps.get(i);
                    Waypoint to = wps.get(i + 1);
                    double dist = Math.sqrt(Math.pow(to.x() - from.x(), 2) + Math.pow(to.z() - from.z(), 2));
                    int steps = Math.max(8, (int) dist / 4);
                    for (int s = 0; s <= steps; s++) {
                        double t = (double) s / steps;
                        double rx = from.x() + (to.x() - from.x()) * t;
                        double rz = from.z() + (to.z() - from.z()) * t;
                        double dx = blockX - rx;
                        double dz = blockZ - rz;
                        // ford is 1.5x river width along the river direction
                        if (Math.sqrt(dx * dx + dz * dz) < halfWidth * 1.5) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }).orElse(false);
    }
}