package com.vinlanx.luxium.client.rainpuddles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class RainPuddleManager {
   private static final RainPuddleManager INSTANCE = new RainPuddleManager();
   private static final String RESOURCE_ROOT = "rain";
   private static final int MIN_SIZE = 1;
   private static final int MAX_SIZE = 10;
   private static final int MAX_PLACEMENTS_PER_DIMENSION = 48;
   private static final double SURFACE_EPSILON = 0.0025;
   private final Map<ResourceKey<Level>, List<RainPuddleInstance>> byDimension = new HashMap<>();
   private ClientLevel activeLevel;

   private RainPuddleManager() {
   }

   public static RainPuddleManager get() {
      return INSTANCE;
   }

   @Nullable
   public synchronized RainPuddleInstance placeRandom(Minecraft mc, BlockPos center, int requestedSize) {
      ClientLevel level = mc.level;
      if (level == null) {
         return null;
      } else {
         this.ensureLevel(level);
         List<ResourceLocation> maps = this.findDepthMaps(mc);
         if (maps.isEmpty()) {
            return null;
         } else {
            int size = Math.max(1, Math.min(10, requestedSize));
            int minX = center.getX() - (size - 1) / 2;
            int minZ = center.getZ() - (size - 1) / 2;
            int maxX = minX + size - 1;
            int maxZ = minZ + size - 1;
            List<RainPuddlePatch> patches = this.buildPatches(level, center.getY(), minX, minZ, size);
            if (patches.isEmpty()) {
               return null;
            } else {
               ResourceLocation selected = maps.get(ThreadLocalRandom.current().nextInt(maps.size()));
               RainPuddleInstance instance = new RainPuddleInstance(selected, size, minX, minZ, maxX, maxZ, patches);
               List<RainPuddleInstance> placements = this.byDimension.computeIfAbsent(level.dimension(), ignored -> new ArrayList<>());
               placements.removeIf(existing -> existing.intersects(minX, minZ, maxX, maxZ));
               placements.add(instance);

               while (placements.size() > 48) {
                  placements.remove(0);
               }

               return instance;
            }
         }
      }
   }

   public synchronized List<RainPuddleInstance> snapshot(@Nullable ClientLevel level) {
      if (level == null) {
         return List.of();
      } else {
         this.ensureLevel(level);
         List<RainPuddleInstance> placements = this.byDimension.get(level.dimension());
         return placements != null && !placements.isEmpty() ? List.copyOf(placements) : List.of();
      }
   }

   public synchronized boolean hasPuddles(@Nullable ClientLevel level) {
      if (level == null) {
         return false;
      } else {
         this.ensureLevel(level);
         List<RainPuddleInstance> placements = this.byDimension.get(level.dimension());
         return placements != null && !placements.isEmpty();
      }
   }

   private void ensureLevel(ClientLevel level) {
      if (this.activeLevel != level) {
         this.byDimension.clear();
         this.activeLevel = level;
      }
   }

   private List<ResourceLocation> findDepthMaps(Minecraft mc) {
      Map<ResourceLocation, Resource> resources = mc.getResourceManager()
         .listResources("rain", id -> id.getNamespace().equals("luxium") && id.getPath().toLowerCase(Locale.ROOT).endsWith(".png"));
      if (resources.isEmpty()) {
         return List.of();
      } else {
         ArrayList<ResourceLocation> ids = new ArrayList<>(resources.keySet());
         ids.sort(Comparator.comparing(ResourceLocation::getPath));
         return ids;
      }
   }

   private List<RainPuddlePatch> buildPatches(ClientLevel level, int blockY, int minX, int minZ, int size) {
      ArrayList<RainPuddlePatch> patches = new ArrayList<>();
      CollisionContext collision = CollisionContext.empty();

      for (int dz = 0; dz < size; dz++) {
         for (int dx = 0; dx < size; dx++) {
            BlockPos pos = new BlockPos(minX + dx, blockY, minZ + dz);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
               VoxelShape shape = state.getShape(level, pos, collision);
               if (!shape.isEmpty()) {
                  float blockU0 = (float)dx / (float)size;
                  float blockV0 = (float)dz / (float)size;
                  float blockUScale = 1.0F / (float)size;
                  float blockVScale = 1.0F / (float)size;

                  for (AABB box : shape.toAabbs()) {
                     if (!(box.getXsize() <= 1.0E-4) && !(box.getZsize() <= 1.0E-4) && !(box.maxY <= 1.0E-4)) {
                        double worldMinX = (double)pos.getX() + box.minX;
                        double worldMaxX = (double)pos.getX() + box.maxX;
                        double worldMinZ = (double)pos.getZ() + box.minZ;
                        double worldMaxZ = (double)pos.getZ() + box.maxZ;
                        double worldY = (double)pos.getY() + box.maxY + 0.0025;
                        float u0 = blockU0 + (float)box.minX * blockUScale;
                        float u1 = blockU0 + (float)box.maxX * blockUScale;
                        float v0 = blockV0 + (float)box.minZ * blockVScale;
                        float v1 = blockV0 + (float)box.maxZ * blockVScale;
                        patches.add(new RainPuddlePatch(worldMinX, worldMaxX, worldY, worldMinZ, worldMaxZ, u0, v0, u1, v1));
                     }
                  }
               }
            }
         }
      }

      return patches;
   }
}
