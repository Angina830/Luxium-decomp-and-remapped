package com.vinlanx.luxium.client.postprocess;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.vinlanx.luxium.mixin.RenderTargetAccessor;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL30;

public final class PostProcessSwapChain {
   private static TextureTarget scratch;

   private PostProcessSwapChain() {
   }

   public static RenderTarget begin(RenderTarget main) {
      ensure(main);
      if (scratch.filterMode != main.filterMode) {
         scratch.setFilterMode(main.filterMode);
      }

      scratch.bindWrite(true);
      return scratch;
   }

   public static void commit(RenderTarget main) {
      if (scratch != null && scratch.width == main.width && scratch.height == main.height) {
         RenderTargetAccessor mainAccess = (RenderTargetAccessor)main;
         RenderTargetAccessor scratchAccess = (RenderTargetAccessor)scratch;
         int oldMain = mainAccess.luxium$getColorTextureIdRaw();
         int newMain = scratchAccess.luxium$getColorTextureIdRaw();
         if (oldMain > 0 && newMain > 0 && oldMain != newMain) {
            mainAccess.luxium$setColorTextureIdRaw(newMain);
            scratchAccess.luxium$setColorTextureIdRaw(oldMain);
            attachColor(main.frameBufferId, newMain);
            attachColor(scratch.frameBufferId, oldMain);
            main.bindWrite(true);
         } else {
            main.bindWrite(true);
         }
      } else {
         main.bindWrite(true);
      }
   }

   public static int scratchTextureId() {
      return scratch != null ? scratch.getColorTextureId() : -1;
   }

   private static void ensure(RenderTarget main) {
      if (scratch == null) {
         scratch = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
         scratch.setFilterMode(9728);
      } else if (scratch.width != main.width || scratch.height != main.height) {
         scratch.resize(main.width, main.height, Minecraft.ON_OSX);
         scratch.setFilterMode(9728);
      }
   }

   private static void attachColor(int framebuffer, int texture) {
      GlStateManager._glBindFramebuffer(36160, framebuffer);
      GL30.glFramebufferTexture2D(36160, 36064, 3553, texture, 0);
   }
}
