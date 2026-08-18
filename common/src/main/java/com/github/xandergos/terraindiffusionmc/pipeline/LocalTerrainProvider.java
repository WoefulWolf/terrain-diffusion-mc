package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.pipeline.river.RiverCarver;
import com.github.xandergos.terraindiffusionmc.pipeline.river.RiverRegions;
import com.github.xandergos.terraindiffusionmc.world.LatitudeParameters;
import com.github.xandergos.terraindiffusionmc.world.RiverMode;
import com.github.xandergos.terraindiffusionmc.world.RiverParameters;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides terrain heightmap and biome data from the local WorldPipeline.
 *
 * <p>When scale=1 the pipeline is sampled at native model resolution directly.
 * When scale>1 the pipeline is sampled at native resolution and the result is
 * bilinearly upsampled, giving 1 block = nativeResolution/scale.
 */
public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f,  2, 2f, 0.6f);

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        /** No river reaches this column; anything below sea level is the ocean's business. */
        public static final short NO_WATER = Short.MIN_VALUE;

        public final short[][] heightmap;
        public final short[][] biomeIds;
        /** River water surface in metres, or {@link #NO_WATER}. */
        public final short[][] waterLevel;
        /**
         * Channel steepness where the rivers carved, {@code 1 + steep * 100}, or 0 where
         * they never touched. Banks carry it too, so bank materials can follow the bed's.
         */
        public final byte[][] riverClass;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, short[][] waterLevel,
                             byte[][] riverClass, int width, int height) {
            this.heightmap  = heightmap;
            this.biomeIds   = biomeIds;
            this.waterLevel = waterLevel;
            this.riverClass = riverClass;
            this.width      = width;
            this.height     = height;
        }
    }

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed) {}

    // Sized so the prefetcher's tile ring never evicts what the workers are reading;
    // a tile is under half a megabyte, so this is cheap insurance.
    private static final int MAX_CACHE_SIZE = 128;
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final Map<CacheKey, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();

    /** Bumped whenever the seed changes; prefetch units check it and drop stale work. */
    private static final AtomicLong GENERATION = new AtomicLong();

    private static final int PRIORITY_REAL = 0;
    private static final int PRIORITY_PREFETCH = 1;
    private static final AtomicLong TASK_SEQ = new AtomicLong();

    /** Real work jumps ahead of any speculative unit still waiting in the queue. */
    private static final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
        final int priority;
        final long seq;
        final Runnable body;

        PrioritizedTask(int priority, Runnable body) {
            this.priority = priority;
            this.seq = TASK_SEQ.incrementAndGet();
            this.body = body;
        }

        @Override
        public void run() {
            body.run();
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            if (priority != other.priority) return Integer.compare(priority, other.priority);
            return Long.compare(seq, other.seq);
        }
    }

    /**
     * Single thread for pipeline.get() so MemoryTileStore is not accessed concurrently.
     * Everything must enter through {@link #enqueue}: submit() would wrap tasks in plain
     * futures the priority queue cannot order.
     */
    private static final ExecutorService INFERENCE_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "terrain-diffusion-inference");
        t.setDaemon(true);
        return t;
    });

    private static void enqueue(int priority, Runnable body) {
        INFERENCE_EXECUTOR.execute(new PrioritizedTask(priority, body));
    }

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    /** Seed is 64-bit world seed. Creates provider once; later worlds only update seed and clear caches (lightweight). */
    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
            GENERATION.incrementAndGet();
            CACHE.clear();
            PENDING.clear();
            RiverRegions.clear();
            MushroomIslands.clear();
            ContinentalField.clear();
            LocalReliefField.clear();
        }
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static void clearCache() {
        // Also retires queued prefetch units: they captured the previous world's scale
        // and river parameters, and a same-seed world with different settings must not
        // inherit regions built under the old ones.
        GENERATION.incrementAndGet();
        CACHE.clear();
        PENDING.clear();
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        return instanceSeed;
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        float[][] out = submitToInferenceThread(() -> getInstance().pipeline.get(i1, j1, i2, j2, withClimate));
        if (withClimate && out != null && out.length > 1) {
            out[1] = withLatitudeBias(out[1], i1, i2 - i1, j2 - j1,
                    WorldScaleManager.getCurrentScale());
        }
        return out;
    }

    /**
     * Adds the latitude temperature band to a fetched climate block, on a copy so the
     * pipeline's cached tiles stay pristine. Native rows map to blocks through the
     * scale, so every consumer of the temperature channel sees the same banded field
     * whatever the window or resolution.
     */
    private static float[] withLatitudeBias(float[] climate, int i1Native, int nH, int nW, int scale) {
        LatitudeParameters p = WorldScaleManager.getLatitudeParameters();
        if (climate == null || p.bandStrengthC == 0 || climate.length < nH * nW) return climate;
        float[] out = climate.clone();
        for (int r = 0; r < nH; r++) {
            float bias = p.temperatureBiasAt((i1Native + r) * (double) scale);
            int row = r * nW;
            for (int c = 0; c < nW; c++) out[row + c] += bias;
        }
        return out;
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        submitToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
            instanceSeed = newSeed;
            GENERATION.incrementAndGet();
            CACHE.clear();
            PENDING.clear();
            // Regions hold the old seed's rivers; without this they would be carved
            // into the new seed's terrain until a world reload cleared them. Island
            // verdicts are seed-pure the same way.
            RiverRegions.clear();
            MushroomIslands.clear();
            ContinentalField.clear();
            LocalReliefField.clear();
            return null;
        });
    }

    /**
     * Distance to the sea in blocks across a block window, at continental scale.
     * Runs on the inference thread, which the coarse slices behind it require.
     */
    public static float[] continentalDistance(int i0, int j0, int height, int width) throws Exception {
        return submitToInferenceThread(() -> ContinentalField.forBlockWindow(
                i0, j0, height, width, WorldScaleManager.getCurrentScale(), getInstance().pipeline));
    }

    /** Local high-ground elevation in metres across a block window, for diagnostics. */
    public static float[] localRelief(int i0, int j0, int height, int width) throws Exception {
        return submitToInferenceThread(() -> LocalReliefField.forBlockWindow(
                i0, j0, height, width, WorldScaleManager.getCurrentScale(), getInstance().pipeline));
    }

    /** Island verdict for one coarse cell, for the explorer and diagnostics. */
    public static boolean isMushroomIslandCell(int ci, int cj) throws Exception {
        return submitToInferenceThread(() ->
                MushroomIslands.isMushroom(ci, cj, getInstance().pipeline, instanceSeed));
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        enqueue(PRIORITY_REAL, future);
        return future.get();
    }

    // =========================================================================
    // Prefetcher hooks — all run on the inference thread at low priority
    // =========================================================================

    /** Cache generation for staleness checks; bumped on every seed change. */
    static long cacheGeneration() {
        return GENERATION.get();
    }

    /** Queues one speculative unit behind all real work. */
    static void enqueuePrefetch(Runnable body) {
        enqueue(PRIORITY_PREFETCH, body);
    }

    /** Stage prewarm for the prefetcher; must run on the inference thread. */
    static void prewarmDirect(WorldPipeline.Stage stage, int i0, int j0, int i1, int j1) {
        getInstance().pipeline.prewarm(stage, i0, j0, i1, j1);
    }

    /**
     * The same fetch the carver's region lambda does: direct, because this already runs
     * on the inference thread, and latitude-biased so prefetched drainage matches what a
     * real tile would have built.
     */
    static float[][] fetchForRegionDirect(int a, int b, int c, int d) {
        float[][] o = getInstance().pipeline.get(a, b, c, d, true);
        o[1] = withLatitudeBias(o[1], a, c - a, d - b, WorldScaleManager.getCurrentScale());
        return o;
    }

    /**
     * Computes one tile in place on the inference thread if nobody has it or is on it.
     * The pending map arbitrates with real requests both ways: a worker that asks for
     * this tile mid-compute waits on the same future instead of recomputing it.
     */
    static void computeTileInlineIfAbsent(int i1, int j1, int i2, int j2) {
        LocalTerrainProvider provider = getInstance();
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        if (CACHE.containsKey(key)) return;
        FutureTask<HeightmapData> task = provider.tileTask(key, i1, j1, i2, j2);
        if (PENDING.putIfAbsent(key, task) == null) {
            task.run();
            // On success the task removed its own pending entry; an exception leaves it,
            // and a stuck entry would make every later request hang on a dead future.
            PENDING.remove(key, task);
        }
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    /**
     * Cache-only lookup: null when the tile was never computed. Safe to call from hooks
     * that may fire on worlds this generator does not own, since it never starts
     * inference; a foreign world simply never has the tile.
     */
    public HeightmapData peekHeightmap(int i1, int j1, int i2, int j2) {
        CacheEntry cached = CACHE.get(new CacheKey(i1, j1, i2, j2));
        return cached == null ? null : cached.data();
    }

    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            TerrainPrefetcher.noteAccess(i1, j1);
            return cached.data;
        }

        HeightmapData data = this.genHeightmap(key, i1, j1, i2, j2);
        TerrainPrefetcher.noteAccess(i1, j1);
        return data;
    }

    /** The full compute for one tile, run wherever the caller decides. */
    private FutureTask<HeightmapData> tileTask(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
        return new FutureTask<>(() -> {
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
            long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();

            long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                    OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight, newlyComputedWindowCount);
            CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
            evictLruTo(MAX_CACHE_SIZE);
            PENDING.remove(key);
            return data;
        });
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        FutureTask<HeightmapData> task = tileTask(key, i1, j1, i2, j2);
        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        FutureTask<HeightmapData> toRun = (existing == null) ? task : (FutureTask<HeightmapData>) existing;
        if (existing == null) {
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) uncached region requested: ({}, {})-({}, {}) size {}x{}",
                    OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, regionWidth, regionHeight);
            enqueue(PRIORITY_REAL, toRun);
        }
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
        }
    }

    private static void evictLruTo(int maxSize) {
        int headroomHalf = MAX_CACHE_SIZE_HEADROOM / 2;
        if (CACHE.size() > maxSize + headroomHalf) {
            CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .limit(MAX_CACHE_SIZE_HEADROOM)
                .map(Map.Entry::getKey)
                .forEach(CACHE::remove);
        }
    }

    // =========================================================================
    // Scale == 1: block coords == native pixel coords
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;

        // The shoreline pass wants a wide halo; the classifier's 1-pixel slope pad is
        // cropped out of the same fetch.
        int shorePad = BiomeClassifier.SHORE_PAD;
        float[] elevWide = pipeline.get(i1 - shorePad, j1 - shorePad, i2 + shorePad, j2 + shorePad, false)[0];
        float[] elevPadded = cropFlatFromFlat(elevWide, shorePad - 1, shorePad - 1, H + 2, W + 2, W + 2 * shorePad);
        float[][] out = pipeline.get(i1, j1, i2, j2, true);
        float[] elevFlat = out[0];
        float[] climate  = withLatitudeBias(out[1], i1, H, W, 1);

        float[] coastDist = BiomeClassifier.coastDistance(elevWide, shorePad, H, W);
        float[] localRelief = LocalReliefField.forBlockWindow(i1, j1, H, W, 1, pipeline);
        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W, NATIVE_RESOLUTION, coastDist, localRelief);
        BiomeClassifier.applyShoreline(biomeFlat, elevFlat, elevWide, shorePad, coastDist, i1, j1, H, W, NATIVE_RESOLUTION);
        stampMushroomIslands(elevFlat, biomeFlat, i1, j1, H, W, 1);
        float[] waterFlat = newWaterField(H * W);
        byte[] riverClassFlat = new byte[H * W];
        carveRivers(elevFlat, biomeFlat, climate, waterFlat, riverClassFlat, i1, j1, H, W, 1);
        return buildHeightmapData(elevFlat, biomeFlat, waterFlat, riverClassFlat, H, W);
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        // Convert block coords to native pixel coords
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        // Native padding: 2 pixels for bilinear + slope, plus enough to cover the
        // shoreline pass's block-space halo after upsampling.
        int padN = 2 + (BiomeClassifier.SHORE_PAD + scale - 1) / scale;
        int i1p = i1n - padN, j1p = j1n - padN;
        int i2p = i2n + padN, j2p = j2n + padN;
        int nH = i2p - i1p, nW = j2p - j1p;

        float[][] out = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat    = out[0];
        float[] climateNativeFlat = withLatitudeBias(out[1], i1p, nH, nW, scale);

        // Bilinear upsample elevation: (nH, nW) → (nH*scale, nW*scale)
        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        float[][] elevUp = LaplacianUtils.bilinearResize(elevNative2D, nH * scale, nW * scale);

        // Crop offsets in the upsampled array
        int padUp   = padN * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        int shorePad = BiomeClassifier.SHORE_PAD;
        float[] elevSmooth = cropFlat(elevUp, cropI1,     cropJ1,     H,   W,   nH * scale, nW * scale);
        float[] elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H+2, W+2, nH * scale, nW * scale);
        float[] elevWide   = cropFlat(elevUp, cropI1 - shorePad, cropJ1 - shorePad,
                H + 2 * shorePad, W + 2 * shorePad, nH * scale, nW * scale);

        // Upsample climate (4, nH, nW) → (4, H, W)
        float[] climate = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale, nH * scale, nW * scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        float[] coastDist = BiomeClassifier.coastDistance(elevWide, shorePad, H, W);
        float[] localRelief = LocalReliefField.forBlockWindow(i1, j1, H, W, scale, pipeline);
        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W, pixelSizeM, coastDist, localRelief);
        BiomeClassifier.applyShoreline(biomeFlat, elevSmooth, elevWide, shorePad, coastDist, i1, j1, H, W, pixelSizeM);
        stampMushroomIslands(elevSmooth, biomeFlat, i1, j1, H, W, scale);
        float[] waterFlat = newWaterField(H * W);
        byte[] riverClassFlat = new byte[H * W];
        carveRivers(elevOut, biomeFlat, climate, waterFlat, riverClassFlat, i1, j1, H, W, scale);
        return buildHeightmapData(elevOut, biomeFlat, waterFlat, riverClassFlat, H, W);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = 100f * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = 70f  * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float nx = j1 + c, ny = i1 + r;
                elevOut[idx] = e
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF * sf;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
            float[][] chNative = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++)
                    result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
        }
        return result;
    }

    private static float[] cropFlatFromFlat(float[] src, int r0, int c0, int H, int W, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++)
            System.arraycopy(src, (r0 + r) * srcW + c0, out, r * W, W);
        return out;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    /**
     * Channel size against catchment. Depth keeps a weak power, as in nature, so a big river
     * is far wider than it is deep.
     *
     * <p>The width reference is the world's headwater catchment, where tracing stops, so a
     * river starts as a one-block spring. Flow spans roughly 250-fold from there to a major
     * trunk, which is why the exponents sit near real hydraulic geometry: the width cap
     * lands deep into trunk territory, and a river spends thousands of blocks getting
     * there instead of arriving fully sized a bend after its source. The caps and the
     * reference come from {@link RiverParameters}, chosen per world.
     */
    private static final float RIVER_WIDTH_AT_REFERENCE = 0.5f;
    private static final float RIVER_DEPTH_AT_SOURCE = 1.4f;
    private static final float RIVER_DEPTH_EXPONENT = 0.28f;

    /**
     * Display width of a channel for the explorer, matching the carve's base curve before
     * gradient modulation. Lives beside the constants it mirrors so they cannot drift.
     */
    public static float baseRiverHalfWidthBlocks(float flow) {
        RiverParameters params = WorldScaleManager.getRiverParameters();
        float catchment = Math.max(1f, flow / params.widthReferenceCells);
        return Math.min(params.maxHalfWidth(),
                RIVER_WIDTH_AT_REFERENCE * (float) Math.pow(catchment, params.widthExponent));
    }

    /**
     * Gradient is what separates width from depth. Catchment alone would make every river of
     * a given size identical, but a steep reach cuts a narrow deep channel while a slack one
     * spreads wide and shallow, which is why a gorge and a lowland river carrying the same
     * water look nothing alike.
     *
     * <p>Gradient is read in blocks of fall per horizontal block, so the same numbers hold at
     * any world scale. Measured over real channels here the median is 0.008 and the 90th
     * percentile 0.042, so treating 0.04 as fully steep puts most of a river in the middle of
     * the range and reserves the extremes for genuine mountain and floodplain reaches.
     */
    private static final int RIVER_GRADIENT_WINDOW = 12;
    private static final float RIVER_STEEP_GRADIENT = 0.04f;
    private static final float RIVER_WIDTH_WHEN_SLACK = 1.35f;
    private static final float RIVER_WIDTH_WHEN_STEEP = 0.6f;
    private static final float RIVER_DEPTH_WHEN_SLACK = 0.75f;
    private static final float RIVER_DEPTH_WHEN_STEEP = 1.7f;
    /**
     * Half-window, in path blocks, that width and depth are averaged over before carving.
     * The gradient modulation reacts faster than a real channel can, and unsmoothed the
     * outline pinches and bulges every few blocks.
     */
    private static final int RIVER_SMOOTH_BLOCKS = 8;

    /**
     * Ocean mouths fade out as a submerged fan: over the last stretch the channel widens
     * while its cut weakens to nothing, feathering into the shelf the way a delta spreads,
     * instead of ending in a full-strength stamped disc. Only mouths near sea level fan
     * out; a river meeting the sea off a cliff keeps its waterfall.
     */
    private static final float MOUTH_GROUND_BLOCKS = 2f;
    private static final float MOUTH_FAN_WIDEN = 1f;
    private static final int MOUTH_TAPER_MIN_BLOCKS = 8;
    private static final int MOUTH_TAPER_MAX_BLOCKS = 96;
    /**
     * Depth a river shallows to across its mouth. The fade alone cannot soften a mouth:
     * blending toward untouched ground is linear in fade while the bed sits many blocks
     * down, so most of the taper still cuts near-full depth and the trench ends in an
     * underwater cliff. Rivers shoal over their own bars; the depth has to come up before
     * the fade can feather what is left into the shelf.
     */
    private static final float MOUTH_DEPTH_BLOCKS = 2.5f;

    /**
     * A path that begins already carrying this much flow had its spring denied by the
     * terrain, so its carve fades in over a few widths instead: the river gathers itself
     * out of the ground rather than materialising at full size in dry country.
     */
    private static final float SOURCE_FADE_MIN_FLOW = 3000f;
    private static final int SOURCE_FADE_MIN_BLOCKS = 16;
    private static final int SOURCE_FADE_MAX_BLOCKS = 96;
    /** Width and depth a source begins at before its ramp; roughly a bubbling spring. */
    private static final float SOURCE_TIP_HALF_BLOCKS = 0.6f;
    private static final float SOURCE_TIP_DEPTH_BLOCKS = 0.9f;
    private static final int SOURCE_RAMP_MIN_BLOCKS = 8;

    /**
     * Water-margin greening. Vegetation follows water it can actually reach, so a green
     * corridor only grows where the bank sits within a few blocks of the river's own
     * surface: a meandering channel at grade waters its margins, while one incised deep
     * between stone walls leaves them bare and the desert canyon keeps its look. Reach
     * grows with river size and breathes with slow noise.
     */
    private static final FastNoiseLite GREEN_NOISE = makeFnl(0x6BEE4, 1f / 120f, 2, 2f, 0.5f);
    private static final float RIPARIAN_BASE_BLOCKS = 4f;
    private static final float RIPARIAN_SIZE_BONUS_BLOCKS = 7f;
    private static final float RIPARIAN_SIZE_REF_HALF = 12f;
    private static final float RIPARIAN_WOBBLE_BLOCKS = 4f;
    private static final float RIPARIAN_MAX_BANK_BLOCKS = 3.5f;
    private static final float RIPARIAN_BANK_WOBBLE_BLOCKS = 1f;

    /**
     * A river surface never claims below this. The drainage surface can dip under sea
     * level across a delta flat, and water claimed there would be dropped as ocean's
     * territory; the real surface of a tidal lower course is the sea's.
     */
    private static final float TIDEWATER_SURFACE_METRES = 0.05f;

    /**
     * Marshy deltas. A big slack river reaching the sea drops its sediment, so the low
     * flats around the mouth silt over into marsh — swamp, or mangrove where it is hot —
     * beaches included. Steep or small mouths keep their sand and waterfalls. Sized so a
     * delta is either a proper landscape feature or absent: a pocket marsh two trees
     * wide reads as a mistake.
     */
    private static final float DELTA_MIN_HALF_BLOCKS = 10f;
    private static final float DELTA_MAX_STEEP = 0.45f;
    private static final int DELTA_STEEP_TAIL_BLOCKS = 24;
    private static final float DELTA_REACH_FACTOR = 3.5f;
    private static final float DELTA_MAX_REACH_BLOCKS = 96f;
    private static final float DELTA_MAX_ELEV_BLOCKS = 2f;
    private static final float DELTA_MIN_TEMP_C = 5f;
    private static final float DELTA_MANGROVE_TEMP_C = 26f;

    /** Swampy fringes where warm, wet lowland meets a lake at nearly its own level. */
    private static final float LAKE_FRINGE_BASE_BLOCKS = 5f;
    private static final float LAKE_FRINGE_WOBBLE_BLOCKS = 3f;
    private static final float LAKE_FRINGE_MAX_RISE_BLOCKS = 1.5f;
    private static final float LAKE_FRINGE_MIN_TEMP_C = 12f;
    private static final float LAKE_FRINGE_MIN_PRECIP_MM = 600f;

    /**
     * Depth grading where a river meets a lake. A lake floor sits a couple of blocks under
     * its surface while a big river runs far deeper; unblended, the bed would leap the
     * whole difference at the shoreline as an underwater cliff, with the last dry discs
     * punched into the shallow pan as deep round holes. Instead the river shallows on
     * approach, pushes a fading scour trench into the basin like a real mouth, and
     * mid-lake the carve settles exactly onto the stamped floor and disappears.
     */
    private static final float RIVER_LAKE_TRANSITION_BLOCKS = 32f;
    private static final float LAKE_ENTRY_SCOUR_BLOCKS = 2.5f;
    private static final float LAKE_ENTRY_SCOUR_LEN_BLOCKS = 24f;

    /**
     * Cuts river channels into a freshly classified tile, in place.
     *
     * <p>Runs on the inference thread, so region terrain is fetched directly rather than
     * resubmitted, which would deadlock. Paths come from the region's own drainage analysis
     * at native resolution, where the D8 descent already is the channel, so a tile only has
     * to carve the part of each path that crosses it.
     */
    private void carveRivers(float[] elev, short[] biomeFlat, float[] climate, float[] waterFlat,
                             byte[] riverClassFlat, int i1, int j1, int height, int width, int scale) {
        RiverMode mode = WorldScaleManager.getRiverMode();
        if (mode == RiverMode.OFF) return;

        // climate is laid out temp, temp seasonality, precip, precip CV; the carver only
        // needs the leading temperature block to decide where a river freezes.
        float[] temperature = (climate != null && climate.length >= height * width) ? climate : null;
        float metresPerBlock = NATIVE_RESOLUTION / scale;

        RiverRegions.Size regionSize = mode == RiverMode.FAST
                ? RiverRegions.Size.SMALL : RiverRegions.Size.LARGE;
        RiverParameters params = WorldScaleManager.getRiverParameters();
        float maxHalfWidth = params.maxHalfWidth();
        float maxDepth = params.maxDepthBlocks;

        List<RiverRegions.Region> regions;
        try {
            regions = RiverRegions.forBlockWindow(i1, j1, i1 + height, j1 + width, scale,
                    regionSize, params, (a, b, c, d) -> {
                        // Same latitude band as every other climate consumer, so frozen
                        // rivers and cold-priced springs agree with the biomes above them.
                        float[][] o = pipeline.get(a, b, c, d, true);
                        o[1] = withLatitudeBias(o[1], a, c - a, d - b, scale);
                        return o;
                    });
        } catch (Exception e) {
            LOG.warn("River paths unavailable for tile ({}, {}): {}", j1, i1, e.toString());
            return;
        }

        int[] localRow = new int[Math.max(16, height * 2)];
        int[] localCol = new int[localRow.length];
        float[] runHalf = new float[localRow.length];
        float[] runDepth = new float[localRow.length];
        float[] runSurf = new float[localRow.length];
        float[] runSteep = new float[localRow.length];
        float[] runFade = new float[localRow.length];

        // A channel centred just outside the tile still hangs its rim into it, so path
        // points are kept within the widest possible footprint of the border. The fan
        // at an ocean mouth can double the half-width, hence the 2.
        int carveMargin = RiverCarver.maxReachBlocks(maxHalfWidth * 2f, params.edgeWobbleBlocks);

        float[] edgeField = new float[height * width];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                edgeField[row * width + col] = EDGE_NOISE.GetNoise(j1 + col, i1 + row);
            }
        }
        float freeboardMetres = params.freeboardBlocks * metresPerBlock;

        // Water claims are first-wins and stamped largest first: lakes, then rivers by
        // mouth size. A tributary reaching a bigger river finds those cells already wet
        // at the lower surface, so its own water stops at the join and steps down like a
        // little waterfall, instead of riding out over the river on its higher surface.
        stampLakes(regions, elev, biomeFlat, waterFlat, riverClassFlat, temperature,
                i1, j1, height, width,
                metresPerBlock, freeboardMetres, params.lakeDepthBlocks, scale);
        // Before the rivers, so a channel crossing a fringe stamps itself back on top.
        stampLakeFringes(regions, elev, biomeFlat, climate, waterFlat, i1, j1, height, width,
                metresPerBlock, freeboardMetres, scale);

        List<RiverRegions.RiverPath> paths = new ArrayList<>();
        for (RiverRegions.Region region : regions) paths.addAll(region.paths);
        paths.sort((a, b) -> Float.compare(b.flow[b.flow.length - 1], a.flow[a.flow.length - 1]));

        // Nearest-point claims within a channel, locked between channels: each path first
        // locks everything already wet, so a smaller stream cannot restamp a bigger one.
        float[] claimDist = new float[height * width];
        Arrays.fill(claimDist, Float.POSITIVE_INFINITY);

        for (RiverRegions.RiverPath path : paths) {
            lockClaims(waterFlat, claimDist);
            // Size the whole path before clipping to the tile, so the smoothing window sees
            // the same neighbours in every tile and the channel cannot step at a border.
            int len = path.blockX.length;
            float[] pHalf = new float[len];
            float[] pDepth = new float[len];
            float[] pSteep = new float[len];
            for (int k = 0; k < len; k++) {
                float catchment = Math.max(1f, path.flow[k] / params.widthReferenceCells);
                float gradient = gradientAt(path, k) / metresPerBlock;
                float steep = clamp01(gradient / RIVER_STEEP_GRADIENT);

                pSteep[k] = steep;
                pHalf[k] = Math.min(maxHalfWidth,
                        RIVER_WIDTH_AT_REFERENCE * (float) Math.pow(catchment, params.widthExponent)
                                * lerp(RIVER_WIDTH_WHEN_SLACK, RIVER_WIDTH_WHEN_STEEP, steep));
                pDepth[k] = Math.min(maxDepth,
                        RIVER_DEPTH_AT_SOURCE * (float) Math.pow(catchment, RIVER_DEPTH_EXPONENT)
                                * lerp(RIVER_DEPTH_WHEN_SLACK, RIVER_DEPTH_WHEN_STEEP, steep));
            }
            float[] pFade = new float[len];
            Arrays.fill(pFade, 1f);
            if (path.ground[len - 1] < MOUTH_GROUND_BLOCKS * metresPerBlock) {
                int taper = Math.min(len, Math.max(MOUTH_TAPER_MIN_BLOCKS,
                        Math.min(MOUTH_TAPER_MAX_BLOCKS, Math.round(3f * pHalf[len - 1]))));
                for (int k = len - taper; k < len; k++) {
                    float p = (k - (len - taper)) / (float) Math.max(1, taper - 1);
                    pFade[k] = 1f - p;
                    pHalf[k] = Math.min(maxHalfWidth * 2f,
                            pHalf[k] * (1f + MOUTH_FAN_WIDEN * p));
                    float shoal = Math.min(pDepth[k], MOUTH_DEPTH_BLOCKS);
                    pDepth[k] = pDepth[k] + (shoal - pDepth[k]) * p;
                }
            }

            // Distances to the nearest lake point and the nearest open-channel point, for
            // grading depth through lake mouths.
            int far = 1 << 28;
            int[] dWet = new int[len];
            int[] dDry = new int[len];
            int runWet = far, runDry = far;
            for (int k = 0; k < len; k++) {
                runWet = path.submerged[k] ? 0 : (runWet == far ? far : runWet + 1);
                runDry = !path.submerged[k] ? 0 : (runDry == far ? far : runDry + 1);
                dWet[k] = runWet;
                dDry[k] = runDry;
            }
            runWet = far;
            runDry = far;
            for (int k = len - 1; k >= 0; k--) {
                runWet = path.submerged[k] ? 0 : (runWet == far ? far : runWet + 1);
                runDry = !path.submerged[k] ? 0 : (runDry == far ? far : runDry + 1);
                dWet[k] = Math.min(dWet[k], runWet);
                dDry[k] = Math.min(dDry[k], runDry);
            }
            for (int k = 0; k < len; k++) {
                // The scour scales with the river, so a brook slips into a pond unchanged
                // while a major river pushes a real trench through the shore.
                float boundaryDepth = params.lakeDepthBlocks
                        + Math.min(LAKE_ENTRY_SCOUR_BLOCKS, 0.4f * pDepth[k]);
                if (path.submerged[k]) {
                    float scour = clamp01(1f - dDry[k] / LAKE_ENTRY_SCOUR_LEN_BLOCKS);
                    pDepth[k] = params.lakeDepthBlocks
                            + (boundaryDepth - params.lakeDepthBlocks) * scour;
                    // Past the entry scour the lake's own floor is the floor. Carving on
                    // cuts a fixed-depth channel across a bed that tapers, and the
                    // descent that routed the path crossed filled, level ground, where
                    // it can only run in straight lines — so the channel surfaces as a
                    // ruled diagonal across the basin.
                    pFade[k] *= scour;
                } else if (dWet[k] < RIVER_LAKE_TRANSITION_BLOCKS) {
                    float t = dWet[k] / RIVER_LAKE_TRANSITION_BLOCKS;
                    pDepth[k] = boundaryDepth + (pDepth[k] - boundaryDepth) * t;
                }
            }

            // Every source starts a spring's size and earns its width. The carve fade
            // alone cannot do this: a half-strength cut of a wide disc is still wide and
            // still wets, so a big start would surface at full width anyway. Springs that
            // already begin near the tip size ramp as a no-op.
            int rampLen = Math.min(len, Math.max(SOURCE_RAMP_MIN_BLOCKS,
                    Math.min(SOURCE_FADE_MAX_BLOCKS, Math.round(6f * pHalf[0]))));
            for (int k = 0; k < rampLen; k++) {
                float p = k / (float) Math.max(1, rampLen - 1);
                float g = p * p * (3f - 2f * p);
                pHalf[k] = Math.min(pHalf[k],
                        SOURCE_TIP_HALF_BLOCKS + (pHalf[k] - SOURCE_TIP_HALF_BLOCKS) * g);
                pDepth[k] = Math.min(pDepth[k],
                        SOURCE_TIP_DEPTH_BLOCKS + (pDepth[k] - SOURCE_TIP_DEPTH_BLOCKS) * g);
            }

            if (path.flow[0] >= SOURCE_FADE_MIN_FLOW) {
                int fadeLen = Math.min(len, Math.max(SOURCE_FADE_MIN_BLOCKS,
                        Math.min(SOURCE_FADE_MAX_BLOCKS, Math.round(6f * pHalf[0]))));
                for (int k = 0; k < fadeLen; k++) {
                    float p = k / (float) Math.max(1, fadeLen - 1);
                    pFade[k] = Math.min(pFade[k], p);
                }
            }

            boxSmooth(pHalf, RIVER_SMOOTH_BLOCKS);
            boxSmooth(pDepth, RIVER_SMOOTH_BLOCKS);
            boxSmooth(pSteep, RIVER_SMOOTH_BLOCKS);
            boxSmooth(pFade, RIVER_SMOOTH_BLOCKS);

            // A river surface may never climb. The drainage elevation behind it usually
            // descends on its own, but not where a basin's water sits below its rim: there
            // the surface steps back up at the shore, and a surface that rises stands above
            // the ground beside it and spills. Carrying the running minimum forward keeps it
            // descending, and is also what cuts the notch through a basin's rim, since the
            // bed is carved below whatever the surface is when it crosses.
            float[] pSurf = new float[len];
            float falling = Float.MAX_VALUE;
            for (int k = 0; k < len; k++) {
                falling = Math.min(falling, path.ground[k] - freeboardMetres);
                pSurf[k] = Math.max(falling, TIDEWATER_SURFACE_METRES);
            }

            int count = 0;
            for (int k = 0; k < len; k++) {
                int col = path.blockX[k] - j1;
                int row = path.blockZ[k] - i1;
                boolean inside = col >= -carveMargin && col < width + carveMargin
                        && row >= -carveMargin && row < height + carveMargin;

                // Lake crossings carve too, at the graded depth above: mid-lake the cut
                // settles onto the stamped floor and vanishes, and the water is already
                // the lake's own first claim, so only the mouths differ.
                if (inside) {
                    if (count == localRow.length) {
                        localRow = Arrays.copyOf(localRow, count * 2);
                        localCol = Arrays.copyOf(localCol, count * 2);
                        runHalf = Arrays.copyOf(runHalf, count * 2);
                        runDepth = Arrays.copyOf(runDepth, count * 2);
                        runSurf = Arrays.copyOf(runSurf, count * 2);
                        runSteep = Arrays.copyOf(runSteep, count * 2);
                        runFade = Arrays.copyOf(runFade, count * 2);
                    }
                    localRow[count] = row;
                    localCol[count] = col;
                    runHalf[count] = pHalf[k];
                    runDepth[count] = pDepth[k];
                    runSteep[count] = pSteep[k];
                    runFade[count] = pFade[k];
                    // Taken from the drainage analysis rather than from this tile. That
                    // elevation is shared by every tile the river crosses, so the water
                    // surface cannot step at a tile border, and the running minimum above is
                    // computed over the whole path for the same reason.
                    runSurf[count] = pSurf[k];
                    count++;
                } else if (count > 0) {
                    // A path may leave and re-enter, so carve each run as it closes.
                    carveRun(elev, biomeFlat, temperature, waterFlat, claimDist, riverClassFlat,
                            edgeField, params, height, width, localRow, localCol, runHalf, runDepth,
                            runSurf, runSteep, runFade, metresPerBlock, count);
                    count = 0;
                }
            }
            if (count > 0) {
                carveRun(elev, biomeFlat, temperature, waterFlat, claimDist, riverClassFlat,
                        edgeField, params, height, width, localRow, localCol, runHalf, runDepth,
                        runSurf, runSteep, runFade, metresPerBlock, count);
            }

            stampRiparian(path, pHalf, pFade, biomeFlat, elev, waterFlat,
                    i1, j1, height, width, metresPerBlock, freeboardMetres);
            stampDelta(path, pHalf, pSteep, biomeFlat, elev, waterFlat, temperature,
                    i1, j1, height, width, metresPerBlock);
        }

        featherBeds(elev, waterFlat, i1, j1, height, width, metresPerBlock,
                params.bedReliefBlocks);
    }

    /**
     * Lakes: water stands at the basin's spill level, less the same freeboard as the
     * channels, so a river entering a lake meets it at exactly its own surface. The bed is
     * lowered to hold a minimum depth of water, because the basins this terrain makes are
     * broad but shallow, and left alone they would read as scattered puddles rather than a
     * lake.
     *
     * <p>Several regions routinely claim the same cell and disagree about how high it
     * stands, because each floods only its own window and treats that window's edge as an
     * outlet — a basin wider than one region drains over a different rim in each of them.
     * Claims are therefore reconciled per cell before anything is stamped: taking whichever
     * region is stamped first leaves one basin standing at three levels at once, terraced,
     * with the highest water in the middle. Clipping a basin can only ever let it drain
     * sooner, so every claim is a lower bound on the true spill, and the highest of them is
     * the one that saw most of the basin.
     */
    private static void stampLakes(List<RiverRegions.Region> regions, float[] elev,
                                   short[] biomeFlat, float[] waterFlat, byte[] riverClassFlat,
                                   float[] temperature,
                                   int i1, int j1, int height, int width,
                                   float metresPerBlock, float freeboardMetres,
                                   float lakeDepthBlocks, int scale) {
        // A cell just outside the tile still lowers banks inside it, so the reconciled grid
        // is padded rather than clipped to the tile edge. Cell coordinates come off the same
        // global native grid in every region, so one shared cell lands on one slot whichever
        // region contributed it.
        int pad = 2;
        int cz0 = Math.floorDiv(i1, scale) - pad;
        int cz1 = Math.floorDiv(i1 + height - 1, scale) + pad;
        int cx0 = Math.floorDiv(j1, scale) - pad;
        int cx1 = Math.floorDiv(j1 + width - 1, scale) + pad;
        int cw = cx1 - cx0 + 1, ch = cz1 - cz0 + 1;
        float[] cellLevel = new float[ch * cw];
        float[] cellSpill = new float[ch * cw];
        float[] cellGround = new float[ch * cw];
        Arrays.fill(cellSpill, Float.NEGATIVE_INFINITY);

        for (RiverRegions.Region region : regions) {
            for (int k = 0; k < region.lakeSurface.length; k++) {
                int cx = Math.floorDiv(region.lakeBlockX[k], scale) - cx0;
                int cz = Math.floorDiv(region.lakeBlockZ[k], scale) - cz0;
                if (cx < 0 || cx >= cw || cz < 0 || cz >= ch) continue;
                int ci = cz * cw + cx;
                if (region.lakeSurface[k] > cellSpill[ci]) {
                    cellSpill[ci] = region.lakeSurface[k];
                    cellGround[ci] = region.lakeGround[k];
                }
            }
        }

        for (int ci = 0; ci < cellLevel.length; ci++) {
            float spill = cellSpill[ci];
            if (spill == Float.NEGATIVE_INFINITY) {
                cellLevel[ci] = Float.NEGATIVE_INFINITY;
                continue;
            }
            float level = spill - freeboardMetres;
            // A coastal basin can sit lower than a full freeboard above the sea. Tuck the
            // water just below its rim rather than leaving a dry pan.
            if (level <= 0f) level = spill - 0.35f * metresPerBlock;
            cellLevel[ci] = level > 0f ? level : Float.NEGATIVE_INFINITY;
        }

        for (int cz = 0; cz < ch; cz++) {
            for (int cx = 0; cx < cw; cx++) {
                int ci = cz * cw + cx;
                float level = cellLevel[ci];
                if (level == Float.NEGATIVE_INFINITY) continue;
                int blockX = (cx0 + cx) * scale, blockZ = (cz0 + cz) * scale;

                // Measured from the spill, not the water level: the freeboard alone can
                // exceed a basin's whole depth, and against the surface every ordinary
                // basin would read as having none.
                float naturalBlocks = Math.max(0f,
                        (cellSpill[ci] - cellGround[ci]) / metresPerBlock);
                float taperRef = Math.max(0.25f, LAKE_TAPER_FRACTION * lakeDepthBlocks);
                float t = clamp01(naturalBlocks / taperRef);
                t = t * t * (3f - 2f * t);
                float wobble = 1f + LAKE_DEPTH_WOBBLE * LAKE_BED_NOISE.GetNoise(blockX, blockZ);
                // Only ever lowers, so a basin already deeper than this keeps its own floor.
                float depthBlocks = Math.max(LAKE_MIN_WATER_BLOCKS,
                        lakeDepthBlocks * t * wobble);
                float bedCap = level - depthBlocks * metresPerBlock;
                for (int dz = 0; dz < scale; dz++) {
                    int row = blockZ + dz - i1;
                    if (row < 0 || row >= height) continue;
                    for (int dx = 0; dx < scale; dx++) {
                        int col = blockX + dx - j1;
                        if (col < 0 || col >= width) continue;
                        int idx = row * width + col;
                        if (elev[idx] > bedCap) elev[idx] = bedCap;
                        if (waterFlat != null && waterFlat[idx] == Float.NEGATIVE_INFINITY) {
                            waterFlat[idx] = level;
                            // A basin otherwise keeps the biome of the ground it drowned,
                            // so it grows and spawns whatever dry land there would. Water
                            // brings its own biome, the same way a channel does.
                            if (biomeFlat != null) {
                                boolean frozen = temperature != null && temperature[idx] < 0f;
                                biomeFlat[idx] = frozen ? BiomeClassifier.FROZEN_LAKE
                                        : BiomeClassifier.LAKE;
                            }
                        }
                        // Slack class, so the bed pass gives lake floors sand and clay.
                        if (riverClassFlat != null && riverClassFlat[idx] == 0) riverClassFlat[idx] = 1;
                        // Severe cold closes a lake completely, same as the rivers.
                        if (riverClassFlat != null && temperature != null
                                && temperature[idx] < RiverCarver.FULL_FREEZE_C) {
                            riverClassFlat[idx] |= RiverCarver.FULLY_FROZEN_BIT;
                        }
                    }
                }
            }
        }

        // A lake sits a freeboard below its shore, and a one-block wall of water cannot be
        // climbed out of. Stretches of the shore ring are lowered flush with the surface, so
        // some of the bank is always a way out. Chosen by a slow noise rather than per-block
        // chance: coin-flipped blocks read as airbrush speckle, while a coherent field makes
        // whole reaches of bank shallow with steep shore between them.
        //
        // Runs only once every lake is placed, so a shore is never cut down at a column the
        // next cell was about to fill with water.
        for (int cz = 0; cz < ch; cz++) {
            for (int cx = 0; cx < cw; cx++) {
                float level = cellLevel[cz * cw + cx];
                if (level == Float.NEGATIVE_INFINITY) continue;
                int blockX = (cx0 + cx) * scale, blockZ = (cz0 + cz) * scale;
                for (int dz = -scale; dz < 2 * scale; dz++) {
                    int row = blockZ + dz - i1;
                    if (row < 0 || row >= height) continue;
                    for (int dx = -scale; dx < 2 * scale; dx++) {
                        int col = blockX + dx - j1;
                        if (col < 0 || col >= width) continue;
                        int idx = row * width + col;
                        if (waterFlat == null || waterFlat[idx] != Float.NEGATIVE_INFINITY) continue;
                        if (elev[idx] <= level || elev[idx] > level + 1.4f * metresPerBlock) continue;
                        float n = SHORE_EXIT_NOISE.GetNoise(blockX + dx, blockZ + dz);
                        if (n > SHORE_EXIT_NOISE_MIN) elev[idx] = level + 0.05f * metresPerBlock;
                    }
                }
            }
        }
    }

    /** Green corridors along rivers through dry country; see the constants above. */
    private static void stampRiparian(RiverRegions.RiverPath path, float[] pHalf, float[] pFade,
                                      short[] biomeFlat, float[] elev, float[] waterFlat,
                                      int i1, int j1, int height, int width,
                                      float metresPerBlock, float freeboardMetres) {
        int len = path.blockX.length;
        for (int k = 0; k < len; k++) {
            int bx = path.blockX[k], bz = path.blockZ[k];
            float wn = GREEN_NOISE.GetNoise(bx, bz);
            float sizeT = Math.min(1f, pHalf[k] / RIPARIAN_SIZE_REF_HALF);
            float beyond = (RIPARIAN_BASE_BLOCKS + RIPARIAN_SIZE_BONUS_BLOCKS * sizeT
                    + RIPARIAN_WOBBLE_BLOCKS * wn) * pFade[k];
            if (beyond <= 0f) continue;
            float reach = pHalf[k] + beyond;

            // Points outside the tile still green the part of their disc that overlaps it.
            int R = (int) Math.ceil(reach);
            int col = bx - j1, row = bz - i1;
            if (col < -R || col >= width + R || row < -R || row >= height + R) continue;

            float waterSurf = path.ground[k] - freeboardMetres;
            float maxBank = (RIPARIAN_MAX_BANK_BLOCKS + RIPARIAN_BANK_WOBBLE_BLOCKS * wn)
                    * metresPerBlock;

            for (int dr = -R; dr <= R; dr++) {
                int r = row + dr;
                if (r < 0 || r >= height) continue;
                for (int dc = -R; dc <= R; dc++) {
                    int c = col + dc;
                    if (c < 0 || c >= width) continue;
                    if (dr * dr + dc * dc > reach * reach) continue;
                    int idx = r * width + c;
                    if (waterFlat[idx] != Float.NEGATIVE_INFINITY) continue;
                    short green = riparianFor(biomeFlat[idx]);
                    if (green == 0) continue;
                    float bank = elev[idx] - waterSurf;
                    if (bank < 0f || bank > maxBank) continue;
                    biomeFlat[idx] = green;
                }
            }
        }
    }

    private static short riparianFor(short biome) {
        switch (biome) {
            case BiomeClassifier.DESERT:
            case BiomeClassifier.BADLANDS:
                return BiomeClassifier.SAVANNA;
            case BiomeClassifier.SAVANNA:
                return BiomeClassifier.FOREST_SPARSE;
            default:
                return 0;
        }
    }

    /** Marshy delta around a big slack ocean mouth; see the constants above. */
    private static void stampDelta(RiverRegions.RiverPath path, float[] pHalf, float[] pSteep,
                                   short[] biomeFlat, float[] elev, float[] waterFlat,
                                   float[] temperature, int i1, int j1, int height, int width,
                                   float metresPerBlock) {
        int len = path.blockX.length;
        if (path.ground[len - 1] >= MOUTH_GROUND_BLOCKS * metresPerBlock) return;
        if (pHalf[len - 1] < DELTA_MIN_HALF_BLOCKS) return;

        int tail = Math.min(len, DELTA_STEEP_TAIL_BLOCKS);
        float steepSum = 0f;
        for (int k = len - tail; k < len; k++) steepSum += pSteep[k];
        if (steepSum / tail > DELTA_MAX_STEEP) return;

        int bx = path.blockX[len - 1], bz = path.blockZ[len - 1];
        float wn = GREEN_NOISE.GetNoise(bx, bz);
        float reach = Math.min(DELTA_MAX_REACH_BLOCKS,
                DELTA_REACH_FACTOR * pHalf[len - 1] * (1f + 0.3f * wn));
        int R = (int) Math.ceil(reach);
        int col = bx - j1, row = bz - i1;
        if (col < -R || col >= width + R || row < -R || row >= height + R) return;

        float maxElev = DELTA_MAX_ELEV_BLOCKS * metresPerBlock;
        for (int dr = -R; dr <= R; dr++) {
            int r = row + dr;
            if (r < 0 || r >= height) continue;
            for (int dc = -R; dc <= R; dc++) {
                int c = col + dc;
                if (c < 0 || c >= width) continue;
                int idx = r * width + c;
                float edge = reach * (1f + 0.2f * EDGE_NOISE.GetNoise(j1 + c, i1 + r));
                if (dr * dr + dc * dc > edge * edge) continue;
                if (waterFlat[idx] != Float.NEGATIVE_INFINITY) continue;
                float e = elev[idx];
                if (e < 0f || e > maxElev) continue;
                if (!deltaReplaceable(biomeFlat[idx])) continue;
                float temp = temperature != null ? temperature[idx] : 10f;
                if (temp < DELTA_MIN_TEMP_C) continue;
                biomeFlat[idx] = temp >= DELTA_MANGROVE_TEMP_C
                        ? BiomeClassifier.MANGROVE_SWAMP : BiomeClassifier.SWAMP;
            }
        }
    }

    private static boolean deltaReplaceable(short biome) {
        if (biome == BiomeClassifier.RIVER || biome == BiomeClassifier.FROZEN_RIVER
                || biome == BiomeClassifier.SWAMP || biome == BiomeClassifier.MANGROVE_SWAMP
                || biome == BiomeClassifier.MUSHROOM_FIELDS) {
            return false;
        }
        // Everything on the flats can silt over, beaches included; the sea keeps its ids.
        return biome < 41 || biome > 49;
    }

    /** Swamp ring where warm, wet lowland meets a lake near its own level. */
    private static void stampLakeFringes(List<RiverRegions.Region> regions, float[] elev,
                                         short[] biomeFlat, float[] climate, float[] waterFlat,
                                         int i1, int j1, int height, int width,
                                         float metresPerBlock, float freeboardMetres, int scale) {
        int n = height * width;
        // Needs both the leading temperature block and the precip block two channels in.
        if (climate == null || climate.length < 3 * n) return;

        float maxRise = LAKE_FRINGE_MAX_RISE_BLOCKS * metresPerBlock;
        for (RiverRegions.Region region : regions) {
            for (int k = 0; k < region.lakeSurface.length; k++) {
                // Same level derivation as stampLakes, so the ring sits on its waterline.
                float spill = region.lakeSurface[k];
                float level = spill - freeboardMetres;
                if (level <= 0f) level = spill - 0.35f * metresPerBlock;
                if (level <= 0f) continue;

                int bx = region.lakeBlockX[k], bz = region.lakeBlockZ[k];
                float ring = LAKE_FRINGE_BASE_BLOCKS
                        + LAKE_FRINGE_WOBBLE_BLOCKS * GREEN_NOISE.GetNoise(bx, bz);
                int R = (int) Math.ceil(ring);

                for (int dz = -R; dz < scale + R; dz++) {
                    int row = bz + dz - i1;
                    if (row < 0 || row >= height) continue;
                    for (int dx = -R; dx < scale + R; dx++) {
                        int col = bx + dx - j1;
                        if (col < 0 || col >= width) continue;
                        int idx = row * width + col;
                        if (waterFlat[idx] != Float.NEGATIVE_INFINITY) continue;
                        float rise = elev[idx] - level;
                        if (rise < 0f || rise > maxRise) continue;
                        if (climate[idx] < LAKE_FRINGE_MIN_TEMP_C) continue;
                        if (climate[2 * n + idx] < LAKE_FRINGE_MIN_PRECIP_MM) continue;
                        if (!fringeReplaceable(biomeFlat[idx])) continue;
                        biomeFlat[idx] = BiomeClassifier.SWAMP;
                    }
                }
            }
        }
    }

    private static boolean fringeReplaceable(short biome) {
        switch (biome) {
            case BiomeClassifier.PLAINS:
            case BiomeClassifier.FOREST:
            case BiomeClassifier.FOREST_SPARSE:
            case BiomeClassifier.BIRCH_FOREST:
            case BiomeClassifier.DARK_FOREST:
            case BiomeClassifier.MEADOW:
            case BiomeClassifier.JUNGLE:
            case BiomeClassifier.SPARSE_JUNGLE:
            case BiomeClassifier.SAVANNA:
                return true;
            default:
                return false;
        }
    }

    /**
     * A lake bed deepens away from its shore instead of dropping to a flat pan at the
     * waterline. How deep the basin already was stands in for distance from the bank —
     * a rim is shallow precisely because the ground barely dips there — so the
     * deepening follows the shape the terrain has rather than a ring measured out from
     * the edge. Measured, most cells sit under a block of natural water and the
     * deepest tenth under five, so the taper reaches full depth at a couple of blocks
     * of natural basin. Slow noise on top keeps the floor off a machined curve.
     */
    private static final float LAKE_TAPER_FRACTION = 0.4f;
    private static final float LAKE_DEPTH_WOBBLE = 0.25f;
    /**
     * Shallowest a lake may taper to and still hold water. Depth is metres here but
     * water is placed in whole blocks, and a claim thinner than a block can land inside
     * the block below it — the filler then finds solid ground where it expected air and
     * writes nothing, leaving dry lake bed inside the waterline. The taper has to stop
     * above that, or every lake gains a dry rim.
     */
    private static final float LAKE_MIN_WATER_BLOCKS = 1.25f;
    private static final FastNoiseLite LAKE_BED_NOISE = makeFnl(0x1A6E5, 1f / 60f, 2, 2f, 0.5f);

    /** Picks which reaches of a lake shore are climbable; world-sampled so tiles agree. */
    private static final FastNoiseLite SHORE_EXIT_NOISE = makeFnl(0xE5CA, 1f / 28f, 2, 2f, 0.5f);
    private static final float SHORE_EXIT_NOISE_MIN = 0.12f;

    /**
     * Coherent relief for every wetted floor. The carver stamps flat discs along the path,
     * and where they overlap the bed steps in concentric arcs that read as brush strokes
     * from the surface. Sampled at world coordinates so tiles agree.
     *
     * <p>The floor is held a full block under the surface. Water is placed in whole
     * blocks against a surface measured in metres, and a claim thinner than a block can
     * round into the block below it: the filler then finds solid ground where it
     * expected air and writes nothing, so a cell the carver called wet renders as dry
     * bed. A whole block of clearance is what makes that impossible.
     */
    private static final FastNoiseLite BED_NOISE = makeFnl(0x52BED, 0.05f, 3, 2f, 0.5f);
    /** Waterline wobble, sampled at world coordinates so every tile draws the same edge. */
    private static final FastNoiseLite EDGE_NOISE = makeFnl(0x51DE5, 0.08f, 2, 2f, 0.5f);

    private static void featherBeds(float[] elev, float[] waterFlat,
                                    int i1, int j1, int height, int width,
                                    float metresPerBlock, float reliefBlocks) {
        if (waterFlat == null) return;
        float amp = reliefBlocks * metresPerBlock;
        float clearance = 1.05f * metresPerBlock;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int idx = row * width + col;
                float surface = waterFlat[idx];
                // At or below sea level the surface claim is the ocean's business, and
                // clamping against it would gouge the shelf into a trench at the mouth.
                if (surface == Float.NEGATIVE_INFINITY || surface <= 0f) continue;
                float shifted = elev[idx] + BED_NOISE.GetNoise(j1 + col, i1 + row) * amp;
                elev[idx] = Math.min(shifted, surface - clearance);
            }
        }
    }

    /** In-place moving average with a window that shrinks symmetrically at the ends. */
    private static void boxSmooth(float[] values, int halfWindow) {
        int n = values.length;
        if (n < 3) return;
        float[] out = new float[n];
        for (int k = 0; k < n; k++) {
            int r = Math.min(halfWindow, Math.min(k, n - 1 - k));
            float sum = 0f;
            for (int t = k - r; t <= k + r; t++) sum += values[t];
            out[k] = sum / (2 * r + 1);
        }
        System.arraycopy(out, 0, values, 0, n);
    }

    /**
     * Downstream fall in metres per block around a path point. Read over a window rather than
     * between neighbours: consecutive points are interpolated between analysis cells, so an
     * adjacent pair often carries no gradient at all.
     */
    private static float gradientAt(RiverRegions.RiverPath path, int k) {
        int last = path.ground.length - 1;
        int lo = Math.max(0, k - RIVER_GRADIENT_WINDOW);
        int hi = Math.min(last, k + RIVER_GRADIENT_WINDOW);
        int span = hi - lo;
        if (span <= 0) return 0f;
        return Math.max(0f, (path.ground[lo] - path.ground[hi]) / span);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Marks every currently wet cell as settled, so later channels cannot restamp it. */
    private static void lockClaims(float[] waterFlat, float[] claimDist) {
        for (int i = 0; i < claimDist.length; i++) {
            if (waterFlat[i] != Float.NEGATIVE_INFINITY) claimDist[i] = -1f;
        }
    }

    private static void carveRun(float[] elev, short[] biomeFlat, float[] temperature,
                                 float[] waterFlat, float[] claimDist, byte[] riverClassFlat,
                                 float[] edgeField, RiverParameters params, int height, int width,
                                 int[] rows, int[] cols, float[] halfWidths, float[] depths,
                                 float[] surfaces, float[] steeps, float[] fades,
                                 float metresPerBlock, int count) {
        if (count < 2) return;
        RiverCarver.carveChannel(elev, biomeFlat, temperature, waterFlat, claimDist,
                riverClassFlat, height, width,
                Arrays.copyOf(rows, count), Arrays.copyOf(cols, count),
                Arrays.copyOf(halfWidths, count),
                Arrays.copyOf(depths, count), Arrays.copyOf(surfaces, count),
                Arrays.copyOf(steeps, count), Arrays.copyOf(fades, count), edgeField,
                params.freeboardBlocks, params.edgeWobbleBlocks, metresPerBlock,
                BiomeClassifier.RIVER, BiomeClassifier.FROZEN_RIVER);
    }

    /**
     * Overwrites the land of chosen remote islands with mushroom fields, shoreline
     * included: a mushroom island runs mycelium to the waterline, which is why this
     * comes after the beach pass. The verdict lives at coarse resolution, but an
     * island's native coast can spill a little past its coarse cells, so land in the
     * one-cell ring around a member cell is stamped too — remoteness guarantees that
     * ring holds no foreign land to catch by mistake.
     */
    private void stampMushroomIslands(float[] elev, short[] biomeFlat,
                                      int i1, int j1, int height, int width, int scale) {
        int cellBlocks = MushroomIslands.CELL_NATIVE * scale;
        int cr0 = Math.floorDiv(i1, cellBlocks) - 1;
        int cr1 = Math.floorDiv(i1 + height - 1, cellBlocks) + 1;
        int cc0 = Math.floorDiv(j1, cellBlocks) - 1;
        int cc1 = Math.floorDiv(j1 + width - 1, cellBlocks) + 1;
        int gh = cr1 - cr0 + 1, gw = cc1 - cc0 + 1;

        boolean[] member = new boolean[gh * gw];
        boolean any = false;
        for (int r = 0; r < gh; r++) {
            for (int c = 0; c < gw; c++) {
                member[r * gw + c] = MushroomIslands.isMushroom(cr0 + r, cc0 + c, pipeline, instanceSeed);
                any |= member[r * gw + c];
            }
        }
        if (!any) return;

        // Dilate by one cell for the native fringe, then stamp the tile's land.
        boolean[] near = new boolean[gh * gw];
        for (int r = 0; r < gh; r++) {
            for (int c = 0; c < gw; c++) {
                for (int dr = -1; dr <= 1 && !near[r * gw + c]; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int nr = r + dr, nc = c + dc;
                        if (nr < 0 || nr >= gh || nc < 0 || nc >= gw) continue;
                        if (member[nr * gw + nc]) {
                            near[r * gw + c] = true;
                            break;
                        }
                    }
                }
            }
        }
        for (int r = 0; r < height; r++) {
            int pr = Math.floorDiv(i1 + r, cellBlocks) - cr0;
            for (int c = 0; c < width; c++) {
                int idx = r * width + c;
                if (elev[idx] <= 0f) continue;
                int pc = Math.floorDiv(j1 + c, cellBlocks) - cc0;
                if (near[pr * gw + pc]) biomeFlat[idx] = BiomeClassifier.MUSHROOM_FIELDS;
            }
        }
    }

    /** Water surface for a column the rivers never reached. */
    private static float[] newWaterField(int n) {
        float[] water = new float[n];
        Arrays.fill(water, Float.NEGATIVE_INFINITY);
        return water;
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat,
                                                    float[] waterFlat, byte[] riverClassFlat,
                                                    int H, int W) {
        // Read once rather than per cell: every rule above works in the mod's own
        // vocabulary, and only the ids leaving here answer to the world's setting.
        boolean additionalBiomes = WorldScaleManager.hasAdditionalBiomes();
        short[][] heightmap = new short[H][W];
        short[][] biomeIds  = new short[H][W];
        short[][] waterLevel = new short[H][W];
        byte[][] riverClass = new byte[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevFlat[idx];
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c]  = additionalBiomes ? biomeFlat[idx]
                        : BiomeClassifier.vanillaEquivalent(biomeFlat[idx]);
                riverClass[r][c] = riverClassFlat == null ? 0 : riverClassFlat[idx];

                // Sea level is 0 m, so a surface at or below it is the ocean's to fill.
                float w = waterFlat == null ? Float.NEGATIVE_INFINITY : waterFlat[idx];
                waterLevel[r][c] = w > 0f
                        ? (short) Math.min(32767, (int) Math.floor(w))
                        : HeightmapData.NO_WATER;
            }
        return new HeightmapData(heightmap, biomeIds, waterLevel, riverClass, W, H);
    }
}
