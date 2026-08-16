package com.github.xandergos.terraindiffusionmc.world;

/**
 * Per-world river generation dials, chosen at creation. Pure data with no Minecraft
 * types, so the analysis code can take it without dragging the game along.
 */
public final class RiverParameters {

    /** Catchment a system must reach somewhere along its run to count as a river at all. */
    public static final int DEFAULT_MAIN_CHANNEL_CELLS = 20000;
    /**
     * Catchment down to which a river's main stem is traced upstream; also the width
     * reference, so a river starts as a one-block spring wherever the trace ends.
     */
    public static final int DEFAULT_HEADWATER_CELLS = 600;
    public static final int DEFAULT_MAX_WIDTH_BLOCKS = 51;
    public static final int DEFAULT_MAX_DEPTH_BLOCKS = 5;
    /** Power of width against catchment; near real hydraulic geometry. */
    public static final float DEFAULT_WIDTH_EXPONENT = 0.6f;
    /** Blocks of bank above the waterline. */
    public static final float DEFAULT_FREEBOARD_BLOCKS = 1.0f;
    /** Smallest basin, in native cells, that fills as a lake instead of being carved through. */
    public static final int DEFAULT_LAKE_MIN_CELLS = 250;
    /**
     * Blocks of water a lake reaches where its basin is deep enough to earn it. The bed
     * tapers up to its shore rather than holding this everywhere, so this is the middle
     * of a lake rather than a floor under all of it.
     */
    public static final float DEFAULT_LAKE_DEPTH_BLOCKS = 5.0f;
    /** Blocks the waterline wobbles in and out on the largest rivers. */
    public static final float DEFAULT_EDGE_WOBBLE_BLOCKS = 5.0f;
    /** Blocks of coherent relief on river and lake floors. */
    public static final float DEFAULT_BED_RELIEF_BLOCKS = 2.0f;

    public static final RiverParameters DEFAULT = new RiverParameters(
            DEFAULT_MAIN_CHANNEL_CELLS, DEFAULT_HEADWATER_CELLS,
            DEFAULT_MAX_WIDTH_BLOCKS, DEFAULT_MAX_DEPTH_BLOCKS,
            DEFAULT_WIDTH_EXPONENT, DEFAULT_FREEBOARD_BLOCKS,
            DEFAULT_LAKE_MIN_CELLS, DEFAULT_LAKE_DEPTH_BLOCKS,
            DEFAULT_EDGE_WOBBLE_BLOCKS, DEFAULT_BED_RELIEF_BLOCKS);

    public final int mainChannelCells;
    public final int headwaterCells;
    public final int maxWidthBlocks;
    public final int maxDepthBlocks;
    public final float widthExponent;
    public final float freeboardBlocks;
    public final int lakeMinCells;
    public final float lakeDepthBlocks;
    public final float edgeWobbleBlocks;
    public final float bedReliefBlocks;

    public RiverParameters(int mainChannelCells, int headwaterCells,
                           int maxWidthBlocks, int maxDepthBlocks,
                           float widthExponent, float freeboardBlocks,
                           int lakeMinCells, float lakeDepthBlocks,
                           float edgeWobbleBlocks, float bedReliefBlocks) {
        this.mainChannelCells = clamp(mainChannelCells, 1000, 500_000);
        this.headwaterCells = clamp(headwaterCells, 50, 20_000);
        this.maxWidthBlocks = clamp(maxWidthBlocks, 5, 121);
        this.maxDepthBlocks = clamp(maxDepthBlocks, 2, 24);
        this.widthExponent = clamp(widthExponent, 0.3f, 1.5f);
        this.freeboardBlocks = clamp(freeboardBlocks, 0.5f, 3f);
        this.lakeMinCells = clamp(lakeMinCells, 50, 10_000);
        this.lakeDepthBlocks = clamp(lakeDepthBlocks, 1f, 8f);
        this.edgeWobbleBlocks = clamp(edgeWobbleBlocks, 0f, 12f);
        this.bedReliefBlocks = clamp(bedReliefBlocks, 0f, 4f);
    }

    /** Edge-fed systems qualify at a quarter of the main bar, so one dial moves both. */
    public float edgeFedCells() {
        return mainChannelCells / 4f;
    }

    public float maxHalfWidth() {
        return (maxWidthBlocks - 1) / 2f;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static float clamp(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
