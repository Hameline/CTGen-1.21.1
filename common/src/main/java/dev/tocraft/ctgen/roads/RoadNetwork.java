package dev.tocraft.ctgen.roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;

public record RoadNetwork(Map<String, RoadType> roadTypes, List<Road> roads) {
    public static final Codec<RoadNetwork> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, RoadType.CODEC).fieldOf("road_types").forGetter(RoadNetwork::roadTypes),
            Codec.list(Road.CODEC).fieldOf("roads").forGetter(RoadNetwork::roads)
    ).apply(instance, RoadNetwork::new));
}