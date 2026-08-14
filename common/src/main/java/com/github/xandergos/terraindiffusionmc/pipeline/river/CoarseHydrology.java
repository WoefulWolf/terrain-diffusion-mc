package com.github.xandergos.terraindiffusionmc.pipeline.river;

import java.util.Arrays;

/**
 * Drainage analysis over a coarse elevation field.
 *
 * <p>Runs on the coarse tensor, where one pixel is 7.68 km. At that size a landmass is
 * only tens of cells across, so this yields basins, outlets, discharge and lakes, but not
 * a channel network: no threshold turns a twenty-cell basin into something that branches.
 * The network itself is traced against the real heightmap.
 *
 * <p>Pure functions over float arrays: no Minecraft or tensor types, so it can be driven
 * from a test harness without launching the game.
 */
public final class CoarseHydrology {

    /** 8-neighbour offsets, starting east and turning clockwise. */
    private static final int[] DR = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DC = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final float SQRT2 = (float) Math.sqrt(2.0);
    private static final float[] DIST = {1, SQRT2, 1, SQRT2, 1, SQRT2, 1, SQRT2};

    /**
     * Raised on each fill step so filled flats keep a downhill gradient. Without it D8 has
     * no defined direction across a filled depression and drainage stalls there.
     */
    private static final float FILL_EPSILON = 1e-3f;

    /** A cell counts as lake bed once the fill raised it this far above the real surface. */
    private static final float LAKE_MIN_DEPTH_M = 1.0f;


    private CoarseHydrology() {
    }

    /** Drainage state for one coarse window. All arrays are length {@code H * W}. */
    public static final class Drainage {
        public final int height;
        public final int width;
        /** Depression-filled elevation in metres. */
        public final float[] filled;
        /** Index of the downstream neighbour, or -1 for ocean and outlets. */
        public final int[] downstream;
        /** Precipitation-weighted upstream accumulation. */
        public final float[] discharge;
        /** Sea and below. */
        public final boolean[] ocean;
        /** Cells the fill raised appreciably: a basin that ponds water. */
        public final boolean[] lake;
        /** Index of the cell this one ultimately drains through, or -1 for ocean. */
        public final int[] basin;
        /** Discharge at this cell's basin outlet, i.e. everything that basin carries. */
        public final float[] basinOutflow;

        Drainage(int height, int width, float[] filled, int[] downstream, float[] discharge,
                 boolean[] ocean, boolean[] lake, int[] basin, float[] basinOutflow) {
            this.height = height;
            this.width = width;
            this.filled = filled;
            this.downstream = downstream;
            this.discharge = discharge;
            this.ocean = ocean;
            this.lake = lake;
            this.basin = basin;
            this.basinOutflow = basinOutflow;
        }

        public int index(int row, int col) {
            return row * width + col;
        }
    }

    /**
     * Fills depressions, routes D8 flow and accumulates precipitation downstream.
     *
     * @param elev   elevation in metres, row-major, length {@code H * W}
     * @param precip precipitation per cell in any consistent unit, same length; may be null
     *               for unweighted accumulation
     * @param height rows
     * @param width  columns
     */
    public static Drainage analyse(float[] elev, float[] precip, int height, int width) {
        return analyse(elev, precip, height, width, 1);
    }

    /**
     * @param minLakeCells ponds smaller than this are discarded. Depth alone cannot tell a
     *                     lake from heightmap noise, and "small" depends on cell size.
     */
    public static Drainage analyse(float[] elev, float[] precip, int height, int width,
                                   int minLakeCells) {
        int n = height * width;
        boolean[] ocean = new boolean[n];
        for (int i = 0; i < n; i++) {
            ocean[i] = Float.isNaN(elev[i]) || elev[i] <= 0f;
        }

        float[] filled = fillDepressions(elev, ocean, height, width);

        boolean[] lake = new boolean[n];
        for (int i = 0; i < n; i++) {
            lake[i] = !ocean[i] && filled[i] - elev[i] >= LAKE_MIN_DEPTH_M;
        }
        if (minLakeCells > 1) dropSmallPonds(lake, height, width, minLakeCells);

        int[] downstream = routeD8(filled, ocean, height, width);
        float[] discharge = accumulate(filled, precip, downstream, ocean, n);

        int[] basin = labelBasins(downstream, ocean, n);
        float[] basinOutflow = new float[n];
        for (int i = 0; i < n; i++) {
            if (basin[i] >= 0) basinOutflow[i] = discharge[basin[i]];
        }

        return new Drainage(height, width, filled, downstream, discharge, ocean, lake,
                basin, basinOutflow);
    }

