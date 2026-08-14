package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.RiverAwareAquifer;
import com.github.xandergos.terraindiffusionmc.world.RiverMode;
import com.github.xandergos.terraindiffusionmc.world.RiverWaterFiller;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fills river channels before surface building, which is where vanilla has already placed
 * its own water and where carvers have not yet run.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {

    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("HEAD"))
    private void terrainDiffusion$fillRivers(WorldGenRegion level, StructureManager structureManager,
                                             RandomState random, ChunkAccess chunk, CallbackInfo ci) {
        // Every dimension shares this generator, so only ours may touch the chunk.
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (self.getBiomeSource() instanceof TerrainDiffusionBiomeSource) {
            RiverWaterFiller.fill(chunk);
        }
    }

    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("TAIL"))
    private void terrainDiffusion$paintRiverBeds(WorldGenRegion level, StructureManager structureManager,
                                                 RandomState random, ChunkAccess chunk, CallbackInfo ci) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (self.getBiomeSource() instanceof TerrainDiffusionBiomeSource) {
            RiverWaterFiller.paintBeds(chunk);
        }
    }

    /**
     * Structure layout asks for column heights before any block exists, and the plain
     * answer comes from carved terrain alone: a village would set its houses on the
     * riverbed while its streets, placed later against the real surface, ride the water
     * above them. For height types that count fluid, water is ground.
     */
    @Inject(method = "getBaseHeight", at = @At("RETURN"), cancellable = true)
    private void terrainDiffusion$waterIsGround(int x, int z, Heightmap.Types type,
                                                LevelHeightAccessor level, RandomState random,
                                                CallbackInfoReturnable<Integer> cir) {
        if (type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG) return;
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (!(self.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return;

        int surface = RiverWaterFiller.waterSurfaceY(x, z);
        if (surface > cir.getReturnValue()) cir.setReturnValue(surface);
    }

    /**
     * Carvers run after river water is placed and would cut dry gashes through channels
     * above sea level, so their aquifer gets wrapped to refuse blocks near a river.
     */
    @Redirect(method = "applyCarvers",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;aquifer()Lnet/minecraft/world/level/levelgen/Aquifer;"))
    private Aquifer terrainDiffusion$protectRivers(NoiseChunk noiseChunk) {
        Aquifer aquifer = noiseChunk.aquifer();
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (self.getBiomeSource() instanceof TerrainDiffusionBiomeSource
                && WorldScaleManager.getRiverMode() != RiverMode.OFF) {
            return new RiverAwareAquifer(aquifer);
        }
        return aquifer;
    }
}
