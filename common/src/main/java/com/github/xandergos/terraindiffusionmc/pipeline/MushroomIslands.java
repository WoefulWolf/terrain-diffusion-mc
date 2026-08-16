package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which small remote islands are mushroom country.
 *
 * <p>The tile pipeline cannot see an island whole: a landmass is judged at coarse
 * resolution, where one cell is hundreds of blocks and the ocean mask for a whole
 * archipelago costs a single cached slice. A candidate must be a connected landmass of
 * at most {@link #MAX_ISLAND_CELLS} cells with nothing but open sea for
 * {@link #REMOTE_MIN_CELLS} cells around it — remoteness is the point, a mushroom rock
 * off a mainland beach would read as an error — and one in {@link #MUSHROOM_ONE_IN} of
 * the qualifiers is chosen by a seed-stable hash of the island's own cells.
 *
 * <p>Most queries are mainland or open sea and settle in a small window that the tile's
 * own climate fetch has already computed: a fill that outgrows the cap is mainland, and
 * only a fill that stays enclosed — a real candidate — pays for the wide window that
 * proves remoteness. Verdicts are cached per cell and are pure functions of the seed,
 * so every tile of an island agrees, whichever tile asked first.
 */
public final class MushroomIslands {

    /** Native pixels per coarse cell, matching WorldPipeline's climate conversion. */
    public static final int CELL_NATIVE = 32 * WorldPipeline.LATENT_COMPRESSION;

    /** A connected landmass larger than this is a mainland, not an island. */
    private static final int MAX_ISLAND_CELLS = 24;
    /**
     * Coarse cells of open sea required on every side before an island is remote.
     * Surveyed archipelago sea puts most small islands two to five cells from their
     * neighbours, so this keeps genuine outliers only without making them mythical.
     */
    private static final int REMOTE_MIN_CELLS = 4;
    /** One in this many qualifying islands actually gets the mycelium. */
    private static final int MUSHROOM_ONE_IN = 8;
    /** Cheap first look, sized to the coarse span climate fetches already warm. */
    private static final int SMALL_RADIUS = 10;
    /**
     * Conclusive window: a capped fill reaches at most {@link #MAX_ISLAND_CELLS} cells
     * from its centre, plus the remoteness ring, so nothing can escape this radius.
     */
    private static final int WIDE_RADIUS = MAX_ISLAND_CELLS + REMOTE_MIN_CELLS + 2;

    private static final long NOT_ISLAND = Long.MIN_VALUE;

    /** Coarse cell → canonical cell of its island, or {@link #NOT_ISLAND}. */
    private static final Map<Long, Long> MEMBER = new ConcurrentHashMap<>();
    /** Canonical island cell → chosen for mushroom. */
    private static final Map<Long, Boolean> VERDICT = new ConcurrentHashMap<>();

    private MushroomIslands() {
    }

    /** Drops every verdict. Call when the seed changes. */
    public static void clear() {
        MEMBER.clear();
        VERDICT.clear();
    }

    /**
     * Whether a coarse cell belongs to a chosen mushroom island. Runs on the inference
     * thread only: a cache miss reads coarse tensor slices directly.
     */
    static boolean isMushroom(int ci, int cj, WorldPipeline pipeline, long seed) {
        long key = pack(ci, cj);
        Long member = MEMBER.get(key);
        if (member == null) {
            Long resolved = resolve(ci, cj, pipeline, seed, SMALL_RADIUS);
            if (resolved == null) resolved = resolve(ci, cj, pipeline, seed, WIDE_RADIUS);
            member = resolved;
        }
        return member != NOT_ISLAND && Boolean.TRUE.equals(VERDICT.get(member));
    }

    /**
     * Classifies one cell within a window of the given radius. Returns the member value,
     * or null when the window was too small to be sure either way.
     */
    private static Long resolve(int ci, int cj, WorldPipeline pipeline, long seed, int radius) {
        int side = 2 * radius + 1;
        FloatTensor slice = pipeline.getCoarseSlice(ci - radius, cj - radius,
                ci + radius + 1, cj + radius + 1);

        long key = pack(ci, cj);
        if (!land(slice, side, radius * side + radius)) {
            MEMBER.put(key, NOT_ISLAND);
            return NOT_ISLAND;
        }

        // Flood the connected landmass from the centre, capped one past the limit.
        boolean[] seen = new boolean[side * side];
        int[] cells = new int[MAX_ISLAND_CELLS + 1];
        int[] stack = new int[MAX_ISLAND_CELLS + 9];
        int count = 0, top = 0;
        boolean touchedEdge = false, overCap = false;
        int start = radius * side + radius;
        seen[start] = true;
        stack[top++] = start;
        while (top > 0) {
            int cur = stack[--top];
            if (count > MAX_ISLAND_CELLS) {
                overCap = true;
                break;
            }
            cells[count++] = cur;
            int r = cur / side, c = cur - r * side;
            if (r == 0 || c == 0 || r == side - 1 || c == side - 1) touchedEdge = true;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int nr = r + dr, nc = c + dc;
                    if (nr < 0 || nr >= side || nc < 0 || nc >= side) continue;
                    int ni = nr * side + nc;
                    if (seen[ni] || !land(slice, side, ni)) continue;
                    seen[ni] = true;
                    if (top == stack.length) stack = java.util.Arrays.copyOf(stack, top * 2);
                    stack[top++] = ni;
                }
            }
        }

        if (overCap) {
            // Mainland. Everything visited shares the component, so it shares the verdict.
            for (int k = 0; k < count; k++) {
                int r = cells[k] / side, c = cells[k] - (cells[k] / side) * side;
                MEMBER.put(pack(ci - radius + r, cj - radius + c), NOT_ISLAND);
            }
            return NOT_ISLAND;
        }
        if (touchedEdge) {
            // A small landmass running past the window: only a wider look can tell a
            // spit of mainland from an island.
            return null;
        }

        // Enclosed island. Remote means nothing but sea within the ring; the ring must
        // itself fit the window, or the verdict would depend on who asked.
        int minR = side, maxR = -1, minC = side, maxC = -1;
        for (int k = 0; k < count; k++) {
            int r = cells[k] / side, c = cells[k] - (cells[k] / side) * side;
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
        }
        if (minR - REMOTE_MIN_CELLS < 0 || maxR + REMOTE_MIN_CELLS >= side
                || minC - REMOTE_MIN_CELLS < 0 || maxC + REMOTE_MIN_CELLS >= side) {
            return null;
        }
        boolean remote = true;
        for (int r = minR - REMOTE_MIN_CELLS; r <= maxR + REMOTE_MIN_CELLS && remote; r++) {
            for (int c = minC - REMOTE_MIN_CELLS; c <= maxC + REMOTE_MIN_CELLS; c++) {
                int i = r * side + c;
                if (!seen[i] && land(slice, side, i)) {
                    remote = false;
                    break;
                }
            }
        }

        long canonical = Long.MAX_VALUE;
        long[] worldCells = new long[count];
        for (int k = 0; k < count; k++) {
            int r = cells[k] / side, c = cells[k] - (cells[k] / side) * side;
            worldCells[k] = pack(ci - radius + r, cj - radius + c);
            canonical = Math.min(canonical, worldCells[k]);
        }
        long member = remote ? canonical : NOT_ISLAND;
        if (remote) {
            VERDICT.put(canonical, Math.floorMod(mix(canonical ^ seed), MUSHROOM_ONE_IN) == 0);
        }
        for (long cell : worldCells) MEMBER.put(cell, member);
        return member;
    }

    /** Land test on a (7, side, side) coarse slice: unnormalized elevation above sea. */
    private static boolean land(FloatTensor slice, int side, int px) {
        int n = side * side;
        float w = slice.data[6 * n + px];
        if (w <= 1e-6f) return false;
        return slice.data[px] / w > 0f;
    }

    private static long pack(int ci, int cj) {
        return ((long) ci << 32) | (cj & 0xFFFFFFFFL);
    }

    /** Seed-stable scatter so which islands qualify changes with the world. */
    private static long mix(long v) {
        v *= 0x9E3779B97F4A7C15L;
        v ^= v >>> 32;
        v *= 0xBF58476D1CE4E5B9L;
        v ^= v >>> 29;
        return v;
    }
}
