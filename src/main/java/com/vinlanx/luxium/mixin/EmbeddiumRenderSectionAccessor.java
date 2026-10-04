package com.vinlanx.luxium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(
   targets = {"org.embeddedt.embeddium.impl.render.chunk.RenderSection"}
)
public interface EmbeddiumRenderSectionAccessor {
   @Invoker("getOriginX")
   int luxium$getOriginX();

   @Invoker("getOriginY")
   int luxium$getOriginY();

   @Invoker("getOriginZ")
   int luxium$getOriginZ();
}
