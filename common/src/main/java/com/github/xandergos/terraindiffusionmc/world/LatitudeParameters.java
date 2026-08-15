package com.github.xandergos.terraindiffusionmc.world;

/**
 * Per-world dials for latitude temperature banding, chosen at world creation.
 *
 * <p>A sine wave along north-south shifts temperature on top of the climate model:
 * warmest at the equator, coldest at the poles, zero at the halfway latitude, so a world
 * spawning there looks exactly like an unbanded one until the player travels. The bands
 * repeat forever, so passing a pole starts warming again.
 *
 * <p>Pure data with clamped construction, like {@link RiverParameters}. Strength zero
 * turns banding off; worlds saved before the feature load that way.
 */
public final class LatitudeParameters {

    // Puts the pole a fifteen-thousand-block trek north of a default spawn: about
    // three quarters of an hour of sprinting, minutes by nether or elytra. Far enough
    // that bands read as geography, near enough that an ice cap is a real goal.
    public static final int DEFAULT_EQUATOR_POLE_BLOCKS = 30_000;
    public static final int DEFAULT_START_LATITUDE_DEG = 45;
    /**
     * Swing at the extremes, in Celsius against the mid-latitude mean. Earth's annual
     * means run about +27 at the equator and -25 at the poles around a ~0 crossing near
     * 50 degrees; the classifier's bands saturate past hot and frozen, so overshoot on
     * already-extreme patches costs nothing.
     */
    public static final int DEFAULT_BAND_STRENGTH_C = 25;

    public static final LatitudeParameters DEFAULT = new LatitudeParameters(
            DEFAULT_EQUATOR_POLE_BLOCKS, DEFAULT_START_LATITUDE_DEG, DEFAULT_BAND_STRENGTH_C);
    public static final LatitudeParameters OFF = new LatitudeParameters(
            DEFAULT_EQUATOR_POLE_BLOCKS, DEFAULT_START_LATITUDE_DEG, 0);

    /** Blocks from the equator to a pole; peak to trough of the wave. */
    public final int equatorPoleBlocks;
    /** Degrees north of the equator the spawn sits at: 0 equator, 90 north pole. */
    public final int startLatitudeDeg;
    /** Celsius added at the equator and subtracted at the poles; 0 disables banding. */
    public final int bandStrengthC;

    public LatitudeParameters(int equatorPoleBlocks, int startLatitudeDeg, int bandStrengthC) {
        this.equatorPoleBlocks = Math.max(5_000, Math.min(1_000_000, equatorPoleBlocks));
        this.startLatitudeDeg = Math.max(0, Math.min(90, startLatitudeDeg));
        this.bandStrengthC = Math.max(0, Math.min(60, bandStrengthC));
    }

    /**
     * Temperature shift at a block-space north-south coordinate. North is negative z,
     * so the equator lies south of a spawn placed at northern latitude.
     */
    public float temperatureBiasAt(double zBlock) {
        if (bandStrengthC == 0) return 0f;
        double equatorZ = equatorPoleBlocks * (startLatitudeDeg / 90.0);
        return (float) (bandStrengthC
                * Math.cos(Math.PI * (zBlock - equatorZ) / equatorPoleBlocks));
    }
}
