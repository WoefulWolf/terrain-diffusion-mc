package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Shields river channels and sealed surfaces from the carver stage.
 *
 * <p>Carvers run after river water is placed, and their aquifer only refills carved space
 * below sea level, so a ravine crossing a river at altitude would cut a dry gash straight
 * through the water. Returning null here leaves the block uncarved, so tunnels and ravines
 * stop short of a channel the way carvers stopped at any water before 1.18.
 *
 * <p>The same veto applies {@link CaveSurfaceGate}: where the column is sealed, tunnels
 * and ravines keep below the gate instead of scarring gentle ground, mirroring what the
 * density functions do for the noise caves.
 *
 * <p>One instance serves one applyCarvers call, so the tile memo needs no locking.
 */
public final class RiverAwareAquifer implements Aquifer {

    /** Blocks of rock kept intact beneath the bed, so a tunnel cannot breach it. */
    private static final int SHELL_BELOW = 6;

    private final Aquifer delegate;

    private int tileStartX = Integer.MIN_VALUE;
    private int tileStartZ = Integer.MIN_VALUE;
    private HeightmapData data;

    public RiverAwareAquifer(Aquifer delegate) {
        this.delegate = delegate;
    }

    @Override
    public BlockState computeSubstance(DensityFunction.FunctionContext context, double substance) {
        if (isProtected(context.blockX(), context.blockY(), context.blockZ())) return null;
        return delegate.computeSubstance(context, substance);
    }

    @Override
    public boolean shouldScheduleFluidUpdate() {
        return delegate.shouldScheduleFluidUpdate();
    }

    private boolean isProtected(int x, int y, int z) {
        HeightmapData d = tileFor(x, z);
        if (d == null || d.heightmap == null) return false;
        int localX = x - tileStartX;
        int localZ = z - tileStartZ;
        if (localX < 0 || localX >= d.width || localZ < 0 || localZ >= d.height) return false;

        int firstAir = HeightConverter.convertToMinecraftHeight(d.heightmap[localZ][localX]);

        // Sealed ground keeps its skin: no tunnel or ravine may cut the top blocks. The
        // boundary jitters so a truncated tunnel ends in ragged rock, not a flat lid.
        int seal = CaveSurfaceGate.smallSealDepth(d, localX, localZ, x, z);
        if (seal > 0 && y > firstAir - seal + CaveSurfaceGate.ceilingJitter(x, y, z)) return true;

        if (d.riverClass == null || d.riverClass[localZ][localX] == 0) return false;

        // The shell runs from below the bed to just above the water, banks included, so a
        // carver can neither cut the channel nor slice the bank that holds it in.
        short water = d.waterLevel[localZ][localX];
        int upper = water != HeightmapData.NO_WATER
                ? HeightConverter.convertToMinecraftHeight(water)
                : firstAir;
        return y >= firstAir - 1 - SHELL_BELOW && y <= upper;
    }

    private HeightmapData tileFor(int x, int z) {
        int size = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(size);
        int startX = (x >> shift) << shift;
        int startZ = (z >> shift) << shift;
        if (startX != tileStartX || startZ != tileStartZ || data == null) {
            tileStartX = startX;
            tileStartZ = startZ;
            data = LocalTerrainProvider.getInstance()
                    .fetchHeightmap(startZ, startX, startZ + size, startX + size);
        }
        return data;
    }
}
