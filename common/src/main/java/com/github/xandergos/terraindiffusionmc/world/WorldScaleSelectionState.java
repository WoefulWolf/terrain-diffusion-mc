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
