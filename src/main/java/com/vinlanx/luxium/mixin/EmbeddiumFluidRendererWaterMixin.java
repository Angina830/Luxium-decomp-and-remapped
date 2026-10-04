package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.water.WaterTerrainPass;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(
   value = {FluidRenderer.class},
   remap = false
)
public abstract class EmbeddiumFluidRendererWaterMixin {
   @Unique
   private static final Material luxium$waterMaterial = new Material(WaterTerrainPass.get(), AlphaCutoffParameter.ONE, true);

   @Redirect(
      method = {"render"},
      at = @At(
         value = "INVOKE",
         target = "Lme/jellysquid/mods/sodium/client/render/chunk/terrain/material/DefaultMaterials;forFluidState(Lnet/minecraft/world/level/material/FluidState;)Lme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;"
      )
   )
   private Material luxium$tagWaterMaterial(FluidState state) {
      Material original = DefaultMaterials.forFluidState(state);
      return Config.isFeatureEnabled(Config.CLIENT.waterEnabled) && state.is(FluidTags.WATER) && original.pass == DefaultTerrainRenderPasses.TRANSLUCENT
         ? luxium$waterMaterial
         : original;
   }
}
