package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.RiverWaterFiller;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class BiomeMixin {

    @Shadow
    public abstract float getBaseTemperature();

    @Shadow
    public abstract boolean hasPrecipitation();

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void preventHighAltitudeSnow(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (!this.hasPrecipitation()) {
            cir.setReturnValue(Biome.Precipitation.NONE);
            return;
        }

        // Base temperature >= 0.15 means this is NOT a snowy biome.
        // Always return RAIN to prevent altitude-based snow in non-snowy biomes.
        if (this.getBaseTemperature() >= 0.15F) {
            cir.setReturnValue(Biome.Precipitation.RAIN);
        }
        // For snowy biomes (base temp < 0.15), let vanilla handle it
    }

    /**
     * Rivers freeze from the banks inward, and never beside a drop. Only vetoes ice that
     * vanilla wanted; everything else, including lakes and oceans, is vanilla's business.
     */
    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z",
            at = @At("RETURN"), cancellable = true)
    private void terrainDiffusion$riversFreezeFromTheBanks(LevelReader level, BlockPos pos,
                                                           boolean mustBeAtEdge,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && !RiverWaterFiller.allowIce(pos.getX(), pos.getZ())) {
            cir.setReturnValue(false);
        }
    }
}
