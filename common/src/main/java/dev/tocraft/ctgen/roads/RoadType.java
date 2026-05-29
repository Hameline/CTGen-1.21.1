package dev.tocraft.ctgen.roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.util.Codecs;
import net.minecraft.world.level.block.Block;

import java.util.List;

public record RoadType(List<Block> blocks, List<Block> slabs, int width) {
    public static final Codec<RoadType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(Codecs.BLOCK).fieldOf("blocks").forGetter(RoadType::blocks),
            Codec.list(Codecs.BLOCK).fieldOf("slabs").forGetter(RoadType::slabs),
            Codec.INT.optionalFieldOf("width", 3).forGetter(RoadType::width)
    ).apply(instance, RoadType::new));
}