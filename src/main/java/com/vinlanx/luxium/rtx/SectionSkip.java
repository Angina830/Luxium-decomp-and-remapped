package com.vinlanx.luxium.rtx;

import net.minecraft.util.Mth;

public final class SectionSkip {
   private static final ThreadLocal<SectionSkip.TraversalState> TRAVERSAL_STATE_TL = ThreadLocal.withInitial(SectionSkip.TraversalState::new);

   private SectionSkip() {
   }

   public static SectionSkip.TraversalState rentState() {
      return TRAVERSAL_STATE_TL.get();
   }

   public static double nextBoundaryDistance(SectionSkip.TraversalState state) {
      return Math.min(state.tMaxX, Math.min(state.tMaxY, state.tMaxZ));
   }

   public static void advanceTo(SectionSkip.TraversalState state, double nextTraveled, double tieEpsilon) {
      if (Math.abs(state.tMaxX - nextTraveled) <= tieEpsilon) {
         state.blockX = state.blockX + state.stepX;
         state.tMaxX = state.tMaxX + state.tDeltaX;
      }

      if (Math.abs(state.tMaxY - nextTraveled) <= tieEpsilon) {
         state.blockY = state.blockY + state.stepY;
         state.tMaxY = state.tMaxY + state.tDeltaY;
      }

      if (Math.abs(state.tMaxZ - nextTraveled) <= tieEpsilon) {
         state.blockZ = state.blockZ + state.stepZ;
         state.tMaxZ = state.tMaxZ + state.tDeltaZ;
      }

      state.traveled = nextTraveled;
   }

   public static double skipEmptySection(
      SectionSkip.TraversalState state, int chunkX, int chunkZ, int sectionIndex, int minBuildHeight, double maxDistance, double exitPadding
   ) {
      double posX = state.currentX();
      double posY = state.currentY();
      double posZ = state.currentZ();
      double minX = (double)chunkX * 16.0;
      double maxX = minX + 16.0;
      double minY = (double)(minBuildHeight + (sectionIndex << 4));
      double maxY = minY + 16.0;
      double minZ = (double)chunkZ * 16.0;
      double maxZ = minZ + 16.0;
      double tx = state.stepX > 0 ? (maxX - posX) / state.dirX : (state.stepX < 0 ? (minX - posX) / state.dirX : Double.MAX_VALUE);
      double ty = state.stepY > 0 ? (maxY - posY) / state.dirY : (state.stepY < 0 ? (minY - posY) / state.dirY : Double.MAX_VALUE);
      double tz = state.stepZ > 0 ? (maxZ - posZ) / state.dirZ : (state.stepZ < 0 ? (minZ - posZ) / state.dirZ : Double.MAX_VALUE);
      double skip = Math.max(0.0, Math.min(tx, Math.min(ty, tz)) + exitPadding);
      double nextTraveled = Math.min(maxDistance, state.traveled + skip);
      state.traveled = nextTraveled;
      if (nextTraveled >= maxDistance) {
         return nextTraveled;
      } else {
         double nextX = state.currentX();
         double nextY = state.currentY();
         double nextZ = state.currentZ();
         state.blockX = Mth.floor(nextX);
         state.blockY = Mth.floor(nextY);
         state.blockZ = Mth.floor(nextZ);
         state.tMaxX = state.stepX > 0
            ? ((double)state.blockX + 1.0 - nextX) / state.dirX + nextTraveled
            : (state.stepX < 0 ? (nextX - (double)state.blockX) / -state.dirX + nextTraveled : Double.POSITIVE_INFINITY);
         state.tMaxY = state.stepY > 0
            ? ((double)state.blockY + 1.0 - nextY) / state.dirY + nextTraveled
            : (state.stepY < 0 ? (nextY - (double)state.blockY) / -state.dirY + nextTraveled : Double.POSITIVE_INFINITY);
         state.tMaxZ = state.stepZ > 0
            ? ((double)state.blockZ + 1.0 - nextZ) / state.dirZ + nextTraveled
            : (state.stepZ < 0 ? (nextZ - (double)state.blockZ) / -state.dirZ + nextTraveled : Double.POSITIVE_INFINITY);
         return nextTraveled;
      }
   }

   public static final class TraversalState {
      public double originX;
      public double originY;
      public double originZ;
      public double dirX;
      public double dirY;
      public double dirZ;
      public int blockX;
      public int blockY;
      public int blockZ;
      public int stepX;
      public int stepY;
      public int stepZ;
      public double tDeltaX;
      public double tDeltaY;
      public double tDeltaZ;
      public double tMaxX;
      public double tMaxY;
      public double tMaxZ;
      public double traveled;

      public SectionSkip.TraversalState reset(
         double originX, double originY, double originZ, double dirX, double dirY, double dirZ, int blockX, int blockY, int blockZ
      ) {
         this.originX = originX;
         this.originY = originY;
         this.originZ = originZ;
         this.dirX = dirX;
         this.dirY = dirY;
         this.dirZ = dirZ;
         this.blockX = blockX;
         this.blockY = blockY;
         this.blockZ = blockZ;
         this.stepX = dirX > 0.0 ? 1 : (dirX < 0.0 ? -1 : 0);
         this.stepY = dirY > 0.0 ? 1 : (dirY < 0.0 ? -1 : 0);
         this.stepZ = dirZ > 0.0 ? 1 : (dirZ < 0.0 ? -1 : 0);
         this.tDeltaX = this.stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dirX);
         this.tDeltaY = this.stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dirY);
         this.tDeltaZ = this.stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dirZ);
         this.tMaxX = this.stepX > 0
            ? ((double)blockX + 1.0 - originX) / dirX
            : (this.stepX < 0 ? (originX - (double)blockX) / -dirX : Double.POSITIVE_INFINITY);
         this.tMaxY = this.stepY > 0
            ? ((double)blockY + 1.0 - originY) / dirY
            : (this.stepY < 0 ? (originY - (double)blockY) / -dirY : Double.POSITIVE_INFINITY);
         this.tMaxZ = this.stepZ > 0
            ? ((double)blockZ + 1.0 - originZ) / dirZ
            : (this.stepZ < 0 ? (originZ - (double)blockZ) / -dirZ : Double.POSITIVE_INFINITY);
         this.traveled = 0.0;
         return this;
      }

      public double currentX() {
         return this.originX + this.dirX * this.traveled;
      }

      public double currentY() {
         return this.originY + this.dirY * this.traveled;
      }

      public double currentZ() {
         return this.originZ + this.dirZ * this.traveled;
      }
   }
}
