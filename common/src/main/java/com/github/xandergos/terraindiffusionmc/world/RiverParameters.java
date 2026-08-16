package com.github.xandergos.terraindiffusionmc.world;

/**
 * Per-world river generation dials, chosen at creation. Pure data with no Minecraft
 * types, so the analysis code can take it without dragging the game along.
 */
public final class RiverParameters {

    /**
     * Catchment a system must reach somewhere along its run to count as a river at all.
     *
     * <p>Set high enough that meeting a river is an event rather than scenery, and that
     * the systems which do qualify are fed from real high country — a permissive bar
     * hands out sources a few hundred metres up, where there is nothing upstream worth
     * walking to.
     *
     * <p>This is the dial that decides how far a player walks between rivers, and close to
     * the only one that does: measured across a continent, it sets the median walk to water
     * almost on its own, while the trace depth barely shifts it even at maximum.
     */
    public static final int DEFAULT_MAIN_CHANNEL_CELLS = 500_000;
    /**
     * Catchment down to which a river's main stem is traced upstream: how far up its
     * valleys a river is followed before it stops being worth drawing.
     *
     * <p>It pulls in the direction most people expect the opposite of. Lowering it does
     * not simply make sources smaller — the trace walks further up every valley, so one
     * river system fans out into many more tributaries, each a channel in its own right.
     * Measured over a region, cutting it tenfold multiplies the channel count about
     * ninefold, enough to swamp a rarity setting raised tenfold alongside it, since
     * rarity only decides which systems qualify, not how far each is followed.
     *
     * <p>Set high by default so a river runs long and unbranched rather than arriving as
     * a delta of little streams; the rarity bar then keeps the systems themselves scarce.
     * Deliberately short of the ceiling: pushed all the way up, a river loses the
     * tributaries that make it read as a river, for very little gained in scarcity.
     */
    public static final int DEFAULT_HEADWATER_CELLS = 10_000;

    /**
     * Catchment that counts as a one-block spring, which every channel's width and depth
     * are measured against.
     *
     * <p>Deliberately its own dial rather than the trace depth, because the two want
     * opposite settings and one number cannot serve both: tracing wants a high bar so
     * rivers stay few and long, while width wants a low one so those rivers are broad
     * enough to be worth the walk. Tie them together and a world can have rare rivers or
     * majestic ones but not both, since every step towards scarcity is also a step
     * towards narrower channels.
     */
    public static final int DEFAULT_WIDTH_REFERENCE_CELLS = 600;
    public static final int DEFAULT_MAX_WIDTH_BLOCKS = 51;
    public static final int DEFAULT_MAX_DEPTH_BLOCKS = 5;
    /** Power of width against catchment; near real hydraulic geometry. */
    public static final float DEFAULT_WIDTH_EXPONENT = 0.6f;
    /** Blocks of bank above the waterline. */
    public static final float DEFAULT_FREEBOARD_BLOCKS = 1.0f;
    /**
     * Smallest basin, in native cells, that fills as a lake instead of being carved through.
     *
     * <p>Set high so lakes are landmarks rather than scenery. This governs how MANY lakes
     * there are and almost nothing else: raising it leaves the large basins untouched and
     * clears out the small ones, so the count falls sharply while the water covered barely
     * moves — the big basins hold nearly all of it. Requiring a deeper flood instead does
     * not work, because the basins this terrain makes are deep as well as broad.
     */
    public static final int DEFAULT_LAKE_MIN_CELLS = 6_000;
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
            DEFAULT_MAIN_CHANNEL_CELLS, DEFAULT_HEADWATER_CELLS, DEFAULT_WIDTH_REFERENCE_CELLS,
            DEFAULT_MAX_WIDTH_BLOCKS, DEFAULT_MAX_DEPTH_BLOCKS,
            DEFAULT_WIDTH_EXPONENT, DEFAULT_FREEBOARD_BLOCKS,
            DEFAULT_LAKE_MIN_CELLS, DEFAULT_LAKE_DEPTH_BLOCKS,
            DEFAULT_EDGE_WOBBLE_BLOCKS, DEFAULT_BED_RELIEF_BLOCKS);

    public final int mainChannelCells;
    public final int headwaterCells;
    public final int widthReferenceCells;
    public final int maxWidthBlocks;
    public final int maxDepthBlocks;
    public final float widthExponent;
    public final float freeboardBlocks;
    public final int lakeMinCells;
    public final float lakeDepthBlocks;
    public final float edgeWobbleBlocks;
    public final float bedReliefBlocks;

    public RiverParameters(int mainChannelCells, int headwaterCells, int widthReferenceCells,
                           int maxWidthBlocks, int maxDepthBlocks,
                           float widthExponent, float freeboardBlocks,
                           int lakeMinCells, float lakeDepthBlocks,
                           float edgeWobbleBlocks, float bedReliefBlocks) {
        // Headroom above the default, so a world can still be made drier than stock.
        this.mainChannelCells = clamp(mainChannelCells, 1000, 2_000_000);
        this.headwaterCells = clamp(headwaterCells, 50, 20_000);
        this.widthReferenceCells = clamp(widthReferenceCells, 50, 20_000);
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
