package com.vinlanx.luxium.Testing;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

final class SphereWarpEffect {
   private static final int FADE_IN_TICKS = 40;
   private static final int FADE_OUT_TICKS = 40;
   private static final int MAX_DURATION_SECONDS = 600;
   private static final float MIN_RADIUS = 0.1F;
   private final Supplier<ShaderInstance> shaderSupplier;
   private final boolean depthTestEnabled;
   private final List<SphereWarpEffect.Instance> instances = new ArrayList<>();
   private final Matrix4f viewProj = new Matrix4f();
   private final Matrix4f inverseViewProj = new Matrix4f();
   @Nullable
   private RenderTarget distortionTarget;
   private int lastWidth = -1;
   private int lastHeight = -1;

   SphereWarpEffect(Supplier<ShaderInstance> shaderSupplier, boolean depthTestEnabled) {
      this.shaderSupplier = shaderSupplier;
      this.depthTestEnabled = depthTestEnabled;
   }

   void spawnAtPlayer(int radiusBlocks) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         this.start(mc.player.position(), 600, (float)radiusBlocks);
      }
   }

   void start(Vec3 center, int durationSeconds, float radiusBlocks) {
      int clampedDuration = Mth.clamp(durationSeconds, 1, 600);
      float radius = Math.max(0.1F, radiusBlocks);
      this.instances.add(SphereWarpEffect.Instance.staticSphere(center, radius, clampedDuration * 20));
   }

   void startExpandingWave(Vec3 center, float startRadiusBlocks, float endRadiusBlocks, int totalTicks, float fadeStartProgress) {
      float startRadius = Math.max(0.1F, startRadiusBlocks);
      float endRadius = Math.max(startRadius, endRadiusBlocks);
      int clampedTicks = Math.max(1, totalTicks);
      float clampedFadeStart = Mth.clamp(fadeStartProgress, 0.0F, 0.98F);
      this.instances.add(SphereWarpEffect.Instance.expandingWave(center, startRadius, endRadius, clampedTicks, clampedFadeStart));
   }

   void onClientTick() {
      if (!this.instances.isEmpty()) {
         Iterator<SphereWarpEffect.Instance> iterator = this.instances.iterator();

         while (iterator.hasNext()) {
            SphereWarpEffect.Instance instance = iterator.next();
            instance.ageTicks++;
            if (instance.ageTicks >= instance.totalTicks) {
               iterator.remove();
            }
         }
      }
   }

   void clear() {
      this.instances.clear();
   }

   void onRenderLevel(Matrix4f viewMatrix) {
      if (!this.instances.isEmpty()) {
         ShaderInstance shader = this.shaderSupplier.get();
         if (shader != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
               RenderTarget mainTarget = mc.getMainRenderTarget();
               if (mainTarget != null) {
                  this.ensureTarget(mainTarget.width, mainTarget.height);
                  if (this.distortionTarget != null) {
                     this.viewProj.set(RenderSystem.getProjectionMatrix());
                     this.viewProj.mul(viewMatrix);
                     this.inverseViewProj.set(this.viewProj).invert();
                     if (this.depthTestEnabled) {
                        RenderSystem.enableDepthTest();
                     } else {
                        RenderSystem.disableDepthTest();
                     }

                     RenderSystem.depthMask(false);
                     RenderSystem.disableBlend();
                     RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                     int width = mainTarget.width;
                     int height = mainTarget.height;
                     int depthTextureId = mainTarget.getDepthTextureId();
                     Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
                     float partialTick = mc.getFrameTime();

                     for (SphereWarpEffect.Instance instance : this.instances) {
                        float intensity = instance.getIntensity(partialTick);
                        if (!(intensity <= 0.001F)) {
                           this.distortionTarget.bindWrite(true);
                           RenderSystem.viewport(0, 0, this.distortionTarget.width, this.distortionTarget.height);
                           this.renderFullscreen(
                              shader, mainTarget.getColorTextureId(), depthTextureId, width, height, instance, intensity, cameraPos, partialTick
                           );
                           mainTarget.bindWrite(true);
                           RenderSystem.viewport(0, 0, width, height);
                           this.renderFullscreen(
                              shader, this.distortionTarget.getColorTextureId(), depthTextureId, width, height, instance, 0.0F, cameraPos, partialTick
                           );
                        }
                     }

                     RenderSystem.enableBlend();
                     RenderSystem.defaultBlendFunc();
                     RenderSystem.depthMask(true);
                     RenderSystem.disableDepthTest();
                  }
               }
            }
         }
      }
   }

   private void ensureTarget(int width, int height) {
      if (this.distortionTarget == null || width != this.lastWidth || height != this.lastHeight) {
         if (this.distortionTarget != null) {
            this.distortionTarget.destroyBuffers();
         }

         this.distortionTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
         this.lastWidth = width;
         this.lastHeight = height;
      }
   }

   private void renderFullscreen(
      ShaderInstance shader,
      int textureId,
      int depthTextureId,
      int width,
      int height,
      SphereWarpEffect.Instance instance,
      float intensity,
      Vec3 cameraPos,
      float partialTick
   ) {
      RenderSystem.setShader(() -> shader);
      RenderSystem.setShaderTexture(0, textureId);
      shader.setSampler("DiffuseSampler", textureId);
      shader.setSampler("DepthSampler", depthTextureId);
      float time = ((float)instance.ageTicks + partialTick) / 20.0F;
      setUniform1f(shader, "Time", time);
      setUniform1f(shader, "Intensity", Mth.clamp(intensity, 0.0F, 1.0F));
      setUniform3f(shader, "SphereCenter", (float)instance.center.x, (float)instance.center.y, (float)instance.center.z);
      setUniform1f(shader, "SphereRadius", instance.getRadius(partialTick));
      setUniformMatrix(shader, "ViewProj", this.viewProj);
      setUniformMatrix(shader, "InverseViewProj", this.inverseViewProj);
      setUniform3f(shader, "CameraPos", (float)cameraPos.x, (float)cameraPos.y, (float)cameraPos.z);
      setUniform2f(shader, "InSize", (float)width, (float)height);
      setUniform2f(shader, "OutSize", (float)width, (float)height);
      BufferBuilder builder = Tesselator.getInstance().getBuilder();
      builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
      builder.vertex(-1.0, -1.0, 0.0).uv(0.0F, 1.0F).endVertex();
      builder.vertex(1.0, -1.0, 0.0).uv(1.0F, 1.0F).endVertex();
      builder.vertex(1.0, 1.0, 0.0).uv(1.0F, 0.0F).endVertex();
      builder.vertex(-1.0, 1.0, 0.0).uv(0.0F, 0.0F).endVertex();
      BufferUploader.drawWithShader(builder.end());
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

   private static void setUniform3f(ShaderInstance shader, String name, float x, float y, float z) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y, z);
      }
   }

   private static void setUniformMatrix(ShaderInstance shader, String name, Matrix4f matrix) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(matrix);
      }
   }

   private static final class Instance {
      private final Vec3 center;
      private final float startRadius;
      private final float endRadius;
      private final int totalTicks;
      private final float fadeStartProgress;
      private final boolean expandingWave;
      private int ageTicks;

      private Instance(Vec3 center, float startRadius, float endRadius, int totalTicks, float fadeStartProgress, boolean expandingWave) {
         this.center = center;
         this.startRadius = startRadius;
         this.endRadius = endRadius;
         this.totalTicks = totalTicks;
         this.fadeStartProgress = fadeStartProgress;
         this.expandingWave = expandingWave;
      }

      private static SphereWarpEffect.Instance staticSphere(Vec3 center, float radius, int totalTicks) {
         return new SphereWarpEffect.Instance(center, radius, radius, totalTicks, 1.0F, false);
      }

      private static SphereWarpEffect.Instance expandingWave(Vec3 center, float startRadius, float endRadius, int totalTicks, float fadeStartProgress) {
         return new SphereWarpEffect.Instance(center, startRadius, endRadius, totalTicks, fadeStartProgress, true);
      }

      private float getRadius(float partialTick) {
         return !this.expandingWave ? this.endRadius : Mth.lerp(this.getSmoothProgress(partialTick), this.startRadius, this.endRadius);
      }

      private float getIntensity(float partialTick) {
         if (!this.expandingWave) {
            float fadeIn = Mth.clamp(((float)this.ageTicks + partialTick) / 40.0F, 0.0F, 1.0F);
            float fadeOut = Mth.clamp(((float)(this.totalTicks - this.ageTicks) - partialTick) / 40.0F, 0.0F, 1.0F);
            return Math.min(fadeIn, fadeOut);
         } else {
            float progress = this.getLinearProgress(partialTick);
            float fadeIn = this.smoothStep(0.0F, 0.03F, progress);
            float fadeOut = 1.0F - Mth.clamp((progress - this.fadeStartProgress) / (1.0F - this.fadeStartProgress), 0.0F, 1.0F);
            return Mth.clamp(fadeIn * fadeOut, 0.0F, 1.0F);
         }
      }

      private float getLinearProgress(float partialTick) {
         return Mth.clamp(((float)this.ageTicks + partialTick) / (float)this.totalTicks, 0.0F, 1.0F);
      }

      private float getSmoothProgress(float partialTick) {
         float progress = this.getLinearProgress(partialTick);
         return progress * progress * (3.0F - 2.0F * progress);
      }

      private float smoothStep(float start, float end, float value) {
         if (end <= start) {
            return value >= end ? 1.0F : 0.0F;
         } else {
            float normalized = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
            return normalized * normalized * (3.0F - 2.0F * normalized);
         }
      }
   }
}
