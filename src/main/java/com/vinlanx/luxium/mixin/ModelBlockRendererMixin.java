package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({ModelBlockRenderer.class})
public abstract class ModelBlockRendererMixin {
   @Redirect(
      method = {"tesselateWithAO(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/block/Block;shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z"
      ),
      require = 0
   )
   private boolean luxium$captureAoFaces(BlockState state, BlockGetter level, BlockPos pos, Direction face, BlockPos neighbourPos) {
      boolean shouldRender = Block.shouldRenderFace(state, level, pos, face, neighbourPos);
      if (shouldRender) {
         ExposedFaceService.get().recordFace(pos, face, level, state);
      }

      return shouldRender;
   }

   @Redirect(
      method = {"tesselateWithoutAO(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/block/Block;shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z"
      ),
      require = 0
   )
   private boolean luxium$captureFlatFaces(BlockState state, BlockGetter level, BlockPos pos, Direction face, BlockPos neighbourPos) {
      boolean shouldRender = Block.shouldRenderFace(state, level, pos, face, neighbourPos);
      if (shouldRender) {
         ExposedFaceService.get().recordFace(pos, face, level, state);
      }

      return shouldRender;
   }
}
