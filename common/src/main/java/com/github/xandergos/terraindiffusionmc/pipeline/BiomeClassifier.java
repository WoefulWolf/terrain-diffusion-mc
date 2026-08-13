package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Rule-based biome classifier port of _classify_biome in minecraft_api.py.
 *
 * <p>Uses fixed-seed FastNoiseLite instances for climate and elevation noise perturbations.
 * Biome IDs match the Python server's _BIOME_ID mapping.
 */
public final class BiomeClassifier {

    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;

    static {
        TEMP_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
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

    // Ids are indices into vanilla's Biomes registration order: plains 1, deep_dark 53.
    // Sparse variants with no vanilla equivalent use base + 100.
    static final short PLAINS = 1, SNOWY_PLAINS = 3, DESERT = 5, SWAMP = 6;
    static final short FOREST = 8, TAIGA = 15, SNOWY_TAIGA = 16, SAVANNA = 17;
    static final short WINDSWEPT_HILLS = 19, JUNGLE = 23, BADLANDS = 26, MEADOW = 29;
    static final short GROVE = 31, SNOWY_SLOPES = 32, FROZEN_PEAKS = 33, STONY_PEAKS = 35;
    static final short WARM_OCEAN = 41, OCEAN = 44, COLD_OCEAN = 46, FROZEN_OCEAN = 48;
    static final short FOREST_SPARSE = 108, TAIGA_SPARSE = 115, SNOWY_TAIGA_SPARSE = 116;

    static final short ICE_SPIKES = 4, MANGROVE_SWAMP = 7, BIRCH_FOREST = 10, DARK_FOREST = 11;
    static final short OLD_GROWTH_BIRCH_FOREST = 12, OLD_GROWTH_PINE_TAIGA = 13;
    static final short OLD_GROWTH_SPRUCE_TAIGA = 14, SAVANNA_PLATEAU = 18;
    static final short WINDSWEPT_GRAVELLY_HILLS = 20, WINDSWEPT_FOREST = 21, WINDSWEPT_SAVANNA = 22;
    static final short SPARSE_JUNGLE = 24, BAMBOO_JUNGLE = 25, ERODED_BADLANDS = 27;
    static final short WOODED_BADLANDS = 28, JAGGED_PEAKS = 34, LUKEWARM_OCEAN = 42;
    static final short DEEP_LUKEWARM_OCEAN = 43, DEEP_OCEAN = 45, DEEP_COLD_OCEAN = 47;
    static final short DEEP_FROZEN_OCEAN = 49;

    // Thresholds separating each variant from its parent biome. All empirical.
    private static final float DEEP_OCEAN_DEPTH_M = -1800f;
    private static final float JAGGED_PEAKS_MIN_ALT_M = 3200f;
    private static final float BADLANDS_MIN_ALT_M = 900f;
    private static final float WOODED_BADLANDS_MAX_MOISTURE = 0.35f;
    private static final float SAVANNA_PLATEAU_MIN_ALT_M = 900f;
    private static final float BAMBOO_JUNGLE_MIN_PRECIP_MM = 2200f;
    private static final float DARK_FOREST_MIN_MOISTURE = 1.05f;
    private static final float OLD_GROWTH_MIN_MOISTURE = 1.10f;
    private static final float BIRCH_FOREST_MAX_TEMP_C = 15f;
    private static final float ICE_SPIKES_MAX_PRECIP_MM = 220f;
    private static final float ICE_SPIKES_MAX_SLOPE = 0.15f;

    /**
     * Classify biomes for a grid of pixels.
     *
     * @param elev       elevation in meters, (H, W) row-major
     * @param climate    climate data (5, H, W) row-major or null
     * @param i0         top-left row in world space (for noise sampling)
     * @param j0         top-left col in world space
     * @param elevPadded elevation with 1-pixel padding, (H+2, W+2) row-major
     * @param H          height
     * @param W          width
     * @param pixelSizeM physical size of one pixel in meters
     * @return short array (H, W) with biome IDs
     */
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM) {
        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = PLAINS;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        // Generate Perlin noise perturbations
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nx = j0 + c, ny = i0 + r;
                float tnc = TEMP_NOISE.GetNoise(nx, ny);
                float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * tnc + 0.2f * tnf;

                float pn = PRECIP_NOISE.GetNoise(nx, ny);
                precipNoiseFact[idx] = 1.0f + 0.2f * pn;

                float snc = SNOW_NOISE.GetNoise(nx, ny);
                float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                snowNoise[idx] = 3.0f * snc + 2.0f * snf;
            }
        }

        // Compute slope from padded elevation using Sobel (divide by pixelSizeM for ratio)
        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

        // Process per-pixel
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elevVal   = elev[idx];
                float altM      = Math.max(0f, elevVal);
                float slope     = slopeRatio[idx];

                // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
                float temp     = climate[idx] + tempNoise[idx];
                float tSeason  = climate[H * W + idx];
                float precip   = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                float pCV      = climate[3 * H * W + idx];

                // Derived climate variables
                float tStd     = tSeason / 100f;
                float tEff     = Math.max(0f, temp + 0.5f * tStd);
                float pet      = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
                float aridity  = precip / Math.max(1f, pet);
                float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture = aridity * seasonPenalty;

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if (x <= -1f) growingSeason = 365f;
                    else if (x >= 1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                // Slope-dependent bare threshold
                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage classification
                boolean treesNone = effTreeMoisture < 0.2f;
                boolean tooArid   = treeMoisture < 0.05f;
                boolean tooCold   = growingSeason < 60f;
                boolean barren    = tooArid || tooCold;
                boolean treesSparse    = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest    = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense     = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Slope overrides
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) { treesSparse = true; }
                    treesForest = treesForest && false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Snow classification
                float snowTemp = temp + snowNoise[idx];
                boolean isSteep = slope > 0.78f;
                boolean hasSnow = snowTemp < 0f && precip > 150f && !isSteep;

                // Elevation/temp bands
                boolean isOcean   = elevVal < 0f;
                boolean mountains = altM > 2500f;
                boolean lowland   = altM < 200f;
                boolean frozen    = temp < -5f;
                boolean cold      = temp >= -5f && temp < 5f;
                boolean cool      = temp >= 5f  && temp < 12f;
                boolean temperate = temp >= 12f && temp < 20f;
                boolean warm      = temp >= 20f && temp < 26f;
                boolean hot       = temp >= 26f;

                short biome = PLAINS;

                if (isOcean) {
                    boolean deep = elevVal < DEEP_OCEAN_DEPTH_M;
                    if (frozen) biome = deep ? DEEP_FROZEN_OCEAN : FROZEN_OCEAN;
                    else if (cold) biome = deep ? DEEP_COLD_OCEAN : COLD_OCEAN;
                    else if (hot) biome = WARM_OCEAN;   // vanilla has no deep warm ocean
                    else if (warm) biome = deep ? DEEP_LUKEWARM_OCEAN : LUKEWARM_OCEAN;
                    else biome = deep ? DEEP_OCEAN : OCEAN;
                } else if (mountains) {
                    if (slopeBare) {
                        if (hasSnow) biome = altM > JAGGED_PEAKS_MIN_ALT_M ? JAGGED_PEAKS : FROZEN_PEAKS;
                        else biome = STONY_PEAKS;
                    } else if (hasSnow) {
                        if (treesNone) biome = SNOWY_SLOPES;
                        else if (treesSparse || treesForest) biome = SNOWY_TAIGA_SPARSE;
                        else biome = SNOWY_TAIGA;
                    } else if (treesNone) {
                        if (barren) biome = slopeMedium ? WINDSWEPT_GRAVELLY_HILLS : WINDSWEPT_HILLS;
                        else if (treeMoisture < 0.35f || precip < 350f) biome = GROVE;
                        else biome = MEADOW;
                    } else if (treesSparse || treesForest) {
                        biome = slopeMedium ? WINDSWEPT_FOREST : TAIGA_SPARSE;
                    } else {
                        biome = TAIGA;
                    }
                } else {
                    // Lowland/midland
                    if (hasSnow && treesNone) {
                        boolean spikes = frozen && precip < ICE_SPIKES_MAX_PRECIP_MM
                                && slope < ICE_SPIKES_MAX_SLOPE;
                        biome = spikes ? ICE_SPIKES : SNOWY_PLAINS;
                    } else if (hasSnow) {
                        biome = (treesSparse || treesForest) ? SNOWY_TAIGA_SPARSE : SNOWY_TAIGA;
                    } else if (treesNone) {
                        if (hot && altM > BADLANDS_MIN_ALT_M) {
                            biome = slopeMedium ? ERODED_BADLANDS : BADLANDS;
                        }
                        else if (warm || hot) biome = DESERT;
                        else if (barren && !lowland && (cold || cool || temperate)) biome = GROVE;
                        else if (treeMoisture < 0.35f || precip < 350f) biome = GROVE;
                        else biome = PLAINS;
                    } else if (treesSparse || treesForest) {
                        if (hot) {
                            if (altM > BADLANDS_MIN_ALT_M && treeMoisture < WOODED_BADLANDS_MAX_MOISTURE) {
                                biome = WOODED_BADLANDS;
                            } else {
                                biome = treesSparse ? SPARSE_JUNGLE : JUNGLE;
                            }
                        }
                        else if (warm && treesSparse && slopeMedium) biome = WINDSWEPT_SAVANNA;
                        else if (warm && treesSparse) {
                            biome = altM > SAVANNA_PLATEAU_MIN_ALT_M ? SAVANNA_PLATEAU : SAVANNA;
                        }
                        else if (warm && treesForest) biome = FOREST_SPARSE;
                        else if (temperate) biome = FOREST_SPARSE;
                        else biome = TAIGA_SPARSE;
                    } else if (treesDense) {
                        if (hot) biome = precip > BAMBOO_JUNGLE_MIN_PRECIP_MM ? BAMBOO_JUNGLE : JUNGLE;
                        else if (warm && lowland) biome = SWAMP;
                        else if (cool || cold) {
                            biome = effTreeMoisture >= OLD_GROWTH_MIN_MOISTURE ? OLD_GROWTH_PINE_TAIGA : TAIGA;
                        }
                        else if (temperate && effTreeMoisture >= DARK_FOREST_MIN_MOISTURE) biome = DARK_FOREST;
                        else if (temperate && temp < BIRCH_FOREST_MAX_TEMP_C) biome = BIRCH_FOREST;
                        else biome = FOREST;
                    } else { // rainforest
                        if (hot && lowland) biome = MANGROVE_SWAMP;
                        else if (hot || (warm && temp >= 18f && tStd < 5f)) {
                            biome = precip > BAMBOO_JUNGLE_MIN_PRECIP_MM ? BAMBOO_JUNGLE : JUNGLE;
                        }
                        else if (lowland) biome = SWAMP;
                        else if (cool || cold) biome = OLD_GROWTH_SPRUCE_TAIGA;
                        else if (temperate && temp < BIRCH_FOREST_MAX_TEMP_C) biome = OLD_GROWTH_BIRCH_FOREST;
                        else biome = FOREST;
                    }
                }

                // Bare slope override for lowland/non-mountain cliffs
                if (slopeBare && !isOcean && !mountains) {
                    biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;
                }

                out[idx] = biome;
            }
        }
        return out;
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) padded array → (H, W) output
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1,0,1, -2,0,2, -1,0,1};
        float[] sy = {-1,-2,-1, 0,0,0, 1,2,1};
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int kr = 0; kr < 3; kr++)
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + (c + kc)];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                dx /= 8f; dy /= 8f;
                slope[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy) / pixelSizeM;
            }
        }
        return slope;
    }
}
