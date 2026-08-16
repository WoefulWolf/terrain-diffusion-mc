package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How far inland a place is, in blocks.
 *
 * <p>The shoreline pass measures distance to the sea too, but only across a 48-block
 * halo — enough to decide where sand lies, useless for telling a coastal plain from the
 * middle of a continent. That question is answered at coarse resolution, where one cell
 * is 256 native pixels and the ocean mask for a whole landmass costs a cached slice
 * instead of a tile of inference.
 *
 * <p>Distances saturate at {@link #PAD} cells because the flood only sees its own
 * window; past that everything is simply "far inland", which is all any consumer needs
 * to know. Results are cached per square of cells, and every cell of a square is
 * computed together since the flood has to run over the whole padded window anyway.
 */
public final class ContinentalField {

    /** Native pixels per coarse cell, matching the pipeline's climate conversion. */
    public static final int CELL_NATIVE = 32 * WorldPipeline.LATENT_COMPRESSION;

    /** Cells per cached square. */
    private static final int TILE = 32;
    /**
     * Halo in cells. Also the saturation distance: a core cell can be no further from
     * the sea than this before the flood runs out of window to look in.
     */
    private static final int PAD = 24;

    private static final Map<Long, float[]> CACHE = new ConcurrentHashMap<>();

    private ContinentalField() {
    }

    /** Drops every cached square. Call when the seed changes. */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Distance to the sea in blocks for every cell of a block-space window, sampled
     * bilinearly out of the coarse field so the value drifts across a continent instead
     * of stepping every 512 blocks.
     *
     * <p>Must run on the inference thread: a cache miss reads coarse tensor slices.
     *
     * @param scale blocks per native pixel
     */
    public static float[] forBlockWindow(int i0, int j0, int height, int width, int scale,
                                         WorldPipeline pipeline) {
        float cellBlocks = CELL_NATIVE * (float) scale;

        // Cell span covering the window, plus one on each side for the interpolation.
        int ci0 = Math.floorDiv(i0, (int) cellBlocks) - 1;
        int cj0 = Math.floorDiv(j0, (int) cellBlocks) - 1;
        int ci1 = Math.floorDiv(i0 + height - 1, (int) cellBlocks) + 1;
        int cj1 = Math.floorDiv(j0 + width - 1, (int) cellBlocks) + 1;
        int gh = ci1 - ci0 + 1, gw = cj1 - cj0 + 1;

        float[] grid = new float[gh * gw];
        for (int r = 0; r < gh; r++) {
            for (int c = 0; c < gw; c++) {
                grid[r * gw + c] = distanceCells(ci0 + r, cj0 + c, pipeline);
            }
        }

        float[] out = new float[height * width];
        for (int r = 0; r < height; r++) {
            // Cell centres sit at cell + 0.5, so a block's position between centres is
            // its cell coordinate less that half.
            float gy = (i0 + r) / cellBlocks - ci0 - 0.5f;
            int y0 = Math.max(0, Math.min(gh - 1, (int) Math.floor(gy)));
            int y1 = Math.min(gh - 1, y0 + 1);
            float wy = Math.max(0f, Math.min(1f, gy - y0));
            for (int c = 0; c < width; c++) {
                float gx = (j0 + c) / cellBlocks - cj0 - 0.5f;
                int x0 = Math.max(0, Math.min(gw - 1, (int) Math.floor(gx)));
                int x1 = Math.min(gw - 1, x0 + 1);
                float wx = Math.max(0f, Math.min(1f, gx - x0));

                float d = (1 - wy) * (1 - wx) * grid[y0 * gw + x0]
                        + (1 - wy) * wx * grid[y0 * gw + x1]
                        + wy * (1 - wx) * grid[y1 * gw + x0]
                        + wy * wx * grid[y1 * gw + x1];
                out[r * width + c] = d * cellBlocks;
            }
        }
        return out;
    }

    /** Distance from one coarse cell to the nearest ocean cell, in cells. */
    static float distanceCells(int ci, int cj, WorldPipeline pipeline) {
        int ti = Math.floorDiv(ci, TILE), tj = Math.floorDiv(cj, TILE);
        float[] square = square(ti, tj, pipeline);
        return square[(ci - ti * TILE) * TILE + (cj - tj * TILE)];
    }

    private static float[] square(int ti, int tj, WorldPipeline pipeline) {
        long key = ((long) ti << 32) | (tj & 0xFFFFFFFFL);
        float[] cached = CACHE.get(key);
        if (cached != null) return cached;

        int side = TILE + 2 * PAD;
        int ci0 = ti * TILE - PAD, cj0 = tj * TILE - PAD;
        FloatTensor slice = pipeline.getCoarseSlice(ci0, cj0, ci0 + side, cj0 + side);

        int n = side * side;
        float[] dist = new float[n];
        for (int i = 0; i < n; i++) {
            float w = slice.data[6 * n + i];
            boolean land = w > 1e-6f && slice.data[i] / w > 0f;
            dist[i] = land ? Float.MAX_VALUE : 0f;
        }
        BiomeClassifier.chamferDistance(dist, side, side);

        float[] core = new float[TILE * TILE];
        for (int r = 0; r < TILE; r++) {
            for (int c = 0; c < TILE; c++) {
                core[r * TILE + c] = Math.min(PAD, dist[(r + PAD) * side + (c + PAD)]);
            }
        }
        CACHE.putIfAbsent(key, core);
        return CACHE.get(key);
    }
}
