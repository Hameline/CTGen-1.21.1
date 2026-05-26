package dev.tocraft.ctgen.underground;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class UndergroundBiomeLoader extends SimplePreparableReloadListener<Optional<UndergroundBiomeSettings>> {
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "underground_biomes";
    private static UndergroundBiomeSettings CURRENT_SETTINGS = null;

    @Override
    protected @NotNull Optional<UndergroundBiomeSettings> prepare(@NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        List<UndergroundZone> mergedZones = new ArrayList<>();

        FileToIdConverter converter = new FileToIdConverter(DIRECTORY, ".json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);

        if (resources.isEmpty()) {
            return Optional.empty();
        }

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open())) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                Optional<UndergroundBiomeSettings> settings = UndergroundBiomeSettings.CODEC
                        .parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> LogUtils.getLogger().error("Failed to parse underground biomes {}: {}", id, error));

                settings.ifPresent(s -> {
                    mergedZones.addAll(s.zones());
                    LogUtils.getLogger().info("Loaded underground biome file {} with {} zones", id, s.zones().size());
                });
            } catch (IOException e) {
                LogUtils.getLogger().error("Failed to load underground biome file {}", id, e);
            }
        }

        if (mergedZones.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new UndergroundBiomeSettings(mergedZones));
    }

    @Override
    protected void apply(@NotNull Optional<UndergroundBiomeSettings> settings, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        CURRENT_SETTINGS = settings.orElse(null);
        if (CURRENT_SETTINGS != null) {
            LogUtils.getLogger().info("Applied underground biome settings with {} zones", CURRENT_SETTINGS.zones().size());
        } else {
            LogUtils.getLogger().info("No underground biome settings found.");
        }
    }

    @Override
    public @NotNull String getName() {
        return "Underground Biome Loader";
    }

    public static Optional<UndergroundBiomeSettings> getSettings() {
        return Optional.ofNullable(CURRENT_SETTINGS);
    }
}