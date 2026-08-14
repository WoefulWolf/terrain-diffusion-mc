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
