package dev.tocraft.ctgen.fabric;

import dev.tocraft.ctgen.CTerrainGeneration;
import dev.tocraft.ctgen.data.BiomeImageRegistry;
import dev.tocraft.ctgen.data.HeightImageRegistry;
import dev.tocraft.ctgen.impl.CTGCommand;
import dev.tocraft.ctgen.impl.network.SyncMapPacket;
import dev.tocraft.ctgen.roads.RoadNetworkLoader;
import dev.tocraft.ctgen.underground.UndergroundBiomeLoader;
import dev.tocraft.ctgen.worldgen.MapBasedBiomeSource;
import dev.tocraft.ctgen.worldgen.MapBasedChunkGenerator;
import dev.tocraft.ctgen.worldgen.noise.CTGAboveSurfaceCondition;
import dev.tocraft.ctgen.worldgen.noise.CTGTemperatureCondition;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@SuppressWarnings("unused")
@ApiStatus.Internal
public final class CTGFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // register chunk generator
        Registry.register(BuiltInRegistries.BIOME_SOURCE, MapBasedBiomeSource.ID, MapBasedBiomeSource.CODEC);
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, MapBasedChunkGenerator.ID, MapBasedChunkGenerator.CODEC);

        // register biome image reload listener
        BiomeImageRegistry biomeImageRegistry = new BiomeImageRegistry();
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new IdentifiableResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return CTerrainGeneration.id("biome_map_image_listener");
            }

            @Override
            public @NotNull CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, ResourceManager resourceManager, ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler, Executor backgroundExecutor, Executor gameExecutor) {
                return biomeImageRegistry.reload(preparationBarrier, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor);
            }

            @Override
            public @NotNull String getName() {
                return biomeImageRegistry.getName();
            }
        });

        UndergroundBiomeLoader undergroundBiomeLoader = new UndergroundBiomeLoader();
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new IdentifiableResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return CTerrainGeneration.id("underground_biome_loader");
            }

            @Override
            public @NotNull CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, ResourceManager resourceManager, ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler, Executor backgroundExecutor, Executor gameExecutor) {
                return undergroundBiomeLoader.reload(preparationBarrier, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor);
            }

            @Override
            public @NotNull String getName() {
                return undergroundBiomeLoader.getName();
            }
        });

        // register heightmap image reload listener
        HeightImageRegistry heightImageRegistry = new HeightImageRegistry();
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new IdentifiableResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return CTerrainGeneration.id("heightmap_image_listener");
            }

            @Override
            public @NotNull CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, ResourceManager resourceManager, ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler, Executor backgroundExecutor, Executor gameExecutor) {
                return heightImageRegistry.reload(preparationBarrier, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor);
            }

            @Override
            public @NotNull String getName() {
                return heightImageRegistry.getName();
            }
        });

        // register road network reload listener
        RoadNetworkLoader roadNetworkLoader = new RoadNetworkLoader();
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new IdentifiableResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return CTerrainGeneration.id("road_network_loader");
            }

            @Override
            public @NotNull CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, ResourceManager resourceManager, ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler, Executor backgroundExecutor, Executor gameExecutor) {
                return roadNetworkLoader.reload(preparationBarrier, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor);
            }

            @Override
            public @NotNull String getName() {
                return roadNetworkLoader.getName();
            }
        });

        // register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> CTGCommand.register(dispatcher, context));

        // register built-in registry entries
        CTGAboveSurfaceCondition.register((id, codec) -> Registry.register(BuiltInRegistries.MATERIAL_CONDITION, id, codec));
        CTGTemperatureCondition.register((id, codec) -> Registry.register(BuiltInRegistries.MATERIAL_CONDITION, id, codec));

        // register network packet type
        PayloadTypeRegistry.playS2C().register(SyncMapPacket.TYPE, SyncMapPacket.streamCodec());
    }
}
