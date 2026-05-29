package dev.tocraft.ctgen.worldgen.noise;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.CTerrainGeneration;
import dev.tocraft.ctgen.data.MapInfoAccessor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

public record CTGTemperatureCondition(float threshold) implements SurfaceRules.ConditionSource {

    public static final KeyDispatchDataCodec<CTGTemperatureCondition> CODEC = KeyDispatchDataCodec.of(
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.FLOAT.fieldOf("threshold").forGetter(CTGTemperatureCondition::threshold)
            ).apply(instance, CTGTemperatureCondition::new)));

    @Override
    public @NotNull KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() {
        return CODEC;
    }

    public static void register(@NotNull BiConsumer<ResourceLocation, MapCodec<? extends SurfaceRules.ConditionSource>> registerFunc) {
        registerFunc.accept(CTerrainGeneration.id("temperature"), CTGTemperatureCondition.CODEC.codec());
    }

    @Override
    public SurfaceRules.Condition apply(final SurfaceRules.Context context) {
        return new SurfaceRules.LazyYCondition(context) {
            @Override
            protected boolean compute() {
                try {
                    Holder<net.minecraft.world.level.biome.Biome> biomeHolder =
                            ((MapInfoAccessor) (Object) context).ctgen$getBiome();

                    if (biomeHolder == null) {
                        LogUtils.getLogger().warn("CTGTemperatureCondition: biome is null at Y {}", context.blockY);
                        return false;
                    }

                    float biomeTemp = biomeHolder.value().getBaseTemperature();
                    float reduction = 0.65f * Math.max(0, context.blockY - 64) / 56.0f;
                    float adjustedTemp = biomeTemp - reduction;

                    LogUtils.getLogger().debug("CTGTemperatureCondition: biome={} temp={} reduction={} adjusted={} threshold={} result={}",
                            biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("unknown"),
                            biomeTemp, reduction, adjustedTemp, threshold, adjustedTemp < threshold);

                    return adjustedTemp < threshold;
                } catch (Exception e) {
                    LogUtils.getLogger().error("CTGTemperatureCondition error", e);
                    return false;
                }
            }
        };
    }
}