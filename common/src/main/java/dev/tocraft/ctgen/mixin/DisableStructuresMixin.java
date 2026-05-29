package dev.tocraft.ctgen.mixin;

import dev.tocraft.ctgen.worldgen.DisabledStructureRegistry;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkGeneratorStructureState.class)
public class DisableStructuresMixin {

    @Inject(method = "hasStructureChunkInRange", at = @At("HEAD"), cancellable = true)
    private void disableStructureSets(Holder<StructureSet> structureSet, int x, int z, int range, CallbackInfoReturnable<Boolean> cir) {
        structureSet.unwrapKey().ifPresent(key -> {
            if (DisabledStructureRegistry.isStructureSetDisabled(key.location())) {
                cir.setReturnValue(false);
            }
        });
    }
}