package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.Config;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({ItemBlockRenderTypes.class})
public abstract class ItemBlockRenderTypesMixin {
   @Unique
   private static final ChunkRenderTypeSet LUXIUM$OPAQUE_ICE_LAYERS = ChunkRenderTypeSet.of(new RenderType[]{RenderType.solid()});

   @Unique
   private static boolean luxium$useOpaqueIce(BlockState state) {
      return state.getBlock() == Blocks.ICE && Config.isFeatureEnabled(Config.CLIENT.opaqueIceEnabled);
   }

   @Inject(
      method = {"getRenderLayers"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false
   )
   private static void luxium$overrideIceRenderLayers(BlockState state, CallbackInfoReturnable<ChunkRenderTypeSet> cir) {
      if (luxium$useOpaqueIce(state)) {
         cir.setReturnValue(LUXIUM$OPAQUE_ICE_LAYERS);
      }
   }

   @Inject(
      method = {"getChunkRenderType"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void luxium$overrideLegacyIceChunkLayer(BlockState state, CallbackInfoReturnable<RenderType> cir) {
      if (luxium$useOpaqueIce(state)) {
         cir.setReturnValue(RenderType.solid());
      }
   }

   @Inject(
      method = {"getMovingBlockRenderType"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void luxium$overrideMovingIceChunkLayer(BlockState state, CallbackInfoReturnable<RenderType> cir) {
      if (luxium$useOpaqueIce(state)) {
         cir.setReturnValue(RenderType.solid());
      }
   }
}
