package com.vinlanx.luxium.mixin.sky;

import com.vinlanx.luxium.client.kawase.KawaseBloomRenderer;
import com.vinlanx.luxium.client.kawase.KawaseSourceRegistry;
import com.vinlanx.luxium.client.shadows.GpuNeoShadows;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.rtx.VanillaLavaLightEngine;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Minecraft.class})
public abstract class MinecraftSkyShadowLifecycleMixin {
   @Inject(
      method = {"setLevel"},
      at = {@At("RETURN")}
   )
   private void luxium$changeSkyShadowWorld(ClientLevel level, CallbackInfo ci) {
      GpuNeoShadows.releaseResources();
      NeoGpuVanilla.releaseResources();
      KawaseBloomRenderer.releaseResources();
      KawaseSourceRegistry.get().setLevel(level);
      NeoSkyCelestia.get().onWorldChanged(level);
      VanillaLavaLightEngine.get().reset(level);
   }

   @Inject(
      method = {"clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V"},
      at = {@At("RETURN")}
   )
   private void luxium$clearSkyShadowWorld(Screen screen, CallbackInfo ci) {
      GpuNeoShadows.releaseResources();
      NeoGpuVanilla.releaseResources();
      KawaseBloomRenderer.releaseResources();
      KawaseSourceRegistry.get().setLevel(null);
      NeoSkyCelestia.get().onWorldChanged(null);
      VanillaLavaLightEngine.get().reset(null);
   }
}
