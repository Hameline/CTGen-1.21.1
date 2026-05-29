package dev.tocraft.ctgen.zone;

import dev.tocraft.ctgen.CTerrainGeneration;
import dev.tocraft.ctgen.xtend.CTRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

public final class Zones {
    // Northern Continent
    public static final ResourceKey<Zone> STONY_FLATS = getKey("stony_flats");
    public static final ResourceKey<Zone> SNOWY_FLATS = getKey("snowy_flats");
    public static final ResourceKey<Zone> SNOWY_SLOPES = getKey("snowy_slopes");
    public static final ResourceKey<Zone> SNOWY_MOUNTAINS = getKey("snowy_mountains");
    public static final ResourceKey<Zone> FROZEN_LAKE = getKey("frozen_lake");
    public static final ResourceKey<Zone> FROZEN_RIVER = getKey("frozen_river");
    // Eastern Continent
    public static final ResourceKey<Zone> PLAINS = getKey("plains");
    public static final ResourceKey<Zone> FOREST = getKey("forest");
    public static final ResourceKey<Zone> HILLS = getKey("hills");
    public static final ResourceKey<Zone> MOUNTAINS = getKey("mountains");
    public static final ResourceKey<Zone> LAKE = getKey("lake");
    // Western Continent
    public static final ResourceKey<Zone> DESERT = getKey("desert");
    public static final ResourceKey<Zone> BADLANDS = getKey("badlands");
    public static final ResourceKey<Zone> BADLANDS_MOUNTAINS = getKey("badlands_mountains");
    // General Water Biomes
    public static final ResourceKey<Zone> RIVER = getKey("river");
    public static final ResourceKey<Zone> OCEAN = getKey("ocean");
    public static final ResourceKey<Zone> DEEP_OCEAN = getKey("deep_ocean");

    public static void bootstrap(@NotNull BootstrapContext<Zone> context) {
        HolderGetter<Biome> lookup = context.lookup(Registries.BIOME);
        // Northern Continent
        context.register(STONY_FLATS, stonyFlats(lookup).build());
        context.register(SNOWY_FLATS, snowyFlats(lookup).build());
        context.register(SNOWY_SLOPES, snowySlopes(lookup).build());
        context.register(SNOWY_MOUNTAINS, snowyMountains(lookup).build());
        context.register(FROZEN_RIVER, frozenRiver(lookup).build());
        context.register(FROZEN_LAKE, frozenLake(lookup).build());
        // Eastern Continent
        context.register(PLAINS, plains(lookup).build());
        context.register(FOREST, forest(lookup).build());
        context.register(HILLS, hills(lookup).build());
        context.register(MOUNTAINS, mountains(lookup).build());
        context.register(LAKE, lake(lookup).build());
        // Western Continent
        context.register(DESERT, desert(lookup).build());
        context.register(BADLANDS, badlands(lookup).build());
        context.register(BADLANDS_MOUNTAINS, badlandMountains(lookup).build());
        // General Water Biomes
        context.register(RIVER, river(lookup).build());
        context.register(OCEAN, ocean(lookup).build());
        context.register(DEEP_OCEAN, deepOcean(lookup).build());
    }

    public static ZoneBuilder deepOcean(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.DEEP_OCEAN), 1.0)
                .setColor(new Color(0, 35, 85))
                .setTerrainModifier(33);
    }

    public static ZoneBuilder ocean(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.OCEAN), 1.0)
                .setColor(new Color(0, 42, 103))
                .setTerrainModifier(16);
    }

    public static ZoneBuilder river(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.RIVER), 1.0)
                .setColor(new Color(1, 98, 255))
                .setPixelWeight(2)
                .setTerrainModifier(0.5);
    }

    public static ZoneBuilder badlandMountains(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.BADLANDS), 1.0)
                .setColor(new Color(70, 71, 53))
                .setTerrainModifier(24);
    }

    public static ZoneBuilder badlands(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.BADLANDS), 0.6)
                .addBiome(getBiome(lookup, Biomes.WOODED_BADLANDS), 0.4)
                .setColor(new Color(84, 84, 56))
                .setTerrainModifier(12);
    }

    public static ZoneBuilder desert(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.DESERT), 1.0)
                .setColor(new Color(165, 171, 54))
                .setTerrainModifier(4);
    }

    public static ZoneBuilder lake(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.LUKEWARM_OCEAN), 1.0)
                .setColor(new Color(0, 83, 217))
                .setPixelWeight(3);
    }

    public static ZoneBuilder mountains(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.STONY_PEAKS), 0.5)
                .addBiome(getBiome(lookup, Biomes.JAGGED_PEAKS), 0.3)
                .addBiome(getBiome(lookup, Biomes.FROZEN_PEAKS), 0.2)
                .setColor(new Color(130, 130, 130))
                .setTerrainModifier(50);
    }

    public static ZoneBuilder hills(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.WINDSWEPT_GRAVELLY_HILLS), 0.5)
                .addBiome(getBiome(lookup, Biomes.WINDSWEPT_HILLS), 0.5)
                .setColor(new Color(151, 151, 151))
                .setTerrainModifier(18);
    }

    public static ZoneBuilder forest(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.FOREST), 0.5)
                .addBiome(getBiome(lookup, Biomes.BIRCH_FOREST), 0.3)
                .addBiome(getBiome(lookup, Biomes.DARK_FOREST), 0.2)
                .setColor(new Color(43, 70, 43))
                .setTerrainModifier(10);
    }

    public static ZoneBuilder plains(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.PLAINS), 0.6)
                .addBiome(getBiome(lookup, Biomes.MEADOW), 0.4)
                .setColor(new Color(57, 95, 57));
    }

    public static ZoneBuilder frozenLake(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.FROZEN_OCEAN), 1.0)
                .setColor(new Color(78, 126, 204))
                .setPixelWeight(3);
    }

    public static ZoneBuilder frozenRiver(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.FROZEN_RIVER), 1.0)
                .setColor(new Color(87, 145, 240))
                .setPixelWeight(2);
    }

    public static ZoneBuilder snowyMountains(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.JAGGED_PEAKS), 0.6)
                .addBiome(getBiome(lookup, Biomes.FROZEN_PEAKS), 0.4)
                .setColor(new Color(168, 168, 168))
                .setTerrainModifier(50);
    }

    public static ZoneBuilder snowySlopes(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.SNOWY_SLOPES), 0.7)
                .addBiome(getBiome(lookup, Biomes.GROVE), 0.3)
                .setColor(new Color(192, 192, 192))
                .setTerrainModifier(20)
                .setPixelWeight(1.5);
    }

    public static ZoneBuilder snowyFlats(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.SNOWY_PLAINS), 0.6)
                .addBiome(getBiome(lookup, Biomes.SNOWY_SLOPES), 0.4)
                .setColor(new Color(217, 217, 217));
    }

    public static ZoneBuilder stonyFlats(@NotNull HolderGetter<Biome> lookup) {
        return new ZoneBuilder()
                .addBiome(getBiome(lookup, Biomes.STONY_SHORE), 0.7)
                .addBiome(getBiome(lookup, Biomes.STONY_PEAKS), 0.3)
                .setColor(new Color(130, 140, 130));
    }

    public static @NotNull Holder<Biome> getBiome(@NotNull HolderGetter<Biome> lookup, ResourceKey<Biome> biome) {
        return lookup.getOrThrow(biome);
    }

    private static @NotNull ResourceKey<Zone> getKey(String name) {
        return ResourceKey.create(CTRegistries.ZONES_KEY, CTerrainGeneration.id(name));
    }
}