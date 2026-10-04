package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(
   targets = {"org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderMeshingTask"}
)
public abstract class EmbeddiumChunkBuilderMeshingTaskMixin {
   @Inject(
      method = {"execute"},
      at = {@At("HEAD")},
      remap = false,
      require = 0
   )
   private void luxium$beginEmbeddiumSectionCapture(CallbackInfoReturnable<Object> cir) {
      if (((EmbeddiumChunkBuilderMeshingTaskAccessor)this).luxium$getRender() instanceof EmbeddiumRenderSectionAccessor access) {
         ExposedFaceService.get().beginSectionCapture(new BlockPos(access.luxium$getOriginX(), access.luxium$getOriginY(), access.luxium$getOriginZ()));
      } else {
         ExposedFaceService.get().discardSectionCapture();
      }
   }

   @Inject(
      method = {"execute"},
      at = {@At("RETURN")},
      remap = false,
      require = 0
   )
   private void luxium$finishEmbeddiumSectionCapture(CallbackInfoReturnable<Object> cir) {
      ExposedFaceService.get().finishSectionCapture();
   }

   @Inject(
      method = {"fillCrashInfo"},
      at = {@At("HEAD")},
      remap = false,
      require = 0
   )
   private void luxium$discardEmbeddiumCaptureOnCrash(CallbackInfoReturnable<ReportedException> cir) {
      ExposedFaceService.get().discardSectionCapture();
   }
}
