package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.client.guiscreen.VideoSettingsHubScreen;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin({OptionsScreen.class})
public abstract class OptionsScreenMixin {
   @ModifyArg(
      method = {"init"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;",
         ordinal = 2
      ),
      index = 1
   )
   private Supplier<Screen> luxium$redirectVideoSettingsButton(Supplier<Screen> originalSupplier) {
      return () -> new VideoSettingsHubScreen((Screen)this, originalSupplier);
   }
}
