package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Depth below the surface as {@link TerrainDiffusionDensityFunction} reports it, minus
 * the column's {@link CaveSurfaceGate} seal. The cave stacks gate on this instead of the
 * true terrain, so in sealed country every cave behaves as if the surface sat a dozen
 * blocks lower and nothing can break the skin; where the gate is open the two functions
 * agree and generation is untouched.
 */
public final class CaveGateDensityFunction implements DensityFunction {

    private static final double DEPTH_SCALE = 16.0;
    private static final double DENSITY_LIMIT = 64.0;

    public static final MapCodec<CaveGateDensityFunction> SMALL_CODEC =
            MapCodec.unit(new CaveGateDensityFunction(false));
    public static final MapCodec<CaveGateDensityFunction> LARGE_CODEC =
            MapCodec.unit(new CaveGateDensityFunction(true));

    private final boolean large;

    private CaveGateDensityFunction(boolean large) {
        this.large = large;
    }

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        int x = pos.blockX();
        int z = pos.blockZ();
        long column = columnGate(x, z);
        return gateDensity(column, x, pos.blockY(), z);
    }

    @Override
    public void fillArray(double[] densities, DensityFunction.ContextProvider applier) {
        // Consecutive samples run down one column, so the per-column work is memoised.
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        long column = 0;
        for (int i = 0; i < densities.length; i++) {
            DensityFunction.FunctionContext pos = applier.forIndex(i);
            int x = pos.blockX();
            int z = pos.blockZ();
            if (x != lastX || z != lastZ) {
                lastX = x;
                lastZ = z;
                column = columnGate(x, z);
            }
            densities[i] = gateDensity(column, x, pos.blockY(), z);
        }
    }

    /** Gate height in the low half, seal depth in the high half. */
    private long columnGate(int x, int z) {
        int size = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(size);
        int startX = (x >> shift) << shift;
        int startZ = (z >> shift) << shift;

        HeightmapData data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(startZ, startX, startZ + size, startX + size);
        if (data == null || data.heightmap == null) {
            return Integer.MIN_VALUE / 2 & 0xFFFFFFFFL;
        }

        int localX = Math.max(0, Math.min(data.width - 1, x - startX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - startZ));

        int surface = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        int seal = large
                ? CaveSurfaceGate.largeSealDepth(data, localX, localZ, x, z)
                : CaveSurfaceGate.smallSealDepth(data, localX, localZ, x, z);
        return ((long) seal << 32) | ((surface - seal) & 0xFFFFFFFFL);
    }

    private double gateDensity(long column, int x, int y, int z) {
        double gateY = (int) column;
        int seal = (int) (column >>> 32);
        // A sealed cut jitters in 3D so a truncated void ends in ragged rock, not a
        // flat lid. Open columns take none, staying identical to the terrain function.
        if (seal != 0) gateY += CaveSurfaceGate.ceilingJitter(x, y, z, seal);
        return Mth.clamp((gateY - y) / DEPTH_SCALE, -DENSITY_LIMIT, DENSITY_LIMIT);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return -DENSITY_LIMIT;
    }

    @Override
    public double maxValue() {
        return DENSITY_LIMIT;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(large ? LARGE_CODEC : SMALL_CODEC);
    }
}