    /**
     * Assigns every land cell the index of the outlet it drains through, so callers can
     * judge a cell against its own landmass rather than against the whole window.
     */
    private static int[] labelBasins(int[] downstream, boolean[] ocean, int n) {
        int[] basin = new int[n];
        Arrays.fill(basin, -1);
        int[] walk = new int[64];

        for (int start = 0; start < n; start++) {
            if (ocean[start] || basin[start] >= 0) continue;

            int depth = 0;
            int cur = start;
            while (basin[cur] < 0) {
                int to = downstream[cur];
                if (to < 0 || ocean[to] || depth > n) {
                    basin[cur] = cur;
                    break;
                }
                if (depth == walk.length) walk = Arrays.copyOf(walk, depth * 2);
                walk[depth++] = cur;
                cur = to;
            }

            int outlet = basin[cur];
            while (depth > 0) basin[walk[--depth]] = outlet;
        }
        return basin;
    }

    /**
     * Priority-flood depression filling (Barnes et al.), seeded from the ocean and the
     * window edge. Every land cell ends up with a monotone path to a seed, so D8 below
     * cannot strand flow in a pit.
     */
    private static float[] fillDepressions(float[] elev, boolean[] ocean, int height, int width) {
        int n = height * width;
        float[] filled = new float[n];
        boolean[] queued = new boolean[n];
        Arrays.fill(filled, Float.MAX_VALUE);

        LongHeap open = new LongHeap(Math.max(16, 2 * (height + width)));

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int i = r * width + c;
                boolean edge = r == 0 || c == 0 || r == height - 1 || c == width - 1;
                if (!ocean[i] && !edge) continue;
                // The window edge is treated as an outlet: flow leaving the window is not
                // our concern, and the alternative is damming every river at the boundary.
                float z = ocean[i] ? Math.min(0f, safeElev(elev[i])) : safeElev(elev[i]);
                filled[i] = z;
                queued[i] = true;
                open.push(packKey(z, i));
            }
        }

        while (!open.isEmpty()) {
            int i = (int) (open.pop() & 0xFFFFFFFFL);
            int r = i / width;
            int c = i - r * width;
            float z = filled[i];

            for (int d = 0; d < 8; d++) {
                int nr = r + DR[d];
                int nc = c + DC[d];
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                int ni = nr * width + nc;
                if (queued[ni]) continue;

                float ne = safeElev(elev[ni]);
                // Raise anything at or below the spill level just above it, so the filled
                // basin still slopes towards its outlet.
                filled[ni] = ne <= z ? z + FILL_EPSILON : ne;
                queued[ni] = true;
                open.push(packKey(filled[ni], ni));
            }
        }

        for (int i = 0; i < n; i++) {
            if (filled[i] == Float.MAX_VALUE) filled[i] = safeElev(elev[i]);
        }
        return filled;
    }

    /** Clears connected groups of lake cells smaller than {@code minCells}. */
    private static void dropSmallPonds(boolean[] lake, int height, int width, int minCells) {
        int n = height * width;
        boolean[] seen = new boolean[n];
        int[] stack = new int[Math.max(64, n / 16)];
        int[] group = new int[Math.max(64, minCells * 2)];

        for (int start = 0; start < n; start++) {
            if (!lake[start] || seen[start]) continue;

            int top = 0, count = 0;
            stack[top++] = start;
            seen[start] = true;

            while (top > 0) {
                int cur = stack[--top];
                if (count == group.length) group = Arrays.copyOf(group, count * 2);
                group[count++] = cur;

                int r = cur / width;
                int c = cur - r * width;
                for (int d = 0; d < 8; d++) {
                    int nr = r + DR[d];
                    int nc = c + DC[d];
                    if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                    int ni = nr * width + nc;
                    if (!lake[ni] || seen[ni]) continue;
                    seen[ni] = true;
                    if (top == stack.length) stack = Arrays.copyOf(stack, top * 2);
                    stack[top++] = ni;
                }
            }

            if (count < minCells) {
                for (int k = 0; k < count; k++) lake[group[k]] = false;
            }
        }
    }

    /** Steepest-descent D8 over the filled surface. Ocean cells terminate flow. */
    private static int[] routeD8(float[] filled, boolean[] ocean, int height, int width) {
        int[] downstream = new int[height * width];

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int i = r * width + c;
                if (ocean[i]) {
                    downstream[i] = -1;
                    continue;
                }

                float z = filled[i];
                float bestSlope = 0f;
                int best = -1;

                for (int d = 0; d < 8; d++) {
                    int nr = r + DR[d];
                    int nc = c + DC[d];
                    if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                    int ni = nr * width + nc;

                    // Reaching the sea always wins: it ends the river at a real coastline.
                    if (ocean[ni]) {
                        best = ni;
                        bestSlope = Float.MAX_VALUE;
                        break;
                    }
                    float slope = (z - filled[ni]) / DIST[d];
                    if (slope > bestSlope) {
                        bestSlope = slope;
                        best = ni;
                    }
                }
                downstream[i] = best;
            }
        }
        return downstream;
    }

    /**
     * Accumulates precipitation downstream, processing cells from high to low so every
     * contributor is added before its receiver is read.
     *
     * <p>Seeding with rainfall rather than a unit per cell makes this a discharge proxy,
     * so an arid catchment accumulates little however large it is.
     */
    private static float[] accumulate(float[] filled, float[] precip, int[] downstream,
                                      boolean[] ocean, int n) {
        float[] discharge = new float[n];
        long[] order = new long[n];
        int count = 0;

        for (int i = 0; i < n; i++) {
            if (ocean[i]) continue;
            discharge[i] = precip == null ? 1f : Math.max(0f, precip[i]);
            order[count++] = packKey(filled[i], i);
        }

        // Primitive sort on packed keys; ascending by elevation, so walk it backwards.
        Arrays.sort(order, 0, count);
        for (int k = count - 1; k >= 0; k--) {
            int i = (int) (order[k] & 0xFFFFFFFFL);
            int to = downstream[i];
            if (to >= 0 && !ocean[to]) discharge[to] += discharge[i];
        }
        return discharge;
    }

    private static float safeElev(float v) {
        return Float.isNaN(v) ? 0f : v;
    }

    /**
     * Packs elevation above index so plain long ordering matches float ordering exactly.
     * Quantising collapses the fill epsilon across a flat, which reorders accumulation.
     */
    private static long packKey(float elev, int index) {
        int bits = Float.floatToIntBits(elev);
        int ordered = bits ^ ((bits >> 31) & 0x7FFFFFFF);
        return ((long) ordered << 32) | (index & 0xFFFFFFFFL);
    }

    /** Minimal long-keyed binary min-heap, to keep the flood off the boxing path. */
    private static final class LongHeap {
        private long[] heap;
        private int size;

        LongHeap(int capacity) {
            this.heap = new long[Math.max(1, capacity)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(long value) {
            if (size == heap.length) heap = Arrays.copyOf(heap, size * 2);
            int i = size++;
            heap[i] = value;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (heap[parent] <= heap[i]) break;
                long tmp = heap[parent];
                heap[parent] = heap[i];
                heap[i] = tmp;
                i = parent;
            }
        }

        long pop() {
            long top = heap[0];
            heap[0] = heap[--size];
            int i = 0;
            while (true) {
                int left = 2 * i + 1;
                if (left >= size) break;
                int right = left + 1;
                int smallest = (right < size && heap[right] < heap[left]) ? right : left;
                if (heap[i] <= heap[smallest]) break;
                long tmp = heap[i];
                heap[i] = heap[smallest];
                heap[smallest] = tmp;
                i = smallest;
            }
            return top;
        }
    }
}
