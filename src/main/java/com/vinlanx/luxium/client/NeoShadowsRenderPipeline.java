package com.vinlanx.luxium.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.client.neocpu.NeoCpuShadowsEngine;
import com.vinlanx.luxium.client.postprocess.PostProcessStateGuard;
import com.vinlanx.luxium.mixin.LightTextureAccessor;
import com.vinlanx.luxium.mixin.MinecraftAccessor;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

final class NeoShadowsRenderPipeline {
   private final NeoShadowsEngine engine;

   NeoShadowsRenderPipeline(NeoShadowsEngine engine) {
      this.engine = engine;
   }

   void captureSkyLightSceneInternal(
      LevelRenderer levelRenderer,
      PoseStack poseStack,
      float partialTick,
      long finishNano,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f projectionMatrix
   ) {
      Minecraft mc = Minecraft.getInstance();
      boolean neoSelected = this.engine.isSelected();
      boolean cpuSelected = NeoCpuShadowsEngine.isActive();
      this.engine.setEnabled(neoSelected);
      if (cpuSelected) {
         NeoCpuShadowsEngine.prepareForShadowlessCapture(mc, camera);
      }

      if ((this.engine.enabled || cpuSelected)
         && mc.level != null
         && mc.player != null
         && !ReflectionSystem.isRenderingWorldPass()
         && !this.engine.skyLightCapturePass) {
         if (this.engine.enabled && !this.engine.framePrepared) {
            this.engine.runtime.prepareFrameInternal(mc, camera, partialTick);
         }

         boolean hasShadowPolygons = this.engine.enabled && this.engine.hasAnyShadowPolygons() || NeoCpuShadowsEngine.hasActivePolygons();
         if (!hasShadowPolygons) {
            this.engine.skySceneReady = false;
         } else {
            RenderTarget originalTarget = mc.getMainRenderTarget();
            this.engine.ensureTargets(originalTarget.viewWidth, originalTarget.viewHeight);
            long nowMs = System.currentTimeMillis();
            if (this.shouldRefreshSkyCapture(camera, projectionMatrix, this.engine.skyCaptureTarget.width, this.engine.skyCaptureTarget.height, nowMs)) {
               NeoShadowsEngine.flushSharedRenderBuffers(mc);
               PoseStack skyPose = new PoseStack();
               skyPose.last().pose().set(poseStack.last().pose());
               skyPose.last().normal().set(poseStack.last().normal());
               MinecraftAccessor accessor = (MinecraftAccessor)mc;
               RenderTarget oldMainTargetField = accessor.luxium$getMainRenderTargetField();
               float savedFogStart = RenderSystem.getShaderFogStart();
               float savedFogEnd = RenderSystem.getShaderFogEnd();
               float[] savedFogColor = RenderSystem.getShaderFogColor();
               float[] savedShaderColor = RenderSystem.getShaderColor();
               int savedShaderTex0 = RenderSystem.getShaderTexture(0);
               int savedShaderTex1 = RenderSystem.getShaderTexture(1);
               int savedShaderTex2 = RenderSystem.getShaderTexture(2);
               int savedShaderTex3 = RenderSystem.getShaderTexture(3);
               this.engine.skyCaptureTarget.clear(Minecraft.ON_OSX);
               this.engine.skyCaptureTarget.bindWrite(true);
               accessor.luxium$setMainRenderTargetField(this.engine.skyCaptureTarget);
               this.engine.skyLightCapturePass = true;
               this.engine.skySceneReady = false;

               try {
                  LightTextureAccessor lightTextureAccessor = (LightTextureAccessor)lightTexture;
                  lightTextureAccessor.luxium$setUpdateLightTexture(true);
                  lightTexture.updateLightTexture(partialTick);
                  levelRenderer.renderLevel(skyPose, partialTick, finishNano, false, camera, gameRenderer, lightTexture, projectionMatrix);
                  NeoShadowsEngine.flushSharedRenderBuffers(mc);
                  this.rememberSkyCapture(camera, projectionMatrix, this.engine.skyCaptureTarget.width, this.engine.skyCaptureTarget.height, nowMs);
               } finally {
                  this.engine.skyLightCapturePass = false;
                  accessor.luxium$setMainRenderTargetField(oldMainTargetField);
                  GlStateManager._depthMask(true);
                  GlStateManager._colorMask(true, true, true, true);
                  RenderSystem.disableBlend();
                  RenderSystem.defaultBlendFunc();
                  RenderSystem.depthFunc(515);
                  RenderSystem.setShaderFogStart(savedFogStart);
                  RenderSystem.setShaderFogEnd(savedFogEnd);
                  RenderSystem.setShaderFogColor(savedFogColor[0], savedFogColor[1], savedFogColor[2], savedFogColor[3]);
                  RenderSystem.setShaderColor(savedShaderColor[0], savedShaderColor[1], savedShaderColor[2], savedShaderColor[3]);
                  RenderSystem.setShaderTexture(0, savedShaderTex0);
                  RenderSystem.setShaderTexture(1, savedShaderTex1);
                  RenderSystem.setShaderTexture(2, savedShaderTex2);
                  RenderSystem.setShaderTexture(3, savedShaderTex3);
                  LightTextureAccessor lightTextureAccessorx = (LightTextureAccessor)lightTexture;
                  lightTextureAccessorx.luxium$setUpdateLightTexture(true);
                  lightTexture.updateLightTexture(partialTick);
                  originalTarget.bindWrite(true);
                  PostProcessStateGuard.restoreCanonicalMainTargetBinding(mc);
               }
            }
         }
      } else {
         this.engine.skySceneReady = false;
      }
   }

