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
     * Per-step tilt applied across a filled flat. Small enough not to disturb real relief,
     * large enough that D8 has a direction to follow.
     */
    private static final float FLAT_EPSILON = 1e-4f;

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
        /**
         * Cells whose upstream network touches the window border. Their discharge is only
         * a lower bound: the window cannot see inflow from outside, so whatever drains in
         * across the border is missing from the count.
         */
        public final boolean[] edgeFed;
        /** Index of the cell this one ultimately drains through, or -1 for ocean. */
        public final int[] basin;
        /** Discharge at this cell's basin outlet, i.e. everything that basin carries. */
        public final float[] basinOutflow;

        Drainage(int height, int width, float[] filled, int[] downstream, float[] discharge,
                 boolean[] ocean, boolean[] lake, boolean[] edgeFed,
                 int[] basin, float[] basinOutflow) {
            this.height = height;
            this.width = width;
            this.filled = filled;
            this.downstream = downstream;
            this.discharge = discharge;
            this.ocean = ocean;
            this.lake = lake;
            this.edgeFed = edgeFed;
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
        return analyse(elev, precip, height, width, minLakeCells, LAKE_MIN_DEPTH_M);
    }

    /**
     * @param minLakeDepth metres the fill must have raised a cell for it to count as lake
     *                     bed. Callers that put water in lakes pass the depth below which a
     *                     lake could not visibly hold any, so shallow basins stay channels.
     */
    public static Drainage analyse(float[] elev, float[] precip, int height, int width,
                                   int minLakeCells, float minLakeDepth) {
        int n = height * width;
        boolean[] ocean = new boolean[n];
        for (int i = 0; i < n; i++) {
            ocean[i] = Float.isNaN(elev[i]) || elev[i] <= 0f;
        }

        float[] filled = fillDepressions(elev, ocean, height, width);

        boolean[] lake = new boolean[n];
        for (int i = 0; i < n; i++) {
            lake[i] = !ocean[i] && filled[i] - elev[i] >= minLakeDepth;
        }
        if (minLakeCells > 1) dropSmallPonds(lake, height, width, minLakeCells);

        resolveFlats(filled, ocean, height, width);

        int[] downstream = routeD8(filled, ocean, height, width);
        downstream = repairInteriorSinks(filled, ocean, downstream, height, width);
        boolean[] edgeFed = new boolean[n];
        float[] discharge = accumulate(filled, precip, downstream, ocean, height, width, edgeFed);

        int[] basin = labelBasins(downstream, ocean, n);
        float[] basinOutflow = new float[n];
        for (int i = 0; i < n; i++) {
            if (basin[i] >= 0) basinOutflow[i] = discharge[basin[i]];
        }

        return new Drainage(height, width, filled, downstream, discharge, ocean, lake,
                edgeFed, basin, basinOutflow);
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
                // Raise anything at or below the spill level to it exactly, leaving a true
                // flat. Tilting here instead would slope every flat away from the flood
                // seed, and D8 would then send a whole flat the same way, as parallel
                // lines. resolveFlats gives them a gradient that converges instead.
                filled[ni] = Math.max(ne, z);
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

    /**
     * Tilts filled flats so flow converges rather than running in parallel, after
     * Garbrecht and Martz.
     *
     * <p>Two distances are measured across each flat: how far a cell is from an outlet,
     * and how far it is from the higher ground feeding the flat. Combining them drains a
     * flat toward its outlet while still pushing away from the slopes above it, which is
     * what makes channels join up instead of marching side by side.
     */
    private static void resolveFlats(float[] filled, boolean[] ocean, int height, int width) {
        int n = height * width;
        boolean[] flat = new boolean[n];
        int flatCount = 0;

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int i = r * width + c;
                if (ocean[i]) continue;
                boolean canDrain = false;
                for (int d = 0; d < 8 && !canDrain; d++) {
                    int nr = r + DR[d], nc = c + DC[d];
                    if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                    int ni = nr * width + nc;
                    if (ocean[ni] || filled[ni] < filled[i]) canDrain = true;
                }
                if (!canDrain) {
                    flat[i] = true;
                    flatCount++;
                }
            }
        }
        if (flatCount == 0) return;

        int[] toOutlet = bfsOverFlats(filled, ocean, flat, height, width, true);
        int[] fromHigh = bfsOverFlats(filled, ocean, flat, height, width, false);

        int deepest = 0;
        for (int i = 0; i < n; i++) {
            if (flat[i] && fromHigh[i] != Integer.MAX_VALUE) deepest = Math.max(deepest, fromHigh[i]);
        }

        for (int i = 0; i < n; i++) {
            if (!flat[i]) continue;
            int outlet = toOutlet[i] == Integer.MAX_VALUE ? 0 : toOutlet[i];
            int high = fromHigh[i] == Integer.MAX_VALUE ? deepest : fromHigh[i];
            filled[i] += (2 * outlet + (deepest - high)) * FLAT_EPSILON;
        }
    }

    /**
     * Breadth-first distance across flat cells, seeded either from cells that touch a
     * drainable neighbour or from cells that touch higher ground.
     */
    private static int[] bfsOverFlats(float[] filled, boolean[] ocean, boolean[] flat,
                                      int height, int width, boolean fromOutlets) {
        int n = height * width;
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        int[] queue = new int[n];
        int head = 0, tail = 0;

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int i = r * width + c;
                if (!flat[i]) continue;
                for (int d = 0; d < 8; d++) {
                    int nr = r + DR[d], nc = c + DC[d];
                    if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                    int ni = nr * width + nc;
                    boolean seed = fromOutlets
                            ? (!flat[ni] && (ocean[ni] || filled[ni] <= filled[i]))
                            : (!flat[ni] && filled[ni] > filled[i]);
                    if (seed) {
                        dist[i] = 0;
                        queue[tail++] = i;
                        break;
                    }
                }
            }
        }

        while (head < tail) {
            int cur = queue[head++];
            int r = cur / width, c = cur - r * width;
            for (int d = 0; d < 8; d++) {
                int nr = r + DR[d], nc = c + DC[d];
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                int ni = nr * width + nc;
                if (!flat[ni] || dist[ni] != Integer.MAX_VALUE) continue;
                dist[ni] = dist[cur] + 1;
                queue[tail++] = ni;
            }
        }
        return dist;
    }

    /**
     * Connects interior sinks to real drainage by breaching instead of raising.
     *
     * <p>The flat tilt can leave a cell of a filled plateau with no lower neighbour.
     * Raising that cell just hands the minimum to whichever neighbour drained through
     * it: the sink migrates around the flat one cell per pass instead of disappearing,
     * and a river walking in still ends mid-plain with its discharge gone. So take the
     * whole connected patch of stuck cells at once, find its pour point — the lowest
     * surrounding cell whose flow provably leaves without returning, or the sea, or
     * the window border as a legitimate exit — and point every stuck cell along a tree
     * towards it. The surface itself is untouched: the river crosses the flat at spill
     * height, which is exactly what a pond looks like.
     */
    private static int[] repairInteriorSinks(float[] filled, boolean[] ocean,
                                             int[] downstream, int height, int width) {
        int[][] scratch = new int[2][];
        int[] stamp = {0};

        // A patch can only verify an exit once the patch that exit drains into is wired
        // itself, so sweep until nothing changes. Mutually dependent patches starve the
        // strict rule, so after it stabilises one relaxed sweep may drain into a still-
        // stuck patch (never its own — that would be a cycle), then strict finishes up.
        for (int pass = 0; pass < 6; pass++) {
            if (!sweepSinks(filled, ocean, downstream, height, width, scratch, stamp, false)) break;
        }
        sweepSinks(filled, ocean, downstream, height, width, scratch, stamp, true);
        for (int pass = 0; pass < 4; pass++) {
            if (!sweepSinks(filled, ocean, downstream, height, width, scratch, stamp, false)) break;
        }
        return downstream;
    }

    private static boolean sweepSinks(float[] filled, boolean[] ocean, int[] downstream,
                                      int height, int width, int[][] scratch, int[] stamp,
                                      boolean allowStuckEnd) {
        boolean wired = false;
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                int start = r * width + c;
                if (ocean[start] || downstream[start] >= 0) continue;
                if (scratch[0] == null) {
                    scratch[0] = new int[height * width];
                    scratch[1] = new int[height * width];
                }
                stamp[0]++;
                wired |= breachPatch(filled, ocean, downstream, height, width,
                        start, scratch[0], scratch[1], stamp[0], allowStuckEnd);
            }
        }
        return wired;
    }

    /**
     * The tilt's minima are epsilon-scale, so the sink's whole near-level plateau — the
     * pond that funnels into it — is flooded and rewired at once. Anything meaningfully
     * higher is real terrain and keeps its drainage.
     */
    private static final float BREACH_TOLERANCE = 0.25f;
    private static final int BREACH_MAX_CELLS = 1 << 17;

    /** Floods one sink's plateau, finds a proven pour point, and wires a tree to it. */
    private static boolean breachPatch(float[] filled, boolean[] ocean, int[] downstream,
                                       int height, int width, int start,
                                       int[] seen, int[] queue, int stamp,
                                       boolean allowStuckEnd) {
        // Flood the pond: every interior cell effectively level with the sink, draining
        // or not. The level cells that do drain, drain into the sink — that is how it
        // swallowed the river — so they are rewired with everything else. Anything above
        // the band is real terrain, anything below it is escape ground; both are
        // candidate pour points, not members, which keeps the flood the size of the pond.
        float bandLo = filled[start] - 4f * FLAT_EPSILON;
        float bandHi = filled[start] + BREACH_TOLERANCE;
        int head = 0, tail = 0;
        long[] exits = new long[64];
        int exitCount = 0;
        queue[tail++] = start;
        seen[start] = stamp;
        while (head < tail && tail < BREACH_MAX_CELLS) {
            int cur = queue[head++];
            int cr = cur / width, cc = cur - cr * width;
            for (int d = 0; d < 8; d++) {
                int nr = cr + DR[d], nc = cc + DC[d];
                int ni = nr * width + nc;
                if (seen[ni] == stamp) continue;
                boolean border = nr == 0 || nc == 0 || nr == height - 1 || nc == width - 1;
                if (!ocean[ni] && !border && filled[ni] >= bandLo && filled[ni] <= bandHi) {
                    seen[ni] = stamp;
                    queue[tail++] = ni;
                } else {
                    if (exitCount == exits.length) exits = Arrays.copyOf(exits, exitCount * 2);
                    exits[exitCount++] = packKey(filled[ni], ni);
                }
            }
        }
        if (tail >= BREACH_MAX_CELLS) return false;

        // Lowest exit first; sea sits below any land in the key order. An exit whose own
        // flow would come back into the plateau is a cycle waiting to happen; skip it.
        Arrays.sort(exits, 0, exitCount);
        int gate = -1;
        for (int e = 0; e < exitCount; e++) {
            int candidate = (int) (exits[e] & 0xFFFFFFFFL);
            if (chainEscapes(candidate, downstream, ocean, seen, stamp, height, width, allowStuckEnd)) {
                gate = candidate;
                break;
            }
        }
        if (gate < 0) return false; // a genuine terminal pit; the river may end here

        // Wire the whole plateau as a tree draining to the gate. Every plateau pointer
        // is overwritten, so nothing funnels into the old pit any more. The queue is
        // safely reused: membership lives in seen, not in the list.
        int wiredStamp = -stamp;
        int gr = gate / width, gc = gate - gr * width;
        int[] wire = queue;
        int wireTail = 0;
        for (int d = 0; d < 8; d++) {
            int nr = gr + DR[d], nc = gc + DC[d];
            if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
            int ni = nr * width + nc;
            if (seen[ni] == stamp) {
                downstream[ni] = gate;
                seen[ni] = wiredStamp;
                wire[wireTail++] = ni;
            }
        }
        int wireHead = 0;
        while (wireHead < wireTail) {
            int cur = wire[wireHead++];
            int cr = cur / width, cc = cur - cr * width;
            for (int d = 0; d < 8; d++) {
                int nr = cr + DR[d], nc = cc + DC[d];
                int ni = nr * width + nc;
                if (seen[ni] == stamp) {
                    downstream[ni] = cur;
                    seen[ni] = wiredStamp;
                    wire[wireTail++] = ni;
                }
            }
        }
        return true;
    }

    /**
     * True when a candidate exit's flow reaches the sea or the window border without
     * re-entering the stuck patch. An exit that drains back in would close a cycle the
     * moment the patch is wired to it. With {@code allowStuckEnd} a chain may end at a
     * different stuck patch — that is a dead end, not a cycle, and strictly better than
     * leaving this patch stuck too.
     */
    private static boolean chainEscapes(int exit, int[] downstream, boolean[] ocean,
                                        int[] seen, int stamp, int height, int width,
                                        boolean allowStuckEnd) {
        int cur = exit;
        for (int step = 0, cap = height * width; step < cap; step++) {
            if (seen[cur] == stamp) return false;
            if (ocean[cur]) return true;
            int r = cur / width, c = cur - r * width;
            if (r == 0 || c == 0 || r == height - 1 || c == width - 1) return true;
            int to = downstream[cur];
            if (to < 0) return allowStuckEnd;
            cur = to;
        }
        return false;
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
     * Accumulates precipitation downstream in topological order over the pointer graph
     * itself, so every contributor is added before its receiver is read. A height sort
     * would do the same on strictly descending pointers, but breached flats drain along
     * level ground where ties quietly drop contributions.
     *
     * <p>Seeding with rainfall rather than a unit per cell makes this a discharge proxy,
     * so an arid catchment accumulates little however large it is.
     *
     * <p>The same pass marks edge-fed cells: the window border seeds the flag, and it
     * rides the flow downstream, so anything downstream of the border knows its own
     * discharge is undercounted.
     */
    private static float[] accumulate(float[] filled, float[] precip, int[] downstream,
                                      boolean[] ocean, int height, int width,
                                      boolean[] edgeFed) {
        int n = height * width;
        float[] discharge = new float[n];
        int[] indegree = new int[n];

        for (int i = 0; i < n; i++) {
            if (ocean[i]) continue;
            int r = i / width, c = i - r * width;
            edgeFed[i] = r == 0 || c == 0 || r == height - 1 || c == width - 1;
            discharge[i] = precip == null ? 1f : Math.max(0f, precip[i]);
            int to = downstream[i];
            if (to >= 0 && !ocean[to]) indegree[to]++;
        }

        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++)
            if (!ocean[i] && indegree[i] == 0) queue[tail++] = i;
        while (head < tail) {
            int i = queue[head++];
            int to = downstream[i];
            if (to >= 0 && !ocean[to]) {
                discharge[to] += discharge[i];
                if (edgeFed[i]) edgeFed[to] = true;
                if (--indegree[to] == 0) queue[tail++] = to;
            }
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
