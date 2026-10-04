package com.vinlanx.luxium.client.reflections;

import net.minecraft.core.BlockPos;

public final class RainPuddleSurface {
   private final RainHeightmap heightmap;
   private final int spanBlocks;
   private final int minBlockX;
   private final int minBlockZ;
   private final long[] coveredBlockKeys;

   public RainPuddleSurface(RainHeightmap heightmap, int spanBlocks, int minBlockX, int minBlockZ, long[] coveredBlockKeys) {
      if (heightmap == null) {
         throw new IllegalArgumentException("heightmap");
      } else if (spanBlocks <= 0) {
         throw new IllegalArgumentException("spanBlocks must be positive");
      } else if (coveredBlockKeys == null) {
         throw new IllegalArgumentException("coveredBlockKeys");
      } else {
         this.heightmap = heightmap;
         this.spanBlocks = spanBlocks;
         this.minBlockX = minBlockX;
         this.minBlockZ = minBlockZ;
         this.coveredBlockKeys = (long[])coveredBlockKeys.clone();
      }
   }

   public RainHeightmap getHeightmap() {
      return this.heightmap;
   }

   public int getSpanBlocks() {
      return this.spanBlocks;
   }

   public int getCoveredBlockCount() {
      return this.coveredBlockKeys.length;
   }

   public int getRecommendedSubdivision() {
      return this.heightmap.getRecommendedSubdivision(this.spanBlocks);
   }

   public long[] getCoveredBlockKeys() {
      return (long[])this.coveredBlockKeys.clone();
   }

   public float sample(BlockPos pos, float faceU, float faceV) {
      float globalU = ((float)(pos.getX() - this.minBlockX) + faceU) / (float)this.spanBlocks;
      float globalV = ((float)(pos.getZ() - this.minBlockZ) + faceV) / (float)this.spanBlocks;
      return this.heightmap.sample(globalU, globalV);
   }
}