   void renderAfterParticles(Minecraft mc, RenderLevelStageEvent event) {
      this.renderShadowMask(mc, event.getPoseStack(), event.getCamera(), event.getFrustum());
      this.composeShadowMask(mc);
   }

   void renderShadowMask(Minecraft mc, PoseStack poseStack, Camera camera, @Nullable Frustum frustum) {
      if (!this.engine.framePrepared) {
         this.engine.runtime.prepareFrameInternal(mc, camera, mc.getPartialTick());
      }

      if (!this.engine.hasAnyShadowPolygons()) {
         this.engine.maskReady = false;
         this.engine.framePrepared = false;
      } else {
         Vec3 cameraPos = camera.getPosition();
         double cameraX = cameraPos.x;
         double cameraY = cameraPos.y;
         double cameraZ = cameraPos.z;
         RenderTarget main = mc.getMainRenderTarget();
         this.engine.ensureTargets(main.viewWidth, main.viewHeight);
         this.engine.shadowMaskTarget.clear(Minecraft.ON_OSX);
         this.engine.shadowMaskTarget.copyDepthFrom(main);
         this.engine.shadowMaskTarget.bindWrite(false);
         BufferSource bufferSource = mc.renderBuffers().bufferSource();
         VertexConsumer consumer = bufferSource.getBuffer(NeoShadowsEngine.MASK_PASS);
         Matrix4f pose = poseStack.last().pose();
         NeoShadowsTypes.StaticHotPatchOverlay hotPatch = this.engine.activeStaticHotPatch;
         boolean hasHotPatch = !hotPatch.isEmpty();
         if (this.engine.activeStaticOutput != null) {
            for (NeoShadowsTypes.ShadowPolygon polygon : this.engine.activeStaticOutput.shadowPolygons) {
               if ((!hasHotPatch || !hotPatch.replaces(polygon)) && !this.engine.shouldCullShadowPolygon(polygon, cameraX, cameraY, cameraZ, frustum)) {
                  this.engine.emitPolygon(pose, consumer, cameraPos, polygon);
               }
            }
         }

         List<NeoShadowsTypes.ShadowPolygon> staging = this.engine.inFlightStaticPolygons;
         if (this.engine.activeStaticOutput == null && !staging.isEmpty() && this.engine.inFlightStaticEpoch == this.engine.buildEpoch) {
            for (NeoShadowsTypes.ShadowPolygon polygonx : staging) {
               if (!this.engine.shouldCullShadowPolygon(polygonx, cameraX, cameraY, cameraZ, frustum)) {
                  this.engine.emitPolygon(pose, consumer, cameraPos, polygonx);
               }
            }
         }

         if (hasHotPatch) {
            for (NeoShadowsTypes.ShadowPolygon polygonxx : hotPatch.polygons) {
               if (!this.engine.shouldCullShadowPolygon(polygonxx, cameraX, cameraY, cameraZ, frustum)) {
                  this.engine.emitPolygon(pose, consumer, cameraPos, polygonxx);
               }
            }
         }

         for (NeoShadowsTypes.ShadowPolygon polygonxxx : this.engine.activeDynamicPolygons) {
            if (!this.engine.shouldCullShadowPolygon(polygonxxx, cameraX, cameraY, cameraZ, frustum)) {
               this.engine.emitPolygon(pose, consumer, cameraPos, polygonxxx);
            }
         }

         bufferSource.endBatch(NeoShadowsEngine.MASK_PASS);
         main.bindWrite(false);
         this.engine.maskReady = true;
         this.engine.framePrepared = false;
      }
   }

