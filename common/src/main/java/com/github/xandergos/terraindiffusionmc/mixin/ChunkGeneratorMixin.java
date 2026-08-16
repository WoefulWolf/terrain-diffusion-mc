package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.RiverWaterFiller;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps surface structures out of standing water.
 *
 * <p>A lake is not a biome — it is ordinary land the drainage flooded, so it carries
 * whatever biome its shore does, and a village asked to place on plains has no way to
 * know the plains in question is under four metres of water. Vanilla's own water
 * structures are unaffected: their homes are the ocean biomes, and a river surface is
 * only ever claimed above sea level, so the sea never trips this.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$keepStructuresDry(
            StructureSet.StructureSelectionEntry entry, StructureManager structureManager,
            RegistryAccess registryAccess, RandomState randomState,
            StructureTemplateManager templateManager, long seed, ChunkAccess chunk,
            ChunkPos chunkPos, SectionPos sectionPos, CallbackInfoReturnable<Boolean> cir) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (!(self.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        if (RiverWaterFiller.standsInWater(chunkPos.getMinBlockX(), chunkPos.getMinBlockZ())) {
            cir.setReturnValue(false);
        }
    }
}
