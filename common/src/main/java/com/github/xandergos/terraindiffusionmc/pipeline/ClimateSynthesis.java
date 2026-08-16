package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Describes a place in vanilla's climate coordinates, so mods that place biomes by
 * those coordinates can work in this world.
 *
 * <p>Vanilla derives its six parameters from six unrelated noise fields; this world
 * has real quantities — degrees, millimetres, metres of relief, distance to the sea —
 * and each axis is anchored to whatever physically happens at the vanilla boundary
 * rather than to a band index. The snow line is where vanilla stops being frozen, the
 * point trees give out is where it stops being arid, and so on. That keeps a modded
 * biome tuned against vanilla landing somewhere recognisable here.
 *
 * <p>Two of the six have no physical counterpart. Depth is vanilla's surface-versus-
 * underground axis and is fixed at the surface, since cave biomes are chosen by their
 * own pass. Weirdness is vanilla's variant selector — it decides which of two forms of
 * a region appears and answers to nothing in the landscape — so it is noise, and which
 * noise is a free choice rather than a derivation.
 *
 * <p>Values are vanilla's own scale, nominally -1 to 1. Pure float maths over the
 * quantities in {@link DerivedClimate} and the fields around it; nothing here touches
 * Minecraft types, so it can be measured headlessly.
 */
public final class ClimateSynthesis {

    /** Axis order, matching {@code Climate.TargetPoint}'s constructor. */
    public static final int TEMPERATURE = 0;
    public static final int HUMIDITY = 1;
    public static final int CONTINENTALNESS = 2;
    public static final int EROSION = 3;
    public static final int DEPTH = 4;
    public static final int WEIRDNESS = 5;
    public static final int AXES = 6;

    // Every anchor below is set against a measured distribution of this world's own
    // signals, not against what the quantities look like on Earth. Ranges chosen by eye
    // bank half the land in a single humidity band and leave vanilla's far-inland band
    // unreachable, which hides every modded biome tuned to it with no visible symptom.
    // Re-measure with the ClimateInputStats harness if the classifier is retuned: the
    // interface would not change, but every consumer's tuning would.

    /**
     * Celsius against vanilla's temperature axis, spanning the measured -31 to +26 this
     * world produces. The interior anchors stay physical rather than statistical — the
     * snow line first of all — so a mod's cold biomes land in country that is actually
     * cold. The latitude wave spends most of its length near the extremes, so the
     * middle bands are genuinely narrow here, exactly as tropics and ice caps are broad
     * on Earth while the temperate belt is thin.
     */
    private static final float[] TEMP_C = {-31f, 0f, 8f, 15f, 21f, 26f};
    private static final float[] TEMP_V = {-1f, -0.45f, -0.15f, 0.2f, 0.55f, 1f};

    /**
     * Moisture against vanilla's humidity axis, anchored on the tree-cover thresholds
     * the classifier already uses — where nothing grows, where cover turns sparse,
     * where it closes into rainforest — with the top anchor at the measured 95th
     * percentile rather than at the rainforest threshold, or everything wet would pin
     * to the same end of the axis.
     */
    private static final float[] MOIST_X = {0.05f, 0.12f, 0.35f, 0.8f, 1.5f, 5f};
    private static final float[] MOIST_V = {-1f, -0.35f, -0.1f, 0.1f, 0.3f, 1f};

    /**
     * Sea depth in metres against the ocean half of the continentalness axis, so a
     * mod's deep-ocean biomes want real depth rather than merely being offshore. This
     * world's sea runs deep — a median of about two kilometres — so the deep band is
     * the common one, as abyssal plain is on Earth.
     */
    private static final float[] OCEAN_M = {-4900f, -3000f, -1800f, -500f, 0f};
    private static final float[] OCEAN_V = {-1f, -0.7f, -0.455f, -0.3f, -0.19f};

    /**
     * Distance inland in blocks against the land half. This is an oceanic world of
     * modest landmasses: measured, almost nowhere sits further than about 2600 blocks
     * from salt water, so that is where "far inland" has to begin. Anchoring it at a
     * continental-looking distance instead left the band empty.
     */
    private static final float[] INLAND_BLOCKS = {0f, 200f, 600f, 1500f, 2600f};
    private static final float[] INLAND_V = {-0.15f, -0.05f, 0.1f, 0.4f, 1f};

    /**
     * Slope against erosion, inverted: vanilla's least-eroded end is its mountainous
     * one. Anchored so the measured land quartiles fall on vanilla's own band edges,
     * which spreads real terrain across all seven instead of banking it in the flat
     * ones. Ocean floor is smooth and lands flat, which is correct and also why land
     * and sea have to be measured apart.
     */
    private static final float[] SLOPE_X = {0f, 0.05f, 0.09f, 0.3f, 0.45f, 0.6f, 0.95f, 1.4f};
    private static final float[] SLOPE_V = {1f, 0.55f, 0.45f, 0.05f, -0.2225f, -0.375f, -0.78f, -1f};

    /** Surface biomes only; the cave pass owns everything below. */
    private static final float SURFACE_DEPTH = 0f;

    /** Variant selector, at the scale of vanilla's own ridge noise. */
    private static final FastNoiseLite WEIRDNESS_NOISE = makeFnl(0x5EED1, 1f / 500f, 2, 2f, 0.5f);

    private ClimateSynthesis() {
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    /**
     * Writes one cell's six parameters into {@code out} at {@code off}.
     *
     * @param tempC             mean temperature in Celsius, latitude bias included
     * @param treeMoisture      from {@link DerivedClimate}
     * @param elevMetres        surface elevation; negative is sea
     * @param continentalBlocks distance to the sea in blocks, from {@link ContinentalField}
     * @param slope             blocks risen per block travelled
     * @param blockX            world coordinates, for the variant noise
     */
    public static void fill(float[] out, int off, float tempC, float treeMoisture,
                            float elevMetres, float continentalBlocks, float slope,
                            float blockX, float blockZ) {
        out[off + TEMPERATURE] = piecewise(tempC, TEMP_C, TEMP_V);
        out[off + HUMIDITY] = piecewise(treeMoisture, MOIST_X, MOIST_V);
        out[off + CONTINENTALNESS] = elevMetres < 0f
                ? piecewise(elevMetres, OCEAN_M, OCEAN_V)
                : piecewise(continentalBlocks, INLAND_BLOCKS, INLAND_V);
        out[off + EROSION] = piecewise(slope, SLOPE_X, SLOPE_V);
        out[off + DEPTH] = SURFACE_DEPTH;
        out[off + WEIRDNESS] = WEIRDNESS_NOISE.GetNoise(blockX, blockZ);
    }

    /** Linear between anchors, flat outside them. Both arrays ascend in {@code xs}. */
    private static float piecewise(float v, float[] xs, float[] ys) {
        int n = xs.length;
        if (v <= xs[0]) return ys[0];
        if (v >= xs[n - 1]) return ys[n - 1];
        int i = 0;
        while (i < n - 2 && v > xs[i + 1]) i++;
        float t = (v - xs[i]) / (xs[i + 1] - xs[i]);
        return ys[i] + t * (ys[i + 1] - ys[i]);
    }
}
