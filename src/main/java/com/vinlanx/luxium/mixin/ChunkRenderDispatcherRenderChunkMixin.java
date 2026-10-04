package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.rtx.soasnottointerfere.SectionCaptureAccess;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk;
import net.minecraft.core.BlockPos.MutableBlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({RenderChunk.class})
public abstract class ChunkRenderDispatcherRenderChunkMixin {
   @Shadow
   @Final
   private MutableBlockPos origin;

   @Inject(
      method = {"createCompileTask"},
      at = {@At("RETURN")},
      require = 0
   )
   private void luxium$attachSectionOrigin(RenderRegionCache renderRegionCache, CallbackInfoReturnable<Object> cir) {
      if (cir.getReturnValue() instanceof SectionCaptureAccess access) {
         access.luxium$setSectionOrigin(this.origin.immutable());
      }
   }
}
