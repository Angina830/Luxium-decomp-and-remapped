package com.vinlanx.luxium.rtx;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class EntityShadowManager {
   private static final EntityShadowManager INSTANCE = new EntityShadowManager();
   private static final double INV_DIR_EPSILON = 1.0E-6;
   private final AtomicReference<LongOpenHashSet> shadowedBlocks = new AtomicReference<>(new LongOpenHashSet());
   private final AtomicBoolean isCalculating = new AtomicBoolean(false);
   private final ExecutorService shadowExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "NeoFlood-EntityShadows");
      t.setDaemon(true);
      t.setPriority(4);
      return t;
   });

   private EntityShadowManager() {
   }

   public static EntityShadowManager get() {
      return INSTANCE;
   }

   public boolean isBlockInShadow(long posKey) {
      return this.shadowedBlocks.get().contains(posKey);
   }

   public void updateAsync(ClientLevel level, Vec3 playerPos, double radius) {
      if (this.isCalculating.compareAndSet(false, true)) {
         double radiusSq = radius * radius;
         NeoFloodEngine engine = TorchRtxState.get().getEngine();
         CompletableFuture.runAsync(() -> {
            try {
               LongOpenHashSet newShadows = new LongOpenHashSet();

               for (Entity entity : level.entitiesForRendering()) {
                  if (!(entity.distanceToSqr(playerPos) > radiusSq)) {
                     AABB box = entity.getBoundingBox();
                     if (box != null) {
                        long dominantSourceKey = engine.getDominantSource(entity.blockPosition());
                        if (dominantSourceKey == Long.MIN_VALUE) {
                           long[] closest = TorchRtxState.get().getClosestSources(entity.position(), 256.0, 1);
                           if (closest.length <= 0) {
                              continue;
                           }

                           dominantSourceKey = closest[0];
                        }

                        int emission = TorchRtxState.get().getSourceEmission(dominantSourceKey);
                        if (emission <= 0) {
                           emission = 15;
                        }

                        double maxLightDist = LightRtMath.getMaxDistance(emission);
                        Vec3 sourcePos = Vec3.atCenterOf(BlockPos.of(dominantSourceKey));
                        Vec3 entityCenter = entity.position().add(0.0, (double)entity.getBbHeight() / 2.0, 0.0);
                        double distToEntity = sourcePos.distanceTo(entityCenter);
                        if (!(distToEntity >= maxLightDist)) {
                           double remainingDist = maxLightDist - distToEntity;
                           this.castShadowVolume(sourcePos, box, remainingDist, newShadows);
                        }
                     }
                  }
               }

               this.applyShadowDiffAndNotify(newShadows);
            } catch (Exception var24) {
               var24.printStackTrace();
            } finally {
               this.isCalculating.set(false);
            }
         }, this.shadowExecutor);
      }
   }

   private void castShadowVolume(Vec3 lightPos, AABB entityBox, double castDistance, LongOpenHashSet outShadows) {
      Vec3 entityCenter = entityBox.getCenter();
      Vec3 dir = entityCenter.subtract(lightPos).normalize();
      Vec3 shadowEnd = entityCenter.add(dir.scale(castDistance));
      AABB shadowBounds = entityBox.minmax(new AABB(shadowEnd, shadowEnd)).inflate(0.5);
      int minX = Mth.floor(shadowBounds.minX);
      int maxX = Mth.ceil(shadowBounds.maxX);
      int minY = Mth.floor(shadowBounds.minY);
      int maxY = Mth.ceil(shadowBounds.maxY);
      int minZ = Mth.floor(shadowBounds.minZ);
      int maxZ = Mth.ceil(shadowBounds.maxZ);

      for (int x = minX; x <= maxX; x++) {
         for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
               double bx = (double)x + 0.5;
               double by = (double)y + 0.5;
               double bz = (double)z + 0.5;
               if (intersect(bx, by, bz, lightPos.x, lightPos.y, lightPos.z, entityBox)) {
                  outShadows.add(BlockPos.asLong(x, y, z));
               }
            }
         }
      }
   }

   private void applyShadowDiffAndNotify(LongOpenHashSet newShadows) {
      LongOpenHashSet oldShadows = this.shadowedBlocks.getAndSet(newShadows);
      LongOpenHashSet blocksToUpdate = new LongOpenHashSet();
      LongIterator oldIt = oldShadows.iterator();

      while (oldIt.hasNext()) {
         long k = oldIt.nextLong();
         if (!newShadows.contains(k)) {
            blocksToUpdate.add(k);
         }
      }

      LongIterator newIt = newShadows.iterator();

      while (newIt.hasNext()) {
         long k = newIt.nextLong();
         if (!oldShadows.contains(k)) {
            blocksToUpdate.add(k);
         }
      }

      if (!blocksToUpdate.isEmpty()) {
         Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().levelRenderer != null) {
               LongIterator updateIt = blocksToUpdate.iterator();

               while (updateIt.hasNext()) {
                  long kx = updateIt.nextLong();
                  int x = BlockPos.getX(kx);
                  int y = BlockPos.getY(kx);
                  int z = BlockPos.getZ(kx);
                  Minecraft.getInstance().levelRenderer.setBlocksDirty(x, y, z, x, y, z);
               }
            }
         });
      }
   }

   private static boolean intersect(double ox, double oy, double oz, double lx, double ly, double lz, AABB box) {
      double dirX = lx - ox;
      double dirY = ly - oy;
      double dirZ = lz - oz;
      if (dirX * dirX + dirY * dirY + dirZ * dirZ < 1.0E-4) {
         return false;
      } else {
         double invDirX = 1.0 / (Math.abs(dirX) < 1.0E-6 ? (dirX < 0.0 ? -1.0E-6 : 1.0E-6) : dirX);
         double invDirY = 1.0 / (Math.abs(dirY) < 1.0E-6 ? (dirY < 0.0 ? -1.0E-6 : 1.0E-6) : dirY);
         double invDirZ = 1.0 / (Math.abs(dirZ) < 1.0E-6 ? (dirZ < 0.0 ? -1.0E-6 : 1.0E-6) : dirZ);
         double t1 = (box.minX - ox) * invDirX;
         double t2 = (box.maxX - ox) * invDirX;
         double t3 = (box.minY - oy) * invDirY;
         double t4 = (box.maxY - oy) * invDirY;
         double t5 = (box.minZ - oz) * invDirZ;
         double t6 = (box.maxZ - oz) * invDirZ;
         double tmin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
         double tmax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));
         return tmax >= 0.0 && tmin <= tmax && tmin <= 1.0;
      }
   }

   public void clear() {
      this.shadowedBlocks.set(new LongOpenHashSet());
   }
}
