package com.github.xandergos.terraindiffusionmc.world;

import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory handoff for world-creation scale selection.
 *
 * <p>In single-player, client and integrated server run in the same JVM, so this allows
 * the world-creation UI to pass an initial scale to server-side world initialization.
 */
public final class WorldScaleSelectionState {
    private static final AtomicReference<Integer> PENDING_SCALE = new AtomicReference<>();
    private static final AtomicReference<RiverMode> PENDING_RIVER_MODE = new AtomicReference<>();

    private WorldScaleSelectionState() {
    }

    /**
     * Stores a pending scale selected in world creation UI.
     */
    public static void setPendingScale(int selectedScale) {
        PENDING_SCALE.set(WorldScaleManager.clampScale(selectedScale));
    }

    /**
     * Returns and clears the pending scale, if any.
     */
    public static Integer consumePendingScale() {
        return PENDING_SCALE.getAndSet(null);
    }

    /**
     * Returns the currently selected pending scale, or the default if none is set.
     */
    public static int getPendingScaleOrDefault() {
        Integer pendingScale = PENDING_SCALE.get();
        return pendingScale != null ? pendingScale : WorldScaleManager.DEFAULT_SCALE;
    }

    private static final AtomicReference<RiverParameters> PENDING_RIVER_PARAMETERS =
            new AtomicReference<>();

    /** Stores pending river parameters selected in world creation UI. */
    public static void setPendingRiverParameters(RiverParameters parameters) {
        PENDING_RIVER_PARAMETERS.set(parameters);
    }

    /** Returns and clears the pending river parameters, if any. */
    public static RiverParameters consumePendingRiverParameters() {
        return PENDING_RIVER_PARAMETERS.getAndSet(null);
    }

    /** Returns the pending river parameters, or the defaults if none are set. */
    public static RiverParameters getPendingRiverParametersOrDefault() {
        RiverParameters parameters = PENDING_RIVER_PARAMETERS.get();
        return parameters != null ? parameters : RiverParameters.DEFAULT;
    }

    private static final AtomicReference<CaveParameters> PENDING_CAVE_PARAMETERS =
            new AtomicReference<>();

    /** Stores pending cave gate parameters selected in world creation UI. */
    public static void setPendingCaveParameters(CaveParameters parameters) {
        PENDING_CAVE_PARAMETERS.set(parameters);
    }

    /** Returns and clears the pending cave gate parameters, if any. */
    public static CaveParameters consumePendingCaveParameters() {
        return PENDING_CAVE_PARAMETERS.getAndSet(null);
    }

    /** Returns the pending cave gate parameters, or the defaults if none are set. */
    public static CaveParameters getPendingCaveParametersOrDefault() {
        CaveParameters parameters = PENDING_CAVE_PARAMETERS.get();
        return parameters != null ? parameters : CaveParameters.DEFAULT;
    }

    private static final AtomicReference<LatitudeParameters> PENDING_LATITUDE_PARAMETERS =
            new AtomicReference<>();

    /** Stores pending latitude banding parameters selected in world creation UI. */
    public static void setPendingLatitudeParameters(LatitudeParameters parameters) {
        PENDING_LATITUDE_PARAMETERS.set(parameters);
    }

    /** Returns and clears the pending latitude banding parameters, if any. */
    public static LatitudeParameters consumePendingLatitudeParameters() {
        return PENDING_LATITUDE_PARAMETERS.getAndSet(null);
    }

    /** Returns the pending latitude banding parameters, or the defaults if none are set. */
    public static LatitudeParameters getPendingLatitudeParametersOrDefault() {
        LatitudeParameters parameters = PENDING_LATITUDE_PARAMETERS.get();
        return parameters != null ? parameters : LatitudeParameters.DEFAULT;
    }

    private static final AtomicReference<Boolean> PENDING_ADDITIONAL_BIOMES =
            new AtomicReference<>();

    /** Stores a pending additional-biomes choice selected in world creation UI. */
    public static void setPendingAdditionalBiomes(boolean enabled) {
        PENDING_ADDITIONAL_BIOMES.set(enabled);
    }

    /** Returns and clears the pending additional-biomes choice, if any. */
    public static Boolean consumePendingAdditionalBiomes() {
        return PENDING_ADDITIONAL_BIOMES.getAndSet(null);
    }

    /** Returns the pending additional-biomes choice, or on if none is set. */
    public static boolean getPendingAdditionalBiomesOrDefault() {
        Boolean enabled = PENDING_ADDITIONAL_BIOMES.get();
        return enabled == null || enabled;
    }

    /** Stores a pending river mode selected in world creation UI. */
    public static void setPendingRiverMode(RiverMode mode) {
        PENDING_RIVER_MODE.set(mode);
    }

    /** Returns and clears the pending river mode, if any. */
    public static RiverMode consumePendingRiverMode() {
        return PENDING_RIVER_MODE.getAndSet(null);
    }

    /** Returns the currently selected pending river mode, or the default if none is set. */
    public static RiverMode getPendingRiverModeOrDefault() {
        RiverMode mode = PENDING_RIVER_MODE.get();
        return mode != null ? mode : RiverMode.DEFAULT;
    }
}
