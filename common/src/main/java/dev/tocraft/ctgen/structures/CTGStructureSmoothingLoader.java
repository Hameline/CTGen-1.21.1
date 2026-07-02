package dev.tocraft.ctgen.structures;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.core.registries.Registries;
import org.jetbrains.annotations.NotNull;

import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class CTGStructureSmoothingLoader extends SimplePreparableReloadListener<Map<ResourceLocation, CTGJigsawSmoothing.CTGJigsawSmoothingConfig>> {

    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "ctgen_structure_smoothing";
    public record CTGJigsawSmoothingConfig(int transitionWidth, int yOffset) {}

    public static final Codec<CTGJigsawSmoothing.CTGJigsawSmoothingConfig> CONFIG_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("transition_width", 6).forGetter(CTGJigsawSmoothing.CTGJigsawSmoothingConfig::transitionWidth),
            Codec.INT.optionalFieldOf("y_offset", 0).forGetter(CTGJigsawSmoothing.CTGJigsawSmoothingConfig::yOffset)
    ).apply(instance, instance.stable(CTGJigsawSmoothing.CTGJigsawSmoothingConfig::new)));

    @Override
    protected @NotNull Map<ResourceLocation, CTGJigsawSmoothing.CTGJigsawSmoothingConfig> prepare(
            @NotNull ResourceManager resourceManager,
            @NotNull ProfilerFiller profiler
    ) {
        Map<ResourceLocation, CTGJigsawSmoothing.CTGJigsawSmoothingConfig> configs = new HashMap<>();

        FileToIdConverter converter = new FileToIdConverter(DIRECTORY, ".json");
        Map<ResourceLocation, Resource> resources = converter.listMatchingResources(resourceManager);

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open())) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                CONFIG_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(err -> LogUtils.getLogger().error(
                                "Failed to parse CTGen smoothing config {}: {}", id, err))
                        .ifPresent(config -> {
                            configs.put(id, config);
                            LogUtils.getLogger().info("Loaded CTGen smoothing config for structure {}", id);
                        });
            } catch (Exception e) {
                LogUtils.getLogger().error("Failed to load CTGen smoothing config {}", id, e);
            }
        }

        return configs;
    }

    @Override
    protected void apply(
            @NotNull Map<ResourceLocation, CTGJigsawSmoothing.CTGJigsawSmoothingConfig> configs,
            @NotNull ResourceManager resourceManager,
            @NotNull ProfilerFiller profiler
    ) {
        CTGJigsawSmoothing.setConfigs(configs);
    }

    @Override
    public @NotNull String getName() {
        return "CTGen Structure Smoothing Loader";
    }

    /**
     * Populates STRUCTURE_ID_LOOKUP by walking the structure registry.
     * Call this on server start after registries are frozen.
     */
    public static void populateStructureIdLookup(@NotNull MinecraftServer server) {
        server.registryAccess()
                .registry(Registries.STRUCTURE)
                .ifPresent(structureRegistry -> {
                    structureRegistry.holders().forEach(holder ->
                            holder.unwrapKey().ifPresent(key ->
                                    CTGJigsawSmoothing.registerStructureId(holder.value(), key.location())
                            )
                    );
                    LogUtils.getLogger().info("CTGen: populated structure ID lookup with {} structures",
                            structureRegistry.size());
                });
    }
}