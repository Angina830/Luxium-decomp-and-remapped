package com.vinlanx.luxium.client.water;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.SharedPostResources;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import com.vinlanx.luxium.client.sunmoonapi.CelestialPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class WaterSurfaceState {
   private static boolean materialPassActive;
   private static boolean resolvePassActive;
   private static boolean lateReflectionPassActive;
   private static boolean lateResolvePassActive;
   private static boolean scaledFrameReady;
   private static boolean lateReflectionReady;
   private static long lastFrameNanos;

   private WaterSurfaceState() {
   }

   public static boolean enabled() {
      return Config.isFeatureEnabled(Config.CLIENT.waterEnabled);
   }

   public static boolean canRenderMainPass() {
      if (enabled() && !ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
         Minecraft mc = Minecraft.getInstance();
         return mc.level != null && mc.getMainRenderTarget().getColorTextureId() > 0 && mc.getMainRenderTarget().getDepthTextureId() > 0;
      } else {
         return false;
      }
   }

   public static void beginFrame() {
      materialPassActive = false;
      resolvePassActive = false;
      lateReflectionPassActive = false;
      lateResolvePassActive = false;
      scaledFrameReady = false;
      lateReflectionReady = false;
      lastFrameNanos = System.nanoTime();
   }

   static void beginMaterialPass() {
      materialPassActive = true;
      resolvePassActive = false;
      scaledFrameReady = false;
   }

   static void finishMaterialPass(boolean ready) {
      materialPassActive = false;
      scaledFrameReady = ready;
   }

   static void beginResolvePass() {
      resolvePassActive = true;
   }

   static void finishResolvePass() {
      resolvePassActive = false;
      scaledFrameReady = false;
   }

   static void beginLateReflectionPass() {
      materialPassActive = false;
      resolvePassActive = false;
      lateResolvePassActive = false;
      lateReflectionPassActive = true;
      lateReflectionReady = false;
   }

   static void finishLateReflectionPass(boolean ready) {
      lateReflectionPassActive = false;
      lateReflectionReady = ready;
   }

   static void beginLateResolvePass() {
      lateResolvePassActive = lateReflectionReady;
   }

   static void finishLateResolvePass() {
      lateResolvePassActive = false;
      lateReflectionReady = false;
   }

   public static int renderMode() {
      if (ReflectionSystem.isRenderingWorldPass() || NeoShadowsEngine.isAnyShadowCapturePass()) {
         return 0;
      } else if (materialPassActive) {
         return 1;
      } else if (resolvePassActive && scaledFrameReady) {
         return 2;
      } else if (lateReflectionPassActive) {
         return 3;
      } else {
         return lateResolvePassActive && lateReflectionReady ? 4 : 0;
      }
   }

   public static boolean isScaledPassActive() {
      return materialPassActive;
   }

   public static boolean isResolvePassActive() {
      return resolvePassActive;
   }

   public static boolean isLateReflectionPassActive() {
      return lateReflectionPassActive;
   }

   public static boolean isLateResolvePassActive() {
      return lateResolvePassActive;
   }

   public static boolean sceneReady() {
      Minecraft mc = Minecraft.getInstance();
      return canRenderMainPass() && mc.getMainRenderTarget().getColorTextureId() > 0 && mc.getMainRenderTarget().getDepthTextureId() > 0;
   }

   public static boolean resolvedReady() {
      return scaledFrameReady && SharedPostResources.getWaterRenderColorTextureId() > 0;
   }

   public static boolean lateReflectionResolvedReady() {
      return lateReflectionReady && SharedPostResources.getWaterRenderColorTextureId() > 0;
   }

   public static float timeSeconds() {
      long nanos = lastFrameNanos != 0L ? lastFrameNanos : System.nanoTime();
      return (float)((double)nanos * 1.0E-9 % 4096.0);
   }

   public static Vec3 cameraPosition() {
      return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
   }

   public static WaterSurfaceState.Light light(float partialTick) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null && level.dimensionType().hasSkyLight()) {
         CelestialPath.State path = NeoSkyFrameCache.celestial(level, partialTick);
         Vec3 direction = path.activeDirection();
         float rain = level.getRainLevel(partialTick);
         float thunder = level.getThunderLevel(partialTick);
         float weather = Math.max(0.18F, 1.0F - rain * 0.62F - thunder * 0.25F);
         float elevation = Math.max(0.0F, (float)direction.y);
         float horizonVisibility = smoothstep(0.0F, 0.14F, elevation);
         float sunElevation = (float)path.sunDirection().y;
         float sunColorBlend = smoothstep(-0.08F, 0.08F, sunElevation);
         float sunLow = 1.0F - Math.min(1.0F, Math.max(0.0F, sunElevation) * 2.2F);
         Vector3f moonColor = new Vector3f(0.48F, 0.58F, 0.82F);
         Vector3f sunColor = new Vector3f(1.0F, 0.94F - 0.26F * sunLow, 0.86F - 0.43F * sunLow);
         Vector3f color = mix(moonColor, sunColor, sunColorBlend);
         float strength;
         if (path.usingMoon()) {
            strength = 0.24F * weather * horizonVisibility;
         } else {
            strength = weather * (0.48F + 0.52F * elevation) * horizonVisibility;
         }

         return new WaterSurfaceState.Light(new Vector3f((float)direction.x, (float)direction.y, (float)direction.z).normalize(), color, strength);
      } else {
         return new WaterSurfaceState.Light(new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f(0.0F), 0.0F);
      }
   }

   private static Vector3f mix(Vector3f a, Vector3f b, float value) {
      float t = Math.max(0.0F, Math.min(1.0F, value));
      return new Vector3f(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
   }

   private static float smoothstep(float edge0, float edge1, float value) {
      float denominator = Math.max(edge1 - edge0, 1.0E-5F);
      float t = Math.max(0.0F, Math.min(1.0F, (value - edge0) / denominator));
      return t * t * (3.0F - 2.0F * t);
   }

   public static long lastFrameNanos() {
      return lastFrameNanos;
   }

   public static record Light(Vector3f direction, Vector3f color, float strength) {
   }
}
