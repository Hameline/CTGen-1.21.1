package dev.tocraft.ctgen.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.tocraft.ctgen.CTerrainGeneration;
import dev.tocraft.ctgen.data.MapInfoAccessor;
import dev.tocraft.ctgen.worldgen.MapSettings;
import dev.tocraft.ctgen.worldgen.terrain.CTGenTerrain;
import dev.tocraft.ctgen.worldgen.terrain.CTGenTerrain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

public record CTGAboveSurfaceCondition(int depth) implements SurfaceRules.ConditionSource {
    public static final KeyDispatchDataCodec<CTGAboveSurfaceCondition> CODEC = KeyDispatchDataCodec.of(
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                            Codec.INT.optionalFieldOf("depth", 5).forGetter(CTGAboveSurfaceCondition::depth))
                    .apply(instance, CTGAboveSurfaceCondition::new)));

    @Override
    public @NotNull KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() {
        return CODEC;
    }

    @Override
    public SurfaceRules.Condition apply(final SurfaceRules.Context surfaceRuleContext) {
        MapSettings settings = ((MapInfoAccessor) (Object) surfaceRuleContext).ctgen$getSettings();
        SimplexNoise noise = ((MapInfoAccessor) (Object) surfaceRuleContext).ctgen$getNoise();
        // resolved per column: apply() runs once per chunk, before the context has moved to any
        // column, so reading the height here gave one constant elevation for the whole world
        CTGenTerrain terrain = settings != null && noise != null ? settings.terrain(noise) : null;
        return new SurfaceRules.LazyYCondition(surfaceRuleContext) {
            @Override
            protected boolean compute() {
                return terrain == null
                        || this.context.blockY >= terrain.floor(this.context.blockX, this.context.blockZ) - CTGAboveSurfaceCondition.this.depth;
            }
        };
    }

    public static void register(@NotNull BiConsumer<ResourceLocation, MapCodec<? extends SurfaceRules.ConditionSource>> registerFunc) {
        registerFunc.accept(CTerrainGeneration.id("above_preliminary_surface"), CTGAboveSurfaceCondition.CODEC.codec());
    }
}