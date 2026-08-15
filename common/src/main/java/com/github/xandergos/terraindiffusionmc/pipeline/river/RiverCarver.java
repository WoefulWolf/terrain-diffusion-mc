package com.github.xandergos.terraindiffusionmc.pipeline.river;

/**
 * Cuts a channel into an elevation field and records the water surface above it.
 *
 * <p>The channel is a flat bed with banks rising to meet untouched ground. The water
 * surface comes from the caller rather than from the local terrain, because it has to
 * agree across tiles: a river is carved a tile at a time, and a surface derived locally
 * would step at every tile edge.
 *
 * <p>Nothing decides the waterline width directly. A cell is wet if the carved ground
 * ends up below the surface, so the bank sets the width by rising until it stands clear,
 * and a channel can only hold as much water as its own banks allow.
 *
 * <p>Pure functions over flat arrays; no Minecraft or tensor types.
 */
public final class RiverCarver {

    /** Blocks of bank rise per block outward. Steeper than talus, so banks stay narrow. */
    private static final float BANK_SLOPE = 2f;
    /** Beyond this a bank is a valley side, and the diffusion terrain already has those. */
    private static final float MAX_BANK_CELLS = 12f;
    /**
     * Edge wobble scaling. A channel is a union of discs along the path, and its envelope
     * is a chain of arcs that reads as brush stamps once the discs are large. Pushing the
     * distance test in and out per cell turns the waterline into an organic contour. The
     * push grows with the channel and vanishes toward brook size, so springs stay crisp;
     * its full strength comes from the caller as a per-world choice.
     */
    private static final float EDGE_WOBBLE_FULL_RADIUS = 12f;
    /**
     * Blocks a cell must sit below the water surface to count as wetted. Without a floor,
     * a channel crossing a shallow filled flat wets its whole footprint in a paper-thin
     * sheet, ballooning the visible river far beyond its carved banks.
     */
    private static final float WET_MIN_DEPTH_BLOCKS = 0.35f;
    /**
     * Below this surface elevation, in blocks, the wet floor relaxes with the fade. A
     * fading ocean mouth cuts ever shallower, and against the full floor the river dries
     * up just short of the surf; a fading source in dry uplands keeps the full floor.
     */
    private static final float MOUTH_WET_RELAX_BLOCKS = 3f;

    /**
     * Below this mean temperature a river freezes over completely: ice bank to bank,
     * steps and falls solid, instead of the bank-inward margin milder cold gets. Rivers
     * with real current keep an open channel well under zero; only severe subarctic
     * means close them.
     */
    public static final float FULL_FREEZE_C = -20f;
    /** High bit of a riverClass byte: this cell's water is fully frozen over. */
    public static final int FULLY_FROZEN_BIT = 0x80;
    /** The steepness class lives in the low seven bits. */
    public static final int CLASS_MASK = 0x7F;

    private RiverCarver() {
    }

    /**
     * Widest possible footprint of a path point, in blocks from its centre. Callers
     * collecting path points near a tile must extend their margin this far, or a channel
     * running just outside the border leaves its overhanging rim uncarved in the tile
     * that owns those blocks.
     */
    public static int maxReachBlocks(float maxHalfWidth, float edgeWobbleMax) {
        return (int) Math.ceil(maxHalfWidth + MAX_BANK_CELLS + edgeWobbleMax);
    }

