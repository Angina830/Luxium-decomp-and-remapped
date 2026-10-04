package com.vinlanx.luxium.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin({Camera.class})
public interface CameraAccessor {
   @Invoker("setPosition")
   void luxium$setPosition(double var1, double var3, double var5);

   @Invoker("setRotation")
   void luxium$setRotation(float var1, float var2);
}
