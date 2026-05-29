package dev.tocraft.ctgen.data;

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

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapWaypointLoader extends SimplePreparableReloadListener<Map<ResourceLocation, List<MapWaypoint>>> {

    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "waypoints";

    // datapack waypoints — keyed by dimension id
    private static final Map<ResourceLocation, List<MapWaypoint>> DATAPACK_WAYPOINTS = new HashMap<>();

    // runtime waypoints added programmatically — keyed by dimension id
    private static final Map<ResourceLocation, List<MapWaypoint>> RUNTIME_WAYPOINTS = new HashMap<>();

    @Override
    protected @NotNull Map<ResourceLocation, List<MapWaypoint>> prepare(
            @NotNull ResourceManager resourceManager,
            @NotNull ProfilerFiller profiler
    ) {
        Map<ResourceLocation, List<MapWaypoint>> result = new HashMap<>();

        FileToIdConverter converter = new FileToIdConverter(DIRECTORY, ".json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            // file id is used as dimension id — e.g. agotmod:known_world
            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open())) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                if (!json.isJsonObject()) continue;

                var root = json.getAsJsonObject();
                if (!root.has("waypoints")) continue;

                List<MapWaypoint> waypoints = new ArrayList<>();
                for (JsonElement el : root.getAsJsonArray("waypoints")) {
                    MapWaypoint.CODEC.parse(JsonOps.INSTANCE, el)
                            .resultOrPartial(err -> LogUtils.getLogger().error(
                                    "Failed to parse waypoint in {}: {}", id, err))
                            .ifPresent(waypoints::add);
                }

                if (!waypoints.isEmpty()) {
                    result.put(id, waypoints);
                    LogUtils.getLogger().info("Loaded {} waypoints for dimension {}", waypoints.size(), id);
                }
            } catch (Exception e) {
                LogUtils.getLogger().error("Failed to load waypoint file {}", id, e);
            }
        }

        return result;
    }

    @Override
    protected void apply(
            @NotNull Map<ResourceLocation, List<MapWaypoint>> data,
            @NotNull ResourceManager resourceManager,
            @NotNull ProfilerFiller profiler
    ) {
        DATAPACK_WAYPOINTS.clear();
        DATAPACK_WAYPOINTS.putAll(data);
    }

    @Override
    public @NotNull String getName() {
        return "Map Waypoint Loader";
    }

    /**
     * Returns all waypoints for a dimension — both datapack and runtime combined.
     */
    public static List<MapWaypoint> getWaypoints(ResourceLocation dimensionId) {
        List<MapWaypoint> all = new ArrayList<>();
        all.addAll(DATAPACK_WAYPOINTS.getOrDefault(dimensionId, List.of()));
        all.addAll(RUNTIME_WAYPOINTS.getOrDefault(dimensionId, List.of()));
        return all;
    }

    /**
     * Adds a waypoint programmatically at runtime for a specific dimension.
     * Runtime waypoints are kept in memory and saved separately from datapack waypoints.
     */
    public static void addWaypoint(ResourceLocation dimensionId, MapWaypoint waypoint) {
        RUNTIME_WAYPOINTS.computeIfAbsent(dimensionId, k -> new ArrayList<>()).add(waypoint);
        saveRuntimeWaypoints();
    }

    /**
     * Removes a runtime waypoint by name for a specific dimension.
     */
    public static void removeWaypoint(ResourceLocation dimensionId, String name) {
        List<MapWaypoint> list = RUNTIME_WAYPOINTS.get(dimensionId);
        if (list != null) {
            list.removeIf(wp -> wp.name().equals(name));
            if (list.isEmpty()) RUNTIME_WAYPOINTS.remove(dimensionId);
        }
        saveRuntimeWaypoints();
    }

    /**
     * Clears all runtime waypoints for a dimension.
     */
    public static void clearRuntimeWaypoints(ResourceLocation dimensionId) {
        RUNTIME_WAYPOINTS.remove(dimensionId);
        saveRuntimeWaypoints();
    }

    /**
     * Loads runtime waypoints from disk. Call this on world load.
     * Runtime waypoints are stored in the world save folder.
     */
    public static void loadRuntimeWaypoints(com.google.gson.JsonObject saved) {
        RUNTIME_WAYPOINTS.clear();
        if (saved == null) return;
        for (var entry : saved.entrySet()) {
            ResourceLocation dimId = ResourceLocation.parse(entry.getKey());
            List<MapWaypoint> waypoints = new ArrayList<>();
            for (JsonElement el : entry.getValue().getAsJsonArray()) {
                MapWaypoint.CODEC.parse(JsonOps.INSTANCE, el)
                        .resultOrPartial(err -> LogUtils.getLogger().error("Failed to load runtime waypoint: {}", err))
                        .ifPresent(waypoints::add);
            }
            if (!waypoints.isEmpty()) {
                RUNTIME_WAYPOINTS.put(dimId, waypoints);
            }
        }
    }

    /**
     * Serializes runtime waypoints to JSON for saving.
     */
    public static com.google.gson.JsonObject serializeRuntimeWaypoints() {
        var root = new com.google.gson.JsonObject();
        for (var entry : RUNTIME_WAYPOINTS.entrySet()) {
            var arr = new com.google.gson.JsonArray();
            for (MapWaypoint wp : entry.getValue()) {
                MapWaypoint.CODEC.encodeStart(JsonOps.INSTANCE, wp)
                        .resultOrPartial(err -> LogUtils.getLogger().error("Failed to serialize waypoint: {}", err))
                        .ifPresent(arr::add);
            }
            root.add(entry.getKey().toString(), arr);
        }
        return root;
    }

    private static void saveRuntimeWaypoints() {
        // persistence is handled by the event listener that calls serializeRuntimeWaypoints()
        // and writes to the world save folder — see CTGNeoForgeEventListener
    }
}