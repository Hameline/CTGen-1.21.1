package dev.tocraft.ctgen.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.tocraft.ctgen.impl.screen.MapText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Environment(EnvType.CLIENT)
public class MapOverlayTextLoader extends SimpleJsonResourceReloadListener {
    public static final Map<ResourceLocation, List<MapText>> ENTRIES = new HashMap<>();
    private static final Gson GSON = new Gson();
    private static final Codec<List<MapText>> MAP_TEXTS_CODEC = Codec.list(MapText.CODEC);

    public MapOverlayTextLoader() {
        super(GSON, "map_texts");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        ENTRIES.clear();
        for (Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
            ENTRIES.put(entry.getKey(), MAP_TEXTS_CODEC.parse(JsonOps.INSTANCE, entry.getValue()).getOrThrow());
        }
    }
}
