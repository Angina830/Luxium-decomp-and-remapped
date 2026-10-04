package com.vinlanx.luxium.client.neocpu;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.DynamicShadowMeshCapture;
import com.vinlanx.luxium.rtx.LightRtMath;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class NeoCpuEntityShadows {
   private static final int MAX_DYNAMIC_CASTERS = 160;
   private static final int MAX_TOTAL_POLYGONS = 8192;
   private static final double ENTITY_SNAPSHOT_MOVEMENT_PADDING = 30.0;
   private static final double SNAPSHOT_RADIUS_PADDING = 4.0;
   private static final double MAX_CASTER_AXIS = 12.0;
   private static final double MAX_CASTER_VOLUME = 512.0;
   private static final long FNV_OFFSET_BASIS = 1469598103934665603L;
   private static final long FNV_PRIME = 1099511628211L;
   private static volatile NeoCpuEntityShadows.CaptureState currentState = NeoCpuEntityShadows.CaptureState.empty();
   @Nullable
   private static volatile ClientLevel trackedLevel;
   @Nullable
   private static volatile BlockPos lastCenter;
   private static volatile long lastStateScanMs;
   private static volatile long lastCaptureMs;
   private static volatile long lastSceneFingerprint;

   private NeoCpuEntityShadows() {
   }

   public static boolean isActive() {
      return !NeoGpuVanilla.isConfiguredEnabled()
         && Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled)
         && Config.isFeatureEnabled(Config.CLIENT.entityShadowsEnabled);
   }

   public static void clear() {
      trackedLevel = null;
      lastCenter = null;
      lastStateScanMs = 0L;
      lastCaptureMs = 0L;
      lastSceneFingerprint = 0L;
      currentState = NeoCpuEntityShadows.CaptureState.empty();
   }

   public static NeoCpuEntityShadows.CaptureState updateState(@Nullable ClientLevel level, BlockPos center, float partialTick) {
      if (isActive() && level != null) {
         long nowMs = System.currentTimeMillis();
         long intervalMs = getUpdateIntervalMs();
         boolean levelChanged = trackedLevel != level;
         boolean centerChanged = shouldForceRefresh(center);
         boolean scanElapsed = nowMs - lastStateScanMs >= intervalMs;
         boolean captureElapsed = nowMs - lastCaptureMs >= intervalMs;
         if (!levelChanged && !centerChanged && !scanElapsed && !captureElapsed) {
            return currentState;
         } else {
            long sceneFingerprint = lastSceneFingerprint;
            boolean sceneChanged = levelChanged || centerChanged;
            if (sceneChanged || scanElapsed) {
               long nextSceneFingerprint = scanDynamicSceneFingerprint(level, center);
               lastStateScanMs = nowMs;
               sceneChanged |= sceneFingerprint != nextSceneFingerprint;
               sceneFingerprint = nextSceneFingerprint;
            }

            lastSceneFingerprint = sceneFingerprint;
            if (!sceneChanged && !captureElapsed) {
               trackedLevel = level;
               lastCenter = center.immutable();
               return currentState;
            } else {
               double radius = LightRtMath.getEntityShadowRadius(15) + 30.0 + 4.0;
               DynamicShadowMeshCapture.Snapshot snapshot = DynamicShadowMeshCapture.capture(level, center, radius, 160, partialTick);
               currentState = buildState(snapshot);
               trackedLevel = level;
               lastCenter = center.immutable();
               lastCaptureMs = nowMs;
               return currentState;
            }
         }
      } else {
         currentState = NeoCpuEntityShadows.CaptureState.empty();
         trackedLevel = level;
         lastCenter = center.immutable();
         lastStateScanMs = 0L;
         lastCaptureMs = 0L;
         lastSceneFingerprint = 0L;
         return currentState;
      }
   }

   private static long scanDynamicSceneFingerprint(ClientLevel level, BlockPos center) {
      Vec3 centerPos = Vec3.atCenterOf(center);
      double radius = LightRtMath.getEntityShadowRadius(15) + 30.0 + 4.0;
      double radiusSq = radius * radius;
      long hash = 1469598103934665603L;
      int casterCount = 0;

      for (Entity entity : level.entitiesForRendering()) {
         if (casterCount >= 160) {
            return mixFingerprint(hash, (long)casterCount);
         }

         if (entity != null && !entity.isRemoved() && !entity.isInvisible()) {
            AABB box = entity.getBoundingBox();
            if (box != null && !(distanceToAabbSq(centerPos, box) > radiusSq)) {
               hash = mixFingerprint(hash, (long)entity.getId());
               hash = mixFingerprint(hash, (long)entity.tickCount);
               hash = mixFingerprint(hash, (long)Float.floatToIntBits(entity.getYRot()));
               hash = mixFingerprint(hash, (long)Float.floatToIntBits(entity.getXRot()));
               hash = mixFingerprint(hash, Double.doubleToLongBits(box.minX));
               hash = mixFingerprint(hash, Double.doubleToLongBits(box.minY));
               hash = mixFingerprint(hash, Double.doubleToLongBits(box.minZ));
               hash = mixFingerprint(hash, Double.doubleToLongBits(box.maxX));
               hash = mixFingerprint(hash, Double.doubleToLongBits(box.maxY));
               hash = mixFingerprint(hash, Double.doubleToLongBits(box.maxZ));
               casterCount++;
            }
         }
      }

      Minecraft minecraft = Minecraft.getInstance();
      BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
      Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
      int chunkRadius = Math.max(1, (int)Math.ceil(radius / 16.0));
      int centerChunkX = center.getX() >> 4;
      int centerChunkZ = center.getZ() >> 4;

      for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
         for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            if (casterCount >= 160) {
               return mixFingerprint(hash, (long)casterCount);
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
            if (chunk != null) {
               for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                  if (casterCount >= 160) {
                     return mixFingerprint(hash, (long)casterCount);
                  }

                  if (blockEntity != null && !blockEntity.isRemoved()) {
                     BlockEntityRenderer renderer = dispatcher.getRenderer(blockEntity);
                     if (renderer != null && renderer.shouldRender(blockEntity, cameraPos)) {
                        AABB box = blockEntity.getRenderBoundingBox();
                        if (box == null) {
                           box = new AABB(blockEntity.getBlockPos());
                        }

                        if (!(distanceToAabbSq(centerPos, box) > radiusSq)) {
                           hash = mixFingerprint(hash, blockEntity.getBlockPos().asLong());
                           hash = mixFingerprint(hash, (long)blockEntity.getType().hashCode());
                           hash = mixFingerprint(hash, Double.doubleToLongBits(box.minX));
                           hash = mixFingerprint(hash, Double.doubleToLongBits(box.minY));
                           hash = mixFingerprint(hash, Double.doubleToLongBits(box.minZ));
                           hash = mixFingerprint(hash, Double.doubleToLongBits(box.maxX));
                           hash = mixFingerprint(hash, Double.doubleToLongBits(box.maxY));
                           hash = mixFingerprint(hash, Double.doubleToLongBits(box.maxZ));
                           casterCount++;
                        }
                     }
                  }
               }
            }
         }
      }

      return mixFingerprint(hash, (long)casterCount);
   }

   static void appendEntityShadows(
      NeoCpuFloodShadowBuilder builder,
      Vec3 source,
      int emission,
      NeoCpuEntityShadows.CaptureState state,
      List<NeoCpuShadowTypes.ReceiverGroupEntry> receiverGroups,
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      if (!state.isEmpty() && !receiverGroups.isEmpty() && polygons.size() < 8192) {
         double maxCasterDistance = LightRtMath.getEntityShadowRadius(emission);

         for (NeoCpuShadowTypes.ReceiverGroupEntry group : receiverGroups) {
            if (group.isEmpty() || polygons.size() >= 8192) {
               return;
            }

            NeoCpuShadowTypes.ReceiverSurface reference = group.referenceReceiver();
            if (reference.facesSource(source.x, source.y, source.z)) {
               for (NeoCpuEntityShadows.Caster caster : state.casters()) {
                  if (polygons.size() >= 8192) {
                     return;
                  }

                  if (caster.canAffect(source, maxCasterDistance)) {
                     boolean addedQuadShadow = false;

                     for (Vec3[] quad : caster.quads()) {
                        if (quad != null && quad.length >= 4 && quadFacesSource(quad, source)) {
                           appendProjectedOccluderShadow(builder, source, group, new NeoCpuShadowTypes.QuadOccluder(quad, caster.id(), 1.0F), polygons);
                           addedQuadShadow = true;
                           if (polygons.size() >= 8192) {
                              return;
                           }
                        }
                     }

                     if (!addedQuadShadow) {
                        builder.appendProjectedOccluderShadowFromStaticGroup(source, group.receivers(), caster.box(), polygons);
                     }
                  }
               }
            }
         }
      }
   }

   private static void appendProjectedOccluderShadow(
      NeoCpuFloodShadowBuilder builder,
      Vec3 source,
      NeoCpuShadowTypes.ReceiverGroupEntry group,
      NeoCpuShadowTypes.Occluder occluder,
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      builder.appendProjectedOccluderShadowFromStaticGroup(source, group.receivers(), occluder, polygons);
   }

   private static NeoCpuEntityShadows.CaptureState buildState(DynamicShadowMeshCapture.Snapshot snapshot) {
      if (snapshot.level != null && !snapshot.casters.isEmpty()) {
         ArrayList<NeoCpuEntityShadows.Caster> casters = new ArrayList<>(Math.min(snapshot.casters.size(), 160));
         long hash = 1469598103934665603L;

         for (DynamicShadowMeshCapture.CapturedCaster captured : snapshot.casters) {
            if (captured != null && captured.bounds != null && !isOversizedCaster(captured.bounds)) {
               List<Vec3[]> quads = captured.quads != null && !captured.quads.isEmpty() ? List.copyOf(captured.quads) : Collections.emptyList();
               NeoCpuShadowTypes.Box box = new NeoCpuShadowTypes.Box(
                  captured.bounds.minX,
                  captured.bounds.minY,
                  captured.bounds.minZ,
                  captured.bounds.maxX,
                  captured.bounds.maxY,
                  captured.bounds.maxZ,
                  captured.id,
                  1.0F
               );
               casters.add(new NeoCpuEntityShadows.Caster(captured.id, captured.bounds, box, quads));
               hash = mixFingerprint(hash, captured.id);
               hash = mixFingerprint(hash, Double.doubleToLongBits(captured.bounds.minX));
               hash = mixFingerprint(hash, Double.doubleToLongBits(captured.bounds.minY));
               hash = mixFingerprint(hash, Double.doubleToLongBits(captured.bounds.minZ));
               hash = mixFingerprint(hash, Double.doubleToLongBits(captured.bounds.maxX));
               hash = mixFingerprint(hash, Double.doubleToLongBits(captured.bounds.maxY));
               hash = mixFingerprint(hash, Double.doubleToLongBits(captured.bounds.maxZ));
               hash = mixFingerprint(hash, (long)quads.size());

               for (Vec3[] quad : quads) {
                  if (quad != null && quad.length != 0) {
                     Vec3 first = quad[0];
                     hash = mixFingerprint(hash, Double.doubleToLongBits(first.x));
                     hash = mixFingerprint(hash, Double.doubleToLongBits(first.y));
                     hash = mixFingerprint(hash, Double.doubleToLongBits(first.z));
                     if (quad.length > 2) {
                        Vec3 opposite = quad[2];
                        hash = mixFingerprint(hash, Double.doubleToLongBits(opposite.x));
                        hash = mixFingerprint(hash, Double.doubleToLongBits(opposite.y));
                        hash = mixFingerprint(hash, Double.doubleToLongBits(opposite.z));
                     }
                  }
               }

               if (casters.size() >= 160) {
                  break;
               }
            }
         }

         return casters.isEmpty() ? NeoCpuEntityShadows.CaptureState.empty() : new NeoCpuEntityShadows.CaptureState(hash, List.copyOf(casters));
      } else {
         return NeoCpuEntityShadows.CaptureState.empty();
      }
   }

   private static boolean isOversizedCaster(AABB bounds) {
      double dx = bounds.getXsize();
      double dy = bounds.getYsize();
      double dz = bounds.getZsize();
      return !(dx > 12.0) && !(dy > 12.0) && !(dz > 12.0) ? dx * dy * dz > 512.0 : true;
   }

   private static boolean shouldForceRefresh(BlockPos center) {
      BlockPos previousCenter = lastCenter;
      return previousCenter == null
         ? true
         : Math.abs(center.getX() - previousCenter.getX()) > 4
            || Math.abs(center.getY() - previousCenter.getY()) > 4
            || Math.abs(center.getZ() - previousCenter.getZ()) > 4;
   }

   private static long getUpdateIntervalMs() {
      int fps = Math.max(10, Math.min(120, (Integer)Config.CLIENT.entityShadowUpdateFpsLimit.get()));
      return Math.max(1L, 1000L / (long)fps);
   }

   private static long mixFingerprint(long hash, long value) {
      hash ^= value;
      return hash * 1099511628211L;
   }

   private static boolean quadFacesSource(Vec3[] quad, Vec3 source) {
      Vec3 edgeA = quad[1].subtract(quad[0]);
      Vec3 edgeB = quad[3].subtract(quad[0]);
      Vec3 normal = edgeA.cross(edgeB);
      if (normal.lengthSqr() <= 1.0E-8) {
         return false;
      } else {
         Vec3 toSource = source.subtract(quad[0]);
         return normal.dot(toSource) > 1.0E-6;
      }
   }

   private static double distanceToAabbSq(Vec3 point, AABB box) {
      double dx = 0.0;
      if (point.x < box.minX) {
         dx = box.minX - point.x;
      } else if (point.x > box.maxX) {
         dx = point.x - box.maxX;
      }

      double dy = 0.0;
      if (point.y < box.minY) {
         dy = box.minY - point.y;
      } else if (point.y > box.maxY) {
         dy = point.y - box.maxY;
      }

      double dz = 0.0;
      if (point.z < box.minZ) {
         dz = box.minZ - point.z;
      } else if (point.z > box.maxZ) {
         dz = point.z - box.maxZ;
      }

      return dx * dx + dy * dy + dz * dz;
   }

   public static record CaptureState(long fingerprint, List<NeoCpuEntityShadows.Caster> casters) {
      private static final NeoCpuEntityShadows.CaptureState EMPTY = new NeoCpuEntityShadows.CaptureState(0L, Collections.emptyList());

      public static NeoCpuEntityShadows.CaptureState empty() {
         return EMPTY;
      }

      public boolean isEmpty() {
         return this.casters.isEmpty();
      }
   }

   public static record Caster(long id, AABB bounds, NeoCpuShadowTypes.Box box, List<Vec3[]> quads) {
      boolean canAffect(Vec3 source, double maxDistance) {
         double expanded = maxDistance + 2.0;
         return NeoCpuEntityShadows.distanceToAabbSq(source, this.bounds) <= expanded * expanded;
      }
   }
}
