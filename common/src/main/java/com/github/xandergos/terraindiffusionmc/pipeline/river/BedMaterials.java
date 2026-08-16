package com.github.xandergos.terraindiffusionmc.pipeline.river;

import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;

/**
 * What a river or lake floor is made of at a place.
 *
 * <p>Beds settle in patches, and the patches have to be shaped by noise sampled at world
 * coordinates. Rolling a material per eight-block cell instead tiles every bed with
 * axis-aligned squares; because clay reads pale, sand tan and gravel brown, that shows
 * through shallow water as a chequerboard across every river and lake in the world,
 * since every wet column gets painted. Two scales do the work: a slow field choosing
 * which pair of materials belongs here, a quicker one mixing them inside the patch so it
 * is not a flat slab.
 *
 * <p>Pure position and gradient in, material out, with no Minecraft types, so the
 * pattern can be rendered and inspected without a game to run it in. Anything that
 * decides how the world looks belongs where it can be looked at.
 */
public final class BedMaterials {

    /** Choices a floor or bank can settle on, mapped to blocks by the caller. */
    public enum Material {
        STONE, COBBLESTONE, GRAVEL, SAND, CLAY, DIRT
    }

    /** Above this the current is quick enough to strip a bed to rock. */
    public static final float STEEP_ROCKY = 0.55f;
    /** Above this it is quick enough to sweep the sand away and leave gravel. */
    public static final float STEEP_GRAVEL = 0.25f;

    /**
     * Patch scale picks which pair of materials belongs here; grain mixes them within
     * the patch. Both are wide enough to read as deposits rather than as dither.
     */
    private static final FastNoiseLite PATCH = makeFnl(0x8EDDA, 1f / 34f);
    private static final FastNoiseLite GRAIN = makeFnl(0x64A17, 1f / 11f);

    private BedMaterials() {
    }

    private static FastNoiseLite makeFnl(int seed, float frequency) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(frequency);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(2);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.5f);
        return fnl;
    }

    /**
     * @param steep  local channel gradient, 0 slack to 1 fully steep
     * @param frozen whether the water above is frozen over
     */
    public static Material bed(float steep, boolean frozen, int x, int z) {
        float grain = GRAIN.GetNoise(x, z);
        if (steep >= STEEP_ROCKY) {
            return grain < 0f ? Material.STONE
                    : (grain < 0.21f ? Material.GRAVEL : Material.COBBLESTONE);
        }
        if (steep >= STEEP_GRAVEL) {
            return grain < 0.16f ? Material.GRAVEL : Material.COBBLESTONE;
        }
        if (frozen) {
            return grain < 0.08f ? Material.GRAVEL : Material.DIRT;
        }
        float patch = PATCH.GetNoise(x, z);
        if (patch < -0.18f) return grain < 0.21f ? Material.CLAY : Material.SAND;
        if (patch < 0f) return grain < 0.16f ? Material.DIRT : Material.GRAVEL;
        return grain < 0.27f ? Material.SAND : Material.GRAVEL;
    }

    /** Bank facing above the waterline; rock where the current is quick. */
    public static Material bank(float steep, int x, int z) {
        float grain = GRAIN.GetNoise(x, z);
        if (steep >= STEEP_ROCKY) {
            return grain < 0.04f ? Material.STONE : Material.GRAVEL;
        }
        return grain < 0.21f ? Material.GRAVEL : Material.COBBLESTONE;
    }
}
