package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Pours rivers into carved channels.
 *
 * <p>Vanilla only knows one water level, sea level, and aquifers derive theirs from noise,
 * so neither can hold a river at 400 m. The channel already carries a water surface from the
 * carver, and the columns are simply filled to it.
 *
 * <p>This runs before surface building, matching where vanilla's aquifers place water, so
 * surface rules see the river and carvers refuse to cut into it.
 */
public final class RiverWaterFiller {

    /**
     * How far a column may be filled downward. Water stops at the first solid block, so this
     * only matters where a cave breaks into the bed: without it one opening drains the river
     * into the cave system below.
     */
    private static final int MAX_FILL_DEPTH = 48;

    /** Above this the reach is a mountain stream: stone bed and rocky banks. */
    private static final float STEEP_ROCKY = 0.55f;
    /** Above this the current is quick enough to sweep sand away, leaving gravel. */
    private static final float STEEP_GRAVEL = 0.25f;

    /** Blocks of bank wall repainted from the top down, so the risers show material too. */
    private static final int BANK_PAINT_DEPTH = 3;

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    /**
     * Flowing states for step-downs, chosen to be exactly what vanilla's flow rules settle
     * into so they are stable: a level-1 wedge is held by the source beside it, and a
     * level-8 falling block is held by the water above it.
     */
    private static final BlockState FLOWING = Blocks.WATER.defaultBlockState()
            .setValue(LiquidBlock.LEVEL, 1);
    private static final BlockState FALLING = Blocks.WATER.defaultBlockState()
            .setValue(LiquidBlock.LEVEL, 8);
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState CLAY = Blocks.CLAY.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    private RiverWaterFiller() {
    }

    /**
     * Water surface plane for a column as a first-free-block height, or
     * {@link Integer#MIN_VALUE} where the rivers put no water.
     *
     * <p>Structure layout asks the generator for column heights before any block exists,
     * so without this a village snaps its houses to the carved riverbed while its streets,
     * placed later against the real surface, ride on the water above them.
     */
    public static int waterSurfaceY(int x, int z) {
        if (WorldScaleManager.getRiverMode() == RiverMode.OFF) return Integer.MIN_VALUE;

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (x >> tileShift) << tileShift;
        int tileStartZ = (z >> tileShift) << tileShift;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.waterLevel == null) return Integer.MIN_VALUE;

