package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.WorldScaleDimensions;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleSelectionState;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the scale-specific dimension type right as the world is created.
 *
 * <p>Doing it only when the settings screen confirms is not enough: the default scale
 * needs no visit there, and switching world type rebuilds the dimensions from the preset
 * and discards the swap. Both left worlds on the base dimension type, whose lower ceiling
 * slices the tallest mountain ranges flat.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Inject(method = "onCreate", at = @At("HEAD"))
    private void terrainDiffusion$applyScaleDimensions(CallbackInfo ci) {
        CreateWorldScreen self = (CreateWorldScreen) (Object) this;
        if (WorldScaleDimensions.isTerrainDiffusionSelected(self)) {
            WorldScaleDimensions.apply(self, WorldScaleSelectionState.getPendingScaleOrDefault());
        }
    }
}
