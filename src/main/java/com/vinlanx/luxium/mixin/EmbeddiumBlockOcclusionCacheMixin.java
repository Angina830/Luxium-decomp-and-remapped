package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(
   targets = {"me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockOcclusionCache"}
)
public abstract class EmbeddiumBlockOcclusionCacheMixin {
   @Inject(
      method = {"shouldDrawSide"},
      at = {@At("RETURN")},
      cancellable = true,
      remap = false,
      require = 1
   )
   private void luxium$captureEmbeddiumFaces(BlockState selfState, BlockGetter view, BlockPos pos, Direction facing, CallbackInfoReturnable<Boolean> cir) {
      if ((Boolean)cir.getReturnValue()) {
         ExposedFaceService.get().recordFace(pos, facing, view, selfState);
      }
   }
}
