package com.vinlanx.luxium.client.posteffects;

import com.mojang.blaze3d.shaders.Uniform;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestiaLighting;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import com.vinlanx.luxium.client.sunmoonapi.CelestialPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class fog {
   private static final float FOG_HEIGHT = 72.0F;
   private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();
   private static final Matrix4f INVERSE_VIEW = new Matrix4f();
   private static final Vector4f VIEW_ORIGIN = new Vector4f();
   private static final fog.FogConfigState CONFIG_STATE = new fog.FogConfigState();
   private static final fog.FogFrameState FRAME_STATE = new fog.FogFrameState();
   private static boolean configDirty = true;
   private static long frameSerial = Long.MIN_VALUE;
   private static fog.FogUniforms uniformSlot0;
   private static fog.FogUniforms uniformSlot1;

   private fog() {
   }

   public static void onConfigChanged() {
      configDirty = true;
      frameSerial = Long.MIN_VALUE;
   }

   static boolean isEnabled() {
      fog.FogConfigState config = config();
      return config.renderFog || config.celestialScatteringEnabled;
   }

   @Nullable
   static ShaderInstance getShader() {
      return ShaderManager.getFogShader();
   }

   static void configure(ShaderInstance shader, RenderLevelStageEvent event, Minecraft mc, int depthTexture) {
      shader.setSampler("DepthSampler", depthTexture);
      fog.FogUniforms uniforms = uniforms(shader);
      fog.FogFrameState state = frameState(event, mc);
      setMatrix(uniforms.inverseProjMat, INVERSE_PROJECTION);
      setMatrix(uniforms.inverseViewMat, INVERSE_VIEW);
      set3f(uniforms.fogSunDir, state.sunX, state.sunY, state.sunZ);
      set1i(uniforms.fogEnabled, state.renderFog ? 1 : 0);
      set1i(uniforms.celestialScatteringEnabled, state.celestialScatteringEnabled ? 1 : 0);
      set1f(uniforms.fogDensityAtOrigin, state.densityAtOrigin);
      set1f(uniforms.fogExtinction, state.densitySetting);
      set1f(uniforms.fogScatteringBrightness, state.scatteringBrightness);
      set1f(uniforms.fogMaxBrightness, state.maxBrightness);
      set1f(uniforms.fogNearDensityBoost, state.nearDensityBoost);
      set1f(uniforms.fogNearBoostRange, state.nearBoostRange);
      set1i(uniforms.fogDynamicCelestialColor, state.dynamicCelestialColor ? 1 : 0);
      set1f(uniforms.fogCelestialColorBlend, state.celestialColorBlend);
      set3f(uniforms.fogCelestialColor, state.celestialFogR, state.celestialFogG, state.celestialFogB);
      set1f(uniforms.fogStartDistance, state.startDistance);
      set1f(uniforms.fogNearFade, state.nearFade);
      set1f(uniforms.fogMaxOpacity, state.maxOpacity);
      set1f(uniforms.fogSkyTint, state.skyTint);
      set1f(uniforms.fogScatteringStrength, state.scatteringStrength);
      set1f(uniforms.fogDistanceCurve, state.distanceCurve);
      set1f(uniforms.fogHeightFalloff, state.heightFalloff);
      set3f(uniforms.fogAmbientColor, state.ambientR, state.ambientG, state.ambientB);
      set3f(uniforms.fogSkyColor, state.skyR, state.skyG, state.skyB);
      set3f(uniforms.fogSunColor, state.sunR, state.sunG, state.sunB);
      set3f(uniforms.fogMoonColor, state.moonR, state.moonG, state.moonB);
      set4f(uniforms.fogMieParams, 0.86F, 0.94F, 0.82F, 0.91F);
      set1f(uniforms.fogSunVisibility, state.sunFogVisibility);
      set1f(uniforms.fogMoonVisibility, state.moonFogVisibility);
   }

   private static fog.FogConfigState config() {
      if (configDirty) {
         CONFIG_STATE.refresh();
         configDirty = false;
      }

      return CONFIG_STATE;
   }

   private static fog.FogFrameState frameState(RenderLevelStageEvent event, Minecraft mc) {
      long currentFrame = NeoSkyFrameCache.frameSerial();
      if (frameSerial != currentFrame) {
         FRAME_STATE.update(event, mc, config());
         frameSerial = currentFrame;
      }

      return FRAME_STATE;
   }

   private static fog.FogUniforms uniforms(ShaderInstance shader) {
      if (uniformSlot0 != null && uniformSlot0.shader == shader) {
         return uniformSlot0;
      } else if (uniformSlot1 != null && uniformSlot1.shader == shader) {
         return uniformSlot1;
      } else {
         fog.FogUniforms resolved = new fog.FogUniforms(shader);
         if (uniformSlot0 == null) {
            uniformSlot0 = resolved;
         } else if (uniformSlot1 == null) {
            uniformSlot1 = resolved;
         } else {
            uniformSlot0 = uniformSlot1;
            uniformSlot1 = resolved;
         }

         return resolved;
      }
   }

   private static float lerp(float a, float b, float t) {
      return Mth.lerp(Mth.clamp(t, 0.0F, 1.0F), a, b);
   }

   private static float smoothstep(float edge0, float edge1, float value) {
      float denominator = edge1 - edge0;
      if (Math.abs(denominator) < 1.0E-5F) {
         denominator = Math.copySign(1.0E-5F, denominator == 0.0F ? 1.0F : denominator);
      }

      float t = Mth.clamp((value - edge0) / denominator, 0.0F, 1.0F);
      return t * t * (3.0F - 2.0F * t);
   }

   private static void set1i(Uniform uniform, int value) {
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void set1f(Uniform uniform, float value) {
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void set3f(Uniform uniform, float x, float y, float z) {
      if (uniform != null) {
         uniform.set(x, y, z);
      }
   }

   private static void set4f(Uniform uniform, float x, float y, float z, float w) {
      if (uniform != null) {
         uniform.set(x, y, z, w);
      }
   }

   private static void setMatrix(Uniform uniform, Matrix4f matrix) {
      if (uniform != null) {
         uniform.set(matrix);
      }
   }

   private static final class FogConfigState {
      private boolean renderFog;
      private boolean celestialScatteringEnabled;
      private boolean dynamicCelestialColor;
      private float densitySetting;
      private float scatteringBrightness;
      private float maxBrightness;
      private float nearDensityBoost;
      private float nearBoostRange;
      private float celestialColorBlend;
      private float startDistance;
      private float nearFade;
      private float maxOpacity;
      private float skyTint;
      private float scatteringStrength;
      private float distanceCurve;
      private float heightFalloff;

      private void refresh() {
         this.renderFog = Config.isFeatureEnabled(Config.CLIENT.fogEnabled);
         this.celestialScatteringEnabled = Config.isFeatureEnabled(Config.CLIENT.fogCelestialScatteringEnabled);
         this.dynamicCelestialColor = Config.isFeatureEnabled(Config.CLIENT.fogDynamicCelestialColor);
         this.densitySetting = Mth.clamp(((Double)Config.CLIENT.fogDensity.get()).floatValue(), 0.0F, 20.0F);
         this.scatteringBrightness = Mth.clamp(((Double)Config.CLIENT.fogScatteringBrightness.get()).floatValue(), 0.25F, 1.2F);
         this.maxBrightness = Mth.clamp(((Double)Config.CLIENT.fogMaxBrightness.get()).floatValue(), 0.15F, 1.2F);
         this.nearDensityBoost = Mth.clamp(((Double)Config.CLIENT.fogNearDensityBoost.get()).floatValue(), 0.0F, 5.0F);
         this.nearBoostRange = Mth.clamp(((Double)Config.CLIENT.fogNearBoostRange.get()).floatValue(), 4.0F, 128.0F);
         this.celestialColorBlend = Mth.clamp(((Double)Config.CLIENT.fogCelestialColorBlend.get()).floatValue(), 0.0F, 1.0F);
         this.startDistance = Mth.clamp(((Double)Config.CLIENT.fogStartDistance.get()).floatValue(), 0.0F, 160.0F);
         this.nearFade = Mth.clamp(((Double)Config.CLIENT.fogNearFade.get()).floatValue(), 0.0F, 64.0F);
         this.maxOpacity = Mth.clamp(((Double)Config.CLIENT.fogMaxOpacity.get()).floatValue(), 0.0F, 1.0F);
         this.skyTint = Mth.clamp(((Double)Config.CLIENT.fogSkyTint.get()).floatValue(), 0.0F, 1.0F);
         this.scatteringStrength = Mth.clamp(((Double)Config.CLIENT.fogScatteringStrength.get()).floatValue(), 0.0F, 5.0F);
         this.distanceCurve = Mth.clamp(((Double)Config.CLIENT.fogDistanceCurve.get()).floatValue(), 0.35F, 3.0F);
         float fogHeight = Mth.clamp(((Double)Config.CLIENT.fogHeight.get()).floatValue(), 50.0F, 384.0F);
         this.heightFalloff = 4.0F / fogHeight;
      }
   }

   private static final class FogFrameState {
      private boolean renderFog;
      private boolean celestialScatteringEnabled;
      private boolean dynamicCelestialColor;
      private float sunX;
      private float sunY;
      private float sunZ;
      private float densityAtOrigin;
      private float densitySetting;
      private float scatteringBrightness;
      private float maxBrightness;
      private float nearDensityBoost;
      private float nearBoostRange;
      private float celestialColorBlend;
      private float celestialFogR;
      private float celestialFogG;
      private float celestialFogB;
      private float startDistance;
      private float nearFade;
      private float maxOpacity;
      private float skyTint;
      private float scatteringStrength;
      private float distanceCurve;
      private float heightFalloff;
      private float ambientR;
      private float ambientG;
      private float ambientB;
      private float skyR;
      private float skyG;
      private float skyB;
      private float sunR;
      private float sunG;
      private float sunB;
      private float moonR;
      private float moonG;
      private float moonB;
      private float sunFogVisibility;
      private float moonFogVisibility;

      private void update(RenderLevelStageEvent event, Minecraft mc, fog.FogConfigState config) {
         NeoSkyFrameCache.copyInverseProjection(event.getProjectionMatrix(), fog.INVERSE_PROJECTION);
         NeoSkyFrameCache.copyInverseView(event.getPoseStack().last().pose(), fog.INVERSE_VIEW);
         Vec3 cameraPos = event.getCamera().getPosition();
         fog.VIEW_ORIGIN.set(0.0F, 0.0F, 0.0F, 1.0F).mul(fog.INVERSE_VIEW);
         float rayOriginY = (float)cameraPos.y + fog.VIEW_ORIGIN.y;
         CelestialPath.State celestialPath = NeoSkyFrameCache.celestial(mc.level, event.getPartialTick());
         Vec3 sunDirection = celestialPath.sunDirection();
         this.sunX = (float)sunDirection.x;
         this.sunY = (float)sunDirection.y;
         this.sunZ = (float)sunDirection.z;
         float rain = mc.level != null ? mc.level.getRainLevel(event.getPartialTick()) : 0.0F;
         float thunder = mc.level != null ? mc.level.getThunderLevel(event.getPartialTick()) : 0.0F;
         float weather = Mth.clamp(rain * 0.7F + thunder * 0.55F, 0.0F, 1.0F);
         float sunsetPeak = fog.smoothstep(-0.08F, 0.06F, this.sunY) * (1.0F - fog.smoothstep(0.08F, 0.3F, this.sunY));
         float night = 1.0F - fog.smoothstep(-0.16F, 0.02F, this.sunY);
         float timeDensityMultiplier = 1.0F + sunsetPeak * 0.28F - night * 0.18F;
         float weatherDensityMultiplier = 1.0F + weather * 1.15F;
         float altitudeGate = fog.smoothstep(28.0F, 58.0F, rayOriginY);
         float baseDensity = config.renderFog ? 0.0058F * timeDensityMultiplier * weatherDensityMultiplier * altitudeGate : 0.0F;
         this.densityAtOrigin = baseDensity * (float)Math.exp((double)(-config.heightFalloff * (rayOriginY - 72.0F)));
         this.renderFog = config.renderFog;
         this.celestialScatteringEnabled = config.celestialScatteringEnabled;
         this.dynamicCelestialColor = config.dynamicCelestialColor;
         this.densitySetting = config.densitySetting;
         this.scatteringBrightness = config.scatteringBrightness;
         this.maxBrightness = config.maxBrightness;
         this.nearDensityBoost = config.nearDensityBoost;
         this.nearBoostRange = config.nearBoostRange;
         this.celestialColorBlend = config.celestialColorBlend;
         this.startDistance = config.startDistance;
         this.nearFade = config.nearFade;
         this.maxOpacity = config.maxOpacity;
         this.skyTint = config.skyTint;
         this.scatteringStrength = config.scatteringStrength;
         this.distanceCurve = config.distanceCurve;
         this.heightFalloff = config.heightFalloff;
         NeoSkyCelestiaLighting.State lighting = NeoSkyFrameCache.lighting(mc.level, event.getPartialTick());
         this.celestialFogR = Mth.lerp(0.42F, lighting.skyAmbientColor().x, lighting.directColor().x);
         this.celestialFogG = Mth.lerp(0.42F, lighting.skyAmbientColor().y, lighting.directColor().y);
         this.celestialFogB = Mth.lerp(0.42F, lighting.skyAmbientColor().z, lighting.directColor().z);
         float nightToTwilight = fog.smoothstep(-0.18F, 0.04F, this.sunY);
         float twilightToDay = fog.smoothstep(0.02F, 0.32F, this.sunY);
         this.ambientR = fog.lerp(0.018F, 0.22F, nightToTwilight);
         this.ambientG = fog.lerp(0.03F, 0.2F, nightToTwilight);
         this.ambientB = fog.lerp(0.06F, 0.24F, nightToTwilight);
         this.ambientR = fog.lerp(this.ambientR, 0.245F, twilightToDay);
         this.ambientG = fog.lerp(this.ambientG, 0.335F, twilightToDay);
         this.ambientB = fog.lerp(this.ambientB, 0.455F, twilightToDay);
         float ambientWeather = Mth.clamp(weather * 0.78F, 0.0F, 1.0F);
         this.ambientR = fog.lerp(this.ambientR, 0.2F, ambientWeather);
         this.ambientG = fog.lerp(this.ambientG, 0.25F, ambientWeather);
         this.ambientB = fog.lerp(this.ambientB, 0.31F, ambientWeather);
         this.skyR = fog.lerp(0.028F, 0.42F, nightToTwilight);
         this.skyG = fog.lerp(0.055F, 0.31F, nightToTwilight);
         this.skyB = fog.lerp(0.115F, 0.34F, nightToTwilight);
         this.skyR = fog.lerp(this.skyR, 0.36F, twilightToDay);
         this.skyG = fog.lerp(this.skyG, 0.55F, twilightToDay);
         this.skyB = fog.lerp(this.skyB, 0.78F, twilightToDay);
         float skyWeather = Mth.clamp(weather * 0.82F, 0.0F, 1.0F);
         this.skyR = fog.lerp(this.skyR, 0.25F, skyWeather);
         this.skyG = fog.lerp(this.skyG, 0.32F, skyWeather);
         this.skyB = fog.lerp(this.skyB, 0.4F, skyWeather);
         float sunsetFactor = 1.0F - fog.smoothstep(0.02F, 0.3F, this.sunY);
         this.sunFogVisibility = fog.smoothstep(-0.06F, 0.1F, this.sunY);
         float sunMix = Mth.clamp(sunsetFactor * (1.0F - night), 0.0F, 1.0F);
         float sunScale = this.sunFogVisibility * (1.0F - weather * 0.42F);
         this.sunR = fog.lerp(1.1F, 1.55F, sunMix) * sunScale;
         this.sunG = fog.lerp(0.94F, 0.62F, sunMix) * sunScale;
         this.sunB = fog.lerp(0.7F, 0.2F, sunMix) * sunScale;
         this.moonFogVisibility = fog.smoothstep(0.05F, 0.2F, -this.sunY);
         float moonScale = this.moonFogVisibility * (1.0F - weather * 0.35F);
         this.moonR = 0.34F * moonScale;
         this.moonG = 0.44F * moonScale;
         this.moonB = 0.68F * moonScale;
      }
   }

   private static final class FogUniforms {
      private final ShaderInstance shader;
      private final Uniform inverseProjMat;
      private final Uniform inverseViewMat;
      private final Uniform fogSunDir;
      private final Uniform fogEnabled;
      private final Uniform celestialScatteringEnabled;
      private final Uniform fogDensityAtOrigin;
      private final Uniform fogExtinction;
      private final Uniform fogScatteringBrightness;
      private final Uniform fogMaxBrightness;
      private final Uniform fogNearDensityBoost;
      private final Uniform fogNearBoostRange;
      private final Uniform fogDynamicCelestialColor;
      private final Uniform fogCelestialColorBlend;
      private final Uniform fogCelestialColor;
      private final Uniform fogStartDistance;
      private final Uniform fogNearFade;
      private final Uniform fogMaxOpacity;
      private final Uniform fogSkyTint;
      private final Uniform fogScatteringStrength;
      private final Uniform fogDistanceCurve;
      private final Uniform fogHeightFalloff;
      private final Uniform fogAmbientColor;
      private final Uniform fogSkyColor;
      private final Uniform fogSunColor;
      private final Uniform fogMoonColor;
      private final Uniform fogMieParams;
      private final Uniform fogSunVisibility;
      private final Uniform fogMoonVisibility;

      private FogUniforms(ShaderInstance shader) {
         this.shader = shader;
         this.inverseProjMat = shader.getUniform("InverseProjMat");
         this.inverseViewMat = shader.getUniform("InverseViewMat");
         this.fogSunDir = shader.getUniform("FogSunDir");
         this.fogEnabled = shader.getUniform("FogEnabled");
         this.celestialScatteringEnabled = shader.getUniform("CelestialScatteringEnabled");
         this.fogDensityAtOrigin = shader.getUniform("FogDensityAtOrigin");
         this.fogExtinction = shader.getUniform("FogExtinction");
         this.fogScatteringBrightness = shader.getUniform("FogScatteringBrightness");
         this.fogMaxBrightness = shader.getUniform("FogMaxBrightness");
         this.fogNearDensityBoost = shader.getUniform("FogNearDensityBoost");
         this.fogNearBoostRange = shader.getUniform("FogNearBoostRange");
         this.fogDynamicCelestialColor = shader.getUniform("FogDynamicCelestialColor");
         this.fogCelestialColorBlend = shader.getUniform("FogCelestialColorBlend");
         this.fogCelestialColor = shader.getUniform("FogCelestialColor");
         this.fogStartDistance = shader.getUniform("FogStartDistance");
         this.fogNearFade = shader.getUniform("FogNearFade");
         this.fogMaxOpacity = shader.getUniform("FogMaxOpacity");
         this.fogSkyTint = shader.getUniform("FogSkyTint");
         this.fogScatteringStrength = shader.getUniform("FogScatteringStrength");
         this.fogDistanceCurve = shader.getUniform("FogDistanceCurve");
         this.fogHeightFalloff = shader.getUniform("FogHeightFalloff");
         this.fogAmbientColor = shader.getUniform("FogAmbientColor");
         this.fogSkyColor = shader.getUniform("FogSkyColor");
         this.fogSunColor = shader.getUniform("FogSunColor");
         this.fogMoonColor = shader.getUniform("FogMoonColor");
         this.fogMieParams = shader.getUniform("FogMieParams");
         this.fogSunVisibility = shader.getUniform("FogSunVisibility");
         this.fogMoonVisibility = shader.getUniform("FogMoonVisibility");
      }
   }
}
