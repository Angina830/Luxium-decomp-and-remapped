package com.vinlanx.luxium.client.posteffects;

import com.mojang.blaze3d.shaders.Uniform;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.tfrpluslsr.LsrSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

public final class tonemap {
   private tonemap() {
   }

   static boolean isEnabled() {
      return Config.isFeatureEnabled(Config.CLIENT.tonemapEnabled);
   }

   @Nullable
   public static ShaderInstance getShader() {
      return ShaderManager.getTonemapShader();
   }

   public static void configure(ShaderInstance shader, int sceneTexture, int effectsTexture, boolean compositeEffects) {
      configure(shader, sceneTexture, effectsTexture, compositeEffects, false, 1, 1);
   }

   public static void configure(
      ShaderInstance shader, int sceneTexture, int effectsTexture, boolean compositeEffects, boolean lsrEnabled, int sourceWidth, int sourceHeight
   ) {
      shader.setSampler("SceneSampler", sceneTexture);
      shader.setSampler("EffectsSampler", effectsTexture);
      setUniform1i(shader, "CompositeEffects", compositeEffects ? 1 : 0);
      setUniform1i(shader, "LsrEnabled", lsrEnabled ? 1 : 0);
      setUniform2f(shader, "SceneTexelSize", 1.0F / (float)Math.max(1, sourceWidth), 1.0F / (float)Math.max(1, sourceHeight));
      setUniform1f(shader, "LsrSharpness", lsrEnabled ? LsrSystem.sharpness() : 0.0F);
      setUniform1f(shader, "TonemapExposure", Mth.clamp(((Double)Config.CLIENT.tonemapExposure.get()).floatValue(), -2.0F, 2.0F));
      setUniform1f(shader, "TonemapContrast", Mth.clamp(((Double)Config.CLIENT.tonemapContrast.get()).floatValue(), 0.0F, 1.0F));
      setUniform1f(shader, "TonemapHighlightCompression", Mth.clamp(((Double)Config.CLIENT.tonemapHighlightCompression.get()).floatValue(), 0.0F, 1.0F));
      setUniform1f(shader, "TonemapShadowDepth", Mth.clamp(((Double)Config.CLIENT.tonemapShadowDepth.get()).floatValue(), 0.0F, 1.0F));
      setUniform1f(shader, "TonemapSaturation", Mth.clamp(((Double)Config.CLIENT.tonemapSaturation.get()).floatValue(), 0.0F, 2.0F));
      setUniform1f(shader, "TonemapVibrance", Mth.clamp(((Double)Config.CLIENT.tonemapVibrance.get()).floatValue(), 0.0F, 1.0F));
      setUniform1f(shader, "TonemapGamma", Mth.clamp(((Double)Config.CLIENT.tonemapGamma.get()).floatValue(), 1.6F, 2.8F));
      setUniform1f(shader, "TonemapStrength", Mth.clamp(((Double)Config.CLIENT.tonemapStrength.get()).floatValue(), 0.0F, 1.0F));
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

   private static void setUniform2f(ShaderInstance shader, String name, float x, float y) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y);
      }
   }
}
