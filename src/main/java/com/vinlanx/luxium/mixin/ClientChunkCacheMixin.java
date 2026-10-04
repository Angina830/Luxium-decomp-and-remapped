package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.client.BlockLightTest.BlockLightTest;
import com.vinlanx.luxium.client.kawase.KawaseSourceRegistry;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.VanillaLavaLightEngine;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.BlockEntityTagOutput;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({ClientChunkCache.class})
public abstract class ClientChunkCacheMixin {
   @Shadow
   ClientLevel level;

   @Inject(
      method = {"replaceWithPacketData"},
      at = {@At("RETURN")}
   )
   private void luxium$onChunkLoad(
      int x, int z, FriendlyByteBuf buf, CompoundTag tag, Consumer<BlockEntityTagOutput> consumer, CallbackInfoReturnable<LevelChunk> cir
   ) {
      LevelChunk chunk = (LevelChunk)cir.getReturnValue();
      if (chunk != null) {
         ClientLevel capturedLevel = this.level;
         BlockLightTest.onChunk(capturedLevel, chunk);
         ExposedFaceService.get().removeChunk(x, z);
         VanillaLavaLightEngine.get().onChunkLoaded(capturedLevel, x, z);
         TorchRtxState.get().addChunkLightSources(capturedLevel, chunk);
         KawaseSourceRegistry.get().addChunk(capturedLevel, chunk);
         NeoSkyCelestia.get().markChunkDirty(x, z);
      }
   }

   @Inject(
      method = {"drop"},
      at = {@At("HEAD")}
   )
   private void luxium$onChunkUnload(int x, int z, CallbackInfo ci) {
      BlockLightTest.removeChunk(x, z);
      TorchRtxState.get().removeChunkLightSources(x, z);
      KawaseSourceRegistry.get().removeChunk(x, z);
      NeoSkyCelestia.get().markChunkDirty(x, z);
      ExposedFaceService.get().removeChunk(x, z);
   }

   @Inject(
      method = {"updateViewRadius"},
      at = {@At("RETURN")}
   )
   private void luxium$reconcileKawaseSources(int radius, CallbackInfo ci) {
      KawaseSourceRegistry.get().reconcileLoadedChunks(this.level);
   }
}
