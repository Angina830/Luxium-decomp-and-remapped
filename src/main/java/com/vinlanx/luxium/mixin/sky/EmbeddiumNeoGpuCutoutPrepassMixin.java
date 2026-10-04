package com.vinlanx.luxium.mixin.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.client.SharedPostResources;
import com.vinlanx.luxium.client.water.WaterSurfaceState;
import com.vinlanx.luxium.client.water.WaterTerrainPass;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanillaCutoutPrepass;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {ShaderChunkRenderer.class},
   remap = false
)
public abstract class EmbeddiumNeoGpuCutoutPrepassMixin {
   @Inject(
      method = {"begin"},
      at = {@At(
         value = "INVOKE",
         target = "Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;startDrawing()V",
         shift = Shift.AFTER
      )},
      require = 0
   )
   private void luxium$configureNeoGpuCutoutDepthState(TerrainRenderPass pass, CallbackInfo ci) {
      if (WaterTerrainPass.is(pass) && WaterSurfaceState.isScaledPassActive()) {
         SharedPostResources.bindWaterRenderTarget();
      } else if (pass == DefaultTerrainRenderPasses.CUTOUT) {
         if (NeoGpuVanillaCutoutPrepass.isDepthPrepass()) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(515);
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.disableBlend();
         } else if (NeoGpuVanillaCutoutPrepass.isColorPass()) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(514);
            RenderSystem.depthMask(false);
            RenderSystem.colorMask(true, true, true, true);
         }
      }
   }

   @Inject(
      method = {"end"},
      at = {@At("RETURN")},
      require = 0
   )
   private void luxium$restoreNeoGpuCutoutDepthState(TerrainRenderPass pass, CallbackInfo ci) {
      if (pass == DefaultTerrainRenderPasses.CUTOUT) {
         if (NeoGpuVanillaCutoutPrepass.isDepthPrepass() || NeoGpuVanillaCutoutPrepass.isColorPass()) {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(515);
         }
      }
   }
}
