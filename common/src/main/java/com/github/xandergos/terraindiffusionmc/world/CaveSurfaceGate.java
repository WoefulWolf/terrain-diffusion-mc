package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;

/**
 * Decides, per column, how close to the surface caves may reach.
 *
 * <p>Underground the caves are untouched; this only governs where they may break the
 * sky. Openings in real landscapes are creatures of broken ground: cave mouths sit in
 * cliff faces and gorge walls, sinkholes pock karst hills under forest and jungle,
 * crevasses split glaciers and snowfields, and slot canyons cut arid rock plateaus. A
 * flat green field keeps its skin. So gentle country is sealed — caves must stay a
 * dozen blocks down — while rugged or karst-like country opens up, and the really big
 * mouths need both the right country and a rare landscape-scale mask on top.
 *
 * <p>Two tiers, because the generator's breaks come in two sizes: {@code small} covers
 * noodle and spaghetti tunnels and the old carver tunnels and ravines, {@code large}
 * covers the wide entrance shafts that vanilla cuts near the surface.
 */
public final class CaveSurfaceGate {

    /**
     * Blocks of rise and fall within the relief window before ground counts as broken.
     * Relief rather than point slope: the terrace steps of a rolling knoll never add up
     * to this, while craggy country clears it across whole patches instead of along
     * contour rings.
     */
    private static final int SMALL_OPEN_RELIEF = 7;

    /** Relief a mouth-capable biome still needs; big holes avoid true flats everywhere. */
    private static final int LARGE_OPEN_RELIEF_ROCKY = 7;
    private static final int LARGE_OPEN_RELIEF_SNOWY = 6;
    private static final int LARGE_OPEN_RELIEF_KARST = 5;

    // Seal depths come from CaveParameters, chosen per world; the wobble scales with
    // the chosen base so the character holds at any setting.
    private static final float SMALL_WOBBLE_FRACTION = 1f / 3f;
    private static final float LARGE_WOBBLE_FRACTION = 1f / 4f;
    /**
     * Landscape-scale rarity mask for big mouths. Even in qualifying country most hills
     * stay whole; a mouth is an event, not a texture.
     */
    private static final float LARGE_MASK_MIN = 0.32f;

    private static final FastNoiseLite MOUTH_MASK = makeFnl(0xCAFE5, 1f / 700f, 2);
    private static final FastNoiseLite SEAL_WOBBLE = makeFnl(0x5EA1, 1f / 40f, 2);

    /**
     * Roughens the underside of the seal. The seal is a per-column height, so a void it
     * truncates would end in a dead-flat stone ceiling; sampled in 3D at cave scale, the
     * cut becomes a ragged rock face instead. Only sealed columns use it, so open
     * country generates exactly as before.
     */
    private static final FastNoiseLite CEILING_JITTER = makeFnl(0xCE117, 1f / 16f, 2);
    private static final float CEILING_JITTER_BLOCKS = 3f;

    /** Blocks to shift the seal boundary at this position; in [-3, 3]-ish. */
    public static float ceilingJitter(int x, int y, int z) {
        return CEILING_JITTER.GetNoise(x, y, z) * CEILING_JITTER_BLOCKS;
    }

    private CaveSurfaceGate() {
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        return fnl;
    }

    /** Blocks below the surface that noodle, spaghetti and carver cuts must stay. */
    public static int smallSealDepth(HeightmapData data, int localX, int localZ,
                                     int worldX, int worldZ) {
        int base = WorldScaleManager.getCaveParameters().smallSealBlocks;
        if (base == 0) return 0; // gate switched off for this world
        if (ruggedBiome(biomeAt(data, localX, localZ))
                || reliefAt(data, localX, localZ) >= SMALL_OPEN_RELIEF) {
            return 0;
        }
        return base + Math.round(base * SMALL_WOBBLE_FRACTION
                * (SEAL_WOBBLE.GetNoise(worldX, worldZ) + 1f));
    }

    /** Blocks below the surface that wide entrance shafts must stay. */
    public static int largeSealDepth(HeightmapData data, int localX, int localZ,
                                     int worldX, int worldZ) {
        int base = WorldScaleManager.getCaveParameters().largeSealBlocks;
        if (base == 0) return 0; // gate switched off for this world
        short biome = biomeAt(data, localX, localZ);
        int relief = reliefAt(data, localX, localZ);
        boolean country = ruggedBiome(biome) && relief >= LARGE_OPEN_RELIEF_ROCKY
                || snowyMountain(biome) && relief >= LARGE_OPEN_RELIEF_SNOWY
                || karstForest(biome) && relief >= LARGE_OPEN_RELIEF_KARST;
        if (country && MOUTH_MASK.GetNoise(worldX, worldZ) > LARGE_MASK_MIN) return 0;
        return base + Math.round(base * LARGE_WOBBLE_FRACTION
                * (SEAL_WOBBLE.GetNoise(worldX, worldZ) + 1f));
    }

    // Ids are vanilla registration order, as everywhere biome ids travel in this mod.

    /** Broken rocky country: windswept, badlands, stony peaks and shores. */
    private static boolean ruggedBiome(short id) {
        switch (id) {
            case 19: case 20: case 21: case 22: // windswept family
            case 26: case 27: case 28:          // badlands family
            case 34: case 35:                   // jagged and stony peaks
            case 40:                            // stony shore
                return true;
            default:
                return false;
        }
    }

    /** Glacier and snowfield country, where crevasses split the surface. */
    private static boolean snowyMountain(short id) {
        switch (id) {
            case 31: case 32: case 33: // grove, snowy slopes, frozen peaks
                return true;
            default:
                return false;
        }
    }

    /** Humid forested hill country, where limestone sinks into dolines and cenotes. */
    private static boolean karstForest(short id) {
        switch (id) {
            case 8: case 10: case 11:  // forest, birch forest, dark forest
            case 23: case 24: case 25: // jungle family
                return true;
            default:
                return false;
        }
    }

    private static short biomeAt(HeightmapData data, int localX, int localZ) {
        return data.biomeIds == null ? 1 : data.biomeIds[localZ][localX];
    }

    /** Half-width of the relief window, in blocks. */
    private static final int RELIEF_RADIUS = 8;

    /**
     * Rise and fall in blocks across a ring of samples {@link #RELIEF_RADIUS} out plus
     * the column itself. Reads converted heights so the same numbers hold at any world
     * scale; clamps at tile edges, which at worst misjudges a border column slightly.
     */
    private static int reliefAt(HeightmapData data, int localX, int localZ) {
        if (data.heightmap == null) return 0;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int d = 0; d < 9; d++) {
            int dx = d == 8 ? 0 : (d % 3 - 1) * RELIEF_RADIUS;
            int dz = d == 8 ? 0 : (d / 3 - 1) * RELIEF_RADIUS;
            int nx = Math.max(0, Math.min(data.width - 1, localX + dx));
            int nz = Math.max(0, Math.min(data.height - 1, localZ + dz));
            int y = HeightConverter.convertToMinecraftHeight(data.heightmap[nz][nx]);
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        return hi - lo;
    }
}