   void composeShadowMask(Minecraft mc) {
      if (this.engine.maskReady && this.engine.shadowMaskTarget != null && this.engine.skySceneReady && this.engine.skyCaptureTarget != null) {
         ShaderInstance shader = ShaderManager.getFloodShadowCompositeShader();
         int sceneDepthTexture = SharedPostResources.getDepthTextureId();
         if (shader != null && sceneDepthTexture >= 0) {
            RenderTarget main = mc.getMainRenderTarget();
            this.engine.ensureTargets(main.viewWidth, main.viewHeight);
            this.engine.sceneCopyTarget.clear(Minecraft.ON_OSX);
            GL30.glBindFramebuffer(36008, main.frameBufferId);
            GL30.glBindFramebuffer(36009, this.engine.sceneCopyTarget.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, main.viewWidth, main.viewHeight, 0, 0, main.viewWidth, main.viewHeight, 16384, 9728);
            GL30.glBindFramebuffer(36160, 0);
            main.bindWrite(false);
            int width = main.viewWidth;
            int height = main.viewHeight;
            Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
            VertexSorting previousSorting = RenderSystem.getVertexSorting();
            int savedShaderTex0 = RenderSystem.getShaderTexture(0);
            int savedShaderTex1 = RenderSystem.getShaderTexture(1);
            int savedShaderTex2 = RenderSystem.getShaderTexture(2);
            int savedShaderTex3 = RenderSystem.getShaderTexture(3);
            int savedShaderTex4 = RenderSystem.getShaderTexture(4);
            Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float)width, (float)height, 0.0F, 1000.0F, 3000.0F);

            try {
               RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
               GlStateManager._disableDepthTest();
               GlStateManager._depthMask(false);
               RenderSystem.disableBlend();
               shader.setSampler("SceneSampler", this.engine.sceneCopyTarget.getColorTextureId());
               shader.setSampler("ShadowlessSceneSampler", this.engine.skyCaptureTarget.getColorTextureId());
               shader.setSampler("ShadowlessDepthSampler", this.engine.skyCaptureTarget.getDepthTextureId());
               shader.setSampler("ShadowMaskSampler", this.engine.shadowMaskTarget.getColorTextureId());
               shader.setSampler("DepthSampler", sceneDepthTexture);
               shader.MODEL_VIEW_MATRIX.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
               shader.PROJECTION_MATRIX.set(ortho);
               shader.apply();
               Tesselator tessellator = RenderSystem.renderThreadTesselator();
               BufferBuilder buffer = tessellator.getBuilder();
               buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
               buffer.vertex(0.0, (double)height, 0.0).uv(0.0F, 0.0F).endVertex();
               buffer.vertex((double)width, (double)height, 0.0).uv(1.0F, 0.0F).endVertex();
               buffer.vertex((double)width, 0.0, 0.0).uv(1.0F, 1.0F).endVertex();
               buffer.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).endVertex();
               BufferUploader.draw(buffer.end());
            } finally {
               shader.clear();
               RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
               GlStateManager._colorMask(true, true, true, true);
               GlStateManager._depthMask(true);
               GlStateManager._enableDepthTest();
               RenderSystem.disableBlend();
               RenderSystem.defaultBlendFunc();
               RenderSystem.depthFunc(515);
               RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
               RenderSystem.setShaderTexture(0, savedShaderTex0);
               RenderSystem.setShaderTexture(1, savedShaderTex1);
               RenderSystem.setShaderTexture(2, savedShaderTex2);
               RenderSystem.setShaderTexture(3, savedShaderTex3);
               RenderSystem.setShaderTexture(4, savedShaderTex4);
               RenderSystem.activeTexture(33984);
               main.bindWrite(true);
               this.engine.maskReady = false;
            }
         }
      }
   }

   boolean shouldRefreshSkyCapture(Camera camera, Matrix4f projectionMatrix, int captureWidth, int captureHeight, long nowMs) {
      if (!this.engine.skySceneReady || this.engine.skyCaptureDirty || !this.engine.lastSkyCaptureProjectionValid) {
         return true;
      } else if (captureWidth != this.engine.lastSkyCaptureWidth || captureHeight != this.engine.lastSkyCaptureHeight) {
         return true;
      } else if (this.hasSkyCaptureViewChanged(camera, projectionMatrix)) {
         return true;
      } else {
         return nowMs - this.engine.lastSkyCaptureRenderMs < 100L ? false : nowMs - this.engine.lastSkyCaptureRenderMs > 2000L;
      }
   }

   boolean hasSkyCaptureViewChanged(Camera camera, Matrix4f projectionMatrix) {
      Vec3 cameraPos = camera.getPosition();
      double dx = cameraPos.x - this.engine.lastSkyCaptureCamX;
      double dy = cameraPos.y - this.engine.lastSkyCaptureCamY;
      double dz = cameraPos.z - this.engine.lastSkyCaptureCamZ;
      if (dx * dx + dy * dy + dz * dz > 1.0E-6) {
         return true;
      } else {
         return !(Math.abs(camera.getYRot() - this.engine.lastSkyCaptureYaw) > 0.05F)
               && !(Math.abs(camera.getXRot() - this.engine.lastSkyCapturePitch) > 0.05F)
            ? !projectionMatrix.equals(this.engine.lastSkyCaptureProjection)
            : true;
      }
   }

   void rememberSkyCapture(Camera camera, Matrix4f projectionMatrix, int captureWidth, int captureHeight, long nowMs) {
      Vec3 cameraPos = camera.getPosition();
      this.engine.skyCaptureDirty = false;
      this.engine.skySceneReady = true;
      this.engine.lastSkyCaptureRenderMs = nowMs;
      this.engine.lastSkyCaptureWidth = captureWidth;
      this.engine.lastSkyCaptureHeight = captureHeight;
      this.engine.lastSkyCaptureCamX = cameraPos.x;
      this.engine.lastSkyCaptureCamY = cameraPos.y;
      this.engine.lastSkyCaptureCamZ = cameraPos.z;
      this.engine.lastSkyCaptureYaw = camera.getYRot();
      this.engine.lastSkyCapturePitch = camera.getXRot();
      this.engine.lastSkyCaptureProjection.set(projectionMatrix);
      this.engine.lastSkyCaptureProjectionValid = true;
   }

   static boolean isCompatibleBuildCenter(BlockPos buildCenter, BlockPos currentCenter) {
      double dx = (double)(buildCenter.getX() - currentCenter.getX());
      double dy = (double)(buildCenter.getY() - currentCenter.getY());
      double dz = (double)(buildCenter.getZ() - currentCenter.getZ());
      return dx * dx + dy * dy + dz * dz <= 256.0;
   }
}
