package com.vinlanx.luxium.client.posteffects;

import com.mojang.blaze3d.shaders.Uniform;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestiaFrameState;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class skyvolumetrigodrays {
   private static final float ACTIVE_EPSILON = 1.0E-4F;
   private static final long SKYLIGHT_SAMPLE_TICKS = 2L;
   private static final float ADAPTATION_SETTLE_SECONDS = 0.5F;
   private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();
   private static final Vector3f VIEW_LIGHT_DIRECTION = new Vector3f();
   private static final Vector3f RAY_COLOR = new Vector3f();
   private static Object adaptationLevel;
   private static long lastSkylightSampleGameTime = Long.MIN_VALUE;
   private static long lastAdaptationUpdateNs;
   private static float adaptationTarget;
   private static float adaptationSmoothed;
   private static boolean autoAdaptationWasEnabled;

   private skyvolumetrigodrays() {
   }

   static boolean isEnabled() {
      return Config.isFeatureEnabled(Config.CLIENT.skyVolumetricGodRaysEnabled);
   }

   static boolean shouldRender() {
      if (!isEnabled()) {
         return false;
      } else {
         NeoSkyCelestia system = NeoSkyCelestia.get();
         NeoSkyCelestiaFrameState state = system.frameState();
         return !hasShadowData(system, state)
            ? false
            : state.directStrength() > 1.0E-4F
               && ((Double)Config.CLIENT.skyVolumetricGodRaysIntensity.get()).floatValue() > 1.0E-4F
               && ((Double)Config.CLIENT.skyVolumetricGodRaysDensity.get()).floatValue() > 1.0E-4F;
      }
   }

   private static boolean hasShadowData(NeoSkyCelestia system, NeoSkyCelestiaFrameState state) {
      return system.isMainPassActive() && state.enabled() && state.nearDepthTexture() > 0 && state.farDepthTexture() > 0;
   }

   static void configure(ShaderInstance shader, RenderLevelStageEvent event, boolean inverseProjectionAlreadyConfigured) {
      NeoSkyCelestiaFrameState state = NeoSkyCelestia.get().frameState();
      shader.setSampler("VolumetricShadowMap0", state.nearDepthTexture());
      shader.setSampler("VolumetricShadowMap1", state.farDepthTexture());
      if (!inverseProjectionAlreadyConfigured) {
         NeoSkyFrameCache.copyInverseProjection(event.getProjectionMatrix(), INVERSE_PROJECTION);
         setUniformMatrix(shader, "InverseProjMat", INVERSE_PROJECTION);
      }

      setUniformMatrix(shader, "VolumetricNearLightFromView", state.nearLightFromView());
      setUniformMatrix(shader, "VolumetricFarLightFromView", state.farLightFromView());
      setUniform4f(shader, "VolumetricCascadeData", state.nearRadius(), state.farRadius(), state.baseBiasNear(), state.baseBiasFar());
      setUniform1f(shader, "VolumetricCascadeBlendStart", Mth.clamp(state.cascadeBlendStart(), 0.0F, 1.0F));
      VIEW_LIGHT_DIRECTION.set(state.lightDirection());
      event.getPoseStack().last().pose().transformDirection(VIEW_LIGHT_DIRECTION);
      if (VIEW_LIGHT_DIRECTION.lengthSquared() > 1.0E-8F) {
         VIEW_LIGHT_DIRECTION.normalize();
      } else {
         VIEW_LIGHT_DIRECTION.set(0.0F, 1.0F, 0.0F);
      }

      setUniform3f(shader, "VolumetricLightDirectionView", VIEW_LIGHT_DIRECTION.x, VIEW_LIGHT_DIRECTION.y, VIEW_LIGHT_DIRECTION.z);
      float celestialInfluence = Mth.clamp(((Double)Config.CLIENT.skyVolumetricGodRaysCelestialColorInfluence.get()).floatValue() * 0.01F, 0.0F, 1.0F);
      RAY_COLOR.set(1.0F, 1.0F, 1.0F).lerp(state.directColor(), celestialInfluence);
      setUniform3f(shader, "VolumetricLightColor", RAY_COLOR.x, RAY_COLOR.y, RAY_COLOR.z);
      setUniform1f(shader, "VolumetricLightStrength", Mth.clamp(state.directStrength(), 0.0F, 2.0F));
      setUniform1f(shader, "VolumetricIntensity", Mth.clamp(((Double)Config.CLIENT.skyVolumetricGodRaysIntensity.get()).floatValue(), 0.0F, 3.0F));
      setUniform1f(shader, "VolumetricDensity", Mth.clamp(((Double)Config.CLIENT.skyVolumetricGodRaysDensity.get()).floatValue(), 0.0F, 3.0F));
      setUniform1i(shader, "VolumetricSamples", Mth.clamp((Integer)Config.CLIENT.skyVolumetricGodRaysSamples.get(), 8, 32));
      float baseUniformity = Mth.clamp(((Double)Config.CLIENT.skyVolumetricGodRaysUniformity.get()).floatValue() * 0.01F, 0.0F, 1.0F);
      float baseSideVisibility = Mth.clamp(((Double)Config.CLIENT.skyVolumetricGodRaysSideVisibility.get()).floatValue() * 0.01F, 0.0F, 1.0F);
      float adaptation = updateAutoAdaptation();
      setUniform1f(shader, "VolumetricUniformity", Mth.lerp(adaptation, baseUniformity, 1.0F));
      setUniform1f(shader, "VolumetricSideVisibility", Mth.lerp(adaptation, baseSideVisibility, 1.0F));
      setUniform1f(
         shader, "VolumetricHazeSuppression", Mth.clamp(((Double)Config.CLIENT.skyVolumetricGodRaysHazeSuppression.get()).floatValue() * 0.01F, 0.0F, 1.0F)
      );
      boolean entityOcclusion = Config.isFeatureEnabled(Config.CLIENT.skyVolumetricGodRaysEntityOcclusion)
         && state.entityShadowEnabled()
         && state.entityDepthTexture() > 0;
      setUniform1i(shader, "VolumetricEntityOcclusionEnabled", entityOcclusion ? 1 : 0);
      if (entityOcclusion) {
         shader.setSampler("VolumetricEntityShadowMap", state.entityDepthTexture());
         setUniformMatrix(shader, "VolumetricEntityLightFromView", state.entityLightFromView());
         setUniform1f(shader, "VolumetricEntityShadowBias", Math.max(state.entityBaseBias(), 1.0E-6F));
      }

      setUniform1f(shader, "VolumetricMaxDistance", Mth.clamp(((Double)Config.CLIENT.skyVolumetricGodRaysMaxDistance.get()).floatValue(), 16.0F, 384.0F));
      setUniform1f(shader, "VolumetricAnisotropy", Mth.clamp(((Double)Config.CLIENT.skyVolumetricGodRaysAnisotropy.get()).floatValue(), 0.0F, 0.95F));
   }

   public static int sampleCameraSkylight() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level == null) {
         return -1;
      } else {
         BlockPos cameraPos = BlockPos.containing(minecraft.gameRenderer.getMainCamera().getPosition());
         minecraft.level.updateSkyBrightness();
         return Mth.clamp(minecraft.level.getMaxLocalRawBrightness(cameraPos), 0, 15);
      }
   }

   private static float updateAutoAdaptation() {
      boolean enabled = Config.isFeatureEnabled(Config.CLIENT.skyVolumetricGodRaysAutoAdaptation);
      Minecraft minecraft = Minecraft.getInstance();
      if (enabled && minecraft.level != null) {
         long nowNs = System.nanoTime();
         boolean levelChanged = adaptationLevel != minecraft.level;
         if (levelChanged) {
            adaptationLevel = minecraft.level;
            lastSkylightSampleGameTime = Long.MIN_VALUE;
            adaptationTarget = 0.0F;
            adaptationSmoothed = 0.0F;
            lastAdaptationUpdateNs = nowNs;
         }

         if (!autoAdaptationWasEnabled) {
            adaptationSmoothed = 0.0F;
            lastSkylightSampleGameTime = Long.MIN_VALUE;
            lastAdaptationUpdateNs = nowNs;
            autoAdaptationWasEnabled = true;
         }

         long gameTime = minecraft.level.getGameTime();
         if (lastSkylightSampleGameTime == Long.MIN_VALUE || gameTime < lastSkylightSampleGameTime || gameTime - lastSkylightSampleGameTime >= 2L) {
            int skyLight = sampleCameraSkylight();
            int fullAdaptationSkylight = Mth.clamp((Integer)Config.CLIENT.skyVolumetricGodRaysFullAdaptationSkylight.get(), 0, 15);
            if (fullAdaptationSkylight >= 15) {
               adaptationTarget = 1.0F;
            } else {
               adaptationTarget = Mth.clamp((15.0F - (float)skyLight) / (15.0F - (float)fullAdaptationSkylight), 0.0F, 1.0F);
            }

            lastSkylightSampleGameTime = gameTime;
         }

         if (lastAdaptationUpdateNs == 0L) {
            lastAdaptationUpdateNs = nowNs;
            return adaptationSmoothed;
         } else {
            float dt = (float)((double)(nowNs - lastAdaptationUpdateNs) * 1.0E-9);
            lastAdaptationUpdateNs = nowNs;
            dt = Mth.clamp(dt, 0.0F, 0.25F);
            if (dt > 0.0F) {
               float alpha = 1.0F - (float)Math.exp((double)(-6.0F * dt / 0.5F));
               adaptationSmoothed = adaptationSmoothed + (adaptationTarget - adaptationSmoothed) * alpha;
               if (Math.abs(adaptationTarget - adaptationSmoothed) < 1.0E-4F) {
                  adaptationSmoothed = adaptationTarget;
               }
            }

            return Mth.clamp(adaptationSmoothed, 0.0F, 1.0F);
         }
      } else {
         resetAutoAdaptation();
         return 0.0F;
      }
   }

   private static void resetAutoAdaptation() {
      adaptationLevel = null;
      lastSkylightSampleGameTime = Long.MIN_VALUE;
      lastAdaptationUpdateNs = 0L;
      adaptationTarget = 0.0F;
      adaptationSmoothed = 0.0F;
      autoAdaptationWasEnabled = false;
   }

   private static void setUniform1i(ShaderInstance shader, String name, int value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void setUniform1f(ShaderInstance shader, String name, float value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void setUniform3f(ShaderInstance shader, String name, float x, float y, float z) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y, z);
      }
   }

   private static void setUniform4f(ShaderInstance shader, String name, float x, float y, float z, float w) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y, z, w);
      }
   }

   private static void setUniformMatrix(ShaderInstance shader, String name, Matrix4f matrix) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(matrix);
      }
   }
}
