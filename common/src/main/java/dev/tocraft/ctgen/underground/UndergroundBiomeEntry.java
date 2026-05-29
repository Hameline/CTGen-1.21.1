package dev.tocraft.ctgen.underground;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public record UndergroundBiomeEntry(Holder<Biome> biome, double weight) {
    public static final Codec<UndergroundBiomeEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Biome.CODEC.fieldOf("biome").forGetter(UndergroundBiomeEntry::biome),
            Codec.DOUBLE.fieldOf("weight").forGetter(UndergroundBiomeEntry::weight)
    ).apply(instance, instance.stable(UndergroundBiomeEntry::new)));
}