package dev.tocraft.ctgen.mixin;

import dev.tocraft.ctgen.worldgen.DisabledStructureRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlacedFeature.class)
public class DisableFeaturesMixin {

    @Inject(method = "placeWithContext", at = @At("HEAD"), cancellable = true)
    private void disableFeatures(net.minecraft.world.level.levelgen.placement.PlacementContext context, RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        PlacedFeature feature = (PlacedFeature) (Object) this;
        feature.feature().unwrapKey().ifPresent(key -> {
            if (DisabledStructureRegistry.isFeatureDisabled(key.location())) {
                cir.setReturnValue(false);
            }
        });
    }
}