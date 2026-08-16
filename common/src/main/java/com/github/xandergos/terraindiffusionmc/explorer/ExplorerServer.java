package com.github.xandergos.terraindiffusionmc.explorer;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.river.CoarseHydrology;
import com.github.xandergos.terraindiffusionmc.pipeline.river.RiverNetwork;
import com.github.xandergos.terraindiffusionmc.pipeline.river.RiverRegions;
import com.github.xandergos.terraindiffusionmc.world.LatitudeParameters;
import com.github.xandergos.terraindiffusionmc.world.RiverMode;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded terrain explorer HTTP server. Java port of
 * terrain_diffusion/inference/explorer/server.py.
 *
 * <p>Bound to 127.0.0.1 only. All pipeline calls are routed through
 * LocalTerrainProvider's inference thread for thread safety.
 */
public final class ExplorerServer {

    private static final Logger LOG = LoggerFactory.getLogger(ExplorerServer.class);
    private static final Gson GSON = new Gson();

    private static final String[] CHANNEL_NAMES = {"Elev", "p5", "Temp", "T std", "Precip", "Precip CV"};

    /** Smallest basin, in coarse cells of rainfall, that carries a river at all. */
    private static final float MIN_BASIN_CELLS = 12f;
    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static volatile HttpServer SERVER;
    private static volatile int SERVER_PORT = -1;

    private ExplorerServer() {}

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Start the server if not already running. Returns the port.
     */
    public static synchronized int startIfNotRunning() throws IOException {
        if (SERVER != null) return SERVER_PORT;
        int port = TerrainDiffusionConfig.explorerPort();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", port);
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/", ExplorerServer::handleRoot);
        server.createContext("/api/status", ExplorerServer::handleStatus);
        server.createContext("/api/seed", ExplorerServer::handleSeed);
        server.createContext("/api/new_seed", ExplorerServer::handleNewSeed);
        server.createContext("/api/coarse.png", ExplorerServer::handleCoarsePng);
        server.createContext("/api/coarse_data.json", ExplorerServer::handleCoarseData);
        server.createContext("/api/coarse_stats", ExplorerServer::handleCoarseStats);
        server.createContext("/api/rivers.png", ExplorerServer::handleRiversPng);
        server.createContext("/api/detail.png", ExplorerServer::handleDetailPng);
        server.createContext("/api/detail_raw", ExplorerServer::handleDetailRaw);
        server.createContext("/api/biome_names.json", ExplorerServer::handleBiomeNames);
        // Single-thread executor matches Python's threaded=False
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "terrain-explorer-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        SERVER = server;
        SERVER_PORT = port;
        LOG.info("Terrain explorer started at http://127.0.0.1:{}", port);
        return port;
    }

    public static synchronized void stop() {
        if (SERVER != null) {
            SERVER.stop(0);
            SERVER = null;
            SERVER_PORT = -1;
            LOG.info("Terrain explorer stopped.");
        }
    }

    public static boolean isRunning() {
        return SERVER != null;
    }

    public static int getPort() {
        return SERVER_PORT;
    }

    // =========================================================================
    // Handlers — direct port of server.py routes
    // =========================================================================

    /** GET / → serve index.html */
    private static void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try (InputStream in = ExplorerServer.class.getResourceAsStream(
                "/assets/terrain-diffusion-mc/explorer/index.html")) {
            if (in == null) {
                sendError(ex, 404, "index.html not found");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        } finally {
            ex.close();
        }
    }

