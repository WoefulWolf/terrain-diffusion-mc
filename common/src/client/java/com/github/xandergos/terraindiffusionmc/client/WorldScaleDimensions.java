package com.github.xandergos.terraindiffusionmc.client;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import java.util.HashMap;
import java.util.Map;

/**
 * Swaps the overworld to the scale-specific dimension type, whose height covers the
 * tallest terrain that scale can produce.
 *
 * <p>Called when the settings screen confirms a scale, and again the moment the world is
 * actually created. The second call is what makes it reliable: the default scale needs no
 * visit to the settings screen, and changing world type rebuilds the dimensions from the
 * preset, so a swap done only in the settings screen gets lost, leaving tall mountains
 * sliced off at the base type's ceiling.
 */
public final class WorldScaleDimensions {

    private static final String MOD_ID = "terrain-diffusion-mc";

    private static final ResourceKey<WorldPreset> PRESET_KEY = ResourceKey.create(
            Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath(MOD_ID, "terrain_diffusion"));

    private WorldScaleDimensions() {
    }

    /** Whether the create-world screen currently has the terrain diffusion preset selected. */
    public static boolean isTerrainDiffusionSelected(CreateWorldScreen screen) {
        WorldCreationUiState state = screen.getUiState();
        if (state == null || state.getWorldType() == null) return false;
        WorldCreationUiState.WorldTypeEntry worldType = state.getWorldType();
        if (worldType.preset() != null
                && worldType.preset().unwrapKey().map(PRESET_KEY::equals).orElse(false)) {
            return true;
        }
        return "terrain diffusion".equalsIgnoreCase(worldType.describePreset().getString());
    }

    /** Applies the pre-registered dimension type variant for the chosen scale. */
    public static void apply(CreateWorldScreen screen, int selectedScale) {
        screen.getUiState().updateDimensions((registryManager, selectedDimensions) -> {
            WorldDimensions updated = withScaleDimensionType(
                    registryManager.lookupOrThrow(Registries.DIMENSION_TYPE),
                    selectedDimensions,
                    selectedScale);
            return updated == null ? selectedDimensions : updated;
        });
    }

    /** Replaces only the overworld dimension type entry with the scale-specific one. */
    private static WorldDimensions withScaleDimensionType(
            HolderGetter<DimensionType> dimensionTypeRegistry,
            WorldDimensions selectedDimensions,
            int selectedScale
    ) {
        LevelStem overworldOptions = selectedDimensions.get(LevelStem.OVERWORLD).orElse(null);
        if (overworldOptions == null) {
            return null;
        }

        ResourceKey<DimensionType> dimensionTypeKey = ResourceKey.create(
                Registries.DIMENSION_TYPE,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "terrain_diffusion_scale_" + selectedScale));
        Holder.Reference<DimensionType> selectedDimensionTypeEntry =
                dimensionTypeRegistry.get(dimensionTypeKey).orElse(null);
        if (selectedDimensionTypeEntry == null) {
            return null;
        }

        LevelStem updatedOverworldOptions = new LevelStem(
                selectedDimensionTypeEntry,
                overworldOptions.generator()
        );

        Map<ResourceKey<LevelStem>, LevelStem> updatedDimensionMap =
                new HashMap<>(selectedDimensions.dimensions());
        updatedDimensionMap.put(LevelStem.OVERWORLD, updatedOverworldOptions);
        return new WorldDimensions(updatedDimensionMap);
    }
}
