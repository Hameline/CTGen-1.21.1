package dev.tocraft.ctgen.walls;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WallNetworkLoader extends SimplePreparableReloadListener<Optional<WallNetwork>> {

    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "walls_gen";
    private static WallNetwork CURRENT_NETWORK = null;

    @Override
    protected @NotNull Optional<WallNetwork> prepare(
            @NotNull ResourceManager resourceManager,
            @NotNull ProfilerFiller profiler
    ) {
        List<WallType> allWalls = new ArrayList<>();

        FileToIdConverter converter = new FileToIdConverter(DIRECTORY, ".json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);

        if (resources.isEmpty()) return Optional.empty();

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open())) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                if (!json.isJsonObject()) continue;

                var root = json.getAsJsonObject();
                if (!root.has("walls")) continue;

                for (JsonElement el : root.getAsJsonArray("walls")) {
                    WallType.CODEC.parse(JsonOps.INSTANCE, el)
                            .resultOrPartial(err -> LogUtils.getLogger().error(
                                    "Failed to parse wall in {}: {}", id, err))
                            .ifPresent(allWalls::add);
                }

                LogUtils.getLogger().info("Loaded wall file: {}", id);
            } catch (Exception e) {
                LogUtils.getLogger().error("Failed to load wall file {}", id, e);
            }
        }

        if (allWalls.isEmpty()) return Optional.empty();
        return Optional.of(new WallNetwork(allWalls));
    }

    @Override
    protected void apply(
            @NotNull Optional<WallNetwork> network,
            @NotNull ResourceManager resourceManager,
            @NotNull ProfilerFiller profiler
    ) {
        CURRENT_NETWORK = network.orElse(null);
        if (CURRENT_NETWORK != null) {
            LogUtils.getLogger().info("Applied wall network with {} walls", CURRENT_NETWORK.walls().size());
        }
    }

    @Override
    public @NotNull String getName() {
        return "Wall Network Loader";
    }

    public static Optional<WallNetwork> getNetwork() {
        return Optional.ofNullable(CURRENT_NETWORK);
    }
}