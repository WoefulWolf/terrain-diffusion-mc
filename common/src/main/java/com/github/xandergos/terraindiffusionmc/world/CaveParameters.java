package com.github.xandergos.terraindiffusionmc.world;

/**
 * Per-world dials for the cave surface gate, chosen at world creation.
 *
 * <p>Pure data with clamped construction, like {@link RiverParameters}. A seal of zero
 * switches that tier's gating off entirely, so caves and ravines may break the surface
 * anywhere, the way vanilla generates.
 */
public final class CaveParameters {

    public static final int DEFAULT_SMALL_SEAL_BLOCKS = 9;
    public static final int DEFAULT_LARGE_SEAL_BLOCKS = 16;

    public static final CaveParameters DEFAULT =
            new CaveParameters(DEFAULT_SMALL_SEAL_BLOCKS, DEFAULT_LARGE_SEAL_BLOCKS);

    /** Blocks small tunnels must stay below gentle ground; 0 turns the gate off. */
    public final int smallSealBlocks;
    /** Blocks wide cave mouths must stay below the surface; 0 turns the gate off. */
    public final int largeSealBlocks;

    public CaveParameters(int smallSealBlocks, int largeSealBlocks) {
        this.smallSealBlocks = clampSeal(smallSealBlocks);
        this.largeSealBlocks = clampSeal(largeSealBlocks);
    }

    private static int clampSeal(int value) {
        return Math.max(0, Math.min(48, value));
    }
}