        int localX = x - tileStartX;
        int localZ = z - tileStartZ;
        if (localX < 0 || localX >= data.width || localZ < 0 || localZ >= data.height) {
            return Integer.MIN_VALUE;
        }
        short metres = data.waterLevel[localZ][localX];
        if (metres == HeightmapData.NO_WATER) return Integer.MIN_VALUE;
        return HeightConverter.convertToMinecraftHeight(metres);
    }

    public static void fill(ChunkAccess chunk) {
        if (WorldScaleManager.getRiverMode() == RiverMode.OFF) return;

        ChunkPos chunkPos = chunk.getPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();

        // Tiles are a power of two and origin-aligned, so a chunk never straddles two.
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (minBlockX >> tileShift) << tileShift;
        int tileStartZ = (minBlockZ >> tileShift) << tileShift;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.waterLevel == null) return;

        int minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < 16; dz++) {
            int localZ = minBlockZ + dz - tileStartZ;
            if (localZ < 0 || localZ >= data.height) continue;

            for (int dx = 0; dx < 16; dx++) {
                int localX = minBlockX + dx - tileStartX;
                if (localX < 0 || localX >= data.width) continue;

                short metres = data.waterLevel[localZ][localX];
                if (metres == HeightmapData.NO_WATER) continue;

                // convertToMinecraftHeight names the first air block above an elevation, so
                // the top water block goes one below it, the way sea level 63 puts vanilla's
                // ocean surface at 62. Filling at the converted y itself leaves water level
                // with the air above the lowest bank cells, and it spills the first time
                // anything ticks it.
                int topWaterY = HeightConverter.convertToMinecraftHeight(metres) - 1;
                pos.set(minBlockX + dx, topWaterY, minBlockZ + dz);

                for (int filled = 0; filled < MAX_FILL_DEPTH; filled++) {
                    int y = topWaterY - filled;
                    if (y <= minY) break;
                    pos.setY(y);
                    if (!chunk.getBlockState(pos).isAir()) break;
                    chunk.setBlockState(pos, WATER, false);
                }
            }
        }

        smoothSteps(chunk, data, minBlockX, minBlockZ, tileStartX, tileStartZ, pos);
    }

    /**
     * Dresses every step-down with flowing water, so the surface slopes and falls instead
     * of dropping in bare full-block terraces.
     *
     * <p>Two parts, both the steady state vanilla's own flow rules produce. At the step
     * itself, the lower column is topped up with a level-1 wedge held by the neighbouring
     * source, with falling water beneath on taller drops. From there the flow spreads
     * across the pool below: a source pool cannot be displaced, so it acts as a floor, and
     * the sheet thins one level per block up to the full seven vanilla allows. The spread
     * only walks columns of equal surface height, so a follow-up drop ends it naturally.
     *
     * <p>Levels come from a breadth-first pass over the whole tile, not the chunk, so the
     * ramp does not care where chunk borders fall.
     */
    private static void smoothSteps(ChunkAccess chunk, HeightmapData data,
                                    int minBlockX, int minBlockZ,
                                    int tileStartX, int tileStartZ,
                                    BlockPos.MutableBlockPos pos) {
        int h = data.height, w = data.width;

        // Water surface top block per tile column; MIN_VALUE where the rivers put none.
        // A shoreline column can carry a surface claim while quantisation leaves it no
        // actual water block; trusted as a higher neighbour it gets a wedge built against
        // thin air, standing as a ridge of raised water along the bank.
        int[] top = new int[h * w];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                short metres = data.waterLevel[r][c];
                int t = metres == HeightmapData.NO_WATER
                        ? Integer.MIN_VALUE
                        : HeightConverter.convertToMinecraftHeight(metres) - 1;
                if (t != Integer.MIN_VALUE
                        && t < HeightConverter.convertToMinecraftHeight(data.heightmap[r][c])) {
                    t = Integer.MIN_VALUE;
                }
                top[r * w + c] = t;
            }
        }

        // Seed the lens: 1 where a one-block step puts a wedge on this pool plane, 0 where
        // a taller fall lands, since falling water feeds its neighbours at full strength.
        // Fall seeds go first so the queue stays level-ordered.
        int[] lvl = new int[h * w];
        java.util.Arrays.fill(lvl, Integer.MAX_VALUE);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int seed = 0; seed <= 1; seed++) {
            for (int i = 0; i < h * w; i++) {
                int t = top[i];
                if (t == Integer.MIN_VALUE) continue;
                int maxN = maxNeighbourTop(top, i, h, w);
                boolean falls = maxN >= t + 2;
                boolean wedge = maxN == t + 1;
                if ((seed == 0 && falls) || (seed == 1 && wedge && lvl[i] == Integer.MAX_VALUE)) {
                    lvl[i] = seed;
                    queue.add(i);
                }
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            if (lvl[i] >= 7) continue;
            int r = i / w, c = i - r * w;
            for (int side = 0; side < 4; side++) {
                int nr = r + (side == 0 ? 1 : side == 1 ? -1 : 0);
                int nc = c + (side == 2 ? 1 : side == 3 ? -1 : 0);
                if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue;
                int ni = nr * w + nc;
                if (top[ni] != top[i] || lvl[ni] <= lvl[i] + 1) continue;
                lvl[ni] = lvl[i] + 1;
                queue.add(ni);
            }
        }

        for (int dz = 0; dz < 16; dz++) {
            int localZ = minBlockZ + dz - tileStartZ;
            if (localZ < 0 || localZ >= h) continue;

            for (int dx = 0; dx < 16; dx++) {
                int localX = minBlockX + dx - tileStartX;
                if (localX < 0 || localX >= w) continue;

                int i = localZ * w + localX;
                int t = top[i];
                if (t == Integer.MIN_VALUE) continue;

                // A shallow fringe column may hold no actual water; nothing to slope from.
                pos.set(minBlockX + dx, t, minBlockZ + dz);
                if (!chunk.getBlockState(pos).getFluidState().isSource()) continue;

                int highest = maxNeighbourTop(top, i, h, w);
                if (highest > t) {
                    // Top down, so the chain stays anchored: the wedge leans on the
                    // neighbour's source, and the rest is held falling by the water above.
                    for (int y = highest; y > t; y--) {
                        pos.setY(y);
                        if (!chunk.getBlockState(pos).isAir()) break;
                        chunk.setBlockState(pos, y == highest ? FLOWING : FALLING, false);
                    }
                } else if (lvl[i] >= 2 && lvl[i] <= 7) {
                    // Ramp sheet thinning away across the pool.
                    pos.setY(t + 1);
                    if (chunk.getBlockState(pos).isAir()) {
                        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState()
                                .setValue(LiquidBlock.LEVEL, lvl[i]), false);
                    }
                }
            }
        }
    }

    private static int maxNeighbourTop(int[] top, int i, int h, int w) {
        int r = i / w, c = i - r * w;
        int best = Integer.MIN_VALUE;
        if (r + 1 < h && top[i + w] != Integer.MIN_VALUE) best = Math.max(best, top[i + w]);
        if (r > 0 && top[i - w] != Integer.MIN_VALUE) best = Math.max(best, top[i - w]);
        if (c + 1 < w && top[i + 1] != Integer.MIN_VALUE) best = Math.max(best, top[i + 1]);
        if (c > 0 && top[i - 1] != Integer.MIN_VALUE) best = Math.max(best, top[i - 1]);
        return best;
    }

    /**
     * Repaints bed and bank surfaces after surface rules ran, so a mountain stream cuts
     * through rock and gravel instead of tidy grass. Runs at the tail of surface building:
     * anything earlier and the rules would put the grass right back.
     */
    public static void paintBeds(ChunkAccess chunk) {
        if (WorldScaleManager.getRiverMode() == RiverMode.OFF) return;

        ChunkPos chunkPos = chunk.getPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (minBlockX >> tileShift) << tileShift;
        int tileStartZ = (minBlockZ >> tileShift) << tileShift;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.riverClass == null) return;

        int minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < 16; dz++) {
            int localZ = minBlockZ + dz - tileStartZ;
            if (localZ < 0 || localZ >= data.height) continue;

            for (int dx = 0; dx < 16; dx++) {
                int localX = minBlockX + dx - tileStartX;
                if (localX < 0 || localX >= data.width) continue;

                byte cls = data.riverClass[localZ][localX];
                if (cls == 0) continue;
                float steep = (cls - 1) / 100f;

                int x = minBlockX + dx;
                int z = minBlockZ + dz;
                short waterMetres = data.waterLevel[localZ][localX];

                if (waterMetres != HeightmapData.NO_WATER) {
                    // Wet column: find the bed under the water and repaint two blocks of it.
                    boolean frozen = data.biomeIds[localZ][localX] == 37;
                    BlockState material = bedMaterial(steep, frozen, x, z);
                    int topWaterY = HeightConverter.convertToMinecraftHeight(waterMetres) - 1;
                    pos.set(x, topWaterY, z);
                    for (int fell = 0; fell < MAX_FILL_DEPTH; fell++) {
                        int y = topWaterY - fell;
                        if (y <= minY) break;
                        pos.setY(y);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir() || state.is(Blocks.WATER)) continue;
                        chunk.setBlockState(pos, material, false);
                        pos.setY(y - 1);
                        if (y - 1 > minY && !chunk.getBlockState(pos).isAir()) {
                            chunk.setBlockState(pos, material, false);
                        }
                        break;
                    }
                } else if (steep >= STEEP_GRAVEL) {
                    // Dry bank: rocky only where the reach is quick; a lazy meander keeps
                    // its natural grassy edge. Painted a few blocks down because the bank
                    // is a staircase, and one block would leave every riser in bare dirt.
                    int topY = HeightConverter.convertToMinecraftHeight(
                            data.heightmap[localZ][localX]) - 1;
                    BlockState material = bankMaterial(steep, x, z);
                    for (int down = 0; down < BANK_PAINT_DEPTH; down++) {
                        int y = topY - down;
                        if (y <= minY) break;
                        pos.set(x, y, z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir() || state.is(Blocks.WATER)) break;
                        chunk.setBlockState(pos, material, false);
                    }
                }
            }
        }
    }

    private static BlockState bedMaterial(float steep, boolean frozen, int x, int z) {
        int h = mix(x, z);
        if (steep >= STEEP_ROCKY) return h < 128 ? STONE : (h < 208 ? GRAVEL : COBBLESTONE);
        if (steep >= STEEP_GRAVEL) return h < 192 ? GRAVEL : COBBLESTONE;
        if (frozen) return h < 160 ? GRAVEL : DIRT;
        // Slow beds settle in patches, so the material is rolled per 8-block pocket rather
        // than per block: block-level rolls would pepper single blocks everywhere.
        int pocket = mix(x >> 3, z >> 3);
        if (pocket < 56) return h < 208 ? CLAY : SAND;
        if (pocket < 128) return h < 192 ? DIRT : GRAVEL;
        return h < 224 ? SAND : GRAVEL;
    }

    private static BlockState bankMaterial(float steep, int x, int z) {
        int h = mix(x, z);
        if (steep >= STEEP_ROCKY) return h < 144 ? STONE : GRAVEL;
        return h < 208 ? GRAVEL : COBBLESTONE;
    }

    /**
     * Whether vanilla may freeze this water column.
     *
     * <p>Flowing steps carry no ice, so a frozen river with drops freezes in zebra
     * stripes between them; and a real river freezes from its banks inward, where the
     * current is slowest. So: no ice within three blocks of a drop, and ice odds that
     * fall away from the bank, dithered so the frozen margin looks grown rather than
     * drawn. Lakes, oceans, and foreign worlds are left entirely to vanilla.
     */
    public static boolean allowIce(int x, int z) {
        if (WorldScaleManager.getRiverMode() == RiverMode.OFF) return true;

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int tileStartX = (x >> tileShift) << tileShift;
        int tileStartZ = (z >> tileShift) << tileShift;

        // Cache-only: while our features place, the tile always exists; on a world that
        // is not ours it never does, and vanilla keeps its own rules.
        HeightmapData data = LocalTerrainProvider.getInstance().peekHeightmap(
                tileStartZ, tileStartX, tileStartZ + tileSize, tileStartX + tileSize);
        if (data == null || data.waterLevel == null) return true;

        int localX = x - tileStartX;
        int localZ = z - tileStartZ;
        if (localX < 0 || localX >= data.width || localZ < 0 || localZ >= data.height) return true;
        short metres = data.waterLevel[localZ][localX];
        if (metres == HeightmapData.NO_WATER) return true;
        short biome = data.biomeIds[localZ][localX];
        if (biome != 36 && biome != 37) return true;

        int myTop = HeightConverter.convertToMinecraftHeight(metres) - 1;
        int bankDist = Integer.MAX_VALUE;
        for (int dz = -7; dz <= 7; dz++) {
            int nz = localZ + dz;
            if (nz < 0 || nz >= data.height) continue;
            for (int dx = -7; dx <= 7; dx++) {
                int nx = localX + dx;
                if (nx < 0 || nx >= data.width) continue;
                int cheb = Math.max(Math.abs(dz), Math.abs(dx));
                short nm = data.waterLevel[nz][nx];
                if (nm == HeightmapData.NO_WATER) {
                    if (cheb < bankDist) bankDist = cheb;
                } else if (cheb <= 3
                        && HeightConverter.convertToMinecraftHeight(nm) - 1 != myTop) {
                    // Near a drop the water is flowing, and flowing water carries no ice.
                    return false;
                }
            }
        }

        if (bankDist <= 1) return true;
        if (bankDist == Integer.MAX_VALUE) bankDist = 8;
        float chance = 1f - (bankDist - 1) / 6f;
        if (chance <= 0f) return false;
        return mix(x, z) < (int) (chance * 255f);
    }

    /** Deterministic 0..255 from world position, for material variety without noise. */
    private static int mix(int x, int z) {
        int h = x * 0x9E3779B1 + z * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h & 0xFF;
    }
}
