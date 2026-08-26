package dev.tocraft.ctgen.rivers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
        List<String> fallbackNames = new ArrayList<>();
        Map<String, River> riversByName = new HashMap<>();

        FileToIdConverter converter = new FileToIdConverter(DIRECTORY, ".json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);

        if (resources.isEmpty()) return Optional.empty();

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
                                // register by explicit name if provided
                                if (!river.name().isEmpty()) {
                                    riversByName.put(river.name(), river);
                                }
                                // also register by file id + index as fallback
                                String fallbackName = id.toString() + "_" + allRivers.size();
                                riversByName.put(fallbackName, river);
                                fallbackNames.add(fallbackName);
                            });
                }

                LogUtils.getLogger().info("Loaded river file: {}", id);
            } catch (IOException e) {
                LogUtils.getLogger().error("Failed to load river file {}", id, e);
            }
        }

        if (allRivers.isEmpty()) return Optional.empty();

        // validate connects_to targets and strip any that don't resolve, so that
        // river.connectsTo().isEmpty() can be trusted everywhere downstream as
        // "this river actually flows into another one" without re-checking the network
        validateAndCleanConnections(allRivers, fallbackNames, riversByName);

        LogUtils.getLogger().info("Loaded river network with {} rivers", allRivers.size());
        return Optional.of(new RiverNetwork(allRivers, riversByName));
    }

    private void validateAndCleanConnections(List<River> rivers, List<String> fallbackNames, Map<String, River> byName) {
        for (int i = 0; i < rivers.size(); i++) {
            River river = rivers.get(i);
            if (river.connectsTo().isEmpty()) continue;

            List<String> validTargets = new ArrayList<>();
            for (String target : river.connectsTo()) {
                if (byName.containsKey(target)) {
                    validTargets.add(target);
                    LogUtils.getLogger().info("River '{}' connects to '{}'", river.name(), target);
                } else {
                    LogUtils.getLogger().warn("River '{}' connects_to '{}' but no river with that name was found", river.name(), target);
                }
            }

            if (validTargets.size() != river.connectsTo().size()) {
                River cleaned = new River(river.name(), river.type(), river.waypoints(), List.copyOf(validTargets));
                rivers.set(i, cleaned);
                if (!river.name().isEmpty()) byName.put(river.name(), cleaned);
                byName.put(fallbackNames.get(i), cleaned);
            }
        }
    }

    @Override
    protected void apply(@NotNull Optional<RiverNetwork> network, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        RiverGenerator.clearCaches();
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