package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.river.RiverRegions;
import com.github.xandergos.terraindiffusionmc.world.RiverMode;
import com.github.xandergos.terraindiffusionmc.world.RiverParameters;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Speculative generation of the terrain around wherever the game last asked for a tile.
 *
 * <p>A cold river region costs minutes of inference while a cached one serves tiles in
 * milliseconds, and during ordinary play the GPU sits idle. So every time the frontier
 * moves, the eight regions touching the current one — and a ring of tiles around the
 * current tile — are queued for computation at a lower priority than real requests,
 * which jump ahead of anything still waiting.
 *
 * <p>The queue is ordered stage by stage across the whole plan: all coarse windows,
 * then all latent windows, then all decoder windows, then drainage analyses, then
 * tiles. Each stage keeps one model resident for its whole pass, which matters when
 * models are offloaded: a session swap costs seconds, so interleaving stages per
 * window would spend more time reloading models than running them.
 *
 * <p>Units carry the cache generation and their target, and exit in microseconds when
 * the seed changed, the player moved on, or the work is already cached — a stale plan
 * drains without cost rather than needing cancellation.
 */
public final class TerrainPrefetcher {

    private static final Logger LOG = LoggerFactory.getLogger(TerrainPrefetcher.class);

    /** Ring of neighbouring regions to keep warm: 1 means the 8 touching squares. */
    private static final int REGION_RING = 1;
    /** Ring of tiles around the frontier tile to keep in the tile cache. */
    private static final int TILE_RING = 2;
    /** Native-pixel height of one prewarm strip: one decoder-window row per unit. */
    private static final int STRIP_NATIVE = 192;
    /** A fresh plan this soon after the last would mostly duplicate it. */
    private static final long REPLAN_MIN_NANOS = 3_000_000_000L;

    private static final AtomicLong FRONTIER = new AtomicLong(Long.MIN_VALUE);
    private static volatile long lastPlanned = Long.MIN_VALUE;
    private static volatile long lastPlanNanos;

    private TerrainPrefetcher() {
    }

    /**
     * Called on every tile fetch, from any thread; the common case is one volatile read.
     * Replans when the frontier tile actually changes, with a short holdoff so workers
     * generating a burst of adjacent tiles do not thrash the plan.
     */
    public static void noteAccess(int i1, int j1) {
        if (!TerrainDiffusionConfig.prefetchEnabled()) return;
        int tileSize = TerrainDiffusionConfig.tileSize();
        long tile = pack(Math.floorDiv(i1, tileSize), Math.floorDiv(j1, tileSize));
        if (FRONTIER.get() == tile) return;
        FRONTIER.set(tile);

        long now = System.nanoTime();
        if (tile == lastPlanned || now - lastPlanNanos < REPLAN_MIN_NANOS) return;
        synchronized (TerrainPrefetcher.class) {
            if (tile == lastPlanned || now - lastPlanNanos < REPLAN_MIN_NANOS) return;
            lastPlanned = tile;
            lastPlanNanos = now;
        }
        plan(unpackHi(tile), unpackLo(tile), tileSize);
    }

