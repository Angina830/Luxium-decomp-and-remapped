package com.vinlanx.luxium.client.tfrpluslsr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.posteffects.PostEffectPipeline;
import com.vinlanx.luxium.client.posteffects.tonemap;
import com.vinlanx.luxium.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

public final class LsrSystem {
   @Nullable
   private static TextureTarget lowResWorldTarget;
   @Nullable
   private static RenderTarget nativeMainTarget;
   private static boolean worldPassActive;

   private LsrSystem() {
   }

   public static boolean isEnabled() {
      return Config.isFeatureEnabled(Config.CLIENT.lsrEnabled);
   }

   public static boolean isWorldPassActive() {
      return worldPassActive;
   }

   public static boolean shouldDeferTonemap() {
      return worldPassActive && !Config.isFeatureEnabled(Config.CLIENT.tfrEnabled);
   }

   public static void onConfigChanged() {
      TemporalFrameSystem.onConfigChanged();
      PostEffectPipeline.clearDeferredTonemap();
      if (!isEnabled() && !worldPassActive && lowResWorldTarget != null) {
         lowResWorldTarget.destroyBuffers();
         lowResWorldTarget = null;
      }
   }

   public static boolean beginWorldRender(Minecraft mc) {
      worldPassActive = false;
      nativeMainTarget = null;
      PostEffectPipeline.clearDeferredTonemap();
      if (isEnabled() && mc.level != null) {
         RenderTarget nativeTarget = mc.getMainRenderTarget();
         if (nativeTarget != null && nativeTarget.width > 0 && nativeTarget.height > 0) {
            int width = expectedWidth(nativeTarget.width);
            int height = expectedHeight(nativeTarget.height);
            ensureLowResTarget(width, height);
            TextureTarget lowRes = lowResWorldTarget;
            if (lowRes == null) {
               return false;
            } else {
               nativeMainTarget = nativeTarget;
               worldPassActive = true;
               MinecraftAccessor accessor = (MinecraftAccessor)mc;
               accessor.luxium$setMainRenderTargetField(lowRes);
               lowRes.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
               lowRes.clear(Minecraft.ON_OSX);
               lowRes.bindWrite(true);
               RenderSystem.viewport(0, 0, lowRes.viewWidth, lowRes.viewHeight);
               return true;
            }
         } else {
            return false;
         }
      } else {
         if (!isEnabled() && lowResWorldTarget != null) {
            lowResWorldTarget.destroyBuffers();
            lowResWorldTarget = null;
         }

         return false;
      }
   }

   public static void rebindWorldTarget() {
      if (worldPassActive && lowResWorldTarget != null) {
         lowResWorldTarget.bindWrite(true);
         RenderSystem.viewport(0, 0, lowResWorldTarget.viewWidth, lowResWorldTarget.viewHeight);
      }
   }

   public static void finishRealFrame(Minecraft mc) {
      if (worldPassActive) {
         TextureTarget source = lowResWorldTarget;
         RenderTarget destination = nativeMainTarget;
         if (source != null && destination != null) {
            MinecraftAccessor accessor = (MinecraftAccessor)mc;
            accessor.luxium$setMainRenderTargetField(destination);
            destination.bindWrite(true);
            RenderSystem.viewport(0, 0, destination.viewWidth, destination.viewHeight);
            int previousFilter = source.filterMode;
            if (previousFilter != 9729) {
               source.setFilterMode(9729);
            }

            try {
               boolean rendered = false;
               if (PostEffectPipeline.hasDeferredTonemap()) {
                  ShaderInstance shader = tonemap.getShader();
                  if (shader != null) {
                     int effectsTexture = PostEffectPipeline.getDeferredEffectsTextureId();
                     tonemap.configure(
                        shader,
                        source.getColorTextureId(),
                        effectsTexture,
                        PostEffectPipeline.deferredTonemapCompositesEffects(),
                        true,
                        source.width,
                        source.height
                     );
                     drawToNative(shader, destination);
                     rendered = true;
                  }
               }

               if (!rendered) {
                  rendered = renderSpatialUpscale(source, destination);
               }

               if (!rendered) {
                  blitFallback(source, destination);
               }
            } finally {
               if (source.filterMode != previousFilter) {
                  source.setFilterMode(previousFilter);
               }

               PostEffectPipeline.clearDeferredTonemap();
               worldPassActive = false;
               nativeMainTarget = null;
               destination.bindWrite(true);
               RenderSystem.viewport(0, 0, destination.viewWidth, destination.viewHeight);
            }
         } else {
            restoreNativeTarget(mc);
         }
      }
   }

   public static void restoreNativeTarget(Minecraft mc) {
      RenderTarget destination = nativeMainTarget;
      if (destination != null) {
         ((MinecraftAccessor)mc).luxium$setMainRenderTargetField(destination);
         destination.bindWrite(true);
         RenderSystem.viewport(0, 0, destination.viewWidth, destination.viewHeight);
      }

      PostEffectPipeline.clearDeferredTonemap();
      worldPassActive = false;
      nativeMainTarget = null;
   }

