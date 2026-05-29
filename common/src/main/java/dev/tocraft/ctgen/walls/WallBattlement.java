package dev.tocraft.ctgen.walls;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.util.Codecs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public record WallBattlement(
        List<Block> floorBlocks,
        List<Block> battlementBlocks,
        List<Block> battlementSlabs,
        List<Block> battlementStairs
) {
    public static final Codec<WallBattlement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(Codecs.BLOCK).optionalFieldOf("floor_blocks", List.of(Blocks.STONE_BRICKS)).forGetter(WallBattlement::floorBlocks),
            Codec.list(Codecs.BLOCK).optionalFieldOf("battlement_blocks", List.of(Blocks.STONE_BRICKS)).forGetter(WallBattlement::battlementBlocks),
            Codec.list(Codecs.BLOCK).optionalFieldOf("battlement_slabs", List.of(Blocks.STONE_BRICK_SLAB)).forGetter(WallBattlement::battlementSlabs),
            Codec.list(Codecs.BLOCK).optionalFieldOf("battlement_stairs", List.of(Blocks.STONE_BRICK_STAIRS)).forGetter(WallBattlement::battlementStairs)
    ).apply(instance, instance.stable(WallBattlement::new)));
}