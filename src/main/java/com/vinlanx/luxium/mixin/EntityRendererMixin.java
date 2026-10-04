package com.vinlanx.luxium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.Testing.TestFlashLight;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.VanillaLavaLightEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({EntityRenderer.class})
public abstract class EntityRendererMixin<T extends Entity> {
   @Inject(
      method = {"render"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void luxium$skipEntityExtrasInCelestialShadow(
      T entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight, CallbackInfo ci
   ) {
      if (NeoSkyCelestia.get().isRenderingEntityShadowPass() || NeoShadowsEngine.isGpuShadowAtlasCapturePass()) {
         ci.cancel();
      }
   }

   @Inject(
      method = {"getPackedLightCoords"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void luxium$rtxEntityLight(T entity, float partialTick, CallbackInfoReturnable<Integer> cir) {
      if (!NeoShadowsEngine.isAnyShadowCapturePass()) {
         TorchRtxState rtx = TorchRtxState.get();
         if (rtx.isEnabled()) {
            BlockPos pos = entity.blockPosition();
            int rtxLight = rtx.sampleLightForRender(pos);
            VanillaLavaLightEngine lava = VanillaLavaLightEngine.get();
            if (rtxLight >= 0 || lava.isActive()) {
               rtxLight = Math.max(0, rtxLight);
               int vanilla = (Integer)cir.getReturnValue();
               int vanillaBlock = vanilla >> 4 & 15;
               int vanillaSky = vanilla >> 20 & 15;
               boolean hybridLocalLighting = Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled)
                  && !Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled)
                  && Config.CLIENT.gpuLocalLightingMode.get() == Config.GpuLocalLightingMode.HYBRID;
               int best = hybridLocalLighting ? rtxLight : Math.max(vanillaBlock, rtxLight);
               if (hybridLocalLighting && lava.isActive()) {
                  best = Math.max(best, lava.sampleLight(pos));
               }

               best = Math.max(best, TestFlashLight.sampleLight(pos));
               cir.setReturnValue(LightTexture.pack(Math.max(0, Math.min(15, best)), vanillaSky));
            }
         }
      }
   }
}
