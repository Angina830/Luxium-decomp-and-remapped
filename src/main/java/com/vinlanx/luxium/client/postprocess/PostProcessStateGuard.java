package com.vinlanx.luxium.client.postprocess;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.client.tfrpluslsr.LsrSystem;
import com.vinlanx.luxium.client.tfrpluslsr.TemporalFrameSystem;
import com.vinlanx.luxium.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.jetbrains.annotations.Nullable;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT}
)
public final class PostProcessStateGuard {
   @Nullable
   private static RenderTarget canonicalMainTarget;
   private static boolean repairedGuiCompositeStateThisFrame;
   private static boolean unsafeGuiCompositeStateSeenThisSession;

   private PostProcessStateGuard() {
   }

   public static void captureCanonicalMainTarget(Minecraft mc) {
      repairedGuiCompositeStateThisFrame = false;
      RenderTarget current = ((MinecraftAccessor)mc).luxium$getMainRenderTargetField();
      if (current instanceof MainTarget) {
         canonicalMainTarget = current;
      }
   }

   public static boolean repairedGuiCompositeStateThisFrame() {
      return repairedGuiCompositeStateThisFrame;
   }

   public static boolean unsafeGuiCompositeStateSeenThisSession() {
      return unsafeGuiCompositeStateSeenThisSession;
   }

   public static void restoreCanonicalMainTargetBinding(Minecraft mc) {
      if (TemporalFrameSystem.isWorldPassActive()) {
         TemporalFrameSystem.rebindWorldTarget();
      } else if (LsrSystem.isWorldPassActive()) {
         LsrSystem.rebindWorldTarget();
      } else {
         RenderTarget target = canonicalMainTarget;
         MinecraftAccessor accessor = (MinecraftAccessor)mc;
         RenderTarget current = accessor.luxium$getMainRenderTargetField();
         boolean repairedTarget = false;
         if (target != null) {
            if (current != target) {
               accessor.luxium$setMainRenderTargetField(target);
               repairedTarget = true;
            }

            target.bindWrite(true);
         } else if (mc.getMainRenderTarget() != null) {
            mc.getMainRenderTarget().bindWrite(true);
            repairedTarget = !(current instanceof MainTarget);
         }

         repairedGuiCompositeStateThisFrame |= repairedTarget;
         unsafeGuiCompositeStateSeenThisSession |= repairedTarget;
      }
   }

   public static void restoreGuiCompositeState() {
      restoreGuiCompositeState(Minecraft.getInstance());
   }

   public static void restoreGuiCompositeState(Minecraft mc) {
      restoreCanonicalMainTargetBinding(mc);
      RenderTarget target = mc.getMainRenderTarget();
      int viewportWidth = mc.getWindow().getWidth();
      int viewportHeight = mc.getWindow().getHeight();
      if (target != null) {
         viewportWidth = target.viewWidth;
         viewportHeight = target.viewHeight;
      }

      RenderSystem.viewport(0, 0, viewportWidth, viewportHeight);
      RenderSystem.activeTexture(33984);
      RenderSystem.setShaderTexture(0, 0);
      RenderSystem.setShaderTexture(1, 0);
      RenderSystem.setShaderTexture(2, 0);
      GlStateManager._colorMask(true, true, true, true);
      GlStateManager._depthMask(true);
      GlStateManager._disableDepthTest();
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.disableCull();
      RenderSystem.depthFunc(515);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
   }
}
