package com.github.xandergos.terraindiffusionmc.pipeline.river;

import com.github.xandergos.terraindiffusionmc.world.RiverParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches river paths from native-resolution elevation, over a fixed grid of
 * regions aligned to the world origin.
 *
 * <p>The coarse field cannot supply the network: a landmass here is only tens of coarse
 * cells across, so no threshold makes one branch. At native resolution the same analysis
 * produces proper dendritic networks, and the D8 descent is already the channel, so the
 * paths need no further routing.
 *
 * <p>A region is analysed with a halo, but a catchment can still outrun it. The cutoff is
 * therefore absolute rather than a share of each basin: D8 directions are local, so
 * neighbouring regions always agree on where channels run, and an absolute cutoff confines
 * any disagreement to headwater tips instead of moving a whole network.
 */
public final class RiverRegions {

    private static final Logger LOG = LoggerFactory.getLogger(RiverRegions.class);

    /**
     * Region geometry, in native pixels. Total work over a given area is much the same
     * either way, since it scales with {@code ((side + 2 * halo) / side)^2}. What changes
     * is how it is delivered, and how large a river can get: a catchment cannot exceed the
     * window it is measured in, so a small region caps the biggest river it can produce.
     */
    public enum Size {
        SMALL(256, 64),
        LARGE(768, 192);

        public final int side;
        public final int halo;

        Size(int side, int halo) {
            this.side = side;
            this.halo = halo;
        }
    }

    // The channel-forming and headwater catchments live in RiverParameters, chosen per
    // world at creation; everything here reads them from the params argument. The spring
    // factors below then move the sources by terrain: high wet mountains trace to a
    // fraction of the headwater catchment, while low or dry ground raises the floor so a
    // channel crossing a plain or a savanna flows through without appearing to begin there.

    /** Below this elevation in metres the lowland factor applies in full. */
    private static final float SPRING_LOW_ELEVATION_M = 150f;
    /** Above this elevation the mountain factor applies in full. */
    private static final float SPRING_HIGH_ELEVATION_M = 1000f;
    private static final float SPRING_LOWLAND_FACTOR = 8f;
    private static final float SPRING_MOUNTAIN_FACTOR = 0.25f;
    /** Rainfall below this share of reference cannot buy the floor down any further. */
    private static final float SPRING_MIN_WETNESS = 0.2f;
    /**
     * Altitude alone earns nothing: the mountain discount is scaled by how spring-friendly
     * the place is, the best of wet, cold, or rugged. A high dry savanna plateau fails all
     * three and keeps the lowland floor, while alps qualify by cold and rain, and a
     * cliff-torn dry range can still qualify by relief alone.
     */
    private static final float SPRING_WET_FULL = 1.0f;
    private static final float SPRING_WET_NONE = 0.55f;
    /** Celsius at and below which cold fully qualifies a place for springs. */
    private static final float SPRING_COLD_FULL_C = -5f;
    private static final float SPRING_COLD_NONE_C = 5f;
    /** Metres of relief across a five-cell window that read as cliffs rather than hills. */
    private static final float SPRING_RELIEF_NONE_M = 25f;
    private static final float SPRING_RELIEF_FULL_M = 90f;
    /**
     * Paths shorter than this that neither join another river nor reach the sea are window
     * fragments, and would read as a noodle starting and ending nowhere.
     */
    private static final int MIN_STANDALONE_PATH_BLOCKS = 64;
    /**
     * Rainfall that counts as ordinary, in the climate field's own units; a measured
     * temperate window averages about 576. The channel threshold is taken against this
     * fixed value rather than each window's own mean: normalising per window handed every
     * desert as many rivers as a rainforest, since an arid window just lowered its own
     * bar. Against a fixed bar, a desert channel must earn its water the way a real one
     * does, by draining a huge area or wet mountains upstream.
     */
    private static final float REFERENCE_PRECIP = 600f;
    /**
     * Guards against a pathological walk consuming the region, set far above any real
     * path. A truncated walk does not merely stop, it orphans its entire downstream:
     * every cell below the cut still has a kept upstream neighbour and so never counts
     * as a source, so a cap that long rivers can actually reach kills them mid-course.
     */
    private static final int MAX_PATH_CELLS = 32768;

