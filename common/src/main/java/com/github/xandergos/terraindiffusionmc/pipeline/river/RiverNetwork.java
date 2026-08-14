package com.github.xandergos.terraindiffusionmc.pipeline.river;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts the drainage tree from a {@link CoarseHydrology.Drainage} as connected reaches.
 *
 * <p>The reaches cover the same cells a discharge threshold would select, since discharge
 * only grows downstream. What this adds is the structure: explicit edges rooted at real
 * outlets, and a Strahler order per reach to size rivers by. Both are what the fine tracer
 * consumes; neither makes the coarse network any less blocky than the cell size allows.
 *
 * <p>Pure functions over the drainage arrays; no Minecraft or tensor types.
 */
public final class RiverNetwork {

    private RiverNetwork() {
    }

    /** One step of river, from the upstream cell to the cell it drains into. */
    public static final class Reach {
        /** Upstream cell index. */
        public final int from;
        /** Downstream cell index, or -1 where the reach leaves the window. */
        public final int to;
        /** Discharge carried at the upstream end. */
        public final float discharge;
        /** Strahler order: 1 is a headwater, higher is a trunk. */
        public final int order;

        Reach(int from, int to, float discharge, int order) {
            this.from = from;
            this.to = to;
            this.discharge = discharge;
            this.order = order;
        }
    }

    /**
     * Walks every qualifying basin's drainage tree from its outlet upstream.
     *
     * @param d                drainage state
     * @param minBasinOutflow  a basin below this carries no river at all, so islets stay dry
     * @param stemFraction     share of its basin's outflow a reach must carry to be kept;
     *                         higher prunes back to trunks, lower reaches further upstream
     */
    public static List<Reach> extract(CoarseHydrology.Drainage d, float minBasinOutflow,
                                      float stemFraction) {
        int n = d.discharge.length;
        boolean[] keep = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (d.ocean[i] || d.basinOutflow[i] < minBasinOutflow) continue;
            keep[i] = d.discharge[i] >= d.basinOutflow[i] * stemFraction;
        }
        return fromKept(d, keep, n);
    }

    /** 8-neighbour offsets, for walking upstream. */
    private static final int[] DR = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DC = {1, 1, 0, -1, -1, -1, 0, 1};

    /**
     * Keeps every reach carrying at least {@code minDischarge}, judged in absolute terms
     * rather than against its basin.
     *
     * <p>Preferred when the analysis window may cut a catchment in half. D8 directions are
     * local, so two windows always agree on where the channels run; only the accumulated
     * total differs. An absolute cutoff therefore disagrees only near the threshold, at
     * headwater tips, instead of shifting an entire basin's network.
     */
    public static List<Reach> extractAbove(CoarseHydrology.Drainage d, float minDischarge) {
        int n = d.discharge.length;
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            keep[i] = !d.ocean[i] && d.discharge[i] >= minDischarge;
        }
        return fromKept(d, keep, n);
    }

    /**
     * Keeps only channels that somewhere reach {@code minDischarge}, then walks each kept
     * headwater upstream along its largest inflow while the flow stays at least
     * {@code headwaterMin}.
     *
     * <p>A single threshold cannot make rivers long: high enough to be rare, it births
     * every river far downhill; low enough to start high, it webs the map with brooks.
     * Selecting rivers by the size they eventually reach and then tracing each back up its
     * main stem gives few rivers that still begin high on the hillsides, growing over
     * their whole run instead of arriving fully sized.
     *
     * <p>Edge-fed channels qualify at {@code edgeFedMin} instead: their discharge is only
     * a lower bound, since the window cannot see what drains in across its border. Judged
     * at full strength, a river whose upper catchment lies in the neighbouring window is
     * dropped by the window that owns its mouth, and ends dead just short of the sea.
     *
     * <p>{@code headwaterMin} is per cell, so where a spring may sit is the terrain's
     * choice: a low floor on high wet ground climbs sources into the mountains, a high
     * floor on flat or dry ground makes a channel earn real size before it may exist
     * there, so rivers cross such country without appearing to begin in it.
     */
    public static List<Reach> extractMainRivers(CoarseHydrology.Drainage d, float minDischarge,
                                                float edgeFedMin, float[] headwaterMin) {
        int n = d.discharge.length;
        int h = d.height, w = d.width;
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            float min = d.edgeFed[i] ? edgeFedMin : minDischarge;
            keep[i] = !d.ocean[i] && d.discharge[i] >= min;
        }

        boolean[] hasKeptUpstream = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            int to = d.downstream[i];
            if (to >= 0 && keep[to]) hasKeptUpstream[to] = true;
        }

        for (int i = 0; i < n; i++) {
            if (!keep[i] || hasKeptUpstream[i]) continue;
            int cur = i;
            while (true) {
                int r = cur / w, c = cur - r * w;
                int best = -1;
                float bestFlow = 0f;
                for (int dir = 0; dir < 8; dir++) {
                    int nr = r + DR[dir], nc = c + DC[dir];
                    if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                    int ni = nr * w + nc;
                    if (d.ocean[ni] || keep[ni] || d.downstream[ni] != cur) continue;
                    if (d.discharge[ni] >= headwaterMin[ni] && d.discharge[ni] > bestFlow) {
                        bestFlow = d.discharge[ni];
                        best = ni;
                    }
                }
                if (best < 0) break;
                keep[best] = true;
                cur = best;
            }
        }
        return fromKept(d, keep, n);
    }

    private static List<Reach> fromKept(CoarseHydrology.Drainage d, boolean[] keep, int n) {
        // Discharge only grows downstream, so the kept cells already form subtrees rooted
        // at their outlets; no connectivity repair is needed.
        int[] order = strahler(d, keep, n);

        List<Reach> reaches = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            int to = d.downstream[i];
            reaches.add(new Reach(i, to, d.discharge[i], order[i]));
        }
        return reaches;
    }

    /**
     * Strahler stream order over the kept cells: two equal-order tributaries meeting make
     * the next order up, anything less keeps the larger.
     */
    private static int[] strahler(CoarseHydrology.Drainage d, boolean[] keep, int n) {
        int[] order = new int[n];
        // Best and second-best child order seen so far, folded in as children are visited.
        int[] best = new int[n];
        int[] second = new int[n];

        long[] sorted = new long[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            sorted[count++] = pack(d.discharge[i], i);
        }
        Arrays.sort(sorted, 0, count);

        // Ascending discharge is upstream-first, so a cell's children are all resolved
        // before it is read.
        for (int k = 0; k < count; k++) {
            int i = (int) (sorted[k] & 0xFFFFFFFFL);
            int mine = best[i] == 0 ? 1 : (best[i] == second[i] ? best[i] + 1 : best[i]);
            order[i] = mine;

            int to = d.downstream[i];
            if (to < 0 || !keep[to]) continue;
            if (mine > best[to]) {
                second[to] = best[to];
                best[to] = mine;
            } else if (mine > second[to]) {
                second[to] = mine;
            }
        }
        return order;
    }

    private static long pack(float value, int index) {
        int bits = Float.floatToIntBits(value);
        int ordered = bits ^ ((bits >> 31) & 0x7FFFFFFF);
        return ((long) ordered << 32) | (index & 0xFFFFFFFFL);
    }
}
