package com.vinlanx.luxium.client.ssr;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.water.WaterSurfaceRenderer;
import org.jetbrains.annotations.Nullable;

public final class ScreenSpaceReflectionSystem {
   private static final SsrHitBuffer WATER_HITS = new SsrHitBuffer();

   private ScreenSpaceReflectionSystem() {
   }

   public static SsrSettings settings(SsrConsumer consumer) {
      return switch (consumer) {
         case WATER -> waterSettings();
         case PUDDLE -> puddleSettings();
         case WET -> wetSettings();
      };
   }

   public static void resolveLateFrame() {
      WaterSurfaceRenderer.renderLateReflections();
   }

   public static boolean beginHitTrace(SsrConsumer consumer, int framebufferId, int width, int height) {
      SsrHitBuffer buffer = hitBuffer(consumer);
      return buffer != null && buffer.beginCapture(framebufferId, width, height);
   }

   public static void endHitTrace(SsrConsumer consumer, boolean successful) {
      SsrHitBuffer buffer = hitBuffer(consumer);
      if (buffer != null) {
         buffer.endCapture(successful);
      }
   }

   public static boolean isHitTraceActive(SsrConsumer consumer) {
      SsrHitBuffer buffer = hitBuffer(consumer);
      return buffer != null && buffer.captureActive();
   }

   public static boolean isHitReady(SsrConsumer consumer) {
      SsrHitBuffer buffer = hitBuffer(consumer);
      return buffer != null && buffer.ready();
   }

   public static int hitTexture(SsrConsumer consumer) {
      SsrHitBuffer buffer = hitBuffer(consumer);
      return buffer != null ? buffer.textureId() : -1;
   }

   public static void invalidateHit(SsrConsumer consumer) {
      SsrHitBuffer buffer = hitBuffer(consumer);
      if (buffer != null) {
         buffer.invalidate();
      }
   }

   @Nullable
   private static SsrHitBuffer hitBuffer(SsrConsumer consumer) {
      return consumer == SsrConsumer.WATER ? WATER_HITS : null;
   }

   private static SsrSettings waterSettings() {
      if (Config.isFeatureEnabled(Config.CLIENT.waterEnabled) && Config.isFeatureEnabled(Config.CLIENT.waterSsrEnabled)) {
         int[] budget = qualityBudget();
         return new SsrSettings(
            true,
            budget[0],
            budget[1],
            clamp(((Double)Config.CLIENT.waterSsrMaxDistance.get()).floatValue(), 4.0F, 128.0F),
            clamp(((Double)Config.CLIENT.waterSsrThickness.get()).floatValue(), 0.02F, 2.0F),
            clamp(((Double)Config.CLIENT.waterSsrEdgeFade.get()).floatValue(), 0.01F, 0.4F),
            clamp(((Double)Config.CLIENT.waterSsrStrength.get()).floatValue(), 0.0F, 2.0F)
         );
      } else {
         return SsrSettings.disabled();
      }
   }

   private static SsrSettings puddleSettings() {
      if (!Config.isFeatureEnabled(Config.CLIENT.puddleSsrEnabled)) {
         return SsrSettings.disabled();
      } else {
         int[] budget = qualityBudget();
         return new SsrSettings(
            true,
            budget[0],
            budget[1],
            clamp(((Double)Config.CLIENT.puddleSsrMaxDistance.get()).floatValue(), 4.0F, 96.0F),
            clamp(((Double)Config.CLIENT.puddleSsrThickness.get()).floatValue(), 0.02F, 2.0F),
            clamp(((Double)Config.CLIENT.puddleSsrEdgeFade.get()).floatValue(), 0.01F, 0.4F),
            clamp(((Double)Config.CLIENT.puddleSsrStrength.get()).floatValue(), 0.0F, 2.0F)
         );
      }
   }

   private static SsrSettings wetSettings() {
      if (Config.isFeatureEnabled(Config.CLIENT.wetEnabled) && Config.isFeatureEnabled(Config.CLIENT.wetSsrEnabled)) {
         int[] budget = qualityBudget();
         return new SsrSettings(
            true,
            budget[0],
            budget[1],
            clamp(((Double)Config.CLIENT.wetSsrMaxDistance.get()).floatValue(), 4.0F, 96.0F),
            clamp(((Double)Config.CLIENT.wetSsrThickness.get()).floatValue(), 0.02F, 2.0F),
            clamp(((Double)Config.CLIENT.wetSsrEdgeFade.get()).floatValue(), 0.01F, 0.4F),
            clamp(((Double)Config.CLIENT.wetSsrStrength.get()).floatValue(), 0.0F, 2.0F)
         );
      } else {
         return SsrSettings.disabled();
      }
   }

   private static int[] qualityBudget() {
      float quality = clamp(((Double)Config.CLIENT.ssrQuality.get()).floatValue(), 0.25F, 1.0F);
      int coarseSteps = 5 + Math.round(quality * 11.0F);
      int refinementSteps = quality < 0.6F ? 1 : (quality < 0.9F ? 2 : 3);
      return new int[]{coarseSteps, refinementSteps};
   }

   private static float clamp(float value, float min, float max) {
      return Math.max(min, Math.min(max, value));
   }
}