    private static final Map<Long, Region> CACHE = new ConcurrentHashMap<>();

    private RiverRegions() {
    }

    /** Everything one region's drainage analysis produced: channels and standing water. */
    public static final class Region {
        public final List<RiverPath> paths;
        /** South-west block corner of each lake cell; a cell covers scale x scale blocks. */
        public final int[] lakeBlockX;
        public final int[] lakeBlockZ;
        /** Spill elevation of each lake cell in metres; water belongs just below it. */
        public final float[] lakeSurface;
        /**
         * Ground under each lake cell before the basin was filled, in metres. How far
         * the surface stands above it is how deep the basin already was there, which is
         * what lets a bed deepen away from its own shore instead of dropping to a flat
         * pan at the waterline.
         */
        public final float[] lakeGround;

        Region(List<RiverPath> paths, int[] lakeBlockX, int[] lakeBlockZ,
               float[] lakeSurface, float[] lakeGround) {
            this.paths = paths;
            this.lakeBlockX = lakeBlockX;
            this.lakeBlockZ = lakeBlockZ;
            this.lakeSurface = lakeSurface;
            this.lakeGround = lakeGround;
        }

        static final Region EMPTY = new Region(List.of(), new int[0], new int[0],
                new float[0], new float[0]);
    }

    /** A channel as a run of block positions, ordered downstream. */
    public static final class RiverPath {
        public final int[] blockX;
        public final int[] blockZ;
        /**
         * Upstream catchment at each point, in cells. An absolute measure, deliberately not
         * scaled by the channel-forming threshold: otherwise thinning the network by raising
         * that threshold would shrink every river at the same time, and river count and
         * river size could not be tuned apart.
         */
        public final float[] flow;
        /** Ground elevation in metres at each point, used to read the local gradient. */
        public final float[] ground;
        /**
         * Points crossing a filled basin. The path continues through so the river resumes
         * at the outlet, but nothing is carved: the basin is a lake, not a channel.
         */
        public final boolean[] submerged;
        /** Strahler order at the upstream end: 1 is a headwater. */
        public final int order;

