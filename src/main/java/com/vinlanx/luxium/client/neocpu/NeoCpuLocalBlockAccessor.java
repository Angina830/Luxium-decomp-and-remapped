package com.vinlanx.luxium.client.neocpu;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class NeoCpuLocalBlockAccessor {
   private final ClientLevel level;
   private final NeoCpuShadowShapeCache shapeCache;
   private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap();
   private final Long2ObjectOpenHashMap<NeoCpuShadowShape> shapes = new Long2ObjectOpenHashMap();
   private final MutableBlockPos cursor = new MutableBlockPos();
   private final int minBuildHeight;
   private final int maxBuildHeight;

   NeoCpuLocalBlockAccessor(ClientLevel level, NeoCpuShadowShapeCache shapeCache) {
      this.level = level;
      this.shapeCache = shapeCache;
      this.minBuildHeight = level.getMinBuildHeight();
      this.maxBuildHeight = level.getMaxBuildHeight();
      this.states.defaultReturnValue(null);
      this.shapes.defaultReturnValue(null);
   }

   boolean isLoaded(int x, int z) {
      this.cursor.set(x, this.minBuildHeight, z);
      return this.level.hasChunkAt(this.cursor);
   }

   boolean isInsideBuildHeight(int y) {
      return y >= this.minBuildHeight && y < this.maxBuildHeight;
   }

   BlockState stateAt(int x, int y, int z) {
      long key = BlockPos.asLong(x, y, z);
      BlockState cached = (BlockState)this.states.get(key);
      if (cached != null) {
         return cached;
      } else {
         this.cursor.set(x, y, z);
         BlockState state = this.level.hasChunkAt(this.cursor) ? this.level.getBlockState(this.cursor) : Blocks.AIR.defaultBlockState();
         this.states.put(key, state);
         return state;
      }
   }

   NeoCpuShadowShape shapeAt(int x, int y, int z) {
      long key = BlockPos.asLong(x, y, z);
      NeoCpuShadowShape cached = (NeoCpuShadowShape)this.shapes.get(key);
      if (cached != null) {
         return cached;
      } else {
         BlockState state = this.stateAt(x, y, z);
         this.cursor.set(x, y, z);
         NeoCpuShadowShape shape = state.isAir() ? NeoCpuShadowShape.EMPTY : this.shapeCache.get(this.level, this.cursor, state);
         this.shapes.put(key, shape);
         return shape;
      }
   }

   NeoCpuShadowShape shapeAt(long key) {
      return this.shapeAt(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
   }

   BlockState stateAt(long key) {
      return this.stateAt(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
   }

   boolean blocksFlood(long key, BlockState state) {
      if (!state.isAir() && state.canOcclude()) {
         this.cursor.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
         return state.isCollisionShapeFullBlock(this.level, this.cursor);
      } else {
         return false;
      }
   }
}
