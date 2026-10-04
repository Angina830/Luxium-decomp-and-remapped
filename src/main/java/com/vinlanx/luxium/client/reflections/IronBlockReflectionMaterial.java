package com.vinlanx.luxium.client.reflections;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class IronBlockReflectionMaterial implements ReflectionMaterial {
   @Override
   public boolean supports(BlockState state) {
      return state.is(Blocks.IRON_BLOCK);
   }

   @Override
   public float getBaseAlpha() {
      return 0.85F;
   }
}