    private static void plan(int frontierTi, int frontierTj, int tileSize) {
        long gen = LocalTerrainProvider.cacheGeneration();
        int scale = WorldScaleManager.getCurrentScale();
        RiverMode mode = WorldScaleManager.getRiverMode();
        RiverParameters params = WorldScaleManager.getRiverParameters();
        RiverRegions.Size size = mode == RiverMode.FAST
                ? RiverRegions.Size.SMALL : RiverRegions.Size.LARGE;
        int regionBlocks = size.side * scale;

        int frontierBlockI = frontierTi * tileSize + tileSize / 2;
        int frontierBlockJ = frontierTj * tileSize + tileSize / 2;
        int cri = Math.floorDiv(frontierBlockI, regionBlocks);
        int crj = Math.floorDiv(frontierBlockJ, regionBlocks);

        // Regions ordered centre first, then sides, then corners: the nearest missing
        // terrain is the likeliest to be walked into next.
        int[][] ring = ringOrder(REGION_RING);
        int[][] regions = new int[ring.length][2];
        int count = 0;
        for (int[] o : ring) {
            int ri = cri + o[0], rj = crj + o[1];
            if (mode == RiverMode.OFF || !RiverRegions.isCached(ri, rj, size)) {
                regions[count][0] = ri;
                regions[count][1] = rj;
                count++;
            }
        }

        // Stage-major across all planned regions, so each model runs one long pass.
        for (WorldPipeline.Stage stage : WorldPipeline.Stage.values()) {
            for (int k = 0; k < count; k++) {
                int ri = regions[k][0], rj = regions[k][1];
                int i0 = ri * size.side - size.halo, j0 = rj * size.side - size.halo;
                int i1 = ri * size.side + size.side + size.halo;
                int j1 = rj * size.side + size.side + size.halo;
                if (stage == WorldPipeline.Stage.COARSE) {
                    enqueue(gen, cri, crj, () ->
                            LocalTerrainProvider.prewarmDirect(stage, i0, j0, i1, j1));
                } else {
                    for (int s = i0; s < i1; s += STRIP_NATIVE) {
                        int si = s, sEnd = Math.min(i1, s + STRIP_NATIVE);
                        enqueue(gen, cri, crj, () ->
                                LocalTerrainProvider.prewarmDirect(stage, si, j0, sEnd, j1));
                    }
                }
            }
        }

        if (mode != RiverMode.OFF) {
            for (int k = 0; k < count; k++) {
                int ri = regions[k][0], rj = regions[k][1];
                enqueue(gen, cri, crj, () -> {
                    if (RiverRegions.isCached(ri, rj, size)) return;
                    RiverRegions.prebuild(ri, rj, scale, size, params,
                            LocalTerrainProvider::fetchForRegionDirect);
                });
            }
        }

        // Tiles last, once their windows and regions are warm, so each is CPU-cheap.
        for (int[] o : ringOrder(TILE_RING)) {
            int ti = frontierTi + o[0], tj = frontierTj + o[1];
            int bi = ti * tileSize, bj = tj * tileSize;
            enqueue(gen, cri, crj, () ->
                    LocalTerrainProvider.computeTileInlineIfAbsent(bi, bj, bi + tileSize, bj + tileSize));
        }
        LOG.debug("Prefetch planned around tile ({}, {}): {} uncached regions", frontierTj, frontierTi, count);
    }

    /** Wraps a unit with the freshness checks and hands it to the low-priority queue. */
    private static void enqueue(long gen, int planRi, int planRj, Runnable body) {
        LocalTerrainProvider.enqueuePrefetch(() -> {
            if (gen != LocalTerrainProvider.cacheGeneration()) return;
            // The player moved on: this plan's centre is no longer near the frontier.
            long tile = FRONTIER.get();
            int tileSize = TerrainDiffusionConfig.tileSize();
            int scale = WorldScaleManager.getCurrentScale();
            RiverRegions.Size size = WorldScaleManager.getRiverMode() == RiverMode.FAST
                    ? RiverRegions.Size.SMALL : RiverRegions.Size.LARGE;
            int regionBlocks = size.side * scale;
            int cri = Math.floorDiv(unpackHi(tile) * tileSize + tileSize / 2, regionBlocks);
            int crj = Math.floorDiv(unpackLo(tile) * tileSize + tileSize / 2, regionBlocks);
            if (Math.max(Math.abs(cri - planRi), Math.abs(crj - planRj)) > REGION_RING) return;
            try {
                body.run();
            } catch (Throwable t) {
                LOG.debug("Prefetch unit failed: {}", t.toString());
            }
        });
    }

    /** Offsets within a square ring, sorted by distance from the centre, centre included. */
    private static int[][] ringOrder(int ring) {
        int side = 2 * ring + 1;
        int[][] out = new int[side * side][2];
        int k = 0;
        for (int di = -ring; di <= ring; di++) {
            for (int dj = -ring; dj <= ring; dj++) {
                out[k][0] = di;
                out[k][1] = dj;
                k++;
            }
        }
        java.util.Arrays.sort(out, (a, b) -> Integer.compare(
                a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        return out;
    }

    private static long pack(int hi, int lo) {
        return ((long) hi << 32) | (lo & 0xFFFFFFFFL);
    }

    private static int unpackHi(long v) {
        return (int) (v >> 32);
    }

    private static int unpackLo(long v) {
        return (int) v;
    }
}
