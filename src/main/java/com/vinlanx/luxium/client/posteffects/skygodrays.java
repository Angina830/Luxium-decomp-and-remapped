package com.vinlanx.luxium.client.posteffects;

import com.mojang.blaze3d.shaders.Uniform;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import com.vinlanx.luxium.client.sunmoonapi.CelestialPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class skygodrays {
   private static final float SUN_QUAD_CENTER_Y = 100.0F;
   private static final float MOON_QUAD_CENTER_Y = -100.0F;
   @Nullable
   private static skygodrays.CelestialScreenPos capturedCelestial;

   private skygodrays() {
   }

   public static void beginSkyCapture() {
      capturedCelestial = null;
   }

   public static void captureCelestial(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, boolean useMoon, float partialTick) {
      if (isEnabled() || lensflare.isEnabled()) {
         skygodrays.CelestialScreenPos celestial = projectCelestialToScreen(modelViewMatrix, projectionMatrix, useMoon, partialTick);
         if (celestial != null) {
            capturedCelestial = celestial;
         }
      }
   }

   static boolean isEnabled() {
      return Config.isFeatureEnabled(Config.CLIENT.skyGodRaysEnabled);
   }

   static boolean hasVisibleCelestial() {
      return capturedCelestial != null;
   }

   @Nullable
   static ShaderInstance getShader() {
      return ShaderManager.getSkyGodRaysShader();
   }

   static void configure(ShaderInstance shader, int width, int height, int depthTexture) {
      configure(shader, width, height, depthTexture, 0);
   }

   static void configure(ShaderInstance shader, int width, int height, int depthTexture, int cloudOcclusionTexture) {
      skygodrays.CelestialScreenPos celestial = capturedCelestial;
      if (celestial != null) {
         configureCelestialSamplers(shader, depthTexture, cloudOcclusionTexture);
         setUniform2f(shader, "SunScreenPos", celestial.screenX, celestial.screenY);
         setUniform3f(shader, "LightColor", celestial.color.x, celestial.color.y, celestial.color.z);
         setUniform1f(shader, "ScreenFade", celestial.fade);
         setUniform1f(shader, "UseMoonStyle", celestial.useMoonStyle ? 1.0F : 0.0F);
         setUniform2f(shader, "ScreenSize", (float)width, (float)height);
         setUniform1f(shader, "RayStrength", celestial.rayStrength);
         setUniform1f(shader, "HaloStrength", celestial.haloStrength);
         setUniform1f(shader, "DiscStrength", celestial.discStrength);
         setUniform1f(shader, "HaloRadius", celestial.haloRadius);
         setUniform1f(shader, "DiscRadius", celestial.discRadius);
         setUniform1f(shader, "CenterSuppression", celestial.centerSuppression);
      }
   }

   static void configureCelestialUniforms(ShaderInstance shader, int width, int height, int depthTexture) {
      configureCelestialUniforms(shader, width, height, depthTexture, 0);
   }

   static void configureCelestialUniforms(ShaderInstance shader, int width, int height, int depthTexture, int cloudOcclusionTexture) {
      skygodrays.CelestialScreenPos celestial = capturedCelestial;
      if (celestial != null) {
         configureCelestialSamplers(shader, depthTexture, cloudOcclusionTexture);
         setUniform2f(shader, "SunScreenPos", celestial.screenX, celestial.screenY);
         setUniform3f(shader, "LightColor", celestial.color.x, celestial.color.y, celestial.color.z);
         setUniform1f(shader, "ScreenFade", celestial.fade);
         setUniform1f(shader, "UseMoonStyle", celestial.useMoonStyle ? 1.0F : 0.0F);
         setUniform2f(shader, "ScreenSize", (float)width, (float)height);
      }
   }

   private static void configureCelestialSamplers(ShaderInstance shader, int depthTexture, int cloudOcclusionTexture) {
      shader.setSampler("DepthSampler", depthTexture);
      shader.setSampler("CloudOcclusionSampler", cloudOcclusionTexture != 0 ? cloudOcclusionTexture : depthTexture);
      setUniform1f(shader, "UseCloudOcclusion", cloudOcclusionTexture != 0 ? 1.0F : 0.0F);
   }

   @Nullable
   private static skygodrays.CelestialScreenPos projectCelestialToScreen(
      Matrix4f modelViewMatrix, Matrix4f projectionMatrix, boolean useMoon, float partialTick
   ) {
      Vector4f clip = new Vector4f(0.0F, useMoon ? -100.0F : 100.0F, 0.0F, 1.0F);
      clip.mul(modelViewMatrix);
      clip.mul(projectionMatrix);
      if (clip.w <= 0.0F) {
         return null;
      } else {
         float ndcX = clip.x / clip.w;
         float ndcY = clip.y / clip.w;
         float screenX = (ndcX + 1.0F) * 0.5F;
         float screenY = (ndcY + 1.0F) * 0.5F;
         if (!(screenX < -0.25F) && !(screenX > 1.25F) && !(screenY < -0.25F) && !(screenY > 1.25F)) {
            float distFromCenter = (float)Math.sqrt((double)(ndcX * ndcX + ndcY * ndcY));
            float fade = 1.0F - Mth.clamp((distFromCenter - 0.8F) / 0.5F, 0.0F, 1.0F);
            skygodrays.CelestialLook look = buildCelestialLook(useMoon, partialTick);
            fade *= look.horizonFade;
            return fade <= 0.0F
               ? null
               : new skygodrays.CelestialScreenPos(
                  screenX,
                  screenY,
                  fade,
                  look.color,
                  look.rayStrength,
                  look.haloStrength,
                  look.discStrength,
                  look.haloRadius,
                  look.discRadius,
                  look.centerSuppression,
                  useMoon
               );
         } else {
            return null;
         }
      }
   }

   private static skygodrays.CelestialLook buildCelestialLook(boolean useMoon, float partialTick) {
      Minecraft mc = Minecraft.getInstance();
      CelestialPath.State celestialPath = NeoSkyFrameCache.celestial(mc.level, partialTick);
      float elevation = (float)celestialPath.elevation(useMoon);
      float visible = smoothstep(0.0F, 0.12F, elevation);
      float lowAngle = (1.0F - smoothstep(0.1F, 0.55F, elevation)) * visible;
      float golden = (1.0F - Mth.clamp(Math.abs(elevation - 0.08F) / 0.32F, 0.0F, 1.0F)) * visible;
      float high = smoothstep(0.25F, 0.85F, elevation);
      float rain = mc.level != null ? mc.level.getRainLevel(partialTick) : 0.0F;
      float thunder = mc.level != null ? mc.level.getThunderLevel(partialTick) : 0.0F;
      float rayIntensity = Mth.clamp(((Double)Config.CLIENT.skyGodRaysRayIntensity.get()).floatValue(), 0.0F, 2.5F);
      float haloIntensity = Mth.clamp(((Double)Config.CLIENT.skyGodRaysHaloIntensity.get()).floatValue(), 0.0F, 2.5F);
      float discIntensity = Mth.clamp(((Double)Config.CLIENT.skyGodRaysDiscIntensity.get()).floatValue(), 0.0F, 2.0F);
      float haloSize = Mth.clamp(((Double)Config.CLIENT.skyGodRaysHaloSize.get()).floatValue(), 0.5F, 2.5F);
      float discSize = Mth.clamp(((Double)Config.CLIENT.skyGodRaysDiscSize.get()).floatValue(), 0.5F, 1.8F);
      float sunSizeSlider = Mth.clamp(((Double)Config.CLIENT.skyGodRaysSunSize.get()).floatValue(), 0.5F, 2.0F);
      float moonSizeSlider = Mth.clamp(((Double)Config.CLIENT.skyGodRaysMoonSize.get()).floatValue(), 0.5F, 2.0F);
      float sunSize = sunSizeSlider * sunSizeSlider * sunSizeSlider;
      float moonSize = moonSizeSlider * moonSizeSlider * moonSizeSlider;
      float weatherInfluence = Mth.clamp(((Double)Config.CLIENT.skyGodRaysWeatherInfluence.get()).floatValue(), 0.0F, 2.0F);
      float centerSuppression = Mth.clamp(((Double)Config.CLIENT.skyGodRaysCenterSuppression.get()).floatValue(), 0.0F, 2.0F);
      float weatherOcclusion = Mth.clamp(1.0F - (rain * 0.55F + thunder * 0.25F) * weatherInfluence, 0.2F, 1.0F);
      float haze = Mth.clamp((rain * 0.65F + thunder * 0.35F) * weatherInfluence, 0.0F, 1.0F);
      boolean discEnabled = Config.isFeatureEnabled(Config.CLIENT.skyGodRaysCelestialDiscEnabled);
      boolean haloEnabled = Config.isFeatureEnabled(Config.CLIENT.skyGodRaysHaloEnabled);
      Vector3f sunDayColor = colorFromConfig((Integer)Config.CLIENT.skyGodRaysSunDayColor.get());
      Vector3f sunHorizonColor = colorFromConfig((Integer)Config.CLIENT.skyGodRaysSunsetColor.get());
      Vector3f moonBaseColor = colorFromConfig((Integer)Config.CLIENT.skyGodRaysMoonBaseColor.get());
      Vector3f moonHaloColor = colorFromConfig((Integer)Config.CLIENT.skyGodRaysMoonHaloColor.get());
      if (useMoon) {
         Vector3f color = mix(moonBaseColor, moonHaloColor, 0.25F * lowAngle);
         float rayStrength = (0.05F + 0.11F * lowAngle + 0.06F * haze) * weatherOcclusion * visible * rayIntensity;
         float haloStrength = haloEnabled ? (0.08F + 0.1F * lowAngle + 0.05F * haze) * visible * haloIntensity : 0.0F;
         float discStrength = discEnabled ? 0.035F * visible * discIntensity : 0.0F;
         float haloRadius = (0.033F + 0.017F * lowAngle + 0.01F * haze) * haloSize * moonSize;
         return new skygodrays.CelestialLook(
            color, visible, rayStrength, haloStrength, discStrength, haloRadius, 0.0067F * discSize * moonSize, centerSuppression
         );
      } else {
         Vector3f color = mix(sunHorizonColor, sunDayColor, high);
         color = mix(color, sunHorizonColor, golden * 0.55F);
         float rayStrength = (0.55F + 1.15F * golden + 0.34F * haze) * weatherOcclusion * visible * rayIntensity;
         float haloStrength = haloEnabled ? (0.34F + 0.56F * lowAngle + 0.22F * haze) * weatherOcclusion * visible * haloIntensity : 0.0F;
         float discStrength = discEnabled ? (0.12F + 0.06F * high) * visible * discIntensity : 0.0F;
         float haloRadius = (0.047F + 0.04F * lowAngle + 0.017F * haze) * haloSize * sunSize;
         return new skygodrays.CelestialLook(
            color, visible, rayStrength, haloStrength, discStrength, haloRadius, 0.0133F * discSize * sunSize, centerSuppression
         );
      }
   }

   private static Vector3f colorFromConfig(int rgb) {
      return new Vector3f((float)(rgb >> 16 & 0xFF) / 255.0F, (float)(rgb >> 8 & 0xFF) / 255.0F, (float)(rgb & 0xFF) / 255.0F);
   }

   private static Vector3f mix(Vector3f a, Vector3f b, float t) {
      float f = Mth.clamp(t, 0.0F, 1.0F);
      return new Vector3f(Mth.lerp(f, a.x, b.x), Mth.lerp(f, a.y, b.y), Mth.lerp(f, a.z, b.z));
   }

   private static float smoothstep(float edge0, float edge1, float value) {
      float denominator = edge1 - edge0;
      if (Math.abs(denominator) < 1.0E-5F) {
         denominator = Math.copySign(1.0E-5F, denominator == 0.0F ? 1.0F : denominator);
      }

      float t = Mth.clamp((value - edge0) / denominator, 0.0F, 1.0F);
      return t * t * (3.0F - 2.0F * t);
   }

   private static void setUniform1f(ShaderInstance shader, String name, float value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void setUniform2f(ShaderInstance shader, String name, float x, float y) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y);
      }
   }

   private static void setUniform3f(ShaderInstance shader, String name, float x, float y, float z) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y, z);
      }
   }

   private static record CelestialLook(
      Vector3f color, float horizonFade, float rayStrength, float haloStrength, float discStrength, float haloRadius, float discRadius, float centerSuppression
   ) {
   }

   private static record CelestialScreenPos(
      float screenX,
      float screenY,
      float fade,
      Vector3f color,
      float rayStrength,
      float haloStrength,
      float discStrength,
      float haloRadius,
      float discRadius,
      float centerSuppression,
      boolean useMoonStyle
   ) {
   }
}
