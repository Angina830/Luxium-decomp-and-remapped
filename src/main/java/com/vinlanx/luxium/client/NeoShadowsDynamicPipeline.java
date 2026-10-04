package com.vinlanx.luxium.client;

import com.vinlanx.luxium.client.shadows.GpuNeoShadows;
import com.vinlanx.luxium.rtx.LightRtMath;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
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

final class NeoShadowsDynamicPipeline {
   private final NeoShadowsEngine engine;

   NeoShadowsDynamicPipeline(NeoShadowsEngine engine) {
      this.engine = engine;
   }

   DynamicShadowMeshCapture.Snapshot captureDynamicCasterSnapshot(ClientLevel level, BlockPos center, float partialTick) {
      if (!GpuNeoShadows.entityShadowCastersEnabled()) {
         return DynamicShadowMeshCapture.Snapshot.empty();
      } else {
         double radius = 70.0;
         return DynamicShadowMeshCapture.capture(level, center, radius, 160, partialTick);
      }
   }

   long scanDynamicSceneFingerprint(ClientLevel level, BlockPos center) {
      Vec3 centerPos = Vec3.atCenterOf(center);
      double radius = 70.0;
      double radiusSq = radius * radius;
      long hash = 1469598103934665603L;
      int casterCount = 0;

      for (Entity entity : level.entitiesForRendering()) {
         if (casterCount >= 160) {
            return this.mixFingerprint(hash, (long)casterCount);
         }

         if (entity != null && !entity.isRemoved() && !entity.isInvisible()) {
            AABB box = entity.getBoundingBox();
            if (box != null && !(distanceToAabbSq(centerPos, box) > radiusSq)) {
               hash = this.mixFingerprint(hash, (long)entity.getId());
               hash = this.mixFingerprint(hash, (long)entity.tickCount);
               hash = this.mixFingerprint(hash, (long)Float.floatToIntBits(entity.getYRot()));
               hash = this.mixFingerprint(hash, (long)Float.floatToIntBits(entity.getXRot()));
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.minX));
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.minY));
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.minZ));
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.maxX));
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.maxY));
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.maxZ));
               casterCount++;
            }
         }
      }

      BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
      int chunkRadius = Math.max(1, (int)Math.ceil(radius / 16.0));
      int centerChunkX = center.getX() >> 4;
      int centerChunkZ = center.getZ() >> 4;
      Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

      for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
         for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            if (casterCount >= 160) {
               return this.mixFingerprint(hash, (long)casterCount);
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
            if (chunk != null) {
               for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                  if (casterCount >= 160) {
                     return this.mixFingerprint(hash, (long)casterCount);
                  }

                  if (blockEntity != null && !blockEntity.isRemoved()) {
                     BlockEntityRenderer renderer = dispatcher.getRenderer(blockEntity);
                     if (renderer != null && renderer.shouldRender(blockEntity, cameraPos)) {
                        AABB box = blockEntity.getRenderBoundingBox();
                        if (box == null) {
                           box = new AABB(blockEntity.getBlockPos());
                        }

                        if (!(distanceToAabbSq(centerPos, box) > radiusSq)) {
                           hash = this.mixFingerprint(hash, blockEntity.getBlockPos().asLong());
                           hash = this.mixFingerprint(hash, (long)blockEntity.getType().hashCode());
                           hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.minX));
                           hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.minY));
                           hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.minZ));
                           hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.maxX));
                           hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.maxY));
                           hash = this.mixFingerprint(hash, Double.doubleToLongBits(box.maxZ));
                           casterCount++;
                        }
                     }
                  }
               }
            }
         }
      }

      return this.mixFingerprint(hash, (long)casterCount);
   }

   long fingerprintDynamicSnapshot(DynamicShadowMeshCapture.Snapshot snapshot) {
      long hash = 1469598103934665603L;

      for (DynamicShadowMeshCapture.CapturedCaster caster : snapshot.casters) {
         hash = this.mixFingerprint(hash, caster.id);
         hash = this.mixFingerprint(hash, Double.doubleToLongBits(caster.bounds.minX));
         hash = this.mixFingerprint(hash, Double.doubleToLongBits(caster.bounds.minY));
         hash = this.mixFingerprint(hash, Double.doubleToLongBits(caster.bounds.minZ));
         hash = this.mixFingerprint(hash, Double.doubleToLongBits(caster.bounds.maxX));
         hash = this.mixFingerprint(hash, Double.doubleToLongBits(caster.bounds.maxY));
         hash = this.mixFingerprint(hash, Double.doubleToLongBits(caster.bounds.maxZ));
         hash = this.mixFingerprint(hash, (long)caster.quads.size());

         for (Vec3[] quad : caster.quads) {
            if (quad != null && quad.length != 0) {
               Vec3 first = quad[0];
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(first.x));
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(first.y));
               hash = this.mixFingerprint(hash, Double.doubleToLongBits(first.z));
               if (quad.length > 2) {
                  Vec3 opposite = quad[2];
                  hash = this.mixFingerprint(hash, Double.doubleToLongBits(opposite.x));
                  hash = this.mixFingerprint(hash, Double.doubleToLongBits(opposite.y));
                  hash = this.mixFingerprint(hash, Double.doubleToLongBits(opposite.z));
               }
            }
         }
      }

      return hash;
   }

   long mixFingerprint(long hash, long value) {
      hash ^= value;
      return hash * 1099511628211L;
   }

   List<NeoShadowsTypes.ShadowPolygon> buildRealtimeDynamicShadowPolygons(
      ClientLevel level, BlockPos center, @Nullable NeoShadowsTypes.RealtimeStaticShadowOutput output, DynamicShadowMeshCapture.Snapshot snapshot
   ) {
      if (output != null && !output.receiverPlaneGroups.isEmpty()) {
         NeoShadowsTypes.GeometryBuildState state = new NeoShadowsTypes.GeometryBuildState(this.engine, level, center, ExposedFaceService.get(), true, snapshot);
         NeoShadowsTypes.BuildScratch scratch = new NeoShadowsTypes.BuildScratch();

         for (NeoShadowsTypes.ReceiverPlaneGroup group : output.receiverPlaneGroups) {
            if (scratch.shadowPolygons.size() >= 12288) {
               break;
            }

            this.addDynamicCasterShadows(group, state, scratch);
         }

         return scratch.shadowPolygons.isEmpty() ? Collections.emptyList() : List.copyOf(scratch.shadowPolygons);
      } else {
         return Collections.emptyList();
      }
   }

   void addDynamicCasterShadows(NeoShadowsTypes.ReceiverPlaneGroup group, NeoShadowsTypes.GeometryBuildState state, NeoShadowsTypes.BuildScratch scratch) {
      double maxDist = Math.min(LightRtMath.getMaxDistance(group.sourceEmission), 30.0);

      for (NeoShadowsTypes.DynamicCaster caster : state.dynamicCasters) {
         if (scratch.shadowPolygons.size() >= 12288) {
            return;
         }

         if (caster.canAffect(group.source, state.center, maxDist)
            && group.mayOverlapProjectedBounds(group.source, caster)
            && this.blocksAnyReceiverSample(group, caster)) {
            if (!caster.quads.isEmpty()) {
               boolean added = false;

               for (Vec3[] quad : caster.quads) {
                  if (scratch.shadowPolygons.size() >= 12288) {
                     return;
                  }

                  if (NeoShadowsStaticPipeline.facesSource(quad, group.source)) {
                     added |= this.engine.staticPipeline.addProjectedQuadShadow(group, quad, scratch);
                  }
               }

               if (added) {
                  scratch.occluderHitCount++;
                  continue;
               }
            }

            List<NeoShadowsTypes.UvPoint> projected = NeoShadowsProjection.collectProjectedBoxPoints(
               group.plane, group.source, caster.boxCorners, scratch.projectedPointBuf
            );
            List<NeoShadowsTypes.UvPoint> polygon = NeoShadowsProjection.buildProjectedShadowHullBuffered(projected, scratch);
            if (polygon != null) {
               this.engine.staticPipeline.addPlaneShadowPolygons(group, polygon, scratch);
               scratch.occluderHitCount++;
            }
         }
      }
   }

   boolean blocksAnyReceiverSample(NeoShadowsTypes.ReceiverPlaneGroup group, NeoShadowsTypes.DynamicCaster caster) {
      double[] samples = group.receiverSamples;

      for (int index = 0; index < samples.length; index += 3) {
         if (caster.intersectsSegment(group.source.x, group.source.y, group.source.z, samples[index], samples[index + 1], samples[index + 2])) {
            return true;
         }
      }

      return false;
   }

   List<NeoShadowsTypes.DynamicCaster> collectDynamicCasters(ClientLevel level, BlockPos center, DynamicShadowMeshCapture.Snapshot snapshot) {
      if (!GpuNeoShadows.entityShadowCastersEnabled()) {
         return Collections.emptyList();
      } else {
         if (snapshot.level == level && !snapshot.casters.isEmpty()) {
            List<NeoShadowsTypes.DynamicCaster> casters = new ArrayList<>(Math.min(snapshot.casters.size(), 160));

            for (DynamicShadowMeshCapture.CapturedCaster captured : snapshot.casters) {
               casters.add(new NeoShadowsTypes.DynamicCaster(captured.id, NeoShadowsTypes.Box.fromAabb(captured.bounds), captured.quads));
               if (casters.size() >= 160) {
                  break;
               }
            }

            if (!casters.isEmpty()) {
               return casters;
            }
         }

         Vec3 centerPos = Vec3.atCenterOf(center);
         double radius = 70.0;
         double radiusSq = radius * radius;
         List<NeoShadowsTypes.DynamicCaster> fallbackCasters = new ArrayList<>(160);
         long nextId = 1L;

         for (Entity entity : level.entitiesForRendering()) {
            if (fallbackCasters.size() >= 160) {
               break;
            }

            if (entity != null && !entity.isRemoved() && !entity.isInvisible()) {
               AABB box = entity.getBoundingBox();
               if (box != null && !(distanceToAabbSq(centerPos, box) > radiusSq)) {
                  fallbackCasters.add(new NeoShadowsTypes.DynamicCaster(nextId++, NeoShadowsTypes.Box.fromAabb(box.inflate(0.03)), Collections.emptyList()));
               }
            }
         }

         return fallbackCasters;
      }
   }

   static double distanceToAabbSq(Vec3 point, AABB box) {
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
}
