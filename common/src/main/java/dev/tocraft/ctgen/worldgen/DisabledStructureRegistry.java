package dev.tocraft.ctgen.worldgen;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DisabledStructureRegistry {
    private static final Set<ResourceLocation> DISABLED_STRUCTURE_SETS = new HashSet<>();
    private static final Set<ResourceLocation> DISABLED_FEATURES = new HashSet<>();

    public static void setDisabledStructureSets(List<ResourceLocation> sets) {
        DISABLED_STRUCTURE_SETS.clear();
        DISABLED_STRUCTURE_SETS.addAll(sets);
    }

    public static void setDisabledFeatures(List<ResourceLocation> features) {
        DISABLED_FEATURES.clear();
        DISABLED_FEATURES.addAll(features);
    }

    public static boolean isStructureSetDisabled(ResourceLocation id) {
        return DISABLED_STRUCTURE_SETS.contains(id);
    }

    public static boolean isFeatureDisabled(ResourceLocation id) {
        return DISABLED_FEATURES.contains(id);
    }

    public static void clear() {
        DISABLED_STRUCTURE_SETS.clear();
        DISABLED_FEATURES.clear();
    }
}