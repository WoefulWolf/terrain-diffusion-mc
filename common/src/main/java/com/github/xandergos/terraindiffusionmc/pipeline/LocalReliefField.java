package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How high the high ground gets around here, in metres.
 *
 * <p>Biomes keyed to absolute altitude quietly vanish from whole landmasses. A continent
 * topping out at 1800 m has no cell above the 2500 m the classifier calls mountainous,
 * so it grows no alpine anything: no meadow, no snowy slopes, no peaks, however
 * mountainous it looks from the ground. Judging a place against the country around it
 * instead lets every landmass have a high country of its own, which is what a player
 * reads anyway — nobody converts blocks back to metres to check a biome earned its
 * altitude.
 *
 * <p>The reference is a high percentile rather than the maximum, so one spike cannot
 * speak for a region, taken over land only, at coarse resolution where a neighbourhood
 * of a few thousand blocks costs a cached slice. Callers blend it with an absolute
 * threshold rather than trusting it alone: a genuinely flat island should stay flat, not
 * have its 200 m hillocks promoted to alpine.
 */
public final class LocalReliefField {

    /** Native pixels per coarse cell, matching the pipeline's climate conversion. */
    public static final int CELL_NATIVE = 32 * WorldPipeline.LATENT_COMPRESSION;

    /** Cells per cached square. */
    private static final int TILE = 32;
    /** Neighbourhood radius in cells that "around here" means. */
    private static final int RADIUS = 10;
    /** Percentile of neighbourhood land elevation taken as the local high ground. */
    private static final float PERCENTILE = 0.9f;

    private static final Map<Long, float[]> CACHE = new ConcurrentHashMap<>();

    private LocalReliefField() {
    }

    /** Drops every cached square. Call when the seed changes. */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Local high-ground elevation in metres for every cell of a block-space window,
     * sampled bilinearly so the reference drifts rather than stepping at cell edges.
     * Must run on the inference thread.
     *
     * @param scale blocks per native pixel
     */
    public static float[] forBlockWindow(int i0, int j0, int height, int width, int scale,
                                         WorldPipeline pipeline) {
        int cellBlocks = CELL_NATIVE * scale;
        int ci0 = Math.floorDiv(i0, cellBlocks) - 1;
        int cj0 = Math.floorDiv(j0, cellBlocks) - 1;
        int ci1 = Math.floorDiv(i0 + height - 1, cellBlocks) + 1;
        int cj1 = Math.floorDiv(j0 + width - 1, cellBlocks) + 1;
        int gh = ci1 - ci0 + 1, gw = cj1 - cj0 + 1;

        float[] grid = new float[gh * gw];
        for (int r = 0; r < gh; r++) {
            for (int c = 0; c < gw; c++) {
                grid[r * gw + c] = referenceAt(ci0 + r, cj0 + c, pipeline);
            }
        }

        float[] out = new float[height * width];
        for (int r = 0; r < height; r++) {
            float gy = (float) (i0 + r) / cellBlocks - ci0 - 0.5f;
            int y0 = Math.max(0, Math.min(gh - 1, (int) Math.floor(gy)));
            int y1 = Math.min(gh - 1, y0 + 1);
            float wy = Math.max(0f, Math.min(1f, gy - y0));
            for (int c = 0; c < width; c++) {
                float gx = (float) (j0 + c) / cellBlocks - cj0 - 0.5f;
                int x0 = Math.max(0, Math.min(gw - 1, (int) Math.floor(gx)));
                int x1 = Math.min(gw - 1, x0 + 1);
                float wx = Math.max(0f, Math.min(1f, gx - x0));
                out[r * width + c] = (1 - wy) * (1 - wx) * grid[y0 * gw + x0]
                        + (1 - wy) * wx * grid[y0 * gw + x1]
                        + wy * (1 - wx) * grid[y1 * gw + x0]
                        + wy * wx * grid[y1 * gw + x1];
            }
        }
        return out;
    }

    /** Local high-ground elevation for one coarse cell, in metres. */
    static float referenceAt(int ci, int cj, WorldPipeline pipeline) {
        int ti = Math.floorDiv(ci, TILE), tj = Math.floorDiv(cj, TILE);
        float[] square = square(ti, tj, pipeline);
        return square[(ci - ti * TILE) * TILE + (cj - tj * TILE)];
    }

    private static float[] square(int ti, int tj, WorldPipeline pipeline) {
        long key = ((long) ti << 32) | (tj & 0xFFFFFFFFL);
        float[] cached = CACHE.get(key);
        if (cached != null) return cached;

        int side = TILE + 2 * RADIUS;
        int ci0 = ti * TILE - RADIUS, cj0 = tj * TILE - RADIUS;
        FloatTensor slice = pipeline.getCoarseSlice(ci0, cj0, ci0 + side, cj0 + side);

        // The coarse elevation channel is stored square-rooted and weighted; undo both,
        // exactly as the climate stage does, and clamp the sea away to zero.
        int n = side * side;
        float[] elev = new float[n];
        for (int i = 0; i < n; i++) {
            float w = slice.data[6 * n + i];
            float v = w > 1e-6f ? slice.data[i] / w : 0f;
            v = Math.max(0f, v);
            elev[i] = v * v;
        }

        float[] core = new float[TILE * TILE];
        float[] window = new float[(2 * RADIUS + 1) * (2 * RADIUS + 1)];
        for (int r = 0; r < TILE; r++) {
            for (int c = 0; c < TILE; c++) {
                int count = 0;
                for (int dr = -RADIUS; dr <= RADIUS; dr++) {
                    int nr = r + RADIUS + dr;
                    for (int dc = -RADIUS; dc <= RADIUS; dc++) {
                        int nc = c + RADIUS + dc;
                        float e = elev[nr * side + nc];
                        // Sea contributes nothing: an island's high ground is decided by
                        // the island, not by how much ocean happens to surround it.
                        if (e > 0f) window[count++] = e;
                    }
                }
                if (count == 0) {
                    core[r * TILE + c] = 0f;
                } else {
                    Arrays.sort(window, 0, count);
                    core[r * TILE + c] = window[Math.min(count - 1, (int) (PERCENTILE * count))];
                }
            }
        }
        CACHE.putIfAbsent(key, core);
        return CACHE.get(key);
    }
}
