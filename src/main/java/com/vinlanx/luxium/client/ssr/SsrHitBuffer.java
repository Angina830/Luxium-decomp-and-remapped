package com.vinlanx.luxium.client.ssr;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

final class SsrHitBuffer implements AutoCloseable {
   private int textureId;
   private int width;
   private int height;
   private int attachedFramebuffer;
   private boolean captureActive;
   private boolean ready;

   boolean beginCapture(int framebufferId, int requestedWidth, int requestedHeight) {
      RenderSystem.assertOnRenderThread();
      if (framebufferId > 0 && requestedWidth > 0 && requestedHeight > 0) {
         this.ensure(requestedWidth, requestedHeight);
         if (this.textureId == 0) {
            this.invalidate();
            return false;
         } else {
            GlStateManager._glBindFramebuffer(36160, framebufferId);
            GL30.glFramebufferTexture2D(36160, 36065, 3553, this.textureId, 0);
            GL20.glDrawBuffers(new int[]{36065});
            GlStateManager._clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GlStateManager._clear(16384, Minecraft.ON_OSX);
            GL20.glDrawBuffers(new int[]{36064, 36065});
            int status = GL30.glCheckFramebufferStatus(36160);
            if (status != 36053) {
               GL20.glDrawBuffers(new int[]{36064});
               GL30.glFramebufferTexture2D(36160, 36065, 3553, 0, 0);
               this.invalidate();
               return false;
            } else {
               this.attachedFramebuffer = framebufferId;
               this.captureActive = true;
               this.ready = false;
               return true;
            }
         }
      } else {
         this.invalidate();
         return false;
      }
   }

   void endCapture(boolean successful) {
      RenderSystem.assertOnRenderThread();
      if (!this.captureActive) {
         this.ready = false;
      } else {
         if (this.attachedFramebuffer > 0) {
            GlStateManager._glBindFramebuffer(36160, this.attachedFramebuffer);
            GL20.glDrawBuffers(new int[]{36064});
            GL30.glFramebufferTexture2D(36160, 36065, 3553, 0, 0);
         }

         this.ready = successful && this.textureId != 0 && this.width > 0 && this.height > 0;
         this.captureActive = false;
         this.attachedFramebuffer = 0;
      }
   }

   boolean captureActive() {
      return this.captureActive;
   }

   boolean ready() {
      return this.ready && this.textureId != 0 && this.width > 0 && this.height > 0;
   }

   int textureId() {
      return this.ready() ? this.textureId : -1;
   }

   int width() {
      return this.width;
   }

   int height() {
      return this.height;
   }

   void invalidate() {
      this.ready = false;
      this.captureActive = false;
      this.attachedFramebuffer = 0;
   }

   private void ensure(int requestedWidth, int requestedHeight) {
      int newWidth = Math.max(1, requestedWidth);
      int newHeight = Math.max(1, requestedHeight);
      if (this.textureId == 0 || this.width != newWidth || this.height != newHeight) {
         this.destroyTexture();
         this.width = newWidth;
         this.height = newHeight;
         this.textureId = GlStateManager._genTexture();
         int previousTexture2D = GL11.glGetInteger(32873);

         try {
            GlStateManager._bindTexture(this.textureId);
            GL11.glTexImage2D(3553, 0, 34842, this.width, this.height, 0, 6408, 5126, 0L);
            GL11.glTexParameteri(3553, 10241, 9728);
            GL11.glTexParameteri(3553, 10240, 9728);
            GL11.glTexParameteri(3553, 10242, 33071);
            GL11.glTexParameteri(3553, 10243, 33071);
         } finally {
            GlStateManager._bindTexture(previousTexture2D);
         }
      }
   }

   @Override
   public void close() {
      RenderSystem.assertOnRenderThread();
      this.destroyTexture();
   }

   private void destroyTexture() {
      if (this.textureId != 0) {
         GlStateManager._deleteTexture(this.textureId);
         this.textureId = 0;
      }

      this.width = 0;
      this.height = 0;
      this.ready = false;
      this.captureActive = false;
      this.attachedFramebuffer = 0;
   }
}
