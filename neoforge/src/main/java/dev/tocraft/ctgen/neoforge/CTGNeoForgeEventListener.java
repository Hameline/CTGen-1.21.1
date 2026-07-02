package dev.tocraft.ctgen.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.tocraft.ctgen.data.BiomeImageRegistry;
import dev.tocraft.ctgen.data.HeightImageRegistry;
import dev.tocraft.ctgen.data.MapWaypointLoader;
import dev.tocraft.ctgen.impl.CTGCommand;
import dev.tocraft.ctgen.impl.network.SyncMapPacket;
import dev.tocraft.ctgen.rivers.RiverGenerator;
import dev.tocraft.ctgen.rivers.RiverNetworkLoader;
import dev.tocraft.ctgen.roads.RoadGenerator;
import dev.tocraft.ctgen.roads.RoadNetworkLoader;
import dev.tocraft.ctgen.structures.CTGJigsawSmoothing;
import dev.tocraft.ctgen.structures.CTGStructureSmoothingLoader;
import dev.tocraft.ctgen.underground.UndergroundBiomeLoader;
import dev.tocraft.ctgen.walls.WallGenerator;
import dev.tocraft.ctgen.walls.WallNetworkLoader;
import dev.tocraft.ctgen.worldgen.MapBasedBiomeSource;
import dev.tocraft.ctgen.worldgen.MapBasedChunkGenerator;
import dev.tocraft.ctgen.worldgen.noise.CTGAboveSurfaceCondition;
import dev.tocraft.ctgen.worldgen.noise.CTGTemperatureCondition;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

@ApiStatus.Internal
public final class CTGNeoForgeEventListener {
    private static final String PROTOCOL_VERSION = "1";

    public static void initialize(@NotNull IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(CTGNeoForgeEventListener::addReloadListenerEvent);
        NeoForge.EVENT_BUS.addListener(CTGNeoForgeEventListener::registerCommands);
        NeoForge.EVENT_BUS.addListener(CTGNeoForgeEventListener::onServerStarting);
        NeoForge.EVENT_BUS.addListener(CTGNeoForgeEventListener::onServerStopping);
        modEventBus.addListener(CTGNeoForgeEventListener::register);
        modEventBus.addListener(CTGNeoForgeEventListener::registerPayload);
    }

    private static void addReloadListenerEvent(@NotNull AddReloadListenerEvent event) {
        event.addListener(new BiomeImageRegistry());
        event.addListener(new HeightImageRegistry());
        event.addListener(new RoadNetworkLoader());
        event.addListener(new UndergroundBiomeLoader());
        event.addListener(new RiverNetworkLoader());
        event.addListener(new MapWaypointLoader());
        event.addListener(new WallNetworkLoader());
        event.addListener(new CTGStructureSmoothingLoader());
    }

    private static void registerCommands(@NotNull RegisterCommandsEvent event) {
        CTGCommand.register(event.getDispatcher(), event.getBuildContext());
    }

    private static void register(@NotNull RegisterEvent event) {
        event.register(Registries.BIOME_SOURCE, helper -> helper.register(MapBasedBiomeSource.ID, MapBasedBiomeSource.CODEC));
        event.register(Registries.CHUNK_GENERATOR, helper -> helper.register(MapBasedChunkGenerator.ID, MapBasedChunkGenerator.CODEC));
        event.register(Registries.MATERIAL_CONDITION, helper -> {
            CTGAboveSurfaceCondition.register(helper::register);
            CTGTemperatureCondition.register(helper::register);
        });
    }

    private static void onServerStarting(@NotNull ServerStartingEvent event) {
        // load runtime waypoints from world save
        Path waypointFile = event.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("ctgen_waypoints.json");
        if (Files.exists(waypointFile)) {
            try (var reader = new InputStreamReader(Files.newInputStream(waypointFile))) {
                JsonObject saved = new Gson().fromJson(reader, JsonObject.class);
                MapWaypointLoader.loadRuntimeWaypoints(saved);
            } catch (Exception e) {
                LogUtils.getLogger().error("Failed to load CTGen waypoints", e);
            }
        }

        // populate structure ID lookup so smoothing can identify structures
        CTGStructureSmoothingLoader.populateStructureIdLookup(event.getServer());
    }

    private static void onServerStopping(@NotNull ServerStoppingEvent event) {
        // clear all generator caches to free memory on world unload
        RiverGenerator.clearCaches();
        RoadGenerator.clearCaches();
        WallGenerator.clearCaches();

        // clear structure box cache so it doesn't leak between worlds
        CTGJigsawSmoothing.clearStructureBoxCache();

        // save runtime waypoints to world save
        Path waypointFile = event.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("ctgen_waypoints.json");
        try {
            JsonObject data = MapWaypointLoader.serializeRuntimeWaypoints();
            Files.writeString(waypointFile, new GsonBuilder().setPrettyPrinting().create().toJson(data));
        } catch (Exception e) {
            LogUtils.getLogger().error("Failed to save CTGen waypoints", e);
        }
    }

    private static void registerPayload(@NotNull RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION).playToClient(SyncMapPacket.TYPE, SyncMapPacket.streamCodec(), (packet, context) -> packet.handle());
    }
}