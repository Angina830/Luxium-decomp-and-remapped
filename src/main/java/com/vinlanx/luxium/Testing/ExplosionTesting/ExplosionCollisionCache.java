package com.vinlanx.luxium.Testing.ExplosionTesting;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class ExplosionCollisionCache {
   private static final int SIZE_X = 72;
   private static final int SIZE_Y = 48;
   private static final int SIZE_Z = 72;
   private final AtomicReference<ExplosionCollisionCache.Snapshot> snapshot = new AtomicReference<>(
      new ExplosionCollisionCache.Snapshot(0, 0, 0, new boolean[248832])
   );

   void rebuild(ClientLevel level, Vec3 center) {
      int minX = Mth.floor(center.x) - 36;
      int minY = Math.max(level.getMinBuildHeight(), Mth.floor(center.y) - 8);
      int minZ = Mth.floor(center.z) - 36;
      boolean[] solid = new boolean[248832];
      MutableBlockPos cursor = new MutableBlockPos();

      for (int y = 0; y < 48; y++) {
         for (int z = 0; z < 72; z++) {
            for (int x = 0; x < 72; x++) {
               cursor.set(minX + x, minY + y, minZ + z);
               solid[index(x, y, z)] = level.getBlockState(cursor).canOcclude();
            }
         }
      }

      this.snapshot.set(new ExplosionCollisionCache.Snapshot(minX, minY, minZ, solid));
   }

   boolean isSolid(double worldX, double worldY, double worldZ) {
      ExplosionCollisionCache.Snapshot current = this.snapshot.get();
      int x = Mth.floor(worldX) - current.minX;
      int y = Mth.floor(worldY) - current.minY;
      int z = Mth.floor(worldZ) - current.minZ;
      return x >= 0 && y >= 0 && z >= 0 && x < 72 && y < 48 && z < 72 ? current.solid[index(x, y, z)] : false;
   }

   private static int index(int x, int y, int z) {
      return (y * 72 + z) * 72 + x;
   }

   private static record Snapshot(int minX, int minY, int minZ, boolean[] solid) {
   }
}
