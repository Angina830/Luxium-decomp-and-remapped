package com.vinlanx.luxium.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin({EntityRenderDispatcher.class})
public interface EntityRenderDispatcherAccessor {
   @Accessor("shouldRenderShadow")
   boolean luxium$shouldRenderShadow();
}
