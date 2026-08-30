package dev.tocraft.ctgen.impl.commands;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.tocraft.ctgen.cities.CityPlacer;
import dev.tocraft.ctgen.cities.CitySpawnEntry;
import dev.tocraft.ctgen.cities.CitySpawnLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * {@code /ctgen cities status} — lists every configured city spawn and whether its schematic
 * has finished compiling/mapping yet (see {@link CityPlacer}). Mainly a debugging aid for
 * verifying a consumer mod's {@code cities_gen} JSON was picked up and its schem folder resolved
 * correctly.
 */
public class CTGCitiesCommand {
    public static void register(@NotNull LiteralCommandNode<CommandSourceStack> rootNode) {
        LiteralCommandNode<CommandSourceStack> citiesNode =
                Commands.literal("cities")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .build();

        rootNode.addChild(citiesNode);
    }

    private static int status(@NotNull CommandSourceStack source) {
        Map<ResourceLocation, CitySpawnEntry> cities = CitySpawnLoader.getCities();

        if (cities.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No city spawns configured (checked cities_gen datapack directory)."), false);
            return 0;
        }

        for (Map.Entry<ResourceLocation, CitySpawnEntry> e : cities.entrySet()) {
            ResourceLocation id = e.getKey();
            CitySpawnEntry entry = e.getValue();
            String status = CityPlacer.isReady(id) ? "ready" : "compiling/not ready";
            source.sendSuccess(() -> Component.literal(
                    id + " @ (" + entry.x() + ", " + entry.y() + ", " + entry.z() + ") — " + status), false);
        }

        return cities.size();
    }
}
