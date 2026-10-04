package com.vinlanx.luxium.client.reflections;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class QuartzBlockReflectionMaterial implements ReflectionMaterial {
   private static final Set<Block> QUARTZ_BLOCKS = Set.of(
      Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.CHISELED_QUARTZ_BLOCK, Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_BRICKS
   );

   @Override
   public boolean supports(BlockState state) {
      return QUARTZ_BLOCKS.contains(state.getBlock());
   }

   @Override
   public boolean shouldReflectFace(Minecraft mc, BlockPos pos, BlockState state, Direction face) {
      return face == Direction.UP;
   }

   @Override
   public boolean useRaycastVisibility() {
      return false;
   }

   @Override
   public boolean useCornerVisibilitySamples() {
      return false;
   }

   @Override
   public float getBaseAlpha() {
      return 0.2F;
   }

   @Override
   public float adjustVertexAlpha(float alpha, float projectedU, float projectedV, BlockPos pos, Direction face, int vertexIndex, Vec3 cameraPos) {
      float edgeDistance = Math.min(Math.min(projectedU, 1.0F - projectedU), Math.min(projectedV, 1.0F - projectedV));
      float edgeFade = smoothstep(-0.1F, 0.08F, edgeDistance);
      return alpha * (0.35F + 0.65F * edgeFade);
   }

   @Override
   public float warpU(float u, float v, float worldX, float worldY, float worldZ, BlockPos pos, Direction face, int vertexIndex, Vec3 cameraPos) {
      float scatter = quartzScatterScale(pos, face, cameraPos);
      float grainA = ReflectionMaterial.hashToUnit(pos.getX(), pos.getY(), pos.getZ(), face.get3DDataValue() * 17 + vertexIndex * 31) - 0.5F;
      float grainB = ReflectionMaterial.hashToUnit(pos.getZ(), pos.getX(), pos.getY(), face.get3DDataValue() * 13 + vertexIndex * 29) - 0.5F;
      float lowFreq = (float)Math.sin((double)(worldX * 0.9F + worldZ * 1.3F + worldY * 0.4F)) * 0.0035F * scatter;
      return ReflectionMaterial.clampUv(u + grainA * 0.015F * scatter + grainB * 0.006F * scatter + lowFreq);
   }

   @Override
   public float warpV(float u, float v, float worldX, float worldY, float worldZ, BlockPos pos, Direction face, int vertexIndex, Vec3 cameraPos) {
      float scatter = quartzScatterScale(pos, face, cameraPos);
      float grainA = ReflectionMaterial.hashToUnit(pos.getY(), pos.getZ(), pos.getX(), face.get3DDataValue() * 19 + vertexIndex * 37) - 0.5F;
      float grainB = ReflectionMaterial.hashToUnit(pos.getX(), pos.getY(), pos.getZ(), face.get3DDataValue() * 23 + vertexIndex * 41) - 0.5F;
      float lowFreq = (float)Math.cos((double)(worldZ * 1.1F + worldX * 0.6F + worldY * 0.7F)) * 0.0035F * scatter;
      return ReflectionMaterial.clampUv(v + grainA * 0.015F * scatter + grainB * 0.006F * scatter + lowFreq);
   }

   private static float quartzScatterScale(BlockPos pos, Direction face, Vec3 cameraPos) {
      Vec3 faceCenter = Vec3.atCenterOf(pos).add((double)face.getStepX() * 0.5, (double)face.getStepY() * 0.5, (double)face.getStepZ() * 0.5);
      float distance = (float)cameraPos.distanceTo(faceCenter);
      return 0.35F + 0.65F * smoothstep(1.0F, 4.0F, distance);
   }

   private static float smoothstep(float edge0, float edge1, float value) {
      if (edge1 <= edge0) {
         return value >= edge1 ? 1.0F : 0.0F;
      } else {
         float t = (value - edge0) / (edge1 - edge0);
         t = Math.max(0.0F, Math.min(1.0F, t));
         return t * t * (3.0F - 2.0F * t);
      }
   }
}
