package com.vinlanx.luxium.client.posteffects;

import com.mojang.blaze3d.shaders.Uniform;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.ShaderManager;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

public final class lensflare {
   private lensflare() {
   }

   static boolean isEnabled() {
      return Config.isFeatureEnabled(Config.CLIENT.lensFlareEnabled);
   }

   @Nullable
   static ShaderInstance getShader() {
      return ShaderManager.getLensFlareShader();
   }

   static void configure(ShaderInstance shader, int width, int height, int depthTexture) {
      configure(shader, width, height, depthTexture, 0);
   }

   static void configure(ShaderInstance shader, int width, int height, int depthTexture, int cloudOcclusionTexture) {
      skygodrays.configureCelestialUniforms(shader, width, height, depthTexture, cloudOcclusionTexture);
      configureEffectUniforms(shader);
   }

   static void configureEffectUniforms(ShaderInstance shader) {
      setUniform1f(shader, "FlareIntensity", Mth.clamp(((Double)Config.CLIENT.lensFlareIntensity.get()).floatValue(), 0.0F, 3.0F));
      setUniform1f(shader, "StreakIntensity", Mth.clamp(((Double)Config.CLIENT.lensFlareStreakIntensity.get()).floatValue(), 0.0F, 3.0F));
      setUniform1f(shader, "StreakLength", Mth.clamp(((Double)Config.CLIENT.lensFlareStreakLength.get()).floatValue(), 0.25F, 3.0F));
      setUniform1f(shader, "StreakWidth", Mth.clamp(((Double)Config.CLIENT.lensFlareStreakWidth.get()).floatValue(), 0.25F, 3.0F));
      setUniform1f(shader, "ChromaticSpread", Mth.clamp(((Double)Config.CLIENT.lensFlareChromaticSpread.get()).floatValue(), 0.0F, 3.0F));
      setUniform1f(shader, "GhostIntensity", Mth.clamp(((Double)Config.CLIENT.lensFlareGhostIntensity.get()).floatValue(), 0.0F, 3.0F));
      setUniform1f(shader, "GhostSize", Mth.clamp(((Double)Config.CLIENT.lensFlareGhostSize.get()).floatValue(), 0.35F, 3.0F));
      setUniform1f(shader, "FlareSpread", Mth.clamp(((Double)Config.CLIENT.lensFlareSpread.get()).floatValue(), 0.35F, 3.0F));
   }

   private static void setUniform1f(ShaderInstance shader, String name, float value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }
}