   public static int expectedWidth(int nativeWidth) {
      return Math.max(1, Math.round((float)nativeWidth * renderScale()));
   }

   public static int expectedHeight(int nativeHeight) {
      return Math.max(1, Math.round((float)nativeHeight * renderScale()));
   }

   public static float sharpness() {
      return Mth.clamp(((Double)Config.CLIENT.lsrSharpness.get()).floatValue(), 0.0F, 1.0F);
   }

   private static float renderScale() {
      return Mth.clamp(((Double)Config.CLIENT.lsrRenderScale.get()).floatValue(), 0.5F, 0.85F);
   }

   private static boolean renderSpatialUpscale(RenderTarget source, RenderTarget destination) {
      ShaderInstance shader = ShaderManager.getLsrUpscaleShader();
      if (shader == null) {
         return false;
      } else {
         shader.setSampler("SceneSampler", source.getColorTextureId());
         setUniform2f(shader, "SourceTexelSize", 1.0F / (float)Math.max(1, source.width), 1.0F / (float)Math.max(1, source.height));
         setUniform1f(shader, "LsrSharpness", sharpness());
         drawToNative(shader, destination);
         return true;
      }
   }

   private static void drawToNative(ShaderInstance shader, RenderTarget destination) {
      Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
      VertexSorting previousSorting = RenderSystem.getVertexSorting();
      int savedTexture0 = RenderSystem.getShaderTexture(0);
      int savedTexture1 = RenderSystem.getShaderTexture(1);
      int savedTexture2 = RenderSystem.getShaderTexture(2);
      float[] savedColor = RenderSystem.getShaderColor();
      Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float)destination.viewWidth, (float)destination.viewHeight, 0.0F, 1000.0F, 3000.0F);
      PoseStack modelViewStack = RenderSystem.getModelViewStack();
      modelViewStack.pushPose();
      modelViewStack.setIdentity();
      modelViewStack.translate(0.0F, 0.0F, -2000.0F);
      RenderSystem.applyModelViewMatrix();

      try {
         destination.bindWrite(true);
         RenderSystem.viewport(0, 0, destination.viewWidth, destination.viewHeight);
         RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
         if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
         }

         if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(ortho);
         }

         GlStateManager._colorMask(true, true, true, true);
         GlStateManager._disableDepthTest();
         GlStateManager._depthMask(false);
         RenderSystem.disableBlend();
         RenderSystem.disableCull();
         GlStateManager._blendFuncSeparate(1, 0, 1, 0);
         shader.apply();
         Tesselator tessellator = RenderSystem.renderThreadTesselator();
         BufferBuilder buffer = tessellator.getBuilder();
         buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
         buffer.vertex(0.0, (double)destination.viewHeight, 0.0).uv(0.0F, 0.0F).endVertex();
         buffer.vertex((double)destination.viewWidth, (double)destination.viewHeight, 0.0).uv(1.0F, 0.0F).endVertex();
         buffer.vertex((double)destination.viewWidth, 0.0, 0.0).uv(1.0F, 1.0F).endVertex();
         buffer.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).endVertex();
         BufferUploader.draw(buffer.end());
      } finally {
         shader.clear();
         modelViewStack.popPose();
         RenderSystem.applyModelViewMatrix();
         RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
         GlStateManager._depthMask(true);
         GlStateManager._enableDepthTest();
         RenderSystem.depthFunc(515);
         RenderSystem.enableCull();
         RenderSystem.defaultBlendFunc();
         RenderSystem.setShaderColor(savedColor[0], savedColor[1], savedColor[2], savedColor[3]);
         RenderSystem.setShaderTexture(0, savedTexture0);
         RenderSystem.setShaderTexture(1, savedTexture1);
         RenderSystem.setShaderTexture(2, savedTexture2);
         RenderSystem.activeTexture(33984);
         destination.bindWrite(true);
         RenderSystem.viewport(0, 0, destination.viewWidth, destination.viewHeight);
      }
   }

   private static void blitFallback(RenderTarget source, RenderTarget destination) {
      GL30.glBindFramebuffer(36008, source.frameBufferId);
      GL30.glBindFramebuffer(36009, destination.frameBufferId);
      GL30.glBlitFramebuffer(0, 0, source.width, source.height, 0, 0, destination.width, destination.height, 16384, 9729);
      destination.bindWrite(true);
   }

   private static void ensureLowResTarget(int width, int height) {
      if (lowResWorldTarget == null) {
         lowResWorldTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
         lowResWorldTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         lowResWorldTarget.setFilterMode(9729);
      } else {
         if (lowResWorldTarget.width != width || lowResWorldTarget.height != height) {
            lowResWorldTarget.resize(width, height, Minecraft.ON_OSX);
            lowResWorldTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            lowResWorldTarget.setFilterMode(9729);
         }
      }
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
