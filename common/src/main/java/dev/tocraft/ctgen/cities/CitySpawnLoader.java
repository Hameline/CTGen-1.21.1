package dev.tocraft.ctgen.cities;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Datapack-driven loader for city-schematic spawns, following the same
 * {@code data/<namespace>/cities_gen/*.json} convention as
 * {@link dev.tocraft.ctgen.roads.RoadNetworkLoader}/{@link dev.tocraft.ctgen.rivers.RiverNetworkLoader}/
 * {@link dev.tocraft.ctgen.walls.WallNetworkLoader}. This is the entire integration surface a
 * consumer mod needs: for each city, a coordinate JSON ({@link CitySpawnEntry}) plus a
 * same-named {@code .schem} file sitting right next to it in the same {@code cities_gen}
 * directory — both shipped as ordinary datapack resources inside the mod, no external files and
 * no Java code required. The (potentially huge) schematic is extracted out to CTGen's own private
 * on-disk cache and compiled by {@link dev.tocraft.ctgen.cities.schem.SchemCompiler} — see
 * {@link CityPlacer#onCitiesReloaded}.
 */
public class CitySpawnLoader extends SimplePreparableReloadListener<Map<ResourceLocation, CitySpawnEntry>> {
    private static final Gson GSON = new Gson();

    /**
     * Shared with {@link CityPlacer}, which looks up each city's {@code .schem} resource at the
     * same directory/id, just with a {@code .schem} extension instead of {@code .json}.
     */
    static final String DIRECTORY = "cities_gen";
    private static Map<ResourceLocation, CitySpawnEntry> CURRENT_CITIES = Map.of();

    @Override
    protected @NotNull Map<ResourceLocation, CitySpawnEntry> prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        Map<ResourceLocation, CitySpawnEntry> result = new HashMap<>();

        FileToIdConverter converter = new FileToIdConverter(DIRECTORY, ".json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);

        if (resources.isEmpty()) {
            LogUtils.getLogger().info("No city spawn files found in {}", DIRECTORY);
            return result;
        }

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open())) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                CitySpawnEntry.CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> LogUtils.getLogger().error("Failed to parse city spawn {}: {}", id, error))
                        .ifPresent(city -> {
                            result.put(id, city);
                            LogUtils.getLogger().info("Loaded city spawn {} at ({}, {}, {})",
                                    id, city.x(), city.y(), city.z());
                        });
            } catch (IOException e) {
                LogUtils.getLogger().error("Failed to load city spawn file {}", id, e);
            }
        }

        return result;
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, CitySpawnEntry> cities, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        CURRENT_CITIES = cities;
        LogUtils.getLogger().info("Applied {} city spawn(s)", cities.size());
        CityPlacer.onCitiesReloaded(cities, resourceManager);
    }

    @Override
    public @NotNull String getName() {
        return "City Spawn Loader";
    }

    public static Map<ResourceLocation, CitySpawnEntry> getCities() {
        return CURRENT_CITIES;
    }

    public static Optional<CitySpawnEntry> getCity(ResourceLocation id) {
        return Optional.ofNullable(CURRENT_CITIES.get(id));
    }
}