        RiverPath(int[] blockX, int[] blockZ, float[] flow, float[] ground,
                  boolean[] submerged, int order) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.flow = flow;
            this.ground = ground;
            this.submerged = submerged;
            this.order = order;
        }
    }

    /** Supplies {@code [elevation, climate]} for a native-pixel window. */
    @FunctionalInterface
    public interface FineSource {
        float[][] fetch(int i0, int j0, int i1, int j1) throws Exception;
    }

    /** Drops every cached region. Call when the seed changes. */
    public static void clear() {
        CACHE.clear();
    }

    /** Region grid index of a block coordinate, for planning prefetches. */
    public static int regionIndex(int block, int scale, Size size) {
        return Math.floorDiv(block, size.side * scale);
    }

    /** True when a region is already built and cached. */
    public static boolean isCached(int ri, int rj, Size size) {
        return CACHE.containsKey(cacheKey(ri, rj, size));
    }

    /** Builds and caches exactly one region, for the idle prefetcher. */
    public static void prebuild(int ri, int rj, int scale, Size size, RiverParameters params,
                                FineSource source) {
        region(ri, rj, scale, size, params, source);
    }

    /**
     * Every region whose analysis reaches a block-space window.
     *
     * @param scale blocks per native pixel
     */
    public static List<Region> forBlockWindow(int i0, int j0, int i1, int j1,
                                              int scale, Size size, RiverParameters params,
                                              FineSource source) {
        int regionBlocks = size.side * scale;
        // A neighbour's paths run up to a halo past its core, so border tiles must ask the
        // neighbours too. Without this a river is cut off at the core line of the region
        // that traced it, ending mid-slope for no visible reason.
        int pad = size.halo * scale;
        int ri0 = Math.floorDiv(i0 - pad, regionBlocks), ri1 = Math.floorDiv(i1 - 1 + pad, regionBlocks);
        int rj0 = Math.floorDiv(j0 - pad, regionBlocks), rj1 = Math.floorDiv(j1 - 1 + pad, regionBlocks);

        List<Region> out = new ArrayList<>();
        for (int ri = ri0; ri <= ri1; ri++) {
            for (int rj = rj0; rj <= rj1; rj++) {
                out.add(region(ri, rj, scale, size, params, source));
            }
        }
        return out;
    }

    /** Region grids of different sizes must not share cache entries. */
    private static long cacheKey(int ri, int rj, Size size) {
        return ((long) ri << 33) ^ ((long) rj << 1) ^ size.ordinal();
    }

    private static Region region(int ri, int rj, int scale, Size size, RiverParameters params,
                                 FineSource source) {
        long key = cacheKey(ri, rj, size);
        Region cached = CACHE.get(key);
        if (cached != null) return cached;

        Region built;
        try {
            built = build(ri, rj, scale, size, params, source);
            LOG.info("River region ({}, {}) {}: {} paths, {} lake cells",
                    ri, rj, size, built.paths.size(), built.lakeSurface.length);
        } catch (Exception e) {
            // Must not take terrain generation down, but must not look like "no rivers
            // here" either, so it is logged rather than swallowed.
            LOG.error("River region ({}, {}) failed to build", ri, rj, e);
            built = Region.EMPTY;
        }
        CACHE.putIfAbsent(key, built);
        return CACHE.get(key);
    }

    private static Region build(int ri, int rj, int scale, Size size, RiverParameters params,
                                FineSource source) throws Exception {
        int i0 = ri * size.side - size.halo;
        int j0 = rj * size.side - size.halo;
        int i1 = ri * size.side + size.side + size.halo;
        int j1 = rj * size.side + size.side + size.halo;
        int h = i1 - i0, w = j1 - j0, n = h * w;

        float[][] fetched = source.fetch(i0, j0, i1, j1);
        float[] elev = fetched[0];
        float[] climate = fetched[1];
        float[] precip = (climate != null && climate.length >= 3 * n)
                ? java.util.Arrays.copyOfRange(climate, 2 * n, 3 * n) : null;
        float[] temperature = (climate != null && climate.length >= n)
                ? java.util.Arrays.copyOfRange(climate, 0, n) : null;

        // Depth is deliberately not a lake gate: basins on this terrain run only metres
        // deep, so lakes are made by area and the bed is deepened at carve time.
        CoarseHydrology.Drainage d =
                CoarseHydrology.analyse(elev, precip, h, w, params.lakeMinCells);

        boolean hasLand = false;
        for (int i = 0; i < n && !hasLand; i++) hasLand = !d.ocean[i];
        if (!hasLand) return Region.EMPTY;
        float norm = precip == null ? 1f : REFERENCE_PRECIP;

        // Where a spring may sit is the terrain's choice. Elevation is absolute metres, so
        // the same mountains breed the same springs at any world scale, but height alone
        // earns nothing: the discount is scaled by the best of wet, cold, or rugged, so a
        // high dry savanna plateau stays springless while cliff country qualifies dry.
        float[] relief = fiveCellRelief(elev, h, w);
        float[] headwaterMin = new float[n];
        for (int i = 0; i < n; i++) {
            float ground = Math.max(0f, d.filled[i]);
            float elevT = clamp01((ground - SPRING_LOW_ELEVATION_M)
                    / (SPRING_HIGH_ELEVATION_M - SPRING_LOW_ELEVATION_M));

            float wetT = precip == null ? 1f
                    : clamp01((Math.max(0f, precip[i]) / REFERENCE_PRECIP - SPRING_WET_NONE)
                            / (SPRING_WET_FULL - SPRING_WET_NONE));
            float coldT = temperature == null ? 0f
                    : clamp01((SPRING_COLD_NONE_C - temperature[i])
                            / (SPRING_COLD_NONE_C - SPRING_COLD_FULL_C));
            float ruggedT = clamp01((relief[i] - SPRING_RELIEF_NONE_M)
                    / (SPRING_RELIEF_FULL_M - SPRING_RELIEF_NONE_M));
            float suitability = Math.max(wetT, Math.max(coldT, ruggedT));

            float t = elevT * suitability;
            float elevFactor = SPRING_LOWLAND_FACTOR
                    + (SPRING_MOUNTAIN_FACTOR - SPRING_LOWLAND_FACTOR) * t;
            // Dryness raises the floor; wetness never lowers it below the terrain's
            // choice, or the wettest ranges sprout a spring from every gully.
            float wetness = precip == null ? 1f
                    : Math.min(1f, Math.max(SPRING_MIN_WETNESS,
                            Math.max(0f, precip[i]) / REFERENCE_PRECIP));
            headwaterMin[i] = params.headwaterCells * norm * elevFactor / wetness;
        }

        List<RiverNetwork.Reach> reaches = RiverNetwork.extractMainRivers(d,
                params.mainChannelCells * norm, params.edgeFedCells() * norm, headwaterMin,
                params.headwaterCells * norm, params.lakeMinCells * 4, i0, j0);
        List<RiverPath> paths = List.of();
        if (!reaches.isEmpty()) {
            boolean[] kept = new boolean[n];
            int[] order = new int[n];
            for (RiverNetwork.Reach reach : reaches) {
                kept[reach.from] = true;
                order[reach.from] = reach.order;
            }
            paths = walkPaths(d, kept, order, h, w, i0, j0, scale, norm, size);
        }

        int lakeCount = 0;
        for (int i = 0; i < n; i++) {
            if (d.lake[i]) lakeCount++;
        }
        int[] lakeX = new int[lakeCount];
        int[] lakeZ = new int[lakeCount];
        float[] lakeSurface = new float[lakeCount];
        float[] lakeGround = new float[lakeCount];
        int out = 0;
        for (int i = 0; i < n; i++) {
            if (!d.lake[i]) continue;
            int row = i / w, col = i - row * w;
            lakeX[out] = (j0 + col) * scale;
            lakeZ[out] = (i0 + row) * scale;
            lakeSurface[out] = d.filled[i];
            lakeGround[out] = elev[i];
            out++;
        }

        return new Region(paths, lakeX, lakeZ, lakeSurface, lakeGround);
    }

    /**
     * Splits the kept subtree into paths, each running from a headwater down to the first
     * cell an earlier path already claimed. Every cell lands in exactly one path, so the
     * channel is carved once and a confluence is not cut twice as deep.
     */
    private static List<RiverPath> walkPaths(CoarseHydrology.Drainage d, boolean[] kept,
                                             int[] order, int h, int w,
                                             int i0, int j0, int scale, float precipNorm,
                                             Size size) {
        int n = h * w;
        int[] keptUpstream = new int[n];
        for (int i = 0; i < n; i++) {
            if (!kept[i]) continue;
            int to = d.downstream[i];
            if (to >= 0 && kept[to]) keptUpstream[to]++;
        }

        boolean[] claimed = new boolean[n];
        List<RiverPath> paths = new ArrayList<>();
        int cap = MAX_PATH_CELLS * Math.max(1, scale) + 8;
        int[] bufX = new int[cap];
        int[] bufZ = new int[cap];
        float[] bufFlow = new float[cap];
        float[] bufGround = new float[cap];
        boolean[] bufWet = new boolean[cap];
        int[] claimedCells = new int[MAX_PATH_CELLS];
        float safeNorm = Math.max(1e-6f, precipNorm);
        int coreLo = size.halo, coreHiR = size.halo + size.side;

        for (int source = 0; source < n; source++) {
            if (!kept[source] || keptUpstream[source] != 0 || claimed[source]) continue;

            int count = 0;
            int steps = 0;
            int claimCount = 0;
            int prevX = Integer.MIN_VALUE, prevZ = 0;
            int cur = source;
            boolean joined = false;
            boolean touchesCore = false;

            while (cur >= 0 && kept[cur] && steps < MAX_PATH_CELLS) {
                int row = cur / w, col = cur - row * w;
                if (row >= coreLo && row < coreHiR && col >= coreLo && col < coreHiR) {
                    touchesCore = true;
                }
                int bx = (j0 + col) * scale;
                int bz = (i0 + row) * scale;
                float flow = d.discharge[cur] / safeNorm;
                float ground = d.filled[cur];
                boolean wet = d.lake[cur];

                if (prevX == Integer.MIN_VALUE) {
                    bufX[count] = bx;
                    bufZ[count] = bz;
                    bufFlow[count] = flow;
                    bufGround[count] = ground;
                    bufWet[count] = wet;
                    count++;
                } else {
                    // One analysis cell is `scale` blocks, so consecutive cells are not
                    // adjacent in block space. Without filling the gap the carver cuts a
                    // pit per cell instead of a channel.
                    int span = Math.max(Math.abs(bx - prevX), Math.abs(bz - prevZ));
                    for (int s = 1; s <= span && count < cap; s++) {
                        bufX[count] = prevX + Math.round((bx - prevX) * (float) s / span);
                        bufZ[count] = prevZ + Math.round((bz - prevZ) * (float) s / span);
                        bufFlow[count] = flow;
                        bufGround[count] = ground;
                        bufWet[count] = wet;
                        count++;
                    }
                }
                prevX = bx;
                prevZ = bz;
                steps++;

                boolean joinsExisting = claimed[cur];
                if (!joinsExisting) claimedCells[claimCount++] = cur;
                claimed[cur] = true;
                if (joinsExisting) {
                    joined = true;
                    break;
                }
                cur = d.downstream[cur];
            }

            // A culled path must release its claims, or a later path reaching one of its
            // cells stops there believing it joined a carved river, and ends mid-air.
            boolean toOcean = cur >= 0 && d.ocean[cur];
            boolean cull = count < 2
                    // A path that never enters this region's core is the owning
                    // neighbour's business: if the owner keeps the river, the owner
                    // carves it, and if the owner rejected it, this is a fragment of a
                    // river that does not exist, ending dead in the landscape.
                    || !touchesCore
                    // A short run that neither joins another river nor reaches the sea
                    // is a fragment of a channel mostly outside this window.
                    || (!joined && !toOcean && count < MIN_STANDALONE_PATH_BLOCKS);
            if (cull) {
                for (int k = 0; k < claimCount; k++) claimed[claimedCells[k]] = false;
                continue;
            }
            smoothCenterline(bufX, bufZ, count);
            paths.add(new RiverPath(java.util.Arrays.copyOf(bufX, count),
                    java.util.Arrays.copyOf(bufZ, count),
                    java.util.Arrays.copyOf(bufFlow, count),
                    java.util.Arrays.copyOf(bufGround, count),
                    java.util.Arrays.copyOf(bufWet, count), order[source]));
        }
        return paths;
    }

    /**
     * Highest minus lowest ground across a five-cell window, in metres: how cliff-torn a
     * place is, as opposed to merely high. Ocean cells are ignored.
     */
    private static float[] fiveCellRelief(float[] elev, int h, int w) {
        float[] relief = new float[h * w];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int dr = -2; dr <= 2; dr++) {
                    int nr = r + dr;
                    if (nr < 0 || nr >= h) continue;
                    for (int dc = -2; dc <= 2; dc++) {
                        int nc = c + dc;
                        if (nc < 0 || nc >= w) continue;
                        float e = elev[nr * w + nc];
                        if (Float.isNaN(e)) continue;
                        if (e < lo) lo = e;
                        if (e > hi) hi = e;
                    }
                }
                relief[r * w + c] = hi > lo ? hi - lo : 0f;
            }
        }
        return relief;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * Rounds the D8 staircase out of a path. Descent moves in eight directions, so a raw
     * centerline turns in 45-degree jags that read as a chain of stamped discs once carved.
     *
     * <p>The window shrinks symmetrically at the ends, so endpoints stay put and every tile
     * sees the same line. A clamp afterwards keeps consecutive points adjacent, since a gap
     * would carve a dashed channel.
     */
    private static void smoothCenterline(int[] xs, int[] zs, int count) {
        if (count < 5) return;
        int window = 4;
        int[] sx = new int[count];
        int[] sz = new int[count];

        for (int k = 0; k < count; k++) {
            int r = Math.min(window, Math.min(k, count - 1 - k));
            long ax = 0, az = 0;
            for (int t = k - r; t <= k + r; t++) {
                ax += xs[t];
                az += zs[t];
            }
            int n = 2 * r + 1;
            sx[k] = (int) Math.round(ax / (double) n);
            sz[k] = (int) Math.round(az / (double) n);
        }

        xs[0] = sx[0];
        zs[0] = sz[0];
        for (int k = 1; k < count; k++) {
            xs[k] = Math.max(xs[k - 1] - 1, Math.min(xs[k - 1] + 1, sx[k]));
            zs[k] = Math.max(zs[k - 1] - 1, Math.min(zs[k - 1] + 1, sz[k]));
        }
    }
}
