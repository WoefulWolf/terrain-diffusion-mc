package com.github.xandergos.terraindiffusionmc.pipeline.river;

/**
 * Cuts a channel into an elevation field along a refined path.
 *
 * <p>The bed descends with the terrain rather than dropping to sea level, so a river at
 * 900 m keeps its bed at 900 m. Where the ground rises the bed holds level and the cut
 * deepens into a gorge, since the alternative is a bed that climbs.
 *
 * <p>Pure functions over flat arrays; no Minecraft or tensor types.
 */
public final class RiverCarver {

    /** Below this the taper is flat enough that further cells are not worth touching. */
    private static final float EDGE_EPSILON = 0.02f;

    private RiverCarver() {
    }

    /**
     * Cuts one channel, lowering {@code elev} and stamping the river biome id in place.
     *
     * @param elev        elevation in metres, row-major, mutated
     * @param biomeIds    classifier ids, row-major, mutated along the wetted channel
     * @param temperature per-cell temperature in Celsius, or null to never freeze
     * @param path        cell indices from upstream to downstream, as produced by
     *                    {@link ChannelRefiner#trace}
     * @param halfWidth   channel half-width in cells; scale it by Strahler order so trunks
     *                    read wider than headwaters
     * @param depth       how far below local ground the bed sits, in metres
     */
    public static void carveChannel(float[] elev, short[] biomeIds, float[] temperature,
                                    int height, int width, int[] path,
                                    int halfWidth, float depth,
                                    short riverId, short frozenRiverId) {
        if (path == null || path.length == 0) return;
        int radius = Math.max(0, halfWidth);

        // Fix the bed profile against untouched ground first. Deriving it while carving
        // reads cells earlier steps already lowered, and the cut runs away downstream.
        float[] bedProfile = new float[path.length];
        float bed = elev[path[0]] - depth;
        for (int step = 0; step < path.length; step++) {
            // Monotone descent: the bed follows the ground down but never climbs back up.
            bed = Math.min(bed, elev[path[step]] - depth);
            bedProfile[step] = bed;
        }

        for (int step = 0; step < path.length; step++) {
            int cell = path[step];
            bed = bedProfile[step];

            int r0 = cell / width;
            int c0 = cell - r0 * width;

            for (int dr = -radius; dr <= radius; dr++) {
                int r = r0 + dr;
                if (r < 0 || r >= height) continue;
                for (int dc = -radius; dc <= radius; dc++) {
                    int c = c0 + dc;
                    if (c < 0 || c >= width) continue;

                    float dist = (float) Math.sqrt(dr * dr + dc * dc);
                    if (dist > radius + 0.5f) continue;

                    // 1 at the centre line, easing to 0 at the bank.
                    float t = radius == 0 ? 1f : clamp01(1f - dist / (radius + 0.5f));
                    float ease = t * t * (3f - 2f * t);
                    if (ease < EDGE_EPSILON) continue;

                    int idx = r * width + c;
                    float target = elev[idx] + ease * (bed - elev[idx]);
                    if (target < elev[idx]) elev[idx] = target;

                    // Only the part actually holding water reads as river.
                    if (biomeIds != null && ease > 0.5f) {
                        boolean frozen = temperature != null && temperature[idx] < 0f;
                        biomeIds[idx] = frozen ? frozenRiverId : riverId;
                    }
                }
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
