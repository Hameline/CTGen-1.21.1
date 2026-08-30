package dev.tocraft.ctgen.worldgen.noise;

import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.Collections;

@SuppressWarnings("unused")
public class CTGenNoiseSettings {
    // min_y unchanged at -64; height raised from vanilla's 384 to 1072 (both multiples of 16, as
    // Minecraft requires) so the top build height goes from Y=319 to Y=1007 - the closest multiple
    // of 16 clearing the requested 1000 (1056 would only reach Y=991).
    // CTGenDimensionTypes.OVERWORLD mirrors these same min_y/height values — the two MUST stay in
    // sync, since a dimension's chunk generator noise settings and its dimension type both encode
    // world height and Minecraft breaks if they disagree.
    protected static final NoiseSettings OVERWORLD_NOISE_SETTINGS = NoiseSettings.create(-64, 1072, 1, 2);

    // registered under vanilla's OWN "minecraft:overworld" key (not a new ctgen: id) so this
    // OVERRIDES vanilla's built-in noise settings — any dimension json using the standard
    // "noise_gen_settings": "minecraft:overworld" (the default a MapSettings example would use)
    // picks up the taller height automatically, no datapack change required.
    public static final ResourceKey<NoiseGeneratorSettings> OVERWORLD = NoiseGeneratorSettings.OVERWORLD;

    public static void bootstrap(BootstrapContext<NoiseGeneratorSettings> context) {
        context.register(OVERWORLD, overworld(context));
    }

    private static NoiseGeneratorSettings overworld(BootstrapContext<?> context) {
        return new NoiseGeneratorSettings(
                OVERWORLD_NOISE_SETTINGS,
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                NoiseRouterData.overworld(
                        context.lookup(Registries.DENSITY_FUNCTION),
                        context.lookup(Registries.NOISE),
                        false,  // isNoiseCavesEnabled — large cheese/spaghetti caves
                        true    // isLargeCaves — amplified cave generation, generates higher
                ),
                CTGenSurface.overworld(),
                Collections.emptyList(),
                63,
                false,
                true,
                true,
                true
        );
    }
}