    /**
     * Cuts one channel, lowering {@code elev} and stamping the river biome id in place.
     *
     * @param elev          elevation in metres, row-major, mutated
     * @param biomeIds      classifier ids, row-major, mutated along the wetted channel
     * @param temperature   per-cell temperature in Celsius, or null to never freeze
     * @param waterLevel    water surface in metres per cell, mutated; cells with no water
     *                      are left at {@link Float#NEGATIVE_INFINITY}
     * @param claimDist     water-claim arbitration per cell: negative means locked by an
     *                      earlier, larger claimant, else the distance of the best claim so
     *                      far. Within a channel the nearest path point sets the surface,
     *                      which keeps a cross-section uniform; without it the surface
     *                      field is patchworked by whichever disc reached a cell first,
     *                      and the quantised steps land as random bumps and bank ridges
     * @param riverClass    per-cell channel steepness for bed materials, mutated; 0 means
     *                      the rivers never touched the cell, else {@code 1 + steep * 100}
     * @param pathRow       path point rows from upstream to downstream; may lie outside
     *                      the tile, only in-bounds cells of the disc are written
     * @param pathCol       path point columns, same length and convention
     * @param halfWidths    half-width of the flat bed in blocks at each point
     * @param depths        water depth in blocks at each point
     * @param surfaces      water surface elevation in metres at each point, non-increasing
     * @param steeps        local gradient at each point, 0 slack to 1 fully steep
     * @param fades         carve strength at each point, 1 full cut to 0 none; an ocean
     *                      mouth fades out so the channel feathers into the shelf instead
     *                      of ending in a stamped disc
     * @param edgeWobble    per-cell noise in [-1, 1] sampled at world coordinates, or null
     *                      for plain circular edges; a cell's value is fixed, so every disc
     *                      overlapping it agrees on where the edge sits
     * @param freeboard     blocks of bank standing above the water surface
     * @param edgeWobbleMax blocks the waterline may wobble in and out at full channel size
     * @param metresPerBlock vertical scale, so depths in blocks meet elevations in metres
     */
    public static void carveChannel(float[] elev, short[] biomeIds, float[] temperature,
                                    float[] waterLevel, float[] claimDist, byte[] riverClass,
                                    int height, int width, int[] pathRow, int[] pathCol,
                                    float[] halfWidths, float[] depths, float[] surfaces,
                                    float[] steeps, float[] fades, float[] edgeWobble,
                                    float freeboard, float edgeWobbleMax, float metresPerBlock,
                                    short riverId, short frozenRiverId) {
        if (pathRow == null || pathRow.length == 0) return;
        float wetCut = WET_MIN_DEPTH_BLOCKS * metresPerBlock;

        for (int step = 0; step < pathRow.length; step++) {
            float fade = fades[step];
            if (fade <= 0.02f) continue;

            float surface = surfaces[step];
            float depth = depths[step];
            float bed = surface - depth * metresPerBlock;
            byte cls = (byte) (1 + Math.round(clamp01(steeps[step]) * 100f));

            // Fractional radius and bank, so width varies continuously along the path
            // instead of snapping a whole block at a time.
            float radius = Math.max(0f, halfWidths[step]);
            // The bank has to climb the whole channel, water depth and freeboard alike, so
            // a deep river gets a wider cut at the same slope.
            float bank = Math.max(1f, Math.min(MAX_BANK_CELLS, (depth + freeboard) / BANK_SLOPE));
            float wobbleAmp = edgeWobble == null ? 0f
                    : Math.min(1f, radius / EDGE_WOBBLE_FULL_RADIUS) * edgeWobbleMax;
            int reach = (int) Math.ceil(radius + bank + wobbleAmp);

            int r0 = pathRow[step];
            int c0 = pathCol[step];
            // An out-of-tile point whose whole disc misses the tile costs nothing.
            if (r0 + reach < 0 || r0 - reach >= height
                    || c0 + reach < 0 || c0 - reach >= width) continue;

            for (int dr = -reach; dr <= reach; dr++) {
                int r = r0 + dr;
                if (r < 0 || r >= height) continue;
                for (int dc = -reach; dc <= reach; dc++) {
                    int c = c0 + dc;
                    if (c < 0 || c >= width) continue;

                    int idx = r * width + c;
                    float dist = (float) Math.sqrt(dr * dr + dc * dc);
                    if (wobbleAmp > 0f) dist -= edgeWobble[idx] * wobbleAmp;
                    if (dist > radius + bank) continue;
                    float target;
                    if (dist <= radius) {
                        target = bed;
                    } else {
                        // Ease from the bed edge up to whatever ground is there. Reading
                        // ground that an earlier step already lowered is harmless: the
                        // result only ever settles further towards the bed, never past it.
                        float t = (dist - radius) / bank;
                        float ease = t * t * (3f - 2f * t);
                        target = bed + ease * (elev[idx] - bed);
                    }
                    // Fading pulls the cut back toward untouched ground, so a mouth taper
                    // weakens the whole cross-section at once, banks and bed alike.
                    if (fade < 1f) target = fade * target + (1f - fade) * elev[idx];
                    boolean lowered = target < elev[idx];
                    if (lowered) elev[idx] = target;

                    float stepWetCut = surface < MOUTH_WET_RELAX_BLOCKS * metresPerBlock
                            ? wetCut * fade : wetCut;
                    boolean wet = elev[idx] < surface - stepWetCut;
                    if (riverClass != null && (lowered || wet)
                            && cls > (riverClass[idx] & CLASS_MASK)) {
                        riverClass[idx] = (byte) ((riverClass[idx] & FULLY_FROZEN_BIT) | cls);
                    }
                    if (!wet) continue;

                    if (waterLevel != null && claimDist != null
                            && claimDist[idx] >= 0f && dist < claimDist[idx]) {
                        waterLevel[idx] = surface;
                        // Clamped: a negative marks a lock, and wobble can push dist under 0.
                        claimDist[idx] = Math.max(0f, dist);
                    }
                    if (biomeIds != null) {
                        boolean frozen = temperature != null && temperature[idx] < 0f;
                        biomeIds[idx] = frozen ? frozenRiverId : riverId;
                    }
                    if (riverClass != null && temperature != null
                            && temperature[idx] < FULL_FREEZE_C) {
                        riverClass[idx] |= FULLY_FROZEN_BIT;
                    }
                }
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
