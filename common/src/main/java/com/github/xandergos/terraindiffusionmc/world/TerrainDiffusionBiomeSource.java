package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Map.entry;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    private static final ResourceKey<Biome> FOREST_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "forest_sparse"));
    private static final ResourceKey<Biome> TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "taiga_sparse"));
    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "snowy_taiga_sparse"));

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));


    // Cave biomes are picked from depth and noise instead of the classifier, so they
    // never pass through biomeIdMap.
    private static final FastNoiseLite CAVE_NOISE = makeCaveNoise();
    // HeightConverter scales terrain upward, so land surfaces sit well above y=63. The
    // cave zone is depth below the local surface; an absolute ceiling would miss it.
    private static final int CAVE_MIN_DEPTH = 32;
    // Deep dark stays in the vanilla band: ancient cities place at a fixed low y, so a
    // deep-dark pocket inside a mountain would never be used.
    private static final int DEEP_DARK_MAX_Y = 0;
    private static final int SEA_LEVEL_Y = 63;
    // Cutoffs are picked by the share of underground area they cover. The noise spans
    // only about [-0.74, 0.77], so mirrored cutoffs give very different frequencies.
    // Shares wet/neutral/dry: lush 20/10.5/2.4%, dripstone 4.4/12.4/24%.
    private static final float DEEP_DARK_MIN_NOISE = 0.45f;
    private static final float LUSH_MAX_NOISE_WET = -0.20f;
    private static final float LUSH_MAX_NOISE_BASE = -0.30f;
    private static final float LUSH_MAX_NOISE_DRY = -0.45f;
    private static final float DRIPSTONE_MIN_NOISE_DRY = 0.15f;
    private static final float DRIPSTONE_MIN_NOISE_BASE = 0.25f;
    private static final float DRIPSTONE_MIN_NOISE_WET = 0.35f;
    private static final float DRIPSTONE_MAX_NOISE = 0.45f;

    private HolderGetter<Biome> biomeLookup;
    private Map<Short, Holder<Biome>> biomeIdMap = null;
    private Set<Holder<Biome>> oceanBiomes = null;
    private Holder<Biome> deepDark, lushCaves, dripstoneCaves;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    private static FastNoiseLite makeCaveNoise() {
        FastNoiseLite fnl = new FastNoiseLite(31337);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(1f / 220f);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(2);
        return fnl;
    }

    private Holder<Biome> vanilla(ResourceKey<Biome> key) {
        return this.biomeLookup.getOrThrow(key);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIdMap() {
        if (biomeIdMap == null) {
            biomeIdMap = Map.ofEntries(
                    entry((short) 1, this.biomeLookup.getOrThrow(Biomes.PLAINS)),
                    entry((short) 3, this.biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS)),
                    entry((short) 5, this.biomeLookup.getOrThrow(Biomes.DESERT)),
                    entry((short) 6, this.biomeLookup.getOrThrow(Biomes.SWAMP)),
                    entry((short) 8, this.biomeLookup.getOrThrow(Biomes.FOREST)),
                    entry((short) 15, this.biomeLookup.getOrThrow(Biomes.TAIGA)),
                    entry((short) 16, this.biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA)),
                    entry((short) 17, this.biomeLookup.getOrThrow(Biomes.SAVANNA)),
                    entry((short) 19, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_HILLS)),
                    entry((short) 23, this.biomeLookup.getOrThrow(Biomes.JUNGLE)),
                    entry((short) 26, this.biomeLookup.getOrThrow(Biomes.BADLANDS)),
                    entry((short) 29, this.biomeLookup.getOrThrow(Biomes.MEADOW)),
                    entry((short) 31, this.biomeLookup.getOrThrow(Biomes.GROVE)),
                    entry((short) 32, this.biomeLookup.getOrThrow(Biomes.SNOWY_SLOPES)),
                    entry((short) 33, this.biomeLookup.getOrThrow(Biomes.FROZEN_PEAKS)),
                    entry((short) 35, this.biomeLookup.getOrThrow(Biomes.STONY_PEAKS)),
                    entry((short) 41, this.biomeLookup.getOrThrow(Biomes.WARM_OCEAN)),
                    entry((short) 44, this.biomeLookup.getOrThrow(Biomes.OCEAN)),
                    entry((short) 46, this.biomeLookup.getOrThrow(Biomes.COLD_OCEAN)),
                    entry((short) 48, this.biomeLookup.getOrThrow(Biomes.FROZEN_OCEAN)),
                    entry((short) 4, vanilla(Biomes.ICE_SPIKES)),
                    entry((short) 7, vanilla(Biomes.MANGROVE_SWAMP)),
                    entry((short) 10, vanilla(Biomes.BIRCH_FOREST)),
                    entry((short) 11, vanilla(Biomes.DARK_FOREST)),
                    entry((short) 12, vanilla(Biomes.OLD_GROWTH_BIRCH_FOREST)),
                    entry((short) 13, vanilla(Biomes.OLD_GROWTH_PINE_TAIGA)),
                    entry((short) 14, vanilla(Biomes.OLD_GROWTH_SPRUCE_TAIGA)),
                    entry((short) 18, vanilla(Biomes.SAVANNA_PLATEAU)),
                    entry((short) 20, vanilla(Biomes.WINDSWEPT_GRAVELLY_HILLS)),
                    entry((short) 21, vanilla(Biomes.WINDSWEPT_FOREST)),
                    entry((short) 22, vanilla(Biomes.WINDSWEPT_SAVANNA)),
                    entry((short) 24, vanilla(Biomes.SPARSE_JUNGLE)),
                    entry((short) 25, vanilla(Biomes.BAMBOO_JUNGLE)),
                    entry((short) 27, vanilla(Biomes.ERODED_BADLANDS)),
                    entry((short) 28, vanilla(Biomes.WOODED_BADLANDS)),
                    entry((short) 34, vanilla(Biomes.JAGGED_PEAKS)),
                    entry((short) 36, vanilla(Biomes.RIVER)),
                    entry((short) 37, vanilla(Biomes.FROZEN_RIVER)),
                    entry((short) 38, vanilla(Biomes.BEACH)),
                    entry((short) 39, vanilla(Biomes.SNOWY_BEACH)),
                    entry((short) 40, vanilla(Biomes.STONY_SHORE)),
                    entry((short) 42, vanilla(Biomes.LUKEWARM_OCEAN)),
                    entry((short) 43, vanilla(Biomes.DEEP_LUKEWARM_OCEAN)),
                    entry((short) 45, vanilla(Biomes.DEEP_OCEAN)),
                    entry((short) 47, vanilla(Biomes.DEEP_COLD_OCEAN)),
                    entry((short) 49, vanilla(Biomes.DEEP_FROZEN_OCEAN)),
                    entry((short) 2, vanilla(Biomes.SUNFLOWER_PLAINS)),
                    entry((short) 9, vanilla(Biomes.FLOWER_FOREST)),
                    entry((short) 30, vanilla(Biomes.CHERRY_GROVE)),
                    entry((short) 50, vanilla(Biomes.MUSHROOM_FIELDS)),
                    entry((short) 108, this.biomeLookup.getOrThrow(FOREST_SPARSE)),
                    entry((short) 115, this.biomeLookup.getOrThrow(TAIGA_SPARSE)),
                    entry((short) 116, this.biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE))
            );

            deepDark = vanilla(Biomes.DEEP_DARK);
            lushCaves = vanilla(Biomes.LUSH_CAVES);
            dripstoneCaves = vanilla(Biomes.DRIPSTONE_CAVES);

            // Deep dark is suppressed under oceans, so the ocean ids need to be
            // recognisable from a resolved surface biome.
            Set<Holder<Biome>> oceans = new HashSet<>();
            for (short oceanId : new short[]{41, 42, 43, 44, 45, 46, 47, 48, 49}) {
                oceans.add(biomeIdMap.get(oceanId));
            }
            oceanBiomes = oceans;
        }
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        // Cave biomes bypass biomeIdMap. Structure placement reads this stream, so
        // leaving them out means ancient cities silently never generate.
        return Stream.concat(
                biomeIdMap.values().stream(),
                Stream.of(deepDark, lushCaves, dripstoneCaves));
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();

        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockY = QuartPos.toBlock(y);
        int blockZ = QuartPos.toBlock(z);

        ColumnSample column = sampleColumn(blockX, blockZ);
        if (column.surfaceY - blockY < CAVE_MIN_DEPTH) return column.biome;

        float caveNoise = CAVE_NOISE.GetNoise(blockX, blockZ);
        if (blockY < DEEP_DARK_MAX_Y
                && caveNoise > DEEP_DARK_MIN_NOISE
                && !oceanBiomes.contains(column.biome)) {
            return deepDark;
        }
        int wetness = surfaceWetness(column.biomeId);
        float lushMax = wetness > 0 ? LUSH_MAX_NOISE_WET
                : wetness < 0 ? LUSH_MAX_NOISE_DRY : LUSH_MAX_NOISE_BASE;
        float dripstoneMin = wetness > 0 ? DRIPSTONE_MIN_NOISE_WET
                : wetness < 0 ? DRIPSTONE_MIN_NOISE_DRY : DRIPSTONE_MIN_NOISE_BASE;

        if (caveNoise < lushMax) return lushCaves;
        if (caveNoise > dripstoneMin && caveNoise < DRIPSTONE_MAX_NOISE) return dripstoneCaves;
        return column.biome;
    }

    /**
     * Surface bias for cave biome selection: 1 wet, -1 dry, 0 neutral. Mirrors vanilla
     * keying lush caves off humidity and dripstone off continentalness. Ids are indices
     * into vanilla's Biomes registration order.
     */
    private static int surfaceWetness(short surfaceBiomeId) {
        switch (surfaceBiomeId) {
            case 6:   // swamp
            case 7:   // mangrove_swamp
            case 11:  // dark_forest
            case 12:  // old_growth_birch_forest
            case 13:  // old_growth_pine_taiga
            case 14:  // old_growth_spruce_taiga
            case 23:  // jungle
            case 24:  // sparse_jungle
            case 25:  // bamboo_jungle
                return 1;
            case 3:   // snowy_plains
            case 4:   // ice_spikes
            case 5:   // desert
            case 17:  // savanna
            case 18:  // savanna_plateau
            case 22:  // windswept_savanna
            case 26:  // badlands
            case 27:  // eroded_badlands
            case 28:  // wooded_badlands
            case 35:  // stony_peaks
                return -1;
            default:
                return 0;
        }
    }

    /** Surface biome, its classifier id, and terrain height for one column. */
    private record ColumnSample(Holder<Biome> biome, short biomeId, int surfaceY) {}

    private ColumnSample sampleColumn(int blockX, int blockZ) {
        Holder<Biome> defaultEntry = biomeIdMap.get((short) 1);

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = blockX >> tileShift;
        int tileZ = blockZ >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        if (data != null) {
            int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));

            Holder<Biome> entry = defaultEntry;
            short entryId = 1;
            if (data.biomeIds != null) {
                short id = data.biomeIds[localZ][localX];
                Holder<Biome> resolved = biomeIdMap.get(id);
                if (resolved != null) {
                    entry = resolved;
                    entryId = id;
                }
            }
            int surfaceY = data.heightmap != null
                    ? HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX])
                    : SEA_LEVEL_Y;
            return new ColumnSample(entry, entryId, surfaceY);
        }

        return new ColumnSample(defaultEntry, (short) 1, SEA_LEVEL_Y);
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval, Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return null;
    }
}
