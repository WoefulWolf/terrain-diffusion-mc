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
     * <p>Set low, because this is what puts springs on mountainsides. It cannot crowd the
     * big rivers together: the rarity bar decides how many systems exist at all, and this
     * only decides how far up each of them is followed, so everything it adds is a fine
     * branch at the top of a system that was already there. Raised high it strips those
     * branches and leaves nothing on the map under twenty blocks wide, which reads as a
     * country of big rivers and no streams.
     */
    public static final int DEFAULT_HEADWATER_CELLS = 600;

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
    /**
     * Blocks a basin's outlet has cut down through its rim, and so how far below the level
     * that would brim it the water sits.
     *
     * <p>The only dial that reaches how LARGE a basin is. A lake's shoreline is a contour of
     * the terrain, so the one way to pull it in is to move the contour down; the size floor
     * decides which hollows fill, never how far the water then spreads once one does.
     *
     * <p>Lowering the water is what makes this safe, where refusing the basin outright was
     * not. Every cell the fill still raises is a lake, excavated and given a bed, while the
     * cells left above the new level go back to being ordinary ground, so nothing can lay a
     * water surface over ground that was never dug out. A basin shallower than the cut
     * drains dry rather than inverting.
     *
     * <p>Two blocks is as far as this goes cleanly. A deeper cut steps the drainage surface
     * up at a shore harder than a channel surface can absorb, and water begins standing
     * above the dry ground beside it: measured at five blocks over at a three-block cut,
     * against two blocks anywhere in stock terrain.
     */
    public static final float DEFAULT_LAKE_INCISE_BLOCKS = 2.0f;
    /** Blocks the waterline wobbles in and out on the largest rivers. */
    public static final float DEFAULT_EDGE_WOBBLE_BLOCKS = 5.0f;
    /** Blocks of coherent relief on river and lake floors. */
    public static final float DEFAULT_BED_RELIEF_BLOCKS = 2.0f;

    public static final RiverParameters DEFAULT = new RiverParameters(
            DEFAULT_MAIN_CHANNEL_CELLS, DEFAULT_HEADWATER_CELLS, DEFAULT_WIDTH_REFERENCE_CELLS,
            DEFAULT_MAX_WIDTH_BLOCKS, DEFAULT_MAX_DEPTH_BLOCKS,
            DEFAULT_WIDTH_EXPONENT, DEFAULT_FREEBOARD_BLOCKS,
            DEFAULT_LAKE_MIN_CELLS, DEFAULT_LAKE_DEPTH_BLOCKS, DEFAULT_LAKE_INCISE_BLOCKS,
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
    public final float lakeInciseBlocks;
    public final float edgeWobbleBlocks;
    public final float bedReliefBlocks;

    public RiverParameters(int mainChannelCells, int headwaterCells, int widthReferenceCells,
                           int maxWidthBlocks, int maxDepthBlocks,
                           float widthExponent, float freeboardBlocks,
                           int lakeMinCells, float lakeDepthBlocks, float lakeInciseBlocks,
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
        this.lakeInciseBlocks = clamp(lakeInciseBlocks, 0.25f, 8f);
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
