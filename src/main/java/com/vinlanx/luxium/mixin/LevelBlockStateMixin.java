package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.client.BlockLightTest.BlockLightTest;
import com.vinlanx.luxium.client.kawase.KawaseSourceRegistry;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.rtx.PlayerActionContext;
import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.VanillaLavaLightEngine;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Level.class})
public abstract class LevelBlockStateMixin {
   @Inject(
      method = {"onBlockStateChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"},
      at = {@At("HEAD")}
   )
   private void luxium$onVanillaBlockStateChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
      if (this instanceof ClientLevel level) {
         if (pos != null && oldState != null && newState != null && oldState != newState && !oldState.equals(newState)) {
            TorchRtxState state = TorchRtxState.get();
            boolean immediate = PlayerActionContext.isActive();
            ExposedFaceService faces = ExposedFaceService.get();
            boolean geometryChanged = faces.hasReceiverGeometryChanged(level, pos, oldState, newState);
            if (geometryChanged) {
               faces.refreshAroundNow(level, pos);
            }

            KawaseSourceRegistry.get().updateBlock(level, pos, newState);
            BlockLightTest.changed(level, pos, newState);
            state.trackBlockTransition(level, pos, oldState, newState, immediate, geometryChanged);
            VanillaLavaLightEngine.get().onBlockChanged(level, pos, oldState, newState);
            if (geometryChanged) {
               NeoSkyCelestia.get().markBlockDirty(pos.getX(), pos.getY(), pos.getZ());
            }
         }
      }
   }
}
