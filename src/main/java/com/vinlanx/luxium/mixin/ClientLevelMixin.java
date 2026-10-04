package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.client.BlockLightTest.BlockLightTest;
import com.vinlanx.luxium.client.kawase.KawaseSourceRegistry;
import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.VanillaLavaLightEngine;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ClientLevel.class})
public class ClientLevelMixin {
   @Inject(
      method = {"tick"},
      at = {@At("HEAD")}
   )
   private void luxium$tick(BooleanSupplier runTask, CallbackInfo ci) {
      ClientLevel level = (ClientLevel)this;
      BlockLightTest.tick(level);
      TorchRtxState.get().tick(level);
      VanillaLavaLightEngine.get().tick(level, TorchRtxState.get().isEnabled());
      ExposedFaceService.get().tick(level);
   }

   @Inject(
      method = {"unload"},
      at = {@At("HEAD")}
   )
   private void luxium$unloadLavaLightChunk(LevelChunk chunk, CallbackInfo ci) {
      ClientLevel level = (ClientLevel)this;
      BlockLightTest.removeChunk(chunk.getPos().x, chunk.getPos().z);
      KawaseSourceRegistry.get().removeChunk(chunk.getPos().x, chunk.getPos().z);
      VanillaLavaLightEngine.get().onChunkUnloaded(level, chunk.getPos().x, chunk.getPos().z);
   }
}
