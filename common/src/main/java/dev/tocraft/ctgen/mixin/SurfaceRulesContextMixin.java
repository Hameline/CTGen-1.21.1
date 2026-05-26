package dev.tocraft.ctgen.mixin;

import dev.tocraft.ctgen.data.MapInfoAccessor;
import dev.tocraft.ctgen.worldgen.MapSettings;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Supplier;

@Mixin(SurfaceRules.Context.class)
public class SurfaceRulesContextMixin implements MapInfoAccessor {
    @Shadow
    Supplier<Holder<Biome>> biome;

    @Unique
    private Supplier<MapSettings> ctgen$settings = () -> null;
    @Unique
    private Supplier<SimplexNoise> ctgen$noise = () -> null;

    @Unique
    @Override
    public void ctgen$setSettings(Supplier<MapSettings> settings) {
        this.ctgen$settings = settings;
    }

    @Unique
    @Override
    public MapSettings ctgen$getSettings() {
        return this.ctgen$settings.get();
    }

    @Unique
    @Override
    public void ctgen$setNoise(Supplier<SimplexNoise> noise) {
        this.ctgen$noise = noise;
    }

    @Unique
    @Override
    public SimplexNoise ctgen$getNoise() {
        return this.ctgen$noise.get();
    }

    @Unique
    public Holder<Biome> ctgen$getBiome() {
        return this.biome.get();
    }
}