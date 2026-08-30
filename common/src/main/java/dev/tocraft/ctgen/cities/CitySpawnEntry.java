package dev.tocraft.ctgen.cities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One city-schematic spawn, as configured by a consumer mod/datapack: where it goes
 * ({@link #x}/{@link #y}/{@link #z}, the world-space position the schematic's own
 * origin — its {@code Offset} — is placed at). The schematic itself is a second datapack
 * resource shipped right alongside this JSON — same directory, same filename, {@code .schem}
 * extension instead of {@code .json} (e.g. {@code cities_gen/white_harbor.json} +
 * {@code cities_gen/white_harbor.schem}) — see {@link CitySpawnLoader}.
 */
public record CitySpawnEntry(int x, int y, int z) {
    public static final Codec<CitySpawnEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(CitySpawnEntry::x),
            Codec.INT.fieldOf("y").forGetter(CitySpawnEntry::y),
            Codec.INT.fieldOf("z").forGetter(CitySpawnEntry::z)
    ).apply(instance, instance.stable(CitySpawnEntry::new)));
}
