package dev.tocraft.ctgen.walls;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.util.Codecs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public record WallSection(
        int height,
        List<Block> blocks,
        boolean jaggedIce,
        boolean snow
) {
    public static final Codec<WallSection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("height").forGetter(WallSection::height),
            Codec.list(Codecs.BLOCK).optionalFieldOf("blocks", List.of(Blocks.ICE)).forGetter(WallSection::blocks),
            Codec.BOOL.optionalFieldOf("jagged_ice", false).forGetter(WallSection::jaggedIce),
            Codec.BOOL.optionalFieldOf("snow", false).forGetter(WallSection::snow)
    ).apply(instance, instance.stable(WallSection::new)));
}