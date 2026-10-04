package com.vinlanx.luxium.client.posteffects;

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
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.SharedPostResources;
import com.vinlanx.luxium.client.clouds.LuxiumCloudRenderer;
import com.vinlanx.luxium.client.postprocess.PostProcessSwapChain;
import com.vinlanx.luxium.client.tfrpluslsr.LsrSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.joml.Matrix4f;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT}
)
public final class PostEffectPipeline {
   private static final Matrix4f PREVIOUS_PROJECTION = new Matrix4f();
   private static TextureTarget effectsTarget;
   private static boolean deferredTonemap;
   private static boolean deferredTonemapCompositeEffects;
   private static int deferredEffectsTextureId;

   private PostEffectPipeline() {
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST
   )
   public static void onRenderLevel(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_PARTICLES) {
         if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
            clearDeferredTonemap();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
               boolean renderFog = fog.isEnabled();
               boolean renderRays = skygodrays.isEnabled() && skygodrays.hasVisibleCelestial();
               boolean renderVolumetricRays = skyvolumetrigodrays.shouldRender();
               boolean renderFlare = lensflare.isEnabled() && skygodrays.hasVisibleCelestial();
               boolean renderEffects = renderFog || renderRays || renderVolumetricRays || renderFlare;
               boolean renderTonemap = tonemap.isEnabled();
               if (renderEffects || renderTonemap) {
                  RenderTarget main = mc.getMainRenderTarget();
                  int width = main.viewWidth;
                  int height = main.viewHeight;
                  if (width > 0 && height > 0) {
                     float renderScale = Mth.clamp(((Double)Config.CLIENT.postEffectsRenderScale.get()).floatValue(), 0.1F, 1.0F);
                     boolean useEffectsTarget = renderEffects && (renderTonemap || renderScale < 0.999F);
                     int effectsWidth = useEffectsTarget ? Math.max(1, Math.round((float)width * renderScale)) : width;
                     int effectsHeight = useEffectsTarget ? Math.max(1, Math.round((float)height * renderScale)) : height;
                     if (useEffectsTarget) {
                        ensureEffectsTarget(effectsWidth, effectsHeight);
                     }

                     int effectsDepthTexture = useEffectsTarget ? main.getDepthTextureId() : SharedPostResources.getDepthTextureId();
                     PREVIOUS_PROJECTION.set(RenderSystem.getProjectionMatrix());
                     VertexSorting previousVertexSorting = RenderSystem.getVertexSorting();
                     int[] savedTextures = saveShaderTextures();
                     float[] savedShaderColor = RenderSystem.getShaderColor();
                     int cloudOcclusionTexture = 0;
                     if (renderRays || renderFlare) {
                        Vec3 cameraPos = event.getCamera().getPosition();
                        cloudOcclusionTexture = LuxiumCloudRenderer.get()
                           .renderOcclusionMask(
                              event.getPoseStack(),
                              event.getProjectionMatrix(),
                              event.getPartialTick(),
                              cameraPos.x,
                              cameraPos.y,
                              cameraPos.z,
                              event.getRenderTick(),
                              effectsWidth,
                              effectsHeight
                           );
                     }

                     PoseStack modelViewStack = RenderSystem.getModelViewStack();
                     modelViewStack.pushPose();
                     modelViewStack.setIdentity();
                     modelViewStack.translate(0.0F, 0.0F, -2000.0F);
                     RenderSystem.applyModelViewMatrix();

                     try {
                        if (renderEffects) {
                           RenderTarget destination = main;
                           int activeEffectCount = (renderFog ? 1 : 0) + (renderRays ? 1 : 0) + (renderVolumetricRays ? 1 : 0) + (renderFlare ? 1 : 0);
                           ShaderInstance combinedEffects = !renderVolumetricRays && activeEffectCount < 2
                              ? null
                              : ShaderManager.getPostEffectsCombinedShader();
                           boolean useCombinedEffects = combinedEffects != null;
                           if (useEffectsTarget) {
                              TextureTarget target = effectsTarget;
                              if (target == null) {
                                 return;
                              }

                              target.bindWrite(true);
                              boolean firstPassOverwrites = useCombinedEffects || renderFog && fog.getShader() != null;
                              if (!firstPassOverwrites) {
                                 target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                                 target.clear(Minecraft.ON_OSX);
                                 target.bindWrite(true);
                              }

                              destination = target;
                           }

                           if (useCombinedEffects) {
                              combinedEffects.setSampler("DepthSampler", effectsDepthTexture);
                              setUniform1i(combinedEffects, "RenderFogEffect", renderFog ? 1 : 0);
                              setUniform1i(combinedEffects, "RenderSkyRays", renderRays ? 1 : 0);
                              setUniform1i(combinedEffects, "RenderSkyVolumetricRays", renderVolumetricRays ? 1 : 0);
                              setUniform1i(combinedEffects, "RenderLensFlare", renderFlare ? 1 : 0);
                              setUniform1i(combinedEffects, "RenderCelestialVisibility", !renderRays && !renderFlare ? 0 : 1);
                              if (renderFog) {
                                 fog.configure(combinedEffects, event, mc, effectsDepthTexture);
                              }

                              if (renderRays) {
                                 skygodrays.configure(combinedEffects, effectsWidth, effectsHeight, effectsDepthTexture, cloudOcclusionTexture);
                              }

                              if (renderVolumetricRays) {
                                 skyvolumetrigodrays.configure(combinedEffects, event, renderFog);
                              }

                              if (renderFlare) {
                                 if (renderRays) {
                                    lensflare.configureEffectUniforms(combinedEffects);
                                 } else {
                                    lensflare.configure(combinedEffects, effectsWidth, effectsHeight, effectsDepthTexture, cloudOcclusionTexture);
                                 }
                              }

                              destination.bindWrite(true);
                              draw(
                                 combinedEffects,
                                 effectsWidth,
                                 effectsHeight,
                                 useEffectsTarget
                                    ? PostEffectPipeline.BlendMode.OVERWRITE
                                    : (renderFog ? PostEffectPipeline.BlendMode.PREMULTIPLIED : PostEffectPipeline.BlendMode.ADDITIVE)
                              );
                           } else {
                              if (renderFog) {
                                 destination.bindWrite(true);
                                 ShaderInstance shader = fog.getShader();
                                 if (shader != null) {
                                    fog.configure(shader, event, mc, effectsDepthTexture);
                                    draw(
                                       shader,
                                       effectsWidth,
                                       effectsHeight,
                                       useEffectsTarget ? PostEffectPipeline.BlendMode.OVERWRITE : PostEffectPipeline.BlendMode.PREMULTIPLIED
                                    );
                                 }
                              }

                              if (renderRays) {
                                 ShaderInstance shader = skygodrays.getShader();
                                 if (shader != null) {
                                    skygodrays.configure(shader, effectsWidth, effectsHeight, effectsDepthTexture, cloudOcclusionTexture);
                                    destination.bindWrite(true);
                                    draw(shader, effectsWidth, effectsHeight, PostEffectPipeline.BlendMode.ADDITIVE);
                                 }
                              }

                              if (renderFlare) {
                                 ShaderInstance shader = lensflare.getShader();
                                 if (shader != null) {
                                    lensflare.configure(shader, effectsWidth, effectsHeight, effectsDepthTexture, cloudOcclusionTexture);
                                    destination.bindWrite(true);
                                    draw(shader, effectsWidth, effectsHeight, PostEffectPipeline.BlendMode.ADDITIVE);
                                 }
                              }
                           }
                        }

                        if (renderTonemap) {
                           ShaderInstance shader = tonemap.getShader();
                           if (shader != null) {
                              int sceneTexture = main.getColorTextureId();
                              int effectsTexture = useEffectsTarget && effectsTarget != null ? effectsTarget.getColorTextureId() : 0;
                              if (LsrSystem.shouldDeferTonemap()) {
                                 deferredTonemap = true;
                                 deferredTonemapCompositeEffects = useEffectsTarget;
                                 deferredEffectsTextureId = effectsTexture;
                              } else {
                                 tonemap.configure(shader, sceneTexture, effectsTexture, useEffectsTarget);
                                 int previousSceneFilter = main.filterMode;
                                 if (previousSceneFilter != 9729) {
                                    main.setFilterMode(9729);
                                 }

                                 try {
                                    RenderTarget postTarget = PostProcessSwapChain.begin(main);
                                    postTarget.bindWrite(true);
                                    draw(shader, width, height, PostEffectPipeline.BlendMode.OVERWRITE);
                                 } finally {
                                    if (main.filterMode != previousSceneFilter) {
                                       main.setFilterMode(previousSceneFilter);
                                    }
                                 }

                                 PostProcessSwapChain.commit(main);
                              }

                              return;
                           }
                        } else if (useEffectsTarget && effectsTarget != null) {
                           ShaderInstance shader = ShaderManager.getPostEffectCompositeShader();
                           if (shader != null) {
                              shader.setSampler("EffectsSampler", effectsTarget.getColorTextureId());
                              main.bindWrite(true);
                              draw(shader, width, height, PostEffectPipeline.BlendMode.PREMULTIPLIED);
                           }

                           return;
                        }
                     } finally {
                        restoreRenderState(modelViewStack, previousVertexSorting, savedTextures, savedShaderColor, main);
                     }
                  }
               }
            }
         }
      }
   }

   public static boolean hasDeferredTonemap() {
      return deferredTonemap;
   }

   public static boolean deferredTonemapCompositesEffects() {
      return deferredTonemapCompositeEffects;
   }

   public static int getDeferredEffectsTextureId() {
      return deferredEffectsTextureId;
   }

   // $VF: Could not verify finally blocks. A semaphore variable has been added to preserve control flow.
   // Please report this to the Vineflower issue tracker, at https://github.com/Vineflower/vineflower/issues with a copy of the class file (if you have the rights to distribute it!)
   public static boolean flushDeferredTonemapForSceneConsumer() {
      if (!deferredTonemap) {
         return false;
      } else {
         Minecraft mc = Minecraft.getInstance();
         RenderTarget main = mc.getMainRenderTarget();
         ShaderInstance shader = tonemap.getShader();
         if (main != null && shader != null && main.viewWidth > 0 && main.viewHeight > 0) {
            Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
            VertexSorting previousVertexSorting = RenderSystem.getVertexSorting();
            int[] savedTextures = saveShaderTextures();
            float[] savedShaderColor = RenderSystem.getShaderColor();
            PoseStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushPose();
            modelViewStack.setIdentity();
            modelViewStack.translate(0.0F, 0.0F, -2000.0F);
            RenderSystem.applyModelViewMatrix();
            int previousSceneFilter = main.filterMode;
            boolean var15 = false /* VF: Semaphore variable */;

            boolean var10;
            try {
               var15 = true;
               tonemap.configure(shader, main.getColorTextureId(), deferredEffectsTextureId, deferredTonemapCompositeEffects, false, main.width, main.height);
               if (previousSceneFilter != 9729) {
                  main.setFilterMode(9729);
               }

               RenderTarget postTarget = PostProcessSwapChain.begin(main);
               postTarget.bindWrite(true);
               draw(shader, main.viewWidth, main.viewHeight, PostEffectPipeline.BlendMode.OVERWRITE);
               PostProcessSwapChain.commit(main);
               clearDeferredTonemap();
               var10 = true;
               var15 = false;
            } finally {
               if (var15) {
                  if (main.filterMode != previousSceneFilter) {
                     main.setFilterMode(previousSceneFilter);
                  }

                  modelViewStack.popPose();
                  RenderSystem.applyModelViewMatrix();
                  RenderSystem.setProjectionMatrix(previousProjection, previousVertexSorting);
                  GlStateManager._disableBlend();
                  RenderSystem.defaultBlendFunc();
                  GlStateManager._depthMask(true);
                  GlStateManager._enableDepthTest();
                  RenderSystem.depthFunc(515);
                  RenderSystem.setShaderColor(savedShaderColor[0], savedShaderColor[1], savedShaderColor[2], savedShaderColor[3]);

                  for (int i = 0; i < savedTextures.length; i++) {
                     RenderSystem.setShaderTexture(i, savedTextures[i]);
                  }

                  RenderSystem.activeTexture(33984);
                  main.bindWrite(true);
                  RenderSystem.viewport(0, 0, main.viewWidth, main.viewHeight);
               }
            }

            if (main.filterMode != previousSceneFilter) {
               main.setFilterMode(previousSceneFilter);
            }

            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousVertexSorting);
            GlStateManager._disableBlend();
            RenderSystem.defaultBlendFunc();
            GlStateManager._depthMask(true);
            GlStateManager._enableDepthTest();
            RenderSystem.depthFunc(515);
            RenderSystem.setShaderColor(savedShaderColor[0], savedShaderColor[1], savedShaderColor[2], savedShaderColor[3]);

            for (int i = 0; i < savedTextures.length; i++) {
               RenderSystem.setShaderTexture(i, savedTextures[i]);
            }

            RenderSystem.activeTexture(33984);
            main.bindWrite(true);
            RenderSystem.viewport(0, 0, main.viewWidth, main.viewHeight);
            return var10;
         } else {
            return false;
         }
      }
   }

   public static void clearDeferredTonemap() {
      deferredTonemap = false;
      deferredTonemapCompositeEffects = false;
      deferredEffectsTextureId = 0;
   }

   public static boolean needsSharedDepthSnapshot() {
      boolean renderFog = fog.isEnabled();
      boolean celestialVisible = skygodrays.hasVisibleCelestial();
      boolean renderRays = skygodrays.isEnabled() && celestialVisible;
      boolean renderVolumetricRays = skyvolumetrigodrays.shouldRender();
      boolean renderFlare = lensflare.isEnabled() && celestialVisible;
      boolean renderEffects = renderFog || renderRays || renderVolumetricRays || renderFlare;
      if (!renderEffects) {
         return false;
      } else {
         float renderScale = Mth.clamp(((Double)Config.CLIENT.postEffectsRenderScale.get()).floatValue(), 0.1F, 1.0F);
         boolean useEffectsTarget = tonemap.isEnabled() || renderScale < 0.999F;
         return !useEffectsTarget;
      }
   }

   private static void setUniform1i(ShaderInstance shader, String name, int value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void draw(ShaderInstance shader, int width, int height, PostEffectPipeline.BlendMode blendMode) {
      Matrix4f projection = new Matrix4f().setOrtho(0.0F, (float)width, (float)height, 0.0F, 1000.0F, 3000.0F);
      RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
      RenderSystem.viewport(0, 0, width, height);
      if (shader.MODEL_VIEW_MATRIX != null) {
         shader.MODEL_VIEW_MATRIX.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
      }

      if (shader.PROJECTION_MATRIX != null) {
         shader.PROJECTION_MATRIX.set(projection);
      }

      GlStateManager._disableDepthTest();
      GlStateManager._depthMask(false);
      GlStateManager._colorMask(true, true, true, true);
      shader.apply();

      try {
         applyBlendMode(blendMode);
         renderFullscreenQuad(width, height);
      } finally {
         shader.clear();
      }
   }

   private static void applyBlendMode(PostEffectPipeline.BlendMode blendMode) {
      if (blendMode == PostEffectPipeline.BlendMode.OVERWRITE) {
         RenderSystem.disableBlend();
      } else if (blendMode == PostEffectPipeline.BlendMode.PREMULTIPLIED) {
         RenderSystem.enableBlend();
         GlStateManager._blendFuncSeparate(1, 771, 1, 771);
      } else {
         RenderSystem.enableBlend();
         GlStateManager._blendFuncSeparate(1, 1, 0, 1);
      }
   }

   private static void renderFullscreenQuad(int width, int height) {
      Tesselator tessellator = RenderSystem.renderThreadTesselator();
      BufferBuilder buffer = tessellator.getBuilder();
      buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
      buffer.vertex(0.0, (double)height, 0.0).uv(0.0F, 0.0F).endVertex();
      buffer.vertex((double)width, (double)height, 0.0).uv(1.0F, 0.0F).endVertex();
      buffer.vertex((double)width, 0.0, 0.0).uv(1.0F, 1.0F).endVertex();
      buffer.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).endVertex();
      BufferUploader.draw(buffer.end());
   }

   private static int[] saveShaderTextures() {
      int[] textures = new int[5];

      for (int i = 0; i < textures.length; i++) {
         textures[i] = RenderSystem.getShaderTexture(i);
      }

      return textures;
   }

   private static void restoreRenderState(
      PoseStack modelViewStack, VertexSorting previousVertexSorting, int[] savedTextures, float[] savedShaderColor, RenderTarget main
   ) {
      modelViewStack.popPose();
      RenderSystem.applyModelViewMatrix();
      RenderSystem.setProjectionMatrix(PREVIOUS_PROJECTION, previousVertexSorting);
      GlStateManager._disableBlend();
      RenderSystem.defaultBlendFunc();
      GlStateManager._depthMask(true);
      GlStateManager._enableDepthTest();
      RenderSystem.depthFunc(515);
      RenderSystem.setShaderColor(savedShaderColor[0], savedShaderColor[1], savedShaderColor[2], savedShaderColor[3]);

      for (int i = 0; i < savedTextures.length; i++) {
         RenderSystem.setShaderTexture(i, savedTextures[i]);
      }

      RenderSystem.activeTexture(33984);
      main.bindWrite(true);
      RenderSystem.viewport(0, 0, main.viewWidth, main.viewHeight);
   }

   private static void ensureEffectsTarget(int width, int height) {
      if (effectsTarget == null) {
         effectsTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
         effectsTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         effectsTarget.setFilterMode(9729);
      } else if (effectsTarget.width != width || effectsTarget.height != height) {
         effectsTarget.resize(width, height, Minecraft.ON_OSX);
         effectsTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         effectsTarget.setFilterMode(9729);
      }
   }

   private static enum BlendMode {
      OVERWRITE,
      PREMULTIPLIED,
      ADDITIVE;
   }
}
