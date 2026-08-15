package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persisted per-world settings for terrain diffusion.
 *
 * <p>This is stored in the world save via Minecraft's saved data storage.
 */
public final class WorldScaleSettingsState extends SavedData {

    private static final Codec<WorldScaleSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("scale", WorldScaleManager.DEFAULT_SCALE)
                    .forGetter(WorldScaleSettingsState::getScale),
            Codec.BOOL.optionalFieldOf("explicit_scale", false)
                    .forGetter(WorldScaleSettingsState::hasExplicitScale),
            // Optional so worlds saved before rivers existed still load.
            Codec.STRING.optionalFieldOf("river_mode", RiverMode.DEFAULT.id())
                    .forGetter(state -> state.riverMode.id()),
            RiverParametersCodec.CODEC.optionalFieldOf("river_parameters", RiverParameters.DEFAULT)
                    .forGetter(state -> state.riverParameters),
            CaveParametersCodec.CODEC.optionalFieldOf("cave_parameters", CaveParameters.DEFAULT)
                    .forGetter(state -> state.caveParameters)
    ).apply(instance, WorldScaleSettingsState::new));

    /** Nested for the same builder-field-limit reason as the river codec. */
    private static final class CaveParametersCodec {
        static final Codec<CaveParameters> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("small_seal", CaveParameters.DEFAULT_SMALL_SEAL_BLOCKS)
                        .forGetter(p -> p.smallSealBlocks),
                Codec.INT.optionalFieldOf("large_seal", CaveParameters.DEFAULT_LARGE_SEAL_BLOCKS)
                        .forGetter(p -> p.largeSealBlocks)
        ).apply(instance, CaveParameters::new));
    }

    /** Nested codec so the settings record stays within the builder's field limit. */
    private static final class RiverParametersCodec {
        static final Codec<RiverParameters> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("main_cells", RiverParameters.DEFAULT_MAIN_CHANNEL_CELLS)
                        .forGetter(p -> p.mainChannelCells),
                Codec.INT.optionalFieldOf("headwater_cells", RiverParameters.DEFAULT_HEADWATER_CELLS)
                        .forGetter(p -> p.headwaterCells),
                Codec.INT.optionalFieldOf("max_width", RiverParameters.DEFAULT_MAX_WIDTH_BLOCKS)
                        .forGetter(p -> p.maxWidthBlocks),
                Codec.INT.optionalFieldOf("max_depth", RiverParameters.DEFAULT_MAX_DEPTH_BLOCKS)
                        .forGetter(p -> p.maxDepthBlocks),
                Codec.FLOAT.optionalFieldOf("width_exponent", RiverParameters.DEFAULT_WIDTH_EXPONENT)
                        .forGetter(p -> p.widthExponent),
                Codec.FLOAT.optionalFieldOf("freeboard", RiverParameters.DEFAULT_FREEBOARD_BLOCKS)
                        .forGetter(p -> p.freeboardBlocks),
                Codec.INT.optionalFieldOf("lake_min_cells", RiverParameters.DEFAULT_LAKE_MIN_CELLS)
                        .forGetter(p -> p.lakeMinCells),
                Codec.FLOAT.optionalFieldOf("lake_depth", RiverParameters.DEFAULT_LAKE_DEPTH_BLOCKS)
                        .forGetter(p -> p.lakeDepthBlocks),
                Codec.FLOAT.optionalFieldOf("edge_wobble", RiverParameters.DEFAULT_EDGE_WOBBLE_BLOCKS)
                        .forGetter(p -> p.edgeWobbleBlocks),
                Codec.FLOAT.optionalFieldOf("bed_relief", RiverParameters.DEFAULT_BED_RELIEF_BLOCKS)
                        .forGetter(p -> p.bedReliefBlocks)
        ).apply(instance, RiverParameters::new));
    }

    private int scale;
    private boolean explicitScale;
    private RiverMode riverMode;
    private RiverParameters riverParameters;
    private CaveParameters caveParameters;

    /**
     * Creates a default state for worlds that do not yet have saved terrain diffusion settings.
     */
    private WorldScaleSettingsState(int configuredScale, boolean hasExplicitScale, String riverModeId,
                                    RiverParameters riverParameters, CaveParameters caveParameters) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = hasExplicitScale;
        this.riverMode = RiverMode.byId(riverModeId);
        this.riverParameters = riverParameters;
        this.caveParameters = caveParameters;
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false, RiverMode.DEFAULT.id(),
                RiverParameters.DEFAULT, CaveParameters.DEFAULT);
    }

    public RiverMode getRiverMode() {
        return riverMode;
    }

    public void setRiverMode(RiverMode mode) {
        this.riverMode = mode;
        setDirty();
    }

    public RiverParameters getRiverParameters() {
        return riverParameters;
    }

    public void setRiverParameters(RiverParameters parameters) {
        this.riverParameters = parameters;
        setDirty();
    }

    public CaveParameters getCaveParameters() {
        return caveParameters;
    }

    public void setCaveParameters(CaveParameters parameters) {
        this.caveParameters = parameters;
        setDirty();
    }

    /**
     * Type descriptor used by the saved data storage.
     */
    public static final SavedData.Factory<WorldScaleSettingsState> TYPE =
            new SavedData.Factory<>(
                    WorldScaleSettingsState::createDefault,
                    WorldScaleSettingsState::fromNbt,
                    null
            );

    // Type descriptor helper
    public static WorldScaleSettingsState fromNbt(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        return CODEC.parse(NbtOps.INSTANCE, nbt)
                .result()
                .orElseGet(WorldScaleSettingsState::createDefault);
    }

    // Type descriptor helper
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        CODEC.encodeStart(NbtOps.INSTANCE, this)
                .result()
                .ifPresent(encoded -> nbt.merge((CompoundTag) encoded));
        return nbt;
    }

    /**
     * Returns the currently persisted world scale.
     */
    public int getScale() {
        return scale;
    }

    /**
     * Returns whether this world has an explicitly chosen scale.
     */
    public boolean hasExplicitScale() {
        return explicitScale;
    }

    /**
     * Applies a new persisted world scale and marks the state dirty.
     */
    public void setScale(int configuredScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = true;
        setDirty();
    }
}
