package com.vinlanx.luxium.client.ConfigScreen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.client.ShaderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

final class GlassPanelRenderer implements AutoCloseable {
   private TextureTarget sceneCopy;

   boolean render(int x, int y, int width, int height, int screenWidth, int screenHeight, float blurAmount, float dimmingAmount) {
      Minecraft minecraft = Minecraft.getInstance();
      RenderTarget main = minecraft.getMainRenderTarget();
      ShaderInstance shader = ShaderManager.getConfigGlassShader();
      if (shader != null && main.viewWidth > 0 && main.viewHeight > 0) {
         this.ensureTarget(main);
         GL30.glBindFramebuffer(36008, main.frameBufferId);
         GL30.glBindFramebuffer(36009, this.sceneCopy.frameBufferId);
         GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, this.sceneCopy.width, this.sceneCopy.height, 16384, 9728);
         main.bindWrite(true);
         Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
         VertexSorting previousSorting = RenderSystem.getVertexSorting();
         Matrix4f projection = new Matrix4f().setOrtho(0.0F, (float)screenWidth, (float)screenHeight, 0.0F, 1000.0F, 3000.0F);
         PoseStack modelView = RenderSystem.getModelViewStack();
         modelView.pushPose();
         modelView.setIdentity();
         modelView.translate(0.0F, 0.0F, -2000.0F);
         RenderSystem.applyModelViewMatrix();

         try {
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            if (shader.MODEL_VIEW_MATRIX != null) {
               shader.MODEL_VIEW_MATRIX.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
            }

            if (shader.PROJECTION_MATRIX != null) {
               shader.PROJECTION_MATRIX.set(projection);
            }

            shader.setSampler("SceneSampler", this.sceneCopy.getColorTextureId());
            if (shader.getUniform("ScreenSize") != null) {
               shader.getUniform("ScreenSize").set((float)main.width, (float)main.height);
            }

            if (shader.getUniform("BlurEnabled") != null) {
               shader.getUniform("BlurEnabled").set(blurAmount);
            }

            if (shader.getUniform("DimmingEnabled") != null) {
               shader.getUniform("DimmingEnabled").set(dimmingAmount);
            }

            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            shader.apply();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buffer.vertex((double)x, (double)(y + height), 0.0).uv((float)x / (float)screenWidth, 1.0F - (float)(y + height) / (float)screenHeight).endVertex();
            buffer.vertex((double)(x + width), (double)(y + height), 0.0)
               .uv((float)(x + width) / (float)screenWidth, 1.0F - (float)(y + height) / (float)screenHeight)
               .endVertex();
            buffer.vertex((double)(x + width), (double)y, 0.0).uv((float)(x + width) / (float)screenWidth, 1.0F - (float)y / (float)screenHeight).endVertex();
            buffer.vertex((double)x, (double)y, 0.0).uv((float)x / (float)screenWidth, 1.0F - (float)y / (float)screenHeight).endVertex();
            BufferUploader.draw(buffer.end());
         } finally {
            shader.clear();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.viewport(0, 0, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
            GlStateManager._depthMask(true);
            GlStateManager._enableDepthTest();
            RenderSystem.defaultBlendFunc();
            main.bindWrite(true);
         }

         return true;
      } else {
         return false;
      }
   }

   private void ensureTarget(RenderTarget main) {
      if (this.sceneCopy == null) {
         this.sceneCopy = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
         this.sceneCopy.setFilterMode(9729);
      } else if (this.sceneCopy.width != main.width || this.sceneCopy.height != main.height) {
         this.sceneCopy.resize(main.width, main.height, Minecraft.ON_OSX);
      }
   }

   @Override
   public void close() {
      if (this.sceneCopy != null) {
         this.sceneCopy.destroyBuffers();
         this.sceneCopy = null;
      }
   }
}
