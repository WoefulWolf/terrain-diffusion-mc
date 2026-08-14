package com.github.xandergos.terraindiffusionmc.pipeline.river;

import java.util.Arrays;

/**
 * Routes a channel across one heightmap tile between fixed entry and exit points.
 *
 * <p>The endpoints are given; everything between them is chosen by penalising ascent, so
 * the channel settles into whatever valley the terrain actually has. Reads nothing outside
 * the tile it is handed.
 *
 * <p>Pure functions over float arrays; no Minecraft or tensor types.
 */
public final class ChannelRefiner {

    private static final int[] DR = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DC = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final float SQRT2 = (float) Math.sqrt(2.0);
    private static final float[] DIST = {1, SQRT2, 1, SQRT2, 1, SQRT2, 1, SQRT2};

    /**
     * Measured against real terrain at 30 m cells: below 1 the path ignores the valley and
     * runs 25 m above its floor, by 5 it tracks the real channel within three cells, and
     * higher gains little. Retune if cell size changes, since it trades against ascent.
     */
    public static final float DEFAULT_CLIMB_PENALTY = 12f;

    private ChannelRefiner() {
    }

    /**
     * Least-cost path from {@code start} to {@code goal}, favouring routes that avoid
     * climbing.
     *
     * @param elev         elevation in metres, row-major, length {@code height * width}
     * @param climbPenalty cost per metre of ascent, in units of one cell of travel. Higher
     *                     keeps the channel in valleys at the cost of a longer path; at
     *                     zero this degenerates to a straight line.
     * @return path as cell indices from start to goal inclusive, or null if unreachable
     */
    public static int[] trace(float[] elev, int height, int width,
                              int start, int goal, float climbPenalty) {
        int n = height * width;
        if (start < 0 || goal < 0 || start >= n || goal >= n) return null;

        float[] best = new float[n];
        int[] cameFrom = new int[n];
        boolean[] settled = new boolean[n];
        Arrays.fill(best, Float.MAX_VALUE);
        Arrays.fill(cameFrom, -1);

        FloatHeap open = new FloatHeap(Math.max(64, n / 8));
        best[start] = 0f;
        open.push(0f, start);

        while (!open.isEmpty()) {
            int cur = open.popIndex();
            if (settled[cur]) continue;
            settled[cur] = true;
            if (cur == goal) break;

            int r = cur / width;
            int c = cur - r * width;
            float here = elev[cur];

            for (int d = 0; d < 8; d++) {
                int nr = r + DR[d];
                int nc = c + DC[d];
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                int next = nr * width + nc;
                if (settled[next]) continue;

                // Descending and level travel cost only distance; climbing is what a
                // channel goes around, so that is where the cost lives.
                float climb = Math.max(0f, elev[next] - here);
                float step = DIST[d] + climbPenalty * climb;
                float candidate = best[cur] + step;
                if (candidate < best[next]) {
                    best[next] = candidate;
                    cameFrom[next] = cur;
                    open.push(candidate, next);
                }
            }
        }

        if (!settled[goal] && cameFrom[goal] < 0) return null;

        int length = 0;
        for (int at = goal; at >= 0; at = cameFrom[at]) {
            length++;
            if (at == start) break;
        }
        int[] path = new int[length];
        int at = goal;
        for (int i = length - 1; i >= 0; i--) {
            path[i] = at;
            at = cameFrom[at];
        }
        return path;
    }

    /** Binary min-heap over (cost, index), kept primitive to stay off the boxing path. */
    private static final class FloatHeap {
        private long[] heap;
        private int size;

        FloatHeap(int capacity) {
            this.heap = new long[Math.max(1, capacity)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(float cost, int index) {
            if (size == heap.length) heap = Arrays.copyOf(heap, size * 2);
            int i = size++;
            heap[i] = pack(cost, index);
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (heap[parent] <= heap[i]) break;
                swap(parent, i);
                i = parent;
            }
        }

        int popIndex() {
            long top = heap[0];
            heap[0] = heap[--size];
            int i = 0;
            while (true) {
                int left = 2 * i + 1;
                if (left >= size) break;
                int right = left + 1;
                int smallest = (right < size && heap[right] < heap[left]) ? right : left;
                if (heap[i] <= heap[smallest]) break;
                swap(i, smallest);
                i = smallest;
            }
            return (int) (top & 0xFFFFFFFFL);
        }

        private void swap(int a, int b) {
            long tmp = heap[a];
            heap[a] = heap[b];
            heap[b] = tmp;
        }

        /** Costs are non-negative here, so the raw float bits already sort correctly. */
        private static long pack(float cost, int index) {
            return ((long) Float.floatToIntBits(cost) << 32) | (index & 0xFFFFFFFFL);
        }
    }
}
