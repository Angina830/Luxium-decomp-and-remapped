package com.vinlanx.luxium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin({RenderTarget.class})
public interface RenderTargetAccessor {
   @Accessor("colorTextureId")
   int luxium$getColorTextureIdRaw();

   @Accessor("colorTextureId")
   void luxium$setColorTextureIdRaw(int var1);
}
