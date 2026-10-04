package com.vinlanx.luxium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.client.tfrpluslsr.LsrSystem;
import com.vinlanx.luxium.client.tfrpluslsr.TemporalFrameSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({GlStateManager.class})
public class GlStateManagerMixin {
   @Inject(
      method = {"_enableCull"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void luxium$preventCullInShadowPass(CallbackInfo ci) {
      if (NeoShadowsEngine.isShadowCapturePass() || NeoSkyCelestia.get().isRenderingTerrainShadowPass()) {
         ci.cancel();
      }
   }

   @Inject(
      method = {"_viewport"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void luxium$clampViewportToRenderTarget(int x, int y, int w, int h, CallbackInfo ci) {
      if (NeoShadowsEngine.isAnyShadowCapturePass()
         || ReflectionSystem.isRenderingWorldPass()
         || TemporalFrameSystem.isWorldPassActive()
         || LsrSystem.isWorldPassActive()) {
         Minecraft mc = Minecraft.getInstance();
         RenderTarget target = mc.getMainRenderTarget();
         if (target != null && (w > target.viewWidth || h > target.viewHeight)) {
            GL11.glViewport(x, y, target.viewWidth, target.viewHeight);
            ci.cancel();
         }
      }
   }
}
