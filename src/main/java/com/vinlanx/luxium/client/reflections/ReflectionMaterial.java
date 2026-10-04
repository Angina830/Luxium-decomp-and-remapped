package com.vinlanx.luxium.client.reflections;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public interface ReflectionMaterial {
   boolean supports(BlockState var1);

   default boolean shouldReflectFace(Minecraft mc, BlockPos pos, BlockState state, Direction face) {
      return true;
   }

   default float getBaseAlpha() {
      return 0.65F;
   }

   default float getMaxDistance() {
      return 40.0F;
   }

   default float computeAlpha(BlockPos pos, Direction face, Vec3 cameraPos, double distanceSq) {
      float maxDistance = this.getMaxDistance();
      double maxDistSq = (double)(maxDistance * maxDistance);
      if (distanceSq >= maxDistSq) {
         return 0.0F;
      } else {
         float fade = 1.0F - (float)Math.sqrt(distanceSq) / maxDistance;
         return Math.max(0.0F, this.getBaseAlpha() * fade);
      }
   }

   default float adjustVertexAlpha(float alpha, float projectedU, float projectedV, BlockPos pos, Direction face, int vertexIndex, Vec3 cameraPos) {
      return alpha;
   }

   default boolean useRaycastVisibility() {
      return true;
   }

   default boolean useCornerVisibilitySamples() {
      return true;
   }

   default int getFaceSubdivision() {
      return 1;
   }

   default float warpU(float u, float v, float worldX, float worldY, float worldZ, BlockPos pos, Direction face, int vertexIndex, Vec3 cameraPos) {
      return u;
   }

   default float warpV(float u, float v, float worldX, float worldY, float worldZ, BlockPos pos, Direction face, int vertexIndex, Vec3 cameraPos) {
      return v;
   }

   static float clampUv(float uv) {
      return Math.max(0.0F, Math.min(1.0F, uv));
   }

   static float hashToUnit(int x, int y, int z, int w) {
      int h = x * 73428767 ^ y * 912931 ^ z * 19349663 ^ w * 83492791;
      h ^= h >>> 13;
      h *= 1274126177;
      h ^= h >>> 16;
      return (float)(h & 2147483647) / 2.1474836E9F;
   }
}
