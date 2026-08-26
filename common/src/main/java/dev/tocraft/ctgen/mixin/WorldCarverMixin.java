package dev.tocraft.ctgen.mixin;

import dev.tocraft.ctgen.walls.WallNetwork;
import dev.tocraft.ctgen.walls.WallNetworkLoader;
import dev.tocraft.ctgen.walls.WallType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.List;
import java.util.function.Function;

@Mixin(WorldCarver.class)
public class WorldCarverMixin<C extends CarverConfiguration> {

    @Inject(
            method = "carveBlock",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                    ordinal = 0,
                    shift = At.Shift.AFTER),
            locals = LocalCapture.CAPTURE_FAILHARD,
            cancellable = true
    )
    private void atlas_stopWaterCarving(
            CarvingContext context,
            C config,
            ChunkAccess chunk,
            Function<BlockPos, Holder<Biome>> biomeGetter,
            CarvingMask carvingMask,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos checkPos,
            Aquifer aquifer,
            MutableBoolean reachedSurface,
            CallbackInfoReturnable<Boolean> cir,
            @NotNull BlockState blockState
    ) {
        // prevent carving in water sources
        if (blockState.getFluidState().isSource()) {
            cir.setReturnValue(false);
            return;
        }

        // prevent carving inside or under wall zones
        WallNetworkLoader.getNetwork().ifPresent(network -> {
            if (isNearWall(network, pos.getX(), pos.getZ())) {
                cir.setReturnValue(false);
            }
        });
    }

    private static boolean isNearWall(@NotNull WallNetwork network, int blockX, int blockZ) {
        for (WallType wall : network.walls()) {
            double halfWidth = wall.baseWidth() / 2.0 + 4;
            double dist = distToWallSpline(wall, blockX, blockZ);
            if (dist < halfWidth) return true;
        }
        return false;
    }

    private static double distToWallSpline(@NotNull WallType wall, int blockX, int blockZ) {
        List<dev.tocraft.ctgen.roads.Waypoint> wps = wall.waypoints();
        double minDist = Double.MAX_VALUE;
        int samples = Math.max(64, wps.size() * 20);
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            int segment = Math.min((int)(t * (wps.size() - 1)), wps.size() - 2);
            double localT = t * (wps.size() - 1) - segment;
            double wx = wps.get(segment).x() + (wps.get(segment + 1).x() - wps.get(segment).x()) * localT;
            double wz = wps.get(segment).z() + (wps.get(segment + 1).z() - wps.get(segment).z()) * localT;
            double dx = blockX - wx;
            double dz = blockZ - wz;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }
}