    /** GET /api/status → {seed, channels, native_resolution, scale} */
    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
            resp.put("channels", Arrays.asList(CHANNEL_NAMES));
            resp.put("native_resolution", NATIVE_RESOLUTION);
            resp.put("scale", WorldScaleManager.getCurrentScale());
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /** POST /api/seed body={seed:int} → {seed} */
    private static void handleSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            String body = readBody(ex, 1024);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = GSON.fromJson(body, Map.class);
            if (!data.containsKey("seed")) { sendError(ex, 400, "seed required"); return; }
            long newSeed = ((Number) data.get("seed")).longValue();
            LocalTerrainProvider.changeSeedFromExplorer(newSeed);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** POST /api/new_seed → {seed} */
    private static void handleNewSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            long newSeed = LocalTerrainProvider.generateRandomSeedFromExplorer();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(newSeed));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /**
     * GET /api/coarse.png — port of coarse_png() + _coarse_channel().
     * Query params: channel, ci0, ci1, cj0, cj1, ch{0,2,3,4,5}_min/max
     * Response headers: X-Vmin, X-Vmax
     */
    private static void handleCoarsePng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int channel = getInt(q, "channel", 0);
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);

            float[] data = coarseChannel(ci0, ci1, cj0, cj1, channel);
            int H = ci1 - ci0, W = cj1 - cj0;

            // Precipitation: log1p(max(v,0)) before normalizing (matches Python)
            float[] display = data.clone();
            if (channel == 4) {
                for (int i = 0; i < display.length; i++)
                    display[i] = (float) Math.log1p(Math.max(0f, display[i]));
            }
            float vmin = nanMin(display), vmax = nanMax(display);
            if (vmax == vmin) vmax = vmin + 1f;

            // Viridis colormap
            float[][] rgba = new float[4][H * W];
            for (int i = 0; i < H * W; i++) {
                float t = (display[i] - vmin) / (vmax - vmin);
                float[] rgb = Colormaps.viridis(clamp01(t));
                rgba[0][i] = rgb[0]; rgba[1][i] = rgb[1]; rgba[2][i] = rgb[2]; rgba[3][i] = 1f;
            }

            // Optional filter: dim non-matching pixels to 30% (matches Python rgba[~mask, :3] *= 0.3)
            int[] filterChs = {0, 2, 3, 4, 5};
            boolean filterActive = false;
            for (int ch : filterChs) {
                if (q.containsKey("ch" + ch + "_min") || q.containsKey("ch" + ch + "_max")) {
                    filterActive = true; break;
                }
            }
            if (filterActive) {
                boolean[] mask = new boolean[H * W];
                Arrays.fill(mask, true);
                for (int ch : filterChs) {
                    Float lo = getFloat(q, "ch" + ch + "_min");
                    Float hi = getFloat(q, "ch" + ch + "_max");
                    if (lo == null && hi == null) continue;
                    float[] chData = coarseChannel(ci0, ci1, cj0, cj1, ch);
                    for (int i = 0; i < H * W; i++) {
                        if (lo != null && chData[i] < lo) mask[i] = false;
                        if (hi != null && chData[i] > hi) mask[i] = false;
                    }
                }
                for (int i = 0; i < H * W; i++) {
                    if (!mask[i]) {
                        rgba[0][i] *= 0.3f; rgba[1][i] *= 0.3f; rgba[2][i] *= 0.3f;
                    }
                }
            }

            byte[] png = toPng(rgba, H, W);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-Vmin", String.format("%.3f", vmin));
            ex.getResponseHeaders().set("X-Vmax", String.format("%.3f", vmax));
            ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Vmin, X-Vmax");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            LOG.error("coarse.png error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * GET /api/coarse_data.json — port of coarse_data().
     * Returns all 6 channel values as 2D arrays for client-side hover.
     */
    /**
     * Renders the coarse drainage network: shaded land, lakes, and rivers coloured by
     * discharge. Debug view for the river work.
     *
     * <p>{@code river_pct} is the share of its own basin's outflow a cell must carry to
     * count as river.
     */
    private static void handleRiversPng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);
            Float pct = getFloat(q, "river_pct");
            float riverPct = pct == null ? 2f : Math.max(0.01f, Math.min(100f, pct));

            int H = ci1 - ci0, W = cj1 - cj0;
            float[] elev = coarseChannel(ci0, ci1, cj0, cj1, 0);
            float[] precip = coarseChannel(ci0, ci1, cj0, cj1, 4);

            CoarseHydrology.Drainage d = CoarseHydrology.analyse(elev, precip, H, W);

            float fraction = riverPct / 100f;
            float maxDischarge = 0f, precipSum = 0f;
            int landCount = 0;
            float landLo = Float.MAX_VALUE, landHi = -Float.MAX_VALUE;
            for (int i = 0; i < H * W; i++) {
                if (d.ocean[i]) continue;
                landCount++;
                precipSum += Math.max(0f, precip[i]);
                maxDischarge = Math.max(maxDischarge, d.discharge[i]);
                landLo = Math.min(landLo, elev[i]);
                landHi = Math.max(landHi, elev[i]);
            }
            if (landHi <= landLo) landHi = landLo + 1f;

            // Whether a basin holds a river at all gates on its total outflow; how far up
            // it the river runs is the fraction. Keeping them separate stops islets from
            // sprouting rivers while still letting every real island have one.
            float meanPrecip = landCount > 0 ? precipSum / landCount : 0f;
            float minBasinOutflow = MIN_BASIN_CELLS * meanPrecip;

            // Strahler order separates headwater from trunk more legibly than raw discharge.
            int[] order = new int[H * W];
            int maxOrder = 1;
            for (RiverNetwork.Reach reach : RiverNetwork.extract(d, minBasinOutflow, fraction)) {
                order[reach.from] = reach.order;
                maxOrder = Math.max(maxOrder, reach.order);
            }

            float[][] rgba = new float[4][H * W];
            int riverCells = 0, lakeCells = 0;

            for (int i = 0; i < H * W; i++) {
                float r, g, b;
                boolean isRiver = order[i] > 0;
                if (d.ocean[i]) {
                    r = 0.05f; g = 0.11f; b = 0.24f;
                } else if (d.lake[i]) {
                    r = 0.16f; g = 0.44f; b = 0.74f;
                    lakeCells++;
                } else if (isRiver) {
                    float t = maxOrder > 1 ? (order[i] - 1f) / (maxOrder - 1f) : 1f;
                    r = 0.25f + 0.75f * t;
                    g = 0.70f + 0.30f * t;
                    b = 0.95f + 0.05f * t;
                    riverCells++;
                } else {
                    float t = clamp01((elev[i] - landLo) / (landHi - landLo));
                    r = 0.22f + 0.62f * t; g = 0.24f + 0.60f * t; b = 0.20f + 0.55f * t;
                }
                rgba[0][i] = r; rgba[1][i] = g; rgba[2][i] = b; rgba[3][i] = 1f;
            }

            byte[] png = toPng(rgba, H, W);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-River-Threshold", String.format("%.1f", minBasinOutflow));
            ex.getResponseHeaders().set("X-River-Max", String.format("%.1f", maxDischarge));
            ex.getResponseHeaders().set("X-River-Cells", String.valueOf(riverCells));
            ex.getResponseHeaders().set("X-Lake-Cells", String.valueOf(lakeCells));
            ex.getResponseHeaders().set("Access-Control-Expose-Headers",
                    "X-River-Threshold, X-River-Max, X-River-Cells, X-Lake-Cells");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            LOG.error("rivers.png error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    private static void handleCoarseData(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);
            int H = ci1 - ci0, W = cj1 - cj0;

            Map<String, Object> channels = new LinkedHashMap<>();
            for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                float[] flat = coarseChannel(ci0, ci1, cj0, cj1, ch);
                channels.put(CHANNEL_NAMES[ch], roundedGrid(flat, H, W, 2));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ci0", ci0); resp.put("ci1", ci1);
            resp.put("cj0", cj0); resp.put("cj1", cj1);
            resp.put("channels", channels);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** GET /api/coarse_stats — port of coarse_stats(). */
    private static void handleCoarseStats(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);

            Map<String, Object> stats = new LinkedHashMap<>();
            for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                float[] data = coarseChannel(ci0, ci1, cj0, cj1, ch);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", CHANNEL_NAMES[ch]);
                entry.put("min", round3(nanMin(data)));
                entry.put("max", round3(nanMax(data)));
                stats.put(String.valueOf(ch), entry);
            }
            sendJson(ex, 200, stats);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /**
     * Regions whose analysis reaches a native-pixel window, asked for exactly as world
     * generation asks, so a map can only ever show water the generator will also place.
     */
    private static java.util.List<RiverRegions.Region> regionsFor(int i0, int j0, int H, int W)
            throws Exception {
        int scale = WorldScaleManager.getCurrentScale();
        RiverRegions.Size size = WorldScaleManager.getRiverMode() == RiverMode.FAST
                ? RiverRegions.Size.SMALL : RiverRegions.Size.LARGE;
        return RiverRegions.forBlockWindow(
                i0 * scale, j0 * scale, (i0 + H) * scale, (j0 + W) * scale,
                scale, size, WorldScaleManager.getRiverParameters(),
                (a, b, c, d) -> LocalTerrainProvider.getPipelineData(a, b, c, d, true));
    }

    /**
     * Marks lake cells as water in a biome grid.
     *
     * <p>A lake keeps whatever biome its ground had before the water arrived, so a biome
     * map drawn straight from the ids shows basins as savanna or forest while the rivers
     * running into them are plainly blue. Painting the cells here rather than colouring
     * them afterwards means they pick up the same relief shading as everything else.
     */
    private static void overlayLakeBiomes(short[] ids, int H, int W, int i0, int j0)
            throws Exception {
        if (WorldScaleManager.getRiverMode() == RiverMode.OFF) return;
        int scale = WorldScaleManager.getCurrentScale();
        for (RiverRegions.Region region : regionsFor(i0, j0, H, W)) {
            for (int k = 0; k < region.lakeSurface.length; k++) {
                int r = Math.floorDiv(region.lakeBlockZ[k], scale) - i0;
                int c = Math.floorDiv(region.lakeBlockX[k], scale) - j0;
                if (r < 0 || r >= H || c < 0 || c >= W) continue;
                ids[r * W + c] = (short) BiomeColors.WATER_ID;
            }
        }
    }

    /**
     * Draws the rivers and lakes the generator will actually place: the same cached
     * region analysis, selection, and culling as world generation, so what this shows is
     * what a teleport finds. Any independent extraction here would happily display
     * networks the generator rejects.
     */
    private static void overlayRivers(float[][] rgba, int H, int W, int i0, int j0)
            throws Exception {
        if (WorldScaleManager.getRiverMode() == RiverMode.OFF) return;
        int scale = WorldScaleManager.getCurrentScale();
        java.util.List<RiverRegions.Region> regions = regionsFor(i0, j0, H, W);

        // Lake and channel share one colour, because on the ground they are one body of
        // water: a river running into a basin does not change shade at the shoreline.
        int packed = BiomeColors.rgb(BiomeColors.WATER_ID);
        float pr = ((packed >> 16) & 0xFF) / 255f;
        float pg = ((packed >> 8) & 0xFF) / 255f;
        float pb = (packed & 0xFF) / 255f;

        for (RiverRegions.Region region : regions) {
            for (int k = 0; k < region.lakeSurface.length; k++) {
                int r = Math.floorDiv(region.lakeBlockZ[k], scale) - i0;
                int c = Math.floorDiv(region.lakeBlockX[k], scale) - j0;
                if (r < 0 || r >= H || c < 0 || c >= W) continue;
                int idx = r * W + c;
                rgba[0][idx] = pr; rgba[1][idx] = pg; rgba[2][idx] = pb;
            }
        }

        for (RiverRegions.Region region : regions) {
            for (RiverRegions.RiverPath path : region.paths) {
                for (int k = 0; k < path.blockX.length; k++) {
                    if (path.submerged[k]) continue;
                    int r = Math.floorDiv(path.blockZ[k], scale) - i0;
                    int c = Math.floorDiv(path.blockX[k], scale) - j0;
                    if (r < -32 || r >= H + 32 || c < -32 || c >= W + 32) continue;
                    int rad = Math.round(
                            LocalTerrainProvider.baseRiverHalfWidthBlocks(path.flow[k]) / scale);
                    for (int dr = -rad; dr <= rad; dr++) {
                        int rr = r + dr;
                        if (rr < 0 || rr >= H) continue;
                        for (int dc = -rad; dc <= rad; dc++) {
                            int cc = c + dc;
                            if (cc < 0 || cc >= W) continue;
                            if (dr * dr + dc * dc > rad * rad + 1) continue;
                            int idx = rr * W + cc;
                            rgba[0][idx] = pr; rgba[1][idx] = pg; rgba[2][idx] = pb;
                        }
                    }
                }
            }
        }
    }

    /**
     * Biome ids across a native-pixel window, sampled from the very tiles the game
     * generates rather than re-derived here. That costs a little more than classifying
     * the window directly, and is the whole point: rivers, shorelines, deltas and island
     * mycelium are laid down by later passes, so anything short of the real tile shows a
     * map the player will never see. The tiles are ordinary cache entries, shared with
     * generation and sized like it, instead of one window-shaped block of memory.
     *
     * @param size output side in native pixels; one sample per pixel
     */
    private static short[] biomeGrid(int nativeI0, int nativeJ0, int size) {
        int scale = WorldScaleManager.getCurrentScale();
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();

        short[] out = new short[size * size];
        int blockI0 = nativeI0 * scale, blockJ0 = nativeJ0 * scale;
        int span = size * scale;

        int ti0 = blockI0 >> tileShift, ti1 = (blockI0 + span - 1) >> tileShift;
        int tj0 = blockJ0 >> tileShift, tj1 = (blockJ0 + span - 1) >> tileShift;

        for (int ti = ti0; ti <= ti1; ti++) {
            for (int tj = tj0; tj <= tj1; tj++) {
                int bi = ti << tileShift, bj = tj << tileShift;
                LocalTerrainProvider.HeightmapData data =
                        provider.fetchHeightmap(bi, bj, bi + tileSize, bj + tileSize);
                if (data == null || data.biomeIds == null) continue;

                // Output rows whose sampled block lands inside this tile, so every
                // pixel is visited exactly once across all tiles.
                int rLo = Math.max(0, ceilDiv(bi - blockI0, scale));
                int rHi = Math.min(size, ceilDiv(bi + tileSize - blockI0, scale));
                int cLo = Math.max(0, ceilDiv(bj - blockJ0, scale));
                int cHi = Math.min(size, ceilDiv(bj + tileSize - blockJ0, scale));
                for (int r = rLo; r < rHi; r++) {
                    int localZ = blockI0 + r * scale - bi;
                    for (int c = cLo; c < cHi; c++) {
                        int localX = blockJ0 + c * scale - bj;
                        out[r * size + c] = data.biomeIds[localZ][localX];
                    }
                }
            }
        }
        return out;
    }

    private static int ceilDiv(int a, int b) {
        return -Math.floorDiv(-a, b);
    }

    /** GET /api/biome_names.json → {id: name} for the hover readout. */
    private static void handleBiomeNames(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> resp = new LinkedHashMap<>();
            BiomeColors.names().forEach((id, name) -> resp.put(String.valueOf(id), name));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /**
     * GET /api/detail.png — port of detail_png().
     * Query params: ci, cj, detail_size, pan_i, pan_j, mode
     */
    private static void handleDetailPng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci         = getInt(q, "ci", 0);
            int cj         = getInt(q, "cj", 0);
            int detailSize = getInt(q, "detail_size", 1024);
            int panI       = getInt(q, "pan_i", 0);
            int panJ       = getInt(q, "pan_j", 0);
            String mode    = q.getOrDefault("mode", "relief");

            int centerI = ci * 256 + panI;
            int centerJ = cj * 256 + panJ;
            int half    = detailSize / 2;

            boolean needsClimate = mode.equals("temperature") || mode.equals("rivers");
            float[][] out = LocalTerrainProvider.getPipelineData(
                    centerI - half, centerJ - half, centerI + half, centerJ + half,
                    needsClimate);
            float[] elevFlat  = out[0];
            float[] climate   = out[1];
            int H = detailSize, W = detailSize;

            float[][] rgba;
            if (mode.equals("elevation")) {
                float vmin = nanMin(elevFlat), vmax = nanMax(elevFlat);
                if (vmax == vmin) vmax = vmin + 1f;
                rgba = applyColormap1D(elevFlat, H, W, vmin, vmax, "terrain");
            } else if (mode.equals("temperature") && climate != null) {
                // climate[0] = temperature channel (H*W floats)
                float[] temp = Arrays.copyOfRange(climate, 0, H * W);
                float vmin = nanMin(temp), vmax = nanMax(temp);
                if (vmax == vmin) vmax = vmin + 1f;
                rgba = applyColormap1D(temp, H, W, vmin, vmax, "rdbu_r");
            } else {
                // relief mode (default), and the base layer the river overlay draws onto
                float[][] reliefRgb = ReliefMap.getReliefMap(elevFlat, H, W, 90.0);
                rgba = new float[4][H * W];
                for (int i = 0; i < H * W; i++) {
                    rgba[0][i] = reliefRgb[0][i];
                    rgba[1][i] = reliefRgb[1][i];
                    rgba[2][i] = reliefRgb[2][i];
                    rgba[3][i] = 1f;
                }
                if (mode.equals("rivers")) {
                    overlayRivers(rgba, H, W, centerI - half, centerJ - half);
                } else if (mode.equals("biomes")) {
                    short[] ids = biomeGrid(centerI - half, centerJ - half, H);
                    overlayLakeBiomes(ids, H, W, centerI - half, centerJ - half);
                    shadeByRelief(rgba, reliefRgb, ids, H, W);
                }
            }

            byte[] png = toPng(rgba, H, W);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            LOG.error("detail.png error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * GET /api/detail_raw — port of detail_raw().
     * Binary: int16-LE elevation (H*W*2 bytes) + float32-LE temperature (H*W*4 bytes).
     * Headers: X-Height, X-Width, X-Has-Temp.
     */
    private static void handleDetailRaw(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci         = getInt(q, "ci", 0);
            int cj         = getInt(q, "cj", 0);
            int detailSize = getInt(q, "detail_size", 1024);
            int panI       = getInt(q, "pan_i", 0);
            int panJ       = getInt(q, "pan_j", 0);

            int centerI = ci * 256 + panI;
            int centerJ = cj * 256 + panJ;
            int half    = detailSize / 2;
            int H = detailSize, W = detailSize;

            float[][] out = LocalTerrainProvider.getPipelineData(
                    centerI - half, centerJ - half, centerI + half, centerJ + half, true);
            float[] elevFlat = out[0];
            float[] climate  = out[1];

            // Elevation → int16 LE (matching Python: clip(floor(elev), -32768, 32767).astype('<i2'))
            ByteBuffer elevBuf = ByteBuffer.allocate(H * W * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (float e : elevFlat) {
                short s = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                elevBuf.putShort(s);
            }

            boolean hasTemp = climate != null;
            // Biomes are optional: naming a cell on hover is worth a tile fetch, but only
            // when the caller is showing them, since it is far the costliest part here.
            boolean wantBiomes = "1".equals(q.get("biomes"));
            ByteBuffer biomeBuf = null;
            if (wantBiomes) {
                short[] ids = biomeGrid(centerI - half, centerJ - half, H);
                // Same water overlay the map is drawn with, so hovering a blue basin names
                // it as water rather than as the shore biome underneath it.
                overlayLakeBiomes(ids, H, W, centerI - half, centerJ - half);
                biomeBuf = ByteBuffer.allocate(H * W * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (short id : ids) biomeBuf.putShort(id);
            }

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(elevBuf.array());
            if (hasTemp) {
                ByteBuffer tempBuf = ByteBuffer.allocate(H * W * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < H * W; i++) tempBuf.putFloat(climate[i]);
                body.write(tempBuf.array());
            }
            if (biomeBuf != null) body.write(biomeBuf.array());
            byte[] payload = body.toByteArray();

            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("X-Height", String.valueOf(H));
            ex.getResponseHeaders().set("X-Width", String.valueOf(W));
            ex.getResponseHeaders().set("X-Has-Temp", hasTemp ? "1" : "0");
            ex.getResponseHeaders().set("X-Has-Biome", wantBiomes ? "1" : "0");
            ex.getResponseHeaders().set("Access-Control-Expose-Headers",
                    "X-Height, X-Width, X-Has-Temp, X-Has-Biome");
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
        } catch (Exception e) {
            LOG.error("detail_raw error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    // =========================================================================
    // Coarse channel helper — port of _coarse_channel() in server.py
    // =========================================================================

    /**
     * Return the given channel of the coarse map in real units.
     * Channels 0 and 1: undo signed-sqrt (sign(v) * v^2).
     */
    private static float[] coarseChannel(int ci0, int ci1, int cj0, int cj1, int channel) throws Exception {
        FloatTensor slice = LocalTerrainProvider.getPipelineCoarse(ci0, cj0, ci1, cj1);
        int H = ci1 - ci0, W = cj1 - cj0;
        float[] result = new float[H * W];
        for (int i = 0; i < H * W; i++) {
            float w   = slice.data[6 * H * W + i];
            float raw = (w > 1e-8f) ? slice.data[channel * H * W + i] / w : 0f;
            // Channels 0 (elev) and 1 (p5): signed-sqrt → real units via sign(v)*v^2
            result[i] = (channel <= 1) ? (float) (Math.signum(raw) * raw * raw) : raw;
        }

        // The detail views read climate through the banded pipeline fetch; the raw
        // coarse temperature must carry the same latitude wave or the two maps disagree.
        if (channel == 2) {
            LatitudeParameters lat = WorldScaleManager.getLatitudeParameters();
            int scale = WorldScaleManager.getCurrentScale();
            for (int r = 0; r < H; r++) {
                float bias = lat.temperatureBiasAt(((ci0 + r) * 256.0 + 128.0) * scale);
                for (int c = 0; c < W; c++) result[r * W + c] += bias;
            }
        }
        return result;
    }

    // =========================================================================
    // PNG rendering
    // =========================================================================

    /** Encode RGBA channels (float[4][H*W]) to a PNG byte array. */
    private static byte[] toPng(float[][] rgba, int H, int W) throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                int ri = (int) (clamp01(rgba[0][idx]) * 255f + 0.5f);
                int gi = (int) (clamp01(rgba[1][idx]) * 255f + 0.5f);
                int bi = (int) (clamp01(rgba[2][idx]) * 255f + 0.5f);
                int ai = (int) (clamp01(rgba[3][idx]) * 255f + 0.5f);
                img.setRGB(c, r, (ai << 24) | (ri << 16) | (gi << 8) | bi);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Paints biome colour over the hillshade, so hue says which biome and brightness
     * still says where the slopes are — a flat wash of colour hides the landscape the
     * biomes are sitting on. The shade is the relief's own brightness measured against
     * the window's average, which keeps the effect the same whether the view is an
     * alpine range or a coastal plain.
     */
    private static void shadeByRelief(float[][] rgba, float[][] reliefRgb, short[] biomes,
                                      int H, int W) {
        double sum = 0;
        float[] lum = new float[H * W];
        for (int i = 0; i < H * W; i++) {
            lum[i] = 0.299f * reliefRgb[0][i] + 0.587f * reliefRgb[1][i] + 0.114f * reliefRgb[2][i];
            sum += lum[i];
        }
        float mean = (float) (sum / Math.max(1, H * W));
        if (mean < 1e-4f) mean = 1e-4f;

        for (int i = 0; i < H * W; i++) {
            int packed = BiomeColors.rgb(biomes[i]);
            float shade = Math.max(0.45f, Math.min(1.35f, 0.55f + 0.45f * (lum[i] / mean)));
            rgba[0][i] = clamp01(((packed >> 16) & 0xFF) / 255f * shade);
            rgba[1][i] = clamp01(((packed >> 8) & 0xFF) / 255f * shade);
            rgba[2][i] = clamp01((packed & 0xFF) / 255f * shade);
            rgba[3][i] = 1f;
        }
    }

    private static float[][] applyColormap1D(float[] data, int H, int W, float vmin, float vmax, String cmap) {
        float[][] rgba = new float[4][H * W];
        for (int i = 0; i < H * W; i++) {
            float t = (data[i] - vmin) / (vmax - vmin);
            float[] rgb;
            switch (cmap) {
                case "terrain": rgb = Colormaps.terrain(clamp01(t)); break;
                case "rdbu_r":  rgb = Colormaps.rdBuR(clamp01(t));   break;
                default:        rgb = Colormaps.viridis(clamp01(t)); break;
            }
            rgba[0][i] = rgb[0]; rgba[1][i] = rgb[1]; rgba[2][i] = rgb[2]; rgba[3][i] = 1f;
        }
        return rgba;
    }

    // =========================================================================
    // HTTP utilities
    // =========================================================================

    private static void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] body = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    private static void sendError(HttpExchange ex, int status, String msg) throws IOException {
        Map<String, String> err = new HashMap<>();
        err.put("error", msg != null ? msg : "unknown error");
        sendJson(ex, status, err);
    }

    private static void send405(HttpExchange ex) throws IOException {
        sendError(ex, 405, "Method Not Allowed");
    }

    private static String readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(maxBytes);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else {
                map.put(pair, "");
            }
        }
        return map;
    }

    // =========================================================================
    // Math utilities
    // =========================================================================

    private static float nanMin(float[] arr) {
        float min = Float.MAX_VALUE;
        for (float v : arr) if (!Float.isNaN(v) && v < min) min = v;
        return min == Float.MAX_VALUE ? 0f : min;
    }

    private static float nanMax(float[] arr) {
        float max = -Float.MAX_VALUE;
        for (float v : arr) if (!Float.isNaN(v) && v > max) max = v;
        return max == -Float.MAX_VALUE ? 0f : max;
    }

    private static float clamp01(float v) {
        return Math.min(1f, Math.max(0f, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Rounded 2-D list for coarse_data JSON (np.round equivalent). */
    private static List<List<Double>> roundedGrid(float[] flat, int H, int W, int decimals) {
        double factor = Math.pow(10, decimals);
        List<List<Double>> grid = new ArrayList<>(H);
        for (int r = 0; r < H; r++) {
            List<Double> row = new ArrayList<>(W);
            for (int c = 0; c < W; c++) {
                row.add(Math.round(flat[r * W + c] * factor) / factor);
            }
            grid.add(row);
        }
        return grid;
    }

    private static int getInt(Map<String, String> q, String key, int def) {
        String v = q.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static Float getFloat(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null) return null;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return null; }
    }
}
