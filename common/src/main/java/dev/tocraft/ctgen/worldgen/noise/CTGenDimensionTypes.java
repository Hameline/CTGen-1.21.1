package dev.tocraft.ctgen.worldgen.noise;

import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.OptionalLong;

/**
 * A dimension type matching vanilla's overworld in every respect except build height, registered
 * under vanilla's OWN "minecraft:overworld" key (not a new ctgen: id) so it OVERRIDES vanilla's
 * built-in dimension type — any dimension json using the standard "type": "minecraft:overworld"
 * picks up the taller height automatically, no datapack change required. Stays in sync with
 * {@link CTGenNoiseSettings#OVERWORLD}'s min_y/height — a dimension's chunk generator noise
 * settings and its dimension type must agree on min_y/height/logical_height or the world breaks.
 */
public class CTGenDimensionTypes {
    public static final ResourceKey<DimensionType> OVERWORLD = BuiltinDimensionTypes.OVERWORLD;

    public static void bootstrap(BootstrapContext<DimensionType> context) {
        context.register(OVERWORLD, new DimensionType(
                OptionalLong.empty(),
                true,   // hasSkyLight
                false,  // hasCeiling
                false,  // ultraWarm
                true,   // natural
                1.0,    // coordinateScale
                true,   // bedWorks
                false,  // respawnAnchorWorks
                CTGenNoiseSettings.OVERWORLD_NOISE_SETTINGS.minY(),
                CTGenNoiseSettings.OVERWORLD_NOISE_SETTINGS.height(),
                CTGenNoiseSettings.OVERWORLD_NOISE_SETTINGS.height(),
                BlockTags.INFINIBURN_OVERWORLD,
                BuiltinDimensionTypes.OVERWORLD_EFFECTS,
                0.0f,   // ambientLight
                new DimensionType.MonsterSettings(
                        false,             // piglinSafe
                        true,              // hasRaids
                        UniformInt.of(0, 7),
                        0                  // monsterSpawnBlockLightLimit
                )
        ));
    }
}
