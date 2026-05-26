package dev.tocraft.ctgen.roads;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RoadNetworkLoader extends SimplePreparableReloadListener<Optional<RoadNetwork>> {
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "roads_gen";
    private static RoadNetwork CURRENT_NETWORK = null;

    @Override
    protected @NotNull Optional<RoadNetwork> prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        Map<String, RoadType> mergedRoadTypes = new HashMap<>();
        List<Road> mergedRoads = new ArrayList<>();

        FileToIdConverter converter = new FileToIdConverter(DIRECTORY, ".json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);

        if (resources.isEmpty()) {
            LogUtils.getLogger().info("No road files found in {}", DIRECTORY);
            return Optional.empty();
        }

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open())) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                Optional<RoadNetwork> network = RoadNetwork.CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> LogUtils.getLogger().error("Failed to parse road network {}: {}", id, error));

                network.ifPresent(n -> {
                    mergedRoadTypes.putAll(n.roadTypes());
                    mergedRoads.addAll(n.roads());
                    LogUtils.getLogger().info("Loaded road file {} with {} road types and {} roads",
                            id, n.roadTypes().size(), n.roads().size());
                });
            } catch (IOException e) {
                LogUtils.getLogger().error("Failed to load road file {}", id, e);
            }
        }

        if (mergedRoads.isEmpty() && mergedRoadTypes.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new RoadNetwork(mergedRoadTypes, mergedRoads));
    }

    @Override
    protected void apply(@NotNull Optional<RoadNetwork> network, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        CURRENT_NETWORK = network.orElse(null);
        if (CURRENT_NETWORK != null) {
            LogUtils.getLogger().info("Applied road network with {} total road types and {} total roads",
                    CURRENT_NETWORK.roadTypes().size(),
                    CURRENT_NETWORK.roads().size());
        } else {
            LogUtils.getLogger().info("No road network found - checked roads_gen directory");
        }
    }

    @Override
    public @NotNull String getName() {
        return "Road Network Loader";
    }

    public static Optional<RoadNetwork> getNetwork() {
        return Optional.ofNullable(CURRENT_NETWORK);
    }
}