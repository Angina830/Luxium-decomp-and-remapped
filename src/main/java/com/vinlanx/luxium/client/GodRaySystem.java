package com.vinlanx.luxium.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT}
)
public final class GodRaySystem {
   private static final boolean ENABLED = false;
   private static final float RENDER_SCALE = 0.8F;
   private static final Vector3f GOD_RAY_TINT = new Vector3f(0.82F, 0.92F, 1.0F);
   private static TextureTarget lowResGodRayTarget;
   private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();

   private GodRaySystem() {
   }

   @SubscribeEvent(
      priority = EventPriority.LOW
   )
   public static void onRenderLevel(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_PARTICLES) {
         if (ReflectionSystem.isRenderingWorldPass() || NeoShadowsEngine.isAnyShadowCapturePass()) {
            ;
         }
      }
   }

   private static void renderPostPass(Minecraft mc, RenderLevelStageEvent event, ShaderInstance shader) {
      RenderTarget outputTarget = mc.getMainRenderTarget();
      int outputWidth = outputTarget.viewWidth;
      int outputHeight = outputTarget.viewHeight;
      float renderScale = 0.8F;
      boolean useLowResTarget = renderScale < 0.999F;
      int renderWidth = useLowResTarget ? Math.max(1, Math.round((float)outputWidth * renderScale)) : outputWidth;
      int renderHeight = useLowResTarget ? Math.max(1, Math.round((float)outputHeight * renderScale)) : outputHeight;
      if (useLowResTarget) {
         ensureLowResTarget(renderWidth, renderHeight);
      }

      Vec3 camPos = event.getCamera().getPosition();
      Matrix4f inverseProj = NeoSkyFrameCache.copyInverseProjection(event.getProjectionMatrix(), INVERSE_PROJECTION);
      Vector3f look = event.getCamera().getLookVector();
      Vector3f up = event.getCamera().getUpVector();
      Vector3f left = event.getCamera().getLeftVector();
      Vector3f right = new Vector3f(left).mul(-1.0F);
      Vector3f vMin = LightAtlas.getMin();
      Vector3f vSize = LightAtlas.getSize();
      int depthTextureId = SharedPostResources.getDepthTextureId();
      int lightVolumeTextureId = LightAtlas.getTextureId();
      if (depthTextureId > 0 && lightVolumeTextureId > 0) {
         shader.setSampler("DepthSampler", depthTextureId);
         shader.setSampler("LightVolumeSampler", lightVolumeTextureId);
         if (shader.getUniform("InverseProj") != null) {
            shader.getUniform("InverseProj").set(inverseProj);
         }

         if (shader.getUniform("CamForward") != null) {
            shader.getUniform("CamForward").set(look.x(), look.y(), look.z());
         }

         if (shader.getUniform("CamUp") != null) {
            shader.getUniform("CamUp").set(up.x(), up.y(), up.z());
         }

         if (shader.getUniform("CamRight") != null) {
            shader.getUniform("CamRight").set(right.x(), right.y(), right.z());
         }

         if (shader.getUniform("CameraPos") != null) {
            shader.getUniform("CameraPos").set((float)camPos.x, (float)camPos.y, (float)camPos.z);
         }

         if (shader.getUniform("VolumeMin") != null) {
            shader.getUniform("VolumeMin").set(vMin.x, vMin.y, vMin.z);
         }

         if (shader.getUniform("VolumeSize") != null) {
            shader.getUniform("VolumeSize").set(vSize.x, vSize.y, vSize.z);
         }

         if (shader.getUniform("VolumeDim") != null) {
            shader.getUniform("VolumeDim").set(48.0F, 32.0F, 48.0F);
         }

         if (shader.getUniform("RayColor") != null) {
            shader.getUniform("RayColor").set(GOD_RAY_TINT);
         }

         if (shader.getUniform("GameTime") != null) {
            shader.getUniform("GameTime").set((float)(mc.level.getGameTime() % 100000L));
         }

         if (shader.getUniform("EffectStrength") != null) {
            shader.getUniform("EffectStrength").set(1.0F);
         }

         if (shader.getUniform("ScreenSize") != null) {
            shader.getUniform("ScreenSize").set((float)renderWidth, (float)renderHeight);
         }

         Matrix4f prevProj = new Matrix4f(RenderSystem.getProjectionMatrix());
         VertexSorting prevVS = RenderSystem.getVertexSorting();
         int savedShaderTex0 = RenderSystem.getShaderTexture(0);
         int savedShaderTex1 = RenderSystem.getShaderTexture(1);
         int savedShaderTex2 = RenderSystem.getShaderTexture(2);
         float[] savedShaderColor = RenderSystem.getShaderColor();
         Matrix4f orthoOutput = new Matrix4f().setOrtho(0.0F, (float)renderWidth, (float)renderHeight, 0.0F, 1000.0F, 3000.0F);

         try {
            if (useLowResTarget) {
               lowResGodRayTarget.clear(Minecraft.ON_OSX);
               lowResGodRayTarget.bindWrite(true);
            } else {
               outputTarget.bindWrite(true);
            }

            RenderSystem.setProjectionMatrix(orthoOutput, VertexSorting.ORTHOGRAPHIC_Z);
            if (shader.MODEL_VIEW_MATRIX != null) {
               shader.MODEL_VIEW_MATRIX.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
            }

            if (shader.PROJECTION_MATRIX != null) {
               shader.PROJECTION_MATRIX.set(orthoOutput);
            }

            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            if (useLowResTarget) {
               GlStateManager._disableBlend();
            } else {
               GlStateManager._enableBlend();
               GlStateManager._blendFunc(1, 1);
            }

            shader.apply();
            renderFullscreenQuad(renderWidth, renderHeight);
            shader.clear();
            if (useLowResTarget) {
               outputTarget.bindWrite(true);
               GlStateManager._enableBlend();
               GlStateManager._blendFunc(1, 1);
               lowResGodRayTarget.blitToScreen(outputWidth, outputHeight, false);
            }
         } finally {
            shader.clear();
            RenderSystem.setProjectionMatrix(prevProj, prevVS);
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._disableBlend();
            RenderSystem.defaultBlendFunc();
            GlStateManager._depthMask(true);
            GlStateManager._enableDepthTest();
            RenderSystem.depthFunc(515);
            RenderSystem.setShaderColor(savedShaderColor[0], savedShaderColor[1], savedShaderColor[2], savedShaderColor[3]);
            RenderSystem.setShaderTexture(0, savedShaderTex0);
            RenderSystem.setShaderTexture(1, savedShaderTex1);
            RenderSystem.setShaderTexture(2, savedShaderTex2);
            RenderSystem.activeTexture(33984);
            outputTarget.bindWrite(true);
         }
      }
   }

   private static void ensureLowResTarget(int renderWidth, int renderHeight) {
      if (lowResGodRayTarget == null) {
         lowResGodRayTarget = new TextureTarget(renderWidth, renderHeight, false, Minecraft.ON_OSX);
         lowResGodRayTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         lowResGodRayTarget.setFilterMode(9729);
      } else if (lowResGodRayTarget.width != renderWidth || lowResGodRayTarget.height != renderHeight) {
         lowResGodRayTarget.resize(renderWidth, renderHeight, Minecraft.ON_OSX);
         lowResGodRayTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         lowResGodRayTarget.setFilterMode(9729);
      }
   }

   private static void renderFullscreenQuad(int width, int height) {
      Tesselator tess = RenderSystem.renderThreadTesselator();
      BufferBuilder buf = tess.getBuilder();
      buf.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
      buf.vertex(0.0, (double)height, 0.0).uv(0.0F, 0.0F).endVertex();
      buf.vertex((double)width, (double)height, 0.0).uv(1.0F, 0.0F).endVertex();
      buf.vertex((double)width, 0.0, 0.0).uv(1.0F, 1.0F).endVertex();
      buf.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).endVertex();
      BufferUploader.draw(buf.end());
   }
}
