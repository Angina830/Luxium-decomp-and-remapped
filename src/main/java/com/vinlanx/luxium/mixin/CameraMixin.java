package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.client.ReflectionSystem;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({Camera.class})
public abstract class CameraMixin {
   @Inject(
      method = {"isDetached"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void luxium$renderPlayerInReflection(CallbackInfoReturnable<Boolean> cir) {
      if (ReflectionSystem.isRenderingWorldPass()) {
         cir.setReturnValue(true);
      }
   }
}
