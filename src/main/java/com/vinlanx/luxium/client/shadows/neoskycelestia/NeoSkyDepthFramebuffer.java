package com.vinlanx.luxium.client.shadows.neoskycelestia;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public final class NeoSkyDepthFramebuffer implements AutoCloseable {
   private static final int GL_CLAMP_TO_BORDER = 33069;
   private static final int GL_TEXTURE_COMPARE_MODE = 34892;
   private static final int GL_TEXTURE_COMPARE_FUNC = 34893;
   private static final int GL_COMPARE_REF_TO_TEXTURE = 34894;
   private final boolean comparisonSampling;
   private boolean linearFiltering;
   private int framebufferId;
   private int depthTextureId;
   private int size;

   public NeoSkyDepthFramebuffer() {
      this(false);
   }

   public NeoSkyDepthFramebuffer(boolean comparisonSampling) {
      this.comparisonSampling = comparisonSampling;
   }

   public void resize(int requestedSize) {
      RenderSystem.assertOnRenderThread();
      int newSize = Math.max(16, requestedSize);
      if (this.framebufferId == 0 || this.size != newSize) {
         int previousFramebuffer = GL11.glGetInteger(36006);
         int previousTexture = GL11.glGetInteger(32873);
         MemoryStack stack = MemoryStack.stackPush();

         try {
            IntBuffer previousViewport = stack.mallocInt(4);
            GL11.glGetIntegerv(2978, previousViewport);
            int viewportX = previousViewport.get(0);
            int viewportY = previousViewport.get(1);
            int viewportWidth = previousViewport.get(2);
            int viewportHeight = previousViewport.get(3);
            this.allocate(newSize);
            GlStateManager._bindTexture(previousTexture);
            GL30.glBindFramebuffer(36160, previousFramebuffer);
            GL11.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
         } catch (Throwable var12) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var11) {
                  var12.addSuppressed(var11);
               }
            }

            throw var12;
         }

         if (stack != null) {
            stack.close();
         }
      }
   }

   private void allocate(int newSize) {
      this.destroy();
      this.size = newSize;
      this.framebufferId = GL30.glGenFramebuffers();
      this.depthTextureId = GlStateManager._genTexture();
      GL30.glBindFramebuffer(36160, this.framebufferId);
      GlStateManager._bindTexture(this.depthTextureId);
      GL11.glTexImage2D(3553, 0, 33190, newSize, newSize, 0, 6402, 5126, 0L);
      int textureFilter = this.linearFiltering ? 9729 : 9728;
      GL11.glTexParameteri(3553, 10241, textureFilter);
      GL11.glTexParameteri(3553, 10240, textureFilter);
      GL11.glTexParameteri(3553, 10242, 33069);
      GL11.glTexParameteri(3553, 10243, 33069);
      MemoryStack stack = MemoryStack.stackPush();

      try {
         FloatBuffer border = stack.floats(0.0F, 0.0F, 0.0F, 0.0F);
         GL11.glTexParameterfv(3553, 4100, border);
      } catch (Throwable var7) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (stack != null) {
         stack.close();
      }

      GL11.glTexParameteri(3553, 34892, this.comparisonSampling ? '衎' : 0);
      if (this.comparisonSampling) {
         GL11.glTexParameteri(3553, 34893, 515);
      }

      GL30.glFramebufferTexture2D(36160, 36096, 3553, this.depthTextureId, 0);
      GL11.glDrawBuffer(0);
      GL11.glReadBuffer(0);
      GL11.glClearDepth(1.0);
      int status = GL30.glCheckFramebufferStatus(36160);
      if (status != 36053) {
         this.destroy();
         throw new IllegalStateException("Luxium depth framebuffer is incomplete: 0x" + Integer.toHexString(status));
      } else {
         GlStateManager._bindTexture(0);
      }
   }

   public void bindAndClear() {
      RenderSystem.assertOnRenderThread();
      if (this.framebufferId == 0) {
         throw new IllegalStateException("Depth framebuffer is not allocated");
      } else {
         this.bind();
         GL11.glClear(256);
      }
   }

   public void bind() {
      RenderSystem.assertOnRenderThread();
      if (this.framebufferId == 0) {
         throw new IllegalStateException("Depth framebuffer is not allocated");
      } else {
         GL30.glBindFramebuffer(36160, this.framebufferId);
         GL11.glViewport(0, 0, this.size, this.size);
      }
   }

   public void setLinearFiltering(boolean linearFiltering) {
      RenderSystem.assertOnRenderThread();
      if (this.linearFiltering != linearFiltering) {
         this.linearFiltering = linearFiltering;
         if (this.depthTextureId != 0) {
            int previousTexture = GL11.glGetInteger(32873);
            GlStateManager._bindTexture(this.depthTextureId);
            int textureFilter = this.linearFiltering ? 9729 : 9728;
            GL11.glTexParameteri(3553, 10241, textureFilter);
            GL11.glTexParameteri(3553, 10240, textureFilter);
            GlStateManager._bindTexture(previousTexture);
         }
      }
   }

   public int depthTextureId() {
      return this.depthTextureId;
   }

   public int size() {
      return this.size;
   }

   @Override
   public void close() {
      RenderSystem.assertOnRenderThread();
      this.destroy();
   }

   private void destroy() {
      if (this.depthTextureId != 0) {
         GlStateManager._deleteTexture(this.depthTextureId);
         this.depthTextureId = 0;
      }

      if (this.framebufferId != 0) {
         GL30.glDeleteFramebuffers(this.framebufferId);
         this.framebufferId = 0;
      }

      this.size = 0;
   }
}
