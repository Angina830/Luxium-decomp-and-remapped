package com.vinlanx.luxium.client.rainpuddles;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record RainPuddleInstance(
   ResourceLocation depthMap, int sizeBlocks, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, List<RainPuddlePatch> patches
) {
   public RainPuddleInstance(
      ResourceLocation depthMap, int sizeBlocks, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, List<RainPuddlePatch> patches
   ) {
      patches = List.copyOf(patches);
      this.depthMap = depthMap;
      this.sizeBlocks = sizeBlocks;
      this.minBlockX = minBlockX;
      this.minBlockZ = minBlockZ;
      this.maxBlockX = maxBlockX;
      this.maxBlockZ = maxBlockZ;
      this.patches = patches;
   }

   public int patchCount() {
      return this.patches.size();
   }

   public boolean intersects(int minX, int minZ, int maxX, int maxZ) {
      return this.minBlockX <= maxX && this.maxBlockX >= minX && this.minBlockZ <= maxZ && this.maxBlockZ >= minZ;
   }
}
