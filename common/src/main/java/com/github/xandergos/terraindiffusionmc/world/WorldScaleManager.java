package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.server.level.ServerLevel;

/**
 * Runtime access for world-scoped terrain scale.
 */
public final class WorldScaleManager {
    public static final int DEFAULT_SCALE = 2;
    private static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 6;

    private static volatile int currentScale = DEFAULT_SCALE;
    private static volatile RiverMode currentRiverMode = RiverMode.DEFAULT;
    private static volatile RiverParameters currentRiverParameters = RiverParameters.DEFAULT;
    private static volatile CaveParameters currentCaveParameters = CaveParameters.DEFAULT;
    private static volatile LatitudeParameters currentLatitudeParameters = LatitudeParameters.DEFAULT;

    private WorldScaleManager() {
    }

    /**
     * Loads or creates per-world scale settings and sets the active runtime value.
     *
     * <p>If the world has no explicit stored scale yet, this applies pending
     * world-creation selection when present, otherwise falls back to {@value #DEFAULT_SCALE}.
     */
    public static void initializeForWorld(ServerLevel serverWorld) {
        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getDataStorage()
                .computeIfAbsent(WorldScaleSettingsState.TYPE, "terrain_diffusion_world_settings");

        if (!worldScaleSettingsState.hasExplicitScale()) {
            Integer pendingScale = WorldScaleSelectionState.consumePendingScale();
            int resolvedScale = pendingScale != null ? pendingScale : DEFAULT_SCALE;
            worldScaleSettingsState.setScale(resolvedScale);

            // Rides on the same first-load handoff as scale, so a world keeps whatever was
            // picked at creation and later worlds cannot inherit a stale selection.
            RiverMode pendingRivers = WorldScaleSelectionState.consumePendingRiverMode();
            if (pendingRivers != null) worldScaleSettingsState.setRiverMode(pendingRivers);
            RiverParameters pendingParameters = WorldScaleSelectionState.consumePendingRiverParameters();
            if (pendingParameters != null) worldScaleSettingsState.setRiverParameters(pendingParameters);
            CaveParameters pendingCaves = WorldScaleSelectionState.consumePendingCaveParameters();
            if (pendingCaves != null) worldScaleSettingsState.setCaveParameters(pendingCaves);
            LatitudeParameters pendingLatitude = WorldScaleSelectionState.consumePendingLatitudeParameters();
            if (pendingLatitude != null) worldScaleSettingsState.setLatitudeParameters(pendingLatitude);
        }

        currentScale = clampScale(worldScaleSettingsState.getScale());
        currentRiverMode = worldScaleSettingsState.getRiverMode();
        currentRiverParameters = worldScaleSettingsState.getRiverParameters();
        currentCaveParameters = worldScaleSettingsState.getCaveParameters();
        currentLatitudeParameters = worldScaleSettingsState.getLatitudeParameters();
        // Cached regions were analysed under the previous world's parameters.
        com.github.xandergos.terraindiffusionmc.pipeline.river.RiverRegions.clear();
    }

    /** Returns the river mode active for the loaded world. */
    public static RiverMode getRiverMode() {
        return currentRiverMode;
    }

    /** Returns the river generation parameters active for the loaded world. */
    public static RiverParameters getRiverParameters() {
        return currentRiverParameters;
    }

    /** Returns the cave surface gate parameters active for the loaded world. */
    public static CaveParameters getCaveParameters() {
        return currentCaveParameters;
    }

    /** Returns the latitude banding parameters active for the loaded world. */
    public static LatitudeParameters getLatitudeParameters() {
        return currentLatitudeParameters;
    }

    /** Updates river mode for the currently loaded world and persists it immediately. */
    public static void setRiverMode(ServerLevel serverWorld, RiverMode mode) {
        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getDataStorage()
                .computeIfAbsent(WorldScaleSettingsState.TYPE, "terrain_diffusion_world_settings");

        worldScaleSettingsState.setRiverMode(mode);
        currentRiverMode = mode;
    }

    /**
     * Returns the currently active world scale.
     */
    public static int getCurrentScale() {
        return currentScale;
    }

    /**
     * Updates world scale for the currently loaded world and persists it immediately.
     */
    public static void setCurrentScale(ServerLevel serverWorld, int configuredScale) {
        int clampedScale = clampScale(configuredScale);

        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getDataStorage()
                .computeIfAbsent(WorldScaleSettingsState.TYPE, "terrain_diffusion_world_settings");

        worldScaleSettingsState.setScale(clampedScale);
        currentScale = clampedScale;
    }

    /**
     * Clamps world scale to supported runtime bounds.
     */
    public static int clampScale(int configuredScale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, configuredScale));
    }
}
