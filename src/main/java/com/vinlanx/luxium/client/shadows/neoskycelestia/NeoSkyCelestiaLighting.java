package com.vinlanx.luxium.client.shadows.neoskycelestia;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.sunmoonapi.CelestialPath;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class NeoSkyCelestiaLighting {
   private static final Vector3f DAY_SKY = new Vector3f(0.94F, 0.97F, 1.0F);
   private static final Vector3f SUNSET_SKY = new Vector3f(0.86F, 0.66F, 0.54F);
   private static final Vector3f NIGHT_SKY = new Vector3f(0.48F, 0.56F, 0.7F);
   private static final Vector3f DAY_GROUND = new Vector3f(0.72F, 0.7F, 0.68F);
   private static final Vector3f SUNSET_GROUND = new Vector3f(0.55F, 0.42F, 0.36F);
   private static final Vector3f NIGHT_GROUND = new Vector3f(0.3F, 0.34F, 0.44F);
   private static final float NIGHT_TO_TWILIGHT_START = -0.18F;
   private static final float NIGHT_TO_TWILIGHT_END = 0.04F;
   private static final float TWILIGHT_TO_DAY_START = 0.02F;
   private static final float TWILIGHT_TO_DAY_END = 0.32F;
   private static final float DIRECT_HORIZON_FADE_END = 0.14F;
   private static final float DIRECT_COLOR_BLEND_START = -0.08F;
   private static final float DIRECT_COLOR_BLEND_END = 0.08F;

   private NeoSkyCelestiaLighting() {
   }

   public static NeoSkyCelestiaLighting.State calculate(ClientLevel level, float partialTick, CelestialPath.State celestialPath) {
      if (level != null && level.dimensionType().hasSkyLight()) {
         boolean moon = celestialPath.usingMoon();
         Vec3 sunDirection = celestialPath.sunDirection();
         Vec3 activeDirection = celestialPath.activeDirection();
         float sunElevation = (float)sunDirection.y;
         float activeElevation = Math.max(0.0F, (float)activeDirection.y);
         float nightToTwilight = smoothstep(-0.18F, 0.04F, sunElevation);
         float twilightToDay = smoothstep(0.02F, 0.32F, sunElevation);
         float directColorBlend = smoothstep(-0.08F, 0.08F, sunElevation);
         float directVisibility = smoothstep(0.0F, 0.14F, activeElevation);
         float sunInfluence = smoothstep(0.0F, 0.14F, sunElevation);
         float highAngle = smoothstep(0.25F, 0.85F, Math.max(0.0F, sunElevation));
         float weather = Mth.clamp(level.getRainLevel(partialTick) * 0.65F + level.getThunderLevel(partialTick) * 0.35F, 0.0F, 1.0F);
         float weatherDirect = Mth.lerp(weather, 1.0F, 0.3F);
         float weatherAmbient = Mth.lerp(weather, 1.0F, 0.62F);
         Vector3f sunZenithColor = colorFromConfig((Integer)Config.CLIENT.skyLightSunZenithColor.get());
         Vector3f sunsetColor = colorFromConfig((Integer)Config.CLIENT.skyLightSunsetColor.get());
         Vector3f moonColor = colorFromConfig((Integer)Config.CLIENT.skyLightMoonColor.get());
         Vector3f sunDirectColor = mix(sunsetColor, sunZenithColor, highAngle);
         Vector3f directColor = mix(moonColor, sunDirectColor, directColorBlend);
         Vector3f skyAmbient = mix(mix(NIGHT_SKY, SUNSET_SKY, nightToTwilight), DAY_SKY, twilightToDay);
         Vector3f groundAmbient = mix(mix(NIGHT_GROUND, SUNSET_GROUND, nightToTwilight), DAY_GROUND, twilightToDay);
         float directBaseStrength;
         float globalDirectScale;
         if (moon) {
            directBaseStrength = ((Double)Config.CLIENT.skyLightMoonBaseStrength.get()).floatValue() * 0.01F;
            globalDirectScale = ((Double)Config.CLIENT.skyLightMoonStrength.get()).floatValue() * 0.01F;
         } else {
            float sunsetBaseStrength = ((Double)Config.CLIENT.skyLightSunsetStrength.get()).floatValue() * 0.01F;
            float zenithBaseStrength = ((Double)Config.CLIENT.skyLightSunZenithStrength.get()).floatValue() * 0.01F;
            directBaseStrength = Mth.lerp(highAngle, sunsetBaseStrength, zenithBaseStrength);
            globalDirectScale = ((Double)Config.CLIENT.skyLightSunStrength.get()).floatValue() * 0.01F;
         }

         float directStrength = directBaseStrength * directVisibility * weatherDirect * globalDirectScale;
         float twilightAmbientStrength = Mth.lerp(nightToTwilight, 0.72F, 0.82F);
         float dayAmbientStrength = Mth.lerp(highAngle, 0.82F, 0.98F);
         float ambientStrength = Mth.lerp(twilightToDay, twilightAmbientStrength, dayAmbientStrength)
            * weatherAmbient
            * ((Double)Config.CLIENT.skyLightAmbientStrength.get()).floatValue()
            * 0.01F;
         boolean colorsEnabled = Config.isFeatureEnabled(Config.CLIENT.skyLightColorsEnabled);
         if (!colorsEnabled) {
            directColor.set(1.0F);
            skyAmbient.set(1.0F);
            groundAmbient.set(1.0F);
         }

         Vector3f shadowSkyAmbient = new Vector3f(skyAmbient);
         Vector3f shadowGroundAmbient = new Vector3f(groundAmbient);
         if (colorsEnabled && Config.isFeatureEnabled(Config.CLIENT.realisticShadowTemperature)) {
            float temperatureStrength = ((Double)Config.CLIENT.shadowTemperatureStrength.get()).floatValue();
            int temperatureBias = (Integer)Config.CLIENT.shadowTemperatureBias.get();
            shadowSkyAmbient = applyShadowTemperature(shadowSkyAmbient, directColor, temperatureStrength, temperatureBias);
            shadowGroundAmbient = applyShadowTemperature(shadowGroundAmbient, directColor, temperatureStrength, temperatureBias);
         }

         return new NeoSkyCelestiaLighting.State(
            Config.isFeatureEnabled(Config.CLIENT.skyLightEnabled),
            moon,
            new Vector3f((float)activeDirection.x, (float)activeDirection.y, (float)activeDirection.z).normalize(),
            directColor,
            skyAmbient,
            groundAmbient,
            shadowSkyAmbient,
            shadowGroundAmbient,
            directStrength,
            ambientStrength,
            directVisibility,
            sunInfluence
         );
      } else {
         return NeoSkyCelestiaLighting.State.disabled();
      }
   }

   private static Vector3f applyShadowTemperature(Vector3f ambient, Vector3f directColor, float strength, int bias) {
      float amount = Mth.clamp(strength, 0.0F, 1.0F);
      float manualShift = Mth.clamp((float)bias / 100.0F, -1.0F, 1.0F) * 0.55F;
      if (amount <= 0.0F && Math.abs(manualShift) <= 1.0E-5F) {
         return new Vector3f(ambient);
      } else {
         float warmth = (float)(Math.log((double)((directColor.x + 0.05F) / (directColor.z + 0.05F))) / Math.log(5.0));
         warmth = Mth.clamp(warmth, -1.0F, 1.0F);
         float shift = warmth * amount * 0.38F + manualShift;
         float redScale = (float)Math.exp((double)(-shift));
         float blueScale = (float)Math.exp((double)shift);
         Vector3f shifted = new Vector3f(ambient.x * redScale, ambient.y, ambient.z * blueScale);
         float sourceLuminance = luminance(ambient);
         float shiftedLuminance = luminance(shifted);
         if (shiftedLuminance > 1.0E-5F) {
            shifted.mul(sourceLuminance / shiftedLuminance);
         }

         shifted.x = Mth.clamp(shifted.x, 0.0F, 1.25F);
         shifted.y = Mth.clamp(shifted.y, 0.0F, 1.25F);
         shifted.z = Mth.clamp(shifted.z, 0.0F, 1.25F);
         return shifted;
      }
   }

   private static float luminance(Vector3f color) {
      return Math.max(color.x, 0.0F) * 0.2126F + Math.max(color.y, 0.0F) * 0.7152F + Math.max(color.z, 0.0F) * 0.0722F;
   }

   private static Vector3f mix(Vector3f a, Vector3f b, float value) {
      float t = Mth.clamp(value, 0.0F, 1.0F);
      return new Vector3f(Mth.lerp(t, a.x, b.x), Mth.lerp(t, a.y, b.y), Mth.lerp(t, a.z, b.z));
   }

   private static Vector3f colorFromConfig(int rgb) {
      return new Vector3f((float)(rgb >> 16 & 0xFF) / 255.0F, (float)(rgb >> 8 & 0xFF) / 255.0F, (float)(rgb & 0xFF) / 255.0F);
   }

   private static float smoothstep(float edge0, float edge1, float value) {
      float t = Mth.clamp((value - edge0) / Math.max(edge1 - edge0, 1.0E-5F), 0.0F, 1.0F);
      return t * t * (3.0F - 2.0F * t);
   }

   public static record State(
      boolean enabled,
      boolean moon,
      Vector3f direction,
      Vector3f directColor,
      Vector3f skyAmbientColor,
      Vector3f groundAmbientColor,
      Vector3f shadowSkyAmbientColor,
      Vector3f shadowGroundAmbientColor,
      float directStrength,
      float ambientStrength,
      float visibility,
      float sunInfluence
   ) {
      public static NeoSkyCelestiaLighting.State disabled() {
         return new NeoSkyCelestiaLighting.State(
            false,
            false,
            new Vector3f(0.0F, 1.0F, 0.0F),
            new Vector3f(1.0F),
            new Vector3f(1.0F),
            new Vector3f(1.0F),
            new Vector3f(1.0F),
            new Vector3f(1.0F),
            0.0F,
            1.0F,
            0.0F,
            0.0F
         );
      }
   }
}
