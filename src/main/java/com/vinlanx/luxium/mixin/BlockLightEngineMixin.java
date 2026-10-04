package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.rtx.LuxiumBlockLightEngineExtension;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({BlockLightEngine.class})
public abstract class BlockLightEngineMixin implements LuxiumBlockLightEngineExtension {
   @Unique
   private boolean luxium$lavaOnly;

   @Override
   public void luxium$setLavaOnly(boolean lavaOnly) {
      this.luxium$lavaOnly = lavaOnly;
   }

   @Override
   public boolean luxium$isLavaOnly() {
      return this.luxium$lavaOnly;
   }

   @Inject(
      method = {"getEmission"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void luxium$filterLavaOnlyEmission(long posKey, BlockState state, CallbackInfoReturnable<Integer> cir) {
      if (this.luxium$lavaOnly && !state.getFluidState().is(FluidTags.LAVA)) {
         cir.setReturnValue(0);
      }
   }
}
