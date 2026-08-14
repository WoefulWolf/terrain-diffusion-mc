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
                    .forGetter(state -> state.riverMode.id())
    ).apply(instance, WorldScaleSettingsState::new));

    private int scale;
    private boolean explicitScale;
    private RiverMode riverMode;

    /**
     * Creates a default state for worlds that do not yet have saved terrain diffusion settings.
     */
    private WorldScaleSettingsState(int configuredScale, boolean hasExplicitScale, String riverModeId) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = hasExplicitScale;
        this.riverMode = RiverMode.byId(riverModeId);
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false, RiverMode.DEFAULT.id());
    }

    public RiverMode getRiverMode() {
        return riverMode;
    }

    public void setRiverMode(RiverMode mode) {
        this.riverMode = mode;
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
