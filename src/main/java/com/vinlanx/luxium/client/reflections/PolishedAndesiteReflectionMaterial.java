package com.vinlanx.luxium.client.reflections;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class PolishedAndesiteReflectionMaterial implements ReflectionMaterial {
   @Override
   public boolean supports(BlockState state) {
      return state.is(Blocks.POLISHED_ANDESITE);
   }

   @Override
   public float getBaseAlpha() {
      return 0.65F;
   }

   @Override
   public float warpU(float u, float v, float worldX, float worldY, float worldZ, BlockPos pos, Direction face, int vertexIndex, Vec3 cameraPos) {
      float wave = (float)Math.sin((double)(worldX * 1.7F + worldZ * 1.1F + (float)face.get3DDataValue() * 0.9F)) * 0.004F;
      return ReflectionMaterial.clampUv(u + wave);
   }

   @Override
   public float warpV(float u, float v, float worldX, float worldY, float worldZ, BlockPos pos, Direction face, int vertexIndex, Vec3 cameraPos) {
      float wave = (float)Math.cos((double)(worldY * 2.0F + worldX * 0.8F + (float)face.get3DDataValue() * 1.2F)) * 0.004F;
      return ReflectionMaterial.clampUv(v + wave);
   }
}
