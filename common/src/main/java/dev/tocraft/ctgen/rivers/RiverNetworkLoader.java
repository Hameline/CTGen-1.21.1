package dev.tocraft.ctgen.rivers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import dev.tocraft.ctgen.roads.Waypoint;
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

public class RiverNetworkLoader extends SimplePreparableReloadListener<Optional<RiverNetwork>> {
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "rivers_gen";
    private static RiverNetwork CURRENT_NETWORK = null;

    private final net.minecraft.core.RegistryAccess registryAccess;

    public RiverNetworkLoader(net.minecraft.core.RegistryAccess registryAccess) {
        this.registryAccess = registryAccess;
    }

    @Override
    protected @NotNull Optional<RiverNetwork> prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        List<River> allRivers = new ArrayList<>();
        Map<String, River> riversByName = new HashMap<>();

        FileToIdConverter converter = new FileToIdConverter(DIRECTORY, ".json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);

        if (resources.isEmpty()) return Optional.empty();

        // use registry ops so biome holders can be resolved
        var ops = net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, registryAccess);

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open())) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                if (!json.isJsonObject()) continue;

                JsonObject root = json.getAsJsonObject();
                if (!root.has("rivers")) continue;

                for (JsonElement riverEl : root.getAsJsonArray("rivers")) {
                    River.CODEC.parse(ops, riverEl)
                            .resultOrPartial(error -> LogUtils.getLogger().error("Failed to parse river in {}: {}", id, error))
                            .ifPresent(river -> {
                                allRivers.add(river);
                                String name = id.toString() + "_" + allRivers.size();
                                riversByName.put(name, river);
                            });
                }

                LogUtils.getLogger().info("Loaded river file: {}", id);
            } catch (IOException e) {
                LogUtils.getLogger().error("Failed to load river file {}", id, e);
            }
        }

        if (allRivers.isEmpty()) return Optional.empty();

        resolveConnections(allRivers, riversByName);

        LogUtils.getLogger().info("Loaded river network with {} rivers", allRivers.size());
        return Optional.of(new RiverNetwork(allRivers, riversByName));
    }

    private void resolveConnections(List<River> rivers, Map<String, River> byName) {
        for (River river : rivers) {
            for (String connectionName : river.connectsTo()) {
                River connected = byName.get(connectionName);
                if (connected != null) {
                    // handled by proximity — both rivers in list
                }
            }
        }
    }

    @Override
    protected void apply(@NotNull Optional<RiverNetwork> network, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        CURRENT_NETWORK = network.orElse(null);
        if (CURRENT_NETWORK != null) {
            LogUtils.getLogger().info("Applied river network with {} rivers", CURRENT_NETWORK.rivers().size());
        }
    }

    @Override
    public @NotNull String getName() {
        return "River Network Loader";
    }

    public static Optional<RiverNetwork> getNetwork() {
        return Optional.ofNullable(CURRENT_NETWORK);
    }
}