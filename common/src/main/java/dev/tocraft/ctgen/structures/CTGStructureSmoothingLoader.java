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

    public static final Codec<CTGJigsawSmoothing.CTGJigsawSmoothingConfig> CONFIG_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("transition_width", 6).forGetter(CTGJigsawSmoothing.CTGJigsawSmoothingConfig::transitionWidth),
            Codec.INT.optionalFieldOf("y_offset", 0).forGetter(CTGJigsawSmoothing.CTGJigsawSmoothingConfig::yOffset),
            // upper bound on how far transition_width is allowed to widen when a structure
            // lands far from the natural terrain around it (see "slope" below)
            Codec.INT.optionalFieldOf("max_transition_width", 48).forGetter(CTGJigsawSmoothing.CTGJigsawSmoothingConfig::maxTransitionWidth),
            // horizontal blocks of blend radius per 1 block of height mismatch between the
            // structure and its surroundings — transition_width is used as-is until the
            // measured mismatch needs more room than that to keep this slope; e.g. 2.0 means
            // a 20-block mismatch blends over 40 blocks instead of squeezing into transition_width
            Codec.DOUBLE.optionalFieldOf("slope", 2.0).forGetter(CTGJigsawSmoothing.CTGJigsawSmoothingConfig::slope),
            // how far below the structure's ground level the solid fill is allowed to reach
            // looking for real terrain, so a structure spawning over a cliff/ravine gets a
            // deep-but-bounded foundation instead of an unbounded pillar straight to bedrock
            Codec.INT.optionalFieldOf("max_foundation_depth", 32).forGetter(CTGJigsawSmoothing.CTGJigsawSmoothingConfig::maxFoundationDepth)
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
        // cached placements carry a transition width baked in under the old config —
        // drop them so any already-generated-adjacent chunk recomputes it on next touch
        CTGJigsawSmoothing.clearStructureBoxCache();
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