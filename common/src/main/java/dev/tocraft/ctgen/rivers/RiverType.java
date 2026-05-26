package dev.tocraft.ctgen.rivers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.util.Codecs;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryFixedCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;

public record RiverType(
        int width,
        int depth,
        boolean visibleOnMap,
        List<Block> bedBlocks,
        double transitionMultiplier,
        double meanderStrength,
        Optional<Holder<Biome>> biome
) {
    public static final Codec<RiverType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("width").forGetter(RiverType::width),
            Codec.INT.fieldOf("depth").forGetter(RiverType::depth),
            Codec.BOOL.optionalFieldOf("visible_on_map", false).forGetter(RiverType::visibleOnMap),
            Codec.list(Codecs.BLOCK).optionalFieldOf("bed_blocks", List.of(
                    Blocks.GRAVEL, Blocks.SAND, Blocks.CLAY
            )).forGetter(RiverType::bedBlocks),
            Codec.DOUBLE.optionalFieldOf("transition_multiplier", 6.0).forGetter(RiverType::transitionMultiplier),
            Codec.DOUBLE.optionalFieldOf("meander_strength", 0.0).forGetter(RiverType::meanderStrength),
            RegistryFixedCodec.create(Registries.BIOME).optionalFieldOf("biome").forGetter(RiverType::biome)
    ).apply(instance, instance.stable(RiverType::new)));
}