package com.vinlanx.luxium.client.tfrpluslsr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
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
import com.vinlanx.luxium.mixin.MinecraftAccessor;
import java.nio.ByteBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class TemporalFrameSystem {
   private static final float MOTION_FIELD_SCALE = 0.5F;
   private static final double CAMERA_MOTION_EPSILON_SQ = 1.0E-8;
   private static final float CAMERA_ANGLE_EPSILON = 0.01F;
   @Nullable
   private static TextureTarget sceneTarget;
   @Nullable
   private static TextureTarget preparedHistoryTarget;
   @Nullable
   private static TextureTarget motionTarget;
   @Nullable
   private static RenderTarget nativeMainTarget;
   @Nullable
   private static Object historyLevel;
   private static final Matrix4f previousViewProjection = new Matrix4f();
   private static final Matrix4f previousInverseViewProjection = new Matrix4f();
   private static final Matrix4f currentViewProjection = new Matrix4f();
   private static final Matrix4f currentViewMatrix = new Matrix4f();
   private static final Matrix4f geometryReprojection = new Matrix4f();
   private static final Matrix4f skyReprojection = new Matrix4f();
   private static final Matrix4f cameraTranslation = new Matrix4f();
   private static double previousCameraX;
   private static double previousCameraY;
   private static double previousCameraZ;
   private static float previousYaw;
   private static float previousPitch;
   private static boolean historyValid;
   private static boolean preparedHistoryValid;
   private static int syntheticFramesSinceReal;
   private static boolean syntheticThisFrame;
   private static boolean worldPassActive;
   private static boolean firstPersonOverlayPass;

   private TemporalFrameSystem() {
   }

   public static void onConfigChanged() {
      invalidateHistory();
      if (!Config.isFeatureEnabled(Config.CLIENT.tfrEnabled)) {
         releaseTargets();
      }
   }

   public static boolean isSyntheticThisFrame() {
      return syntheticThisFrame;
   }

   public static boolean isWorldPassActive() {
      return worldPassActive;
   }

   public static boolean beginRealFrame(Minecraft mc) {
      syntheticThisFrame = false;
      if (Config.isFeatureEnabled(Config.CLIENT.tfrEnabled) && mc.level != null) {
         RenderTarget nativeTarget = mc.getMainRenderTarget();
         if (nativeTarget != null && nativeTarget.width > 0 && nativeTarget.height > 0) {
            int sourceWidth = Config.isFeatureEnabled(Config.CLIENT.lsrEnabled) ? LsrSystem.expectedWidth(nativeTarget.width) : nativeTarget.width;
            int sourceHeight = Config.isFeatureEnabled(Config.CLIENT.lsrEnabled) ? LsrSystem.expectedHeight(nativeTarget.height) : nativeTarget.height;
            ensureSceneTarget(sourceWidth, sourceHeight);
            TextureTarget scene = sceneTarget;
            if (scene == null) {
               invalidateHistory();
               return false;
            } else {
               nativeMainTarget = nativeTarget;
               worldPassActive = true;
               ((MinecraftAccessor)mc).luxium$setMainRenderTargetField(scene);
               scene.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
               scene.clear(Minecraft.ON_OSX);
               scene.bindWrite(true);
               RenderSystem.viewport(0, 0, scene.viewWidth, scene.viewHeight);
               return true;
            }
         } else {
            invalidateHistory();
            return false;
         }
      } else {
         if (historyValid || historyLevel != mc.level) {
            invalidateHistory();
         }

         if (!Config.isFeatureEnabled(Config.CLIENT.tfrEnabled)) {
            releaseTargets();
         }

         return false;
      }
   }

   public static void rebindWorldTarget() {
      if (worldPassActive && sceneTarget != null) {
         sceneTarget.bindWrite(true);
         RenderSystem.viewport(0, 0, sceneTarget.viewWidth, sceneTarget.viewHeight);
      }
   }

   public static void finishRealFrame(Minecraft mc, Camera camera, Matrix4f projectionMatrix, Matrix4f viewMatrix) {
      TextureTarget source = sceneTarget;
      RenderTarget destination = nativeMainTarget;
      if (worldPassActive && source != null && destination != null) {
         ((MinecraftAccessor)mc).luxium$setMainRenderTargetField(destination);
         worldPassActive = false;
         destination.bindWrite(true);
         RenderSystem.viewport(0, 0, destination.viewWidth, destination.viewHeight);
         if (Config.isFeatureEnabled(Config.CLIENT.lsrEnabled)) {
            preparedHistoryValid = prepareLsrHistory();
         } else {
            preparedHistoryValid = false;
         }

         previousViewProjection.set(projectionMatrix).mul(viewMatrix);
         previousInverseViewProjection.set(previousViewProjection).invert();
         Vec3 cameraPos = camera.getPosition();
         previousCameraX = cameraPos.x;
         previousCameraY = cameraPos.y;
         previousCameraZ = cameraPos.z;
         previousYaw = camera.getYRot();
         previousPitch = camera.getXRot();
         historyLevel = mc.level;
         historyValid = true;
         syntheticFramesSinceReal = 0;
         ShaderInstance finalShader = ShaderManager.getTemporalReprojectionShader();
         boolean presented = finalShader != null && renderFinalComposite(finalShader, destination, false);
         if (!presented) {
            blitSceneFallback(source, destination);
         }

         nativeMainTarget = null;
         destination.bindWrite(true);
         RenderSystem.viewport(0, 0, destination.viewWidth, destination.viewHeight);
      } else {
         abortRealFrame(mc);
      }
   }

   public static void abortRealFrame(Minecraft mc) {
      RenderTarget destination = nativeMainTarget;
      if (destination != null) {
         ((MinecraftAccessor)mc).luxium$setMainRenderTargetField(destination);
         destination.bindWrite(true);
         RenderSystem.viewport(0, 0, destination.viewWidth, destination.viewHeight);
      }

      worldPassActive = false;
      nativeMainTarget = null;
      invalidateHistory();
   }

   public static boolean tryRenderSynthetic(Minecraft mc, Camera camera, Matrix4f projectionMatrix, Matrix4f viewMatrix) {
      syntheticThisFrame = false;
      currentViewMatrix.set(viewMatrix);
      if (Config.isFeatureEnabled(Config.CLIENT.tfrEnabled) && mc.level != null) {
         RenderTarget main = mc.getMainRenderTarget();
         TextureTarget scene = sceneTarget;
         ShaderInstance finalShader = ShaderManager.getTemporalReprojectionShader();
         if (main == null || scene == null || finalShader == null || main.width <= 0 || main.height <= 0) {
            return false;
         } else if (!historyValid || historyLevel != mc.level) {
            return false;
         } else if (Config.isFeatureEnabled(Config.CLIENT.lsrEnabled) && !preparedHistoryValid) {
            return false;
         } else {
            int expectedWidth = Config.isFeatureEnabled(Config.CLIENT.lsrEnabled) ? LsrSystem.expectedWidth(main.width) : main.width;
            int expectedHeight = Config.isFeatureEnabled(Config.CLIENT.lsrEnabled) ? LsrSystem.expectedHeight(main.height) : main.height;
            if (scene.width != expectedWidth || scene.height != expectedHeight) {
               invalidateHistory();
               return false;
            } else if (syntheticFramesSinceReal < 1 && !isCameraCut(camera)) {
               currentViewProjection.set(projectionMatrix).mul(viewMatrix);
               boolean cameraMoved = hasMeaningfulCameraMotion(camera);
               if (cameraMoved) {
                  ShaderInstance motionShader = ShaderManager.getTemporalMotionShader();
                  if (motionShader == null) {
                     return false;
                  }

                  int motionWidth = Math.max(1, Math.round((float)scene.width * 0.5F));
                  int motionHeight = Math.max(1, Math.round((float)scene.height * 0.5F));
                  ensureMotionTarget(motionWidth, motionHeight);
                  if (motionTarget == null) {
                     return false;
                  }

                  buildReprojectionMatrices(camera);
                  if (!renderMotionField(motionShader)) {
                     return false;
                  }
               }

               if (!renderFinalComposite(finalShader, main, cameraMoved)) {
                  return false;
               } else {
                  syntheticFramesSinceReal++;
                  syntheticThisFrame = true;
                  return true;
               }
            } else {
               return false;
            }
         }
      } else {
         if (historyValid || historyLevel != mc.level) {
            invalidateHistory();
         }

         if (!Config.isFeatureEnabled(Config.CLIENT.tfrEnabled)) {
            releaseTargets();
         }

         return false;
      }
   }

   public static void beginFirstPersonOverlay(Minecraft mc) {
      firstPersonOverlayPass = false;
      if (Config.isFeatureEnabled(Config.CLIENT.tfrEnabled)) {
         RenderTarget main = mc.getMainRenderTarget();
         if (main != null) {
            firstPersonOverlayPass = true;
            main.bindWrite(true);
            RenderSystem.viewport(0, 0, main.viewWidth, main.viewHeight);
            RenderSystem.activeTexture(33984);
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._depthMask(true);
            GlStateManager._enableDepthTest();
            RenderSystem.depthFunc(515);
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (mc.level != null) {
               Matrix4f lightingView = new Matrix4f(currentViewMatrix);
               if (mc.level.effects().constantAmbientLight()) {
                  Lighting.setupNetherLevel(lightingView);
               } else {
                  Lighting.setupLevel(lightingView);
               }
            }
         }
      }
   }

   public static void endFirstPersonOverlay() {
      firstPersonOverlayPass = false;
   }

   public static boolean isFirstPersonOverlayPass() {
      return firstPersonOverlayPass;
   }

   public static void invalidate() {
      invalidateHistory();
      worldPassActive = false;
      nativeMainTarget = null;
   }

   private static void invalidateHistory() {
      historyValid = false;
      preparedHistoryValid = false;
      historyLevel = null;
      syntheticFramesSinceReal = 0;
      syntheticThisFrame = false;
      firstPersonOverlayPass = false;
   }

   private static void releaseTargets() {
      if (sceneTarget != null) {
         sceneTarget.destroyBuffers();
         sceneTarget = null;
      }

      if (preparedHistoryTarget != null) {
         preparedHistoryTarget.destroyBuffers();
         preparedHistoryTarget = null;
      }

      preparedHistoryValid = false;
      if (motionTarget != null) {
         motionTarget.destroyBuffers();
         motionTarget = null;
      }
   }

   private static void ensureSceneTarget(int width, int height) {
      if (sceneTarget == null) {
         sceneTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
         sceneTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         sceneTarget.setFilterMode(9729);
         invalidateHistory();
      } else {
         if (sceneTarget.width != width || sceneTarget.height != height) {
            sceneTarget.resize(width, height, Minecraft.ON_OSX);
            sceneTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            sceneTarget.setFilterMode(9729);
            invalidateHistory();
         }
      }
   }

   private static void ensurePreparedHistoryTarget(int width, int height) {
      if (preparedHistoryTarget == null) {
         preparedHistoryTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
         preparedHistoryTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         preparedHistoryTarget.setFilterMode(9729);
         preparedHistoryValid = false;
      } else {
         if (preparedHistoryTarget.width != width || preparedHistoryTarget.height != height) {
            preparedHistoryTarget.resize(width, height, Minecraft.ON_OSX);
            preparedHistoryTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            preparedHistoryTarget.setFilterMode(9729);
            preparedHistoryValid = false;
         }
      }
   }

   private static void ensureMotionTarget(int width, int height) {
      if (motionTarget == null) {
         motionTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
         configureMotionTexture(motionTarget);
      } else {
         if (motionTarget.width != width || motionTarget.height != height) {
            motionTarget.resize(width, height, Minecraft.ON_OSX);
            configureMotionTexture(motionTarget);
         }
      }
   }

   private static void configureMotionTexture(TextureTarget target) {
      int previousBinding = GL11.glGetInteger(32873);
      GL11.glBindTexture(3553, target.getColorTextureId());
      GL11.glTexImage2D(3553, 0, 34842, target.width, target.height, 0, 6408, 5131, (ByteBuffer)null);
      GL11.glTexParameteri(3553, 10241, 9729);
      GL11.glTexParameteri(3553, 10240, 9729);
      GL11.glTexParameteri(3553, 10242, 33071);
      GL11.glTexParameteri(3553, 10243, 33071);
      GL11.glBindTexture(3553, previousBinding);
      target.setFilterMode(9729);
   }

   private static boolean isCameraCut(Camera camera) {
      Vec3 pos = camera.getPosition();
      double dx = pos.x - previousCameraX;
      double dy = pos.y - previousCameraY;
      double dz = pos.z - previousCameraZ;
      double maxDistance = (Double)Config.CLIENT.tfrCameraCutDistance.get();
      if (dx * dx + dy * dy + dz * dz > maxDistance * maxDistance) {
         return true;
      } else {
         float yawDelta = wrappedDegrees(camera.getYRot() - previousYaw);
         float pitchDelta = wrappedDegrees(camera.getXRot() - previousPitch);
         double angularDelta = Math.sqrt((double)(yawDelta * yawDelta + pitchDelta * pitchDelta));
         return angularDelta > (Double)Config.CLIENT.tfrCameraCutAngle.get();
      }
   }

   private static boolean hasMeaningfulCameraMotion(Camera camera) {
      Vec3 pos = camera.getPosition();
      double dx = pos.x - previousCameraX;
      double dy = pos.y - previousCameraY;
      double dz = pos.z - previousCameraZ;
      return dx * dx + dy * dy + dz * dz > 1.0E-8
         ? true
         : Math.abs(wrappedDegrees(camera.getYRot() - previousYaw)) > 0.01F || Math.abs(wrappedDegrees(camera.getXRot() - previousPitch)) > 0.01F;
   }

   private static float wrappedDegrees(float value) {
      value %= 360.0F;
      if (value >= 180.0F) {
         value -= 360.0F;
      }

      if (value < -180.0F) {
         value += 360.0F;
      }

      return value;
   }

   private static void buildReprojectionMatrices(Camera camera) {
      Vec3 currentCamera = camera.getPosition();
      float dx = (float)(previousCameraX - currentCamera.x);
      float dy = (float)(previousCameraY - currentCamera.y);
      float dz = (float)(previousCameraZ - currentCamera.z);
      cameraTranslation.identity().translation(dx, dy, dz);
      geometryReprojection.set(currentViewProjection).mul(cameraTranslation).mul(previousInverseViewProjection);
      skyReprojection.set(currentViewProjection).mul(previousInverseViewProjection);
   }

   private static boolean prepareLsrHistory() {
      TextureTarget scene = sceneTarget;
      ShaderInstance shader = ShaderManager.getTemporalPrepareShader();
      if (scene != null && shader != null) {
         ensurePreparedHistoryTarget(scene.width, scene.height);
         TextureTarget prepared = preparedHistoryTarget;
         if (prepared == null) {
            return false;
         } else {
            shader.setSampler("SceneSampler", scene.getColorTextureId());
            shader.setSampler("SceneDepth", scene.getDepthTextureId());
            setUniform2f(shader, "SourceTexelSize", 1.0F / (float)Math.max(1, scene.width), 1.0F / (float)Math.max(1, scene.height));
            setUniform1f(shader, "LsrSharpness", LsrSystem.sharpness());
            drawFullscreen(shader, prepared);
            return true;
         }
      } else {
         return false;
      }
   }

   private static boolean renderMotionField(ShaderInstance shader) {
      TextureTarget scene = sceneTarget;
      TextureTarget motion = motionTarget;
      if (scene != null && motion != null) {
         shader.setSampler("HistoryDepth", scene.getDepthTextureId());
         setUniformMatrix(shader, "ReprojectionMat", geometryReprojection);
         setUniformMatrix(shader, "SkyReprojectionMat", skyReprojection);
         drawFullscreen(shader, motion);
         return true;
      } else {
         return false;
      }
   }

   private static boolean renderFinalComposite(ShaderInstance shader, RenderTarget destination, boolean syntheticFrame) {
      TextureTarget scene = sceneTarget;
      if (scene == null) {
         return false;
      } else {
         boolean usePrepared = Config.isFeatureEnabled(Config.CLIENT.lsrEnabled) && preparedHistoryValid && preparedHistoryTarget != null;
         RenderTarget colorSource = usePrepared ? preparedHistoryTarget : scene;
         TextureTarget motion = motionTarget;
         shader.setSampler("HistoryColor", colorSource.getColorTextureId());
         shader.setSampler("MotionSampler", syntheticFrame && motion != null ? motion.getColorTextureId() : colorSource.getColorTextureId());
         setUniform2f(shader, "SourceTexelSize", 1.0F / (float)Math.max(1, colorSource.width), 1.0F / (float)Math.max(1, colorSource.height));
         setUniform1f(shader, "ReprojectionStrength", ((Double)Config.CLIENT.tfrReprojectionStrength.get()).floatValue());
         setUniform1i(shader, "SyntheticFrame", syntheticFrame ? 1 : 0);
         setUniform1i(shader, "HistoryHasEdgeMetadata", usePrepared ? 1 : 0);
         drawFullscreen(shader, destination);
         return true;
      }
   }

   private static void drawFullscreen(ShaderInstance shader, RenderTarget destination) {
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

   private static void blitSceneFallback(RenderTarget source, RenderTarget destination) {
      GL30.glBindFramebuffer(36008, source.frameBufferId);
      GL30.glBindFramebuffer(36009, destination.frameBufferId);
      GL30.glBlitFramebuffer(
         0,
         0,
         source.width,
         source.height,
         0,
         0,
         destination.width,
         destination.height,
         16384,
         source.width == destination.width && source.height == destination.height ? 9728 : 9729
      );
      destination.bindWrite(true);
   }

   private static void setUniformMatrix(ShaderInstance shader, String name, Matrix4f value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
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
