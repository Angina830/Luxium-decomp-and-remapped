package com.vinlanx.luxium.client;

import com.vinlanx.luxium.rtx.LightRtMath;
import com.vinlanx.luxium.rtx.NeoFloodEngine;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class NeoShadowsTypes {
   private NeoShadowsTypes() {
   }

   static final class AtlasUv {
      final double u;
      final double v;

      AtlasUv(double u, double v) {
         this.u = u;
         this.v = v;
      }
   }

   static final class Box {
      final double minX;
      final double minY;
      final double minZ;
      final double maxX;
      final double maxY;
      final double maxZ;

      Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
         this.minX = minX;
         this.minY = minY;
         this.minZ = minZ;
         this.maxX = maxX;
         this.maxY = maxY;
         this.maxZ = maxZ;
      }

      static NeoShadowsTypes.Box fromAabb(AABB aabb) {
         return new NeoShadowsTypes.Box(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
      }

      Vec3[] corners() {
         return this.cornersWithOffset(0, 0, 0);
      }

      boolean contains(double x, double y, double z) {
         return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
      }

      double distanceToPointSq(double x, double y, double z) {
         double dx = 0.0;
         if (x < this.minX) {
            dx = this.minX - x;
         } else if (x > this.maxX) {
            dx = x - this.maxX;
         }

         double dy = 0.0;
         if (y < this.minY) {
            dy = this.minY - y;
         } else if (y > this.maxY) {
            dy = y - this.maxY;
         }

         double dz = 0.0;
         if (z < this.minZ) {
            dz = this.minZ - z;
         } else if (z > this.maxZ) {
            dz = z - this.maxZ;
         }

         return dx * dx + dy * dy + dz * dz;
      }

      Vec3[] cornersWithOffset(int ox, int oy, int oz) {
         double ax = this.minX + (double)ox;
         double bx = this.maxX + (double)ox;
         double ay = this.minY + (double)oy;
         double by = this.maxY + (double)oy;
         double az = this.minZ + (double)oz;
         double bz = this.maxZ + (double)oz;
         return new Vec3[]{
            new Vec3(ax, ay, az),
            new Vec3(ax, ay, bz),
            new Vec3(ax, by, az),
            new Vec3(ax, by, bz),
            new Vec3(bx, ay, az),
            new Vec3(bx, ay, bz),
            new Vec3(bx, by, az),
            new Vec3(bx, by, bz)
         };
      }

      boolean intersectsSegment(double startX, double startY, double startZ, double endX, double endY, double endZ) {
         return this.intersectsSegmentWithOffset(startX, startY, startZ, endX, endY, endZ, 0, 0, 0);
      }

      boolean intersectsSegmentWithOffset(
         double startX, double startY, double startZ, double endX, double endY, double endZ, int offsetX, int offsetY, int offsetZ
      ) {
         double oMinX = this.minX + (double)offsetX;
         double oMaxX = this.maxX + (double)offsetX;
         double oMinY = this.minY + (double)offsetY;
         double oMaxY = this.maxY + (double)offsetY;
         double oMinZ = this.minZ + (double)offsetZ;
         double oMaxZ = this.maxZ + (double)offsetZ;
         double dirX = endX - startX;
         double dirY = endY - startY;
         double dirZ = endZ - startZ;
         double tMin = 0.0;
         double tMax = 1.0;
         tMin = clipAxis(startX, dirX, oMinX, oMaxX, tMin, true);
         if (Double.isNaN(tMin)) {
            return false;
         } else {
            tMax = clipAxis(startX, dirX, oMinX, oMaxX, tMax, false);
            if (!Double.isNaN(tMax) && !(tMax < tMin)) {
               tMin = clipAxis(startY, dirY, oMinY, oMaxY, tMin, true);
               if (Double.isNaN(tMin)) {
                  return false;
               } else {
                  tMax = clipAxis(startY, dirY, oMinY, oMaxY, tMax, false);
                  if (!Double.isNaN(tMax) && !(tMax < tMin)) {
                     tMin = clipAxis(startZ, dirZ, oMinZ, oMaxZ, tMin, true);
                     if (Double.isNaN(tMin)) {
                        return false;
                     } else {
                        tMax = clipAxis(startZ, dirZ, oMinZ, oMaxZ, tMax, false);
                        return !Double.isNaN(tMax) && tMax >= tMin;
                     }
                  } else {
                     return false;
                  }
               }
            } else {
               return false;
            }
         }
      }

      private static double clipAxis(double start, double dir, double min, double max, double current, boolean minBound) {
         if (Math.abs(dir) <= 1.0E-6) {
            return !(start < min) && !(start > max) ? current : Double.NaN;
         } else {
            double inverse = 1.0 / dir;
            double t1 = (min - start) * inverse;
            double t2 = (max - start) * inverse;
            double lower = Math.min(t1, t2);
            double upper = Math.max(t1, t2);
            return minBound ? Math.max(current, lower) : Math.min(current, upper);
         }
      }
   }

   static final class BuildScratch {
      final List<NeoShadowsTypes.ShadowPolygon> shadowPolygons = new ArrayList<>();
      final Long2ObjectLinkedOpenHashMap<NeoShadowsOcclusionPipeline.OccluderHit> reusableGroupHits = new Long2ObjectLinkedOpenHashMap();
      final List<NeoShadowsTypes.UvPoint> projectedPointBuf = new ArrayList<>(64);
      final List<NeoShadowsTypes.UvPoint> clipBufA = new ArrayList<>(32);
      final List<NeoShadowsTypes.UvPoint> clipBufB = new ArrayList<>(32);
      final List<NeoShadowsTypes.UvPoint> hullSortBuf = new ArrayList<>(64);
      final List<NeoShadowsTypes.UvPoint> hullUniqueBuf = new ArrayList<>(64);
      final List<NeoShadowsTypes.UvPoint> hullLowerBuf = new ArrayList<>(64);
      final List<NeoShadowsTypes.UvPoint> hullUpperBuf = new ArrayList<>(64);
      final List<NeoShadowsTypes.UvPoint> hullOutputBuf = new ArrayList<>(64);
      final List<NeoShadowsTypes.UvPoint> polyLocalBuf = new ArrayList<>(32);
      final List<NeoShadowsTypes.UvPoint> polyDilateBuf = new ArrayList<>(32);
      final List<NeoShadowsTypes.UvPoint> polySnapBuf = new ArrayList<>(32);
      int occluderHitCount;
      int shadowPolygonCount;

      void reset() {
         this.shadowPolygons.clear();
         this.reusableGroupHits.clear();
         this.projectedPointBuf.clear();
         this.clipBufA.clear();
         this.clipBufB.clear();
         this.hullSortBuf.clear();
         this.hullUniqueBuf.clear();
         this.hullLowerBuf.clear();
         this.hullUpperBuf.clear();
         this.hullOutputBuf.clear();
         this.polyLocalBuf.clear();
         this.polyDilateBuf.clear();
         this.polySnapBuf.clear();
         this.occluderHitCount = 0;
         this.shadowPolygonCount = 0;
      }
   }

   static final class ContributorCollector implements NeoFloodEngine.ContributorVisitor {
      long[] sourceKeys = new long[16];
      int[] packed = new int[16];
      int count;

      void reset() {
         this.count = 0;
      }

      @Override
      public void visit(long sourceKey, int light, int coverage, int sourceEmission) {
         if (this.count == this.sourceKeys.length) {
            int newCap = this.sourceKeys.length * 2;
            long[] nextKeys = new long[newCap];
            int[] nextPacked = new int[newCap];
            System.arraycopy(this.sourceKeys, 0, nextKeys, 0, this.count);
            System.arraycopy(this.packed, 0, nextPacked, 0, this.count);
            this.sourceKeys = nextKeys;
            this.packed = nextPacked;
         }

         this.sourceKeys[this.count] = sourceKey;
         this.packed[this.count] = light & 0xFF | (coverage & 0xFF) << 8 | (sourceEmission & 0xFF) << 16;
         this.count++;
      }

      NeoShadowsTypes.ContributorList toList() {
         if (this.count == 0) {
            return NeoShadowsTypes.ContributorList.EMPTY;
         } else {
            long[] keys = new long[this.count];
            int[] pack = new int[this.count];
            System.arraycopy(this.sourceKeys, 0, keys, 0, this.count);
            System.arraycopy(this.packed, 0, pack, 0, this.count);
            return new NeoShadowsTypes.ContributorList(keys, pack, this.count);
         }
      }
   }

   static final class ContributorList {
      static final NeoShadowsTypes.ContributorList EMPTY = new NeoShadowsTypes.ContributorList(new long[0], new int[0], 0);
      final long[] sourceKeys;
      final int[] packed;
      final int count;

      ContributorList(long[] sourceKeys, int[] packed, int count) {
         this.sourceKeys = sourceKeys;
         this.packed = packed;
         this.count = count;
      }
   }

   static final class ContributorScoringVisitor implements NeoFloodEngine.ContributorVisitor {
      private Long2ObjectOpenHashMap<NeoShadowsTypes.SourceScore> scoresBySource;
      private long ownPosKey;
      private double sampleX;
      private double sampleY;
      private double sampleZ;
      private long lightSamplePosKey;
      private NeoShadowsTypes.PlaneRect probeRect;

      void bind(Long2ObjectOpenHashMap<NeoShadowsTypes.SourceScore> scoresBySource, long ownPosKey) {
         this.scoresBySource = scoresBySource;
         this.ownPosKey = ownPosKey;
      }

      void setProbe(double sampleX, double sampleY, double sampleZ, long lightSamplePosKey, NeoShadowsTypes.PlaneRect probeRect) {
         this.sampleX = sampleX;
         this.sampleY = sampleY;
         this.sampleZ = sampleZ;
         this.lightSamplePosKey = lightSamplePosKey;
         this.probeRect = probeRect;
      }

      @Override
      public void visit(long sourceKey, int light, int coverage, int sourceEmission) {
         if (sourceKey != this.ownPosKey && sourceEmission > 0 && light > 0) {
            double dx = (double)BlockPos.getX(sourceKey) + 0.5 - this.sampleX;
            double dy = (double)BlockPos.getY(sourceKey) + 0.5 - this.sampleY;
            double dz = (double)BlockPos.getZ(sourceKey) + 0.5 - this.sampleZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            NeoShadowsTypes.SourceScore score = (NeoShadowsTypes.SourceScore)this.scoresBySource.get(sourceKey);
            if (score == null) {
               score = new NeoShadowsTypes.SourceScore(sourceKey, this.lightSamplePosKey, sourceEmission);
               this.scoresBySource.put(sourceKey, score);
            }

            score.sampleCount++;
            if (sourceEmission > score.bestEmission) {
               score.bestEmission = sourceEmission;
            }

            if (distanceSq < score.bestDistanceSq) {
               score.bestDistanceSq = distanceSq;
               score.representativeSamplePosKey = this.lightSamplePosKey;
            }

            score.includeProbeBounds(this.probeRect);
            double coverageFactor = (double)coverage / 255.0;
            if (coverageFactor < 0.15) {
               coverageFactor = 0.15;
            }

            double denom = distanceSq + 0.25;
            if (denom < 0.25) {
               denom = 0.25;
            }

            score.totalScore += (double)light * coverageFactor / denom;
         }
      }
   }

   static final class DynamicCaptureRequest {
      final long id;
      final long epoch;
      final ClientLevel level;
      final BlockPos center;
      final float partialTick;
      final long fingerprint;

      DynamicCaptureRequest(long id, long epoch, ClientLevel level, BlockPos center, float partialTick, long fingerprint) {
         this.id = id;
         this.epoch = epoch;
         this.level = level;
         this.center = center;
         this.partialTick = partialTick;
         this.fingerprint = fingerprint;
      }
   }

   static final class DynamicCaster {
      final long id;
      final NeoShadowsTypes.Box box;
      final Vec3[] boxCorners;
      final List<Vec3[]> quads;

      DynamicCaster(long id, NeoShadowsTypes.Box box, List<Vec3[]> quads) {
         this.id = id;
         this.box = box;
         this.boxCorners = box.corners();
         this.quads = quads;
      }

      boolean canAffect(Vec3 source, BlockPos center, double maxDist) {
         if (this.box.contains(source.x, source.y, source.z)) {
            return false;
         } else {
            double sourceDistanceSq = this.box.distanceToPointSq(source.x, source.y, source.z);
            if (sourceDistanceSq > (maxDist + 2.0) * (maxDist + 2.0)) {
               return false;
            } else {
               double centerDistanceSq = this.box.distanceToPointSq((double)center.getX() + 0.5, (double)center.getY() + 0.5, (double)center.getZ() + 0.5);
               return centerDistanceSq <= 1521.0;
            }
         }
      }

      boolean intersectsSegment(double startX, double startY, double startZ, double endX, double endY, double endZ) {
         if (!this.box.intersectsSegment(startX, startY, startZ, endX, endY, endZ)) {
            return false;
         } else if (!this.quads.isEmpty() && !this.hasThinMesh()) {
            for (Vec3[] quad : this.quads) {
               if (segmentIntersectsQuad(startX, startY, startZ, endX, endY, endZ, quad)) {
                  return true;
               }
            }

            return false;
         } else {
            return true;
         }
      }

      boolean hasThinMesh() {
         return !this.quads.isEmpty()
            && (this.box.maxX - this.box.minX <= 0.1875 || this.box.maxY - this.box.minY <= 0.1875 || this.box.maxZ - this.box.minZ <= 0.1875);
      }

      private static boolean segmentIntersectsQuad(double startX, double startY, double startZ, double endX, double endY, double endZ, Vec3[] quad) {
         return segmentIntersectsTriangle(startX, startY, startZ, endX, endY, endZ, quad[0], quad[1], quad[2])
            || segmentIntersectsTriangle(startX, startY, startZ, endX, endY, endZ, quad[0], quad[2], quad[3]);
      }

      private static boolean segmentIntersectsTriangle(
         double startX, double startY, double startZ, double endX, double endY, double endZ, Vec3 a, Vec3 b, Vec3 c
      ) {
         double dirX = endX - startX;
         double dirY = endY - startY;
         double dirZ = endZ - startZ;
         double edge1X = b.x - a.x;
         double edge1Y = b.y - a.y;
         double edge1Z = b.z - a.z;
         double edge2X = c.x - a.x;
         double edge2Y = c.y - a.y;
         double edge2Z = c.z - a.z;
         double pvecX = dirY * edge2Z - dirZ * edge2Y;
         double pvecY = dirZ * edge2X - dirX * edge2Z;
         double pvecZ = dirX * edge2Y - dirY * edge2X;
         double determinant = edge1X * pvecX + edge1Y * pvecY + edge1Z * pvecZ;
         if (Math.abs(determinant) <= 1.0E-9) {
            return false;
         } else {
            double invDet = 1.0 / determinant;
            double tvecX = startX - a.x;
            double tvecY = startY - a.y;
            double tvecZ = startZ - a.z;
            double u = (tvecX * pvecX + tvecY * pvecY + tvecZ * pvecZ) * invDet;
            if (!(u < -1.0E-6) && !(u > 1.000001)) {
               double qvecX = tvecY * edge1Z - tvecZ * edge1Y;
               double qvecY = tvecZ * edge1X - tvecX * edge1Z;
               double qvecZ = tvecX * edge1Y - tvecY * edge1X;
               double v = (dirX * qvecX + dirY * qvecY + dirZ * qvecZ) * invDet;
               if (!(v < -1.0E-6) && !(u + v > 1.000001)) {
                  double t = (edge2X * qvecX + edge2Y * qvecY + edge2Z * qvecZ) * invDet;
                  return t >= -1.0E-6 && t <= 1.000001;
               } else {
                  return false;
               }
            } else {
               return false;
            }
         }
      }
   }

   static final class DynamicRequest {
      final long id;
      final long epoch;
      final long staticResultId;
      final ClientLevel level;
      final BlockPos center;
      final NeoShadowsTypes.RealtimeStaticShadowOutput staticOutput;
      final DynamicShadowMeshCapture.Snapshot dynamicSnapshot;

      DynamicRequest(
         long id,
         long epoch,
         long staticResultId,
         ClientLevel level,
         BlockPos center,
         NeoShadowsTypes.RealtimeStaticShadowOutput staticOutput,
         DynamicShadowMeshCapture.Snapshot dynamicSnapshot
      ) {
         this.id = id;
         this.epoch = epoch;
         this.staticResultId = staticResultId;
         this.level = level;
         this.center = center;
         this.staticOutput = staticOutput;
         this.dynamicSnapshot = dynamicSnapshot;
      }
   }

   static final class DynamicResult {
      final long id;
      final long epoch;
      final long staticResultId;
      final BlockPos center;
      final List<NeoShadowsTypes.ShadowPolygon> polygons;

      DynamicResult(long id, long epoch, long staticResultId, BlockPos center, List<NeoShadowsTypes.ShadowPolygon> polygons) {
         this.id = id;
         this.epoch = epoch;
         this.staticResultId = staticResultId;
         this.center = center;
         this.polygons = polygons;
      }
   }

   static final class FaceCandidate {
      final BlockPos pos;
      final Direction face;
      final double distanceSq;
      final double planeCoordinate;
      final NeoShadowsTypes.PlaneRect localBounds;
      final long receiverKey;

      FaceCandidate(BlockPos pos, Direction face, double distanceSq, double planeCoordinate, NeoShadowsTypes.PlaneRect localBounds, long receiverKey) {
         this.pos = pos;
         this.face = face;
         this.distanceSq = distanceSq;
         this.planeCoordinate = planeCoordinate;
         this.localBounds = localBounds;
         this.receiverKey = receiverKey;
      }
   }

   static final class FaceLightingInfo {
      final long lightSamplePosKey;
      final long sourceKey;
      final int sourceEmission;
      final List<NeoShadowsTypes.SourceContribution> contributions;

      FaceLightingInfo(long lightSamplePosKey, long sourceKey, int sourceEmission, List<NeoShadowsTypes.SourceContribution> contributions) {
         this.lightSamplePosKey = lightSamplePosKey;
         this.sourceKey = sourceKey;
         this.sourceEmission = sourceEmission;
         this.contributions = contributions;
      }

      static NeoShadowsTypes.FaceLightingInfo empty() {
         return new NeoShadowsTypes.FaceLightingInfo(Long.MIN_VALUE, Long.MIN_VALUE, 0, Collections.emptyList());
      }
   }

   static final class FaceLightingScratch {
      final Long2ObjectOpenHashMap<NeoShadowsTypes.SourceScore> scores = new Long2ObjectOpenHashMap();
      final NeoShadowsTypes.ContributorScoringVisitor visitor = new NeoShadowsTypes.ContributorScoringVisitor();
   }

   static final class GeometryBuildState {
      final ClientLevel level;
      final BlockPos center;
      final ExposedFaceService exposedFaceService;
      final boolean includeDynamicCasters;
      final List<NeoShadowsTypes.DynamicCaster> dynamicCasters;
      final EnumMap<Direction, Long2ObjectOpenHashMap<NeoShadowsTypes.FaceLightingInfo>> faceLightingCache = new EnumMap<>(Direction.class);
      final List<NeoShadowsTypes.ShadowPolygon> shadowPolygons = new ArrayList<>();
      final Long2LongOpenHashMap[] litSampleCache = new Long2LongOpenHashMap[6];
      final Long2ObjectOpenHashMap<NeoShadowsTypes.ContributorList> contributorCache = new Long2ObjectOpenHashMap();
      final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<NeoShadowsOcclusionPipeline.ProbeFirstHitCache>> firstHitScalarCache = new Long2ObjectOpenHashMap();
      int receiverFaceCount;
      int sourceCount;
      int occluderHitCount;
      int shadowPolygonCount;

      GeometryBuildState(
         NeoShadowsEngine engine,
         ClientLevel level,
         BlockPos center,
         ExposedFaceService exposedFaceService,
         boolean includeDynamicCasters,
         DynamicShadowMeshCapture.Snapshot dynamicSnapshot
      ) {
         this.level = level;
         this.center = center;
         this.exposedFaceService = exposedFaceService;
         this.includeDynamicCasters = includeDynamicCasters;
         this.dynamicCasters = includeDynamicCasters ? engine.dynamicPipeline.collectDynamicCasters(level, center, dynamicSnapshot) : Collections.emptyList();
         this.contributorCache.defaultReturnValue(null);
      }

      NeoShadowsOcclusionPipeline.ProbeFirstHitCache firstHitScalarCache(long sourceKey, long receiverKey, int probeCount) {
         Long2ObjectOpenHashMap<NeoShadowsOcclusionPipeline.ProbeFirstHitCache> inner;
         synchronized (this.firstHitScalarCache) {
            inner = (Long2ObjectOpenHashMap<NeoShadowsOcclusionPipeline.ProbeFirstHitCache>)this.firstHitScalarCache.get(sourceKey);
            if (inner == null) {
               inner = new Long2ObjectOpenHashMap();
               this.firstHitScalarCache.put(sourceKey, inner);
            }
         }

         synchronized (inner) {
            NeoShadowsOcclusionPipeline.ProbeFirstHitCache cache = (NeoShadowsOcclusionPipeline.ProbeFirstHitCache)inner.get(receiverKey);
            if (cache == null) {
               cache = new NeoShadowsOcclusionPipeline.ProbeFirstHitCache(probeCount);
               inner.put(receiverKey, cache);
            }

            return cache;
         }
      }
   }

   static final class GlobalReceiverPlane {
      final Direction face;
      final Vec3 origin;
      final Vec3 axisU;
      final Vec3 axisV;
      final Vec3 normal;

      GlobalReceiverPlane(Direction face, double planeCoordinate) {
         this.face = face;
         double epsilon = 0.0015F;
         switch (face) {
            case UP:
               this.origin = new Vec3(0.0, planeCoordinate + epsilon, 0.0);
               this.axisU = new Vec3(1.0, 0.0, 0.0);
               this.axisV = new Vec3(0.0, 0.0, 1.0);
               this.normal = new Vec3(0.0, 1.0, 0.0);
               break;
            case DOWN:
               this.origin = new Vec3(0.0, planeCoordinate - epsilon, 0.0);
               this.axisU = new Vec3(1.0, 0.0, 0.0);
               this.axisV = new Vec3(0.0, 0.0, 1.0);
               this.normal = new Vec3(0.0, -1.0, 0.0);
               break;
            case NORTH:
               this.origin = new Vec3(0.0, 0.0, planeCoordinate - epsilon);
               this.axisU = new Vec3(1.0, 0.0, 0.0);
               this.axisV = new Vec3(0.0, 1.0, 0.0);
               this.normal = new Vec3(0.0, 0.0, -1.0);
               break;
            case SOUTH:
               this.origin = new Vec3(0.0, 0.0, planeCoordinate + epsilon);
               this.axisU = new Vec3(1.0, 0.0, 0.0);
               this.axisV = new Vec3(0.0, 1.0, 0.0);
               this.normal = new Vec3(0.0, 0.0, 1.0);
               break;
            case WEST:
               this.origin = new Vec3(planeCoordinate - epsilon, 0.0, 0.0);
               this.axisU = new Vec3(0.0, 0.0, 1.0);
               this.axisV = new Vec3(0.0, 1.0, 0.0);
               this.normal = new Vec3(-1.0, 0.0, 0.0);
               break;
            default:
               this.origin = new Vec3(planeCoordinate + epsilon, 0.0, 0.0);
               this.axisU = new Vec3(0.0, 0.0, 1.0);
               this.axisV = new Vec3(0.0, 1.0, 0.0);
               this.normal = new Vec3(1.0, 0.0, 0.0);
         }
      }

      boolean isFacingSource(Vec3 source) {
         double dx = source.x - this.origin.x;
         double dy = source.y - this.origin.y;
         double dz = source.z - this.origin.z;
         return dx * this.normal.x + dy * this.normal.y + dz * this.normal.z > 1.0E-6;
      }

      double sampleX(double u, double v) {
         return this.origin.x + this.axisU.x * u + this.axisV.x * v;
      }

      double sampleY(double u, double v) {
         return this.origin.y + this.axisU.y * u + this.axisV.y * v;
      }

      double sampleZ(double u, double v) {
         return this.origin.z + this.axisU.z * u + this.axisV.z * v;
      }

      @Nullable
      NeoShadowsTypes.UvPoint projectFinite(Vec3 source, Vec3 point) {
         NeoShadowsTypes.ProjectionPoint projection = this.projectPoint(source, point);
         return projection.uv;
      }

      NeoShadowsTypes.ProjectionPoint projectPoint(Vec3 source, Vec3 point) {
         double dirX = point.x - source.x;
         double dirY = point.y - source.y;
         double dirZ = point.z - source.z;
         double denominator = dirX * this.normal.x + dirY * this.normal.y + dirZ * this.normal.z;
         if (Math.abs(denominator) <= 1.0E-9) {
            return new NeoShadowsTypes.ProjectionPoint(denominator, Double.NaN, null);
         } else {
            double odX = this.origin.x - source.x;
            double odY = this.origin.y - source.y;
            double odZ = this.origin.z - source.z;
            double t = (odX * this.normal.x + odY * this.normal.y + odZ * this.normal.z) / denominator;
            double minProjectionT = isHorizontalFace(this.face) ? 1.0E-6 : 1.000001;
            if (Double.isFinite(t) && !(t <= minProjectionT)) {
               double px = source.x + dirX * t;
               double py = source.y + dirY * t;
               double pz = source.z + dirZ * t;
               if (Double.isFinite(px) && Double.isFinite(py) && Double.isFinite(pz)) {
                  double lx = px - this.origin.x;
                  double ly = py - this.origin.y;
                  double lz = pz - this.origin.z;
                  return new NeoShadowsTypes.ProjectionPoint(
                     denominator,
                     t,
                     new NeoShadowsTypes.UvPoint(
                        lx * this.axisU.x + ly * this.axisU.y + lz * this.axisU.z, lx * this.axisV.x + ly * this.axisV.y + lz * this.axisV.z
                     )
                  );
               } else {
                  return new NeoShadowsTypes.ProjectionPoint(denominator, t, null);
               }
            } else {
               return new NeoShadowsTypes.ProjectionPoint(denominator, t, null);
            }
         }
      }

      private static boolean isHorizontalFace(Direction face) {
         return face == Direction.UP || face == Direction.DOWN;
      }
   }

   static final class PlaneRect {
      double minU;
      double maxU;
      double minV;
      double maxV;

      PlaneRect(double minU, double maxU, double minV, double maxV) {
         this.minU = minU;
         this.maxU = maxU;
         this.minV = minV;
         this.maxV = maxV;
      }

      void include(NeoShadowsTypes.PlaneRect other) {
         this.minU = Math.min(this.minU, other.minU);
         this.maxU = Math.max(this.maxU, other.maxU);
         this.minV = Math.min(this.minV, other.minV);
         this.maxV = Math.max(this.maxV, other.maxV);
      }
   }

   static final class ProjectionPoint {
      final double denominator;
      final double t;
      @Nullable
      final NeoShadowsTypes.UvPoint uv;

      ProjectionPoint(double denominator, double t, @Nullable NeoShadowsTypes.UvPoint uv) {
         this.denominator = denominator;
         this.t = t;
         this.uv = uv;
      }
   }

   static final class RealtimeStaticShadowOutput {
      final BlockPos center;
      final List<NeoShadowsTypes.ShadowPolygon> shadowPolygons;
      final List<NeoShadowsTypes.ReceiverPlaneGroup> receiverPlaneGroups;
      final int receiverFaceCount;
      final int sourceCount;
      final int occluderHitCount;
      final int shadowPolygonCount;

      RealtimeStaticShadowOutput(
         BlockPos center,
         List<NeoShadowsTypes.ShadowPolygon> shadowPolygons,
         List<NeoShadowsTypes.ReceiverPlaneGroup> receiverPlaneGroups,
         int receiverFaceCount,
         int sourceCount,
         int occluderHitCount,
         int shadowPolygonCount
      ) {
         this.center = center;
         this.shadowPolygons = shadowPolygons;
         this.receiverPlaneGroups = receiverPlaneGroups;
         this.receiverFaceCount = receiverFaceCount;
         this.sourceCount = sourceCount;
         this.occluderHitCount = occluderHitCount;
         this.shadowPolygonCount = shadowPolygonCount;
      }
   }

   static final class ReceiverContributionRect {
      final BlockPos anchorPos;
      final NeoShadowsTypes.PlaneRect planeRect;
      final float strength;

      ReceiverContributionRect(BlockPos anchorPos, NeoShadowsTypes.PlaneRect planeRect, float strength) {
         this.anchorPos = anchorPos;
         this.planeRect = planeRect;
         this.strength = strength;
      }
   }

   static final class ReceiverPlane {
      final Vec3 origin;
      final Vec3 axisU;
      final Vec3 axisV;
      final Vec3 normal;

      ReceiverPlane(BlockPos pos, Direction face, double planeCoordinate) {
         double minX = (double)pos.getX();
         double maxX = minX + 1.0;
         double minY = (double)pos.getY();
         double minZ = (double)pos.getZ();
         double epsilon = 0.0015F;
         switch (face) {
            case UP:
               this.origin = new Vec3(minX, planeCoordinate + epsilon, minZ);
               this.axisU = new Vec3(1.0, 0.0, 0.0);
               this.axisV = new Vec3(0.0, 0.0, 1.0);
               this.normal = new Vec3(0.0, 1.0, 0.0);
               break;
            case DOWN:
               this.origin = new Vec3(minX, planeCoordinate - epsilon, minZ + 1.0);
               this.axisU = new Vec3(1.0, 0.0, 0.0);
               this.axisV = new Vec3(0.0, 0.0, -1.0);
               this.normal = new Vec3(0.0, -1.0, 0.0);
               break;
            case NORTH:
               this.origin = new Vec3(maxX, minY, planeCoordinate - epsilon);
               this.axisU = new Vec3(-1.0, 0.0, 0.0);
               this.axisV = new Vec3(0.0, 1.0, 0.0);
               this.normal = new Vec3(0.0, 0.0, -1.0);
               break;
            case SOUTH:
               this.origin = new Vec3(minX, minY, planeCoordinate + epsilon);
               this.axisU = new Vec3(1.0, 0.0, 0.0);
               this.axisV = new Vec3(0.0, 1.0, 0.0);
               this.normal = new Vec3(0.0, 0.0, 1.0);
               break;
            case WEST:
               this.origin = new Vec3(planeCoordinate - epsilon, minY, minZ + 1.0);
               this.axisU = new Vec3(0.0, 0.0, -1.0);
               this.axisV = new Vec3(0.0, 1.0, 0.0);
               this.normal = new Vec3(-1.0, 0.0, 0.0);
               break;
            default:
               this.origin = new Vec3(planeCoordinate + epsilon, minY, minZ);
               this.axisU = new Vec3(0.0, 0.0, 1.0);
               this.axisV = new Vec3(0.0, 1.0, 0.0);
               this.normal = new Vec3(1.0, 0.0, 0.0);
         }
      }

      double sampleX(double u, double v) {
         return this.origin.x + this.axisU.x * u + this.axisV.x * v;
      }

      double sampleY(double u, double v) {
         return this.origin.y + this.axisU.y * u + this.axisV.y * v;
      }

      double sampleZ(double u, double v) {
         return this.origin.z + this.axisU.z * u + this.axisV.z * v;
      }
   }

   static final class ReceiverPlaneGroup {
      final NeoShadowsTypes.ReceiverPlaneKey key;
      final NeoShadowsTypes.GlobalReceiverPlane plane;
      final Vec3 source;
      final int sourceEmission;
      final List<NeoShadowsTypes.FaceCandidate> faces = new ArrayList<>();
      final Long2ObjectOpenHashMap<NeoShadowsTypes.FaceCandidate> receiverFacesByKey = new Long2ObjectOpenHashMap();
      final Long2FloatOpenHashMap faceWeights = new Long2FloatOpenHashMap();
      final Long2ObjectOpenHashMap<NeoShadowsTypes.PlaneRect> faceContributionBounds = new Long2ObjectOpenHashMap();
      double[] receiverSamples = new double[0];
      double nearestReceiverDistanceSq = Double.POSITIVE_INFINITY;
      int minReceiverCellU = Integer.MAX_VALUE;
      int maxReceiverCellU = Integer.MIN_VALUE;
      int minReceiverCellV = Integer.MAX_VALUE;
      int maxReceiverCellV = Integer.MIN_VALUE;
      @Nullable
      List<NeoShadowsTypes.ReceiverContributionRect> mergedReceiverRects;

      ReceiverPlaneGroup(NeoShadowsTypes.ReceiverPlaneKey key, Vec3 source, int sourceEmission) {
         this.key = key;
         this.plane = new NeoShadowsTypes.GlobalReceiverPlane(key.face, key.planeCoordinate);
         this.source = source;
         this.sourceEmission = sourceEmission;
         this.faceWeights.defaultReturnValue(0.0F);
      }

      void addFace(NeoShadowsTypes.FaceCandidate candidate, float weight, NeoShadowsTypes.PlaneRect localFaceBounds) {
         this.faces.add(candidate);
         this.mergedReceiverRects = null;
         long receiverKey = candidate.receiverKey;
         this.receiverFacesByKey.putIfAbsent(receiverKey, candidate);
         float existing = this.faceWeights.get(receiverKey);
         if (weight > existing) {
            this.faceWeights.put(receiverKey, weight);
         }

         NeoShadowsTypes.PlaneRect existingBounds = (NeoShadowsTypes.PlaneRect)this.faceContributionBounds.get(receiverKey);
         if (existingBounds == null) {
            this.faceContributionBounds
               .put(receiverKey, new NeoShadowsTypes.PlaneRect(localFaceBounds.minU, localFaceBounds.maxU, localFaceBounds.minV, localFaceBounds.maxV));
         } else {
            existingBounds.include(localFaceBounds);
         }

         this.nearestReceiverDistanceSq = Math.min(this.nearestReceiverDistanceSq, candidate.distanceSq);
         int cellU = NeoShadowsProjection.blockPosToPlaneCellU(this.key.face, candidate.pos);
         int cellV = NeoShadowsProjection.blockPosToPlaneCellV(this.key.face, candidate.pos);
         this.minReceiverCellU = Math.min(this.minReceiverCellU, cellU);
         this.maxReceiverCellU = Math.max(this.maxReceiverCellU, cellU);
         this.minReceiverCellV = Math.min(this.minReceiverCellV, cellV);
         this.maxReceiverCellV = Math.max(this.maxReceiverCellV, cellV);
      }

      void addFace(NeoShadowsTypes.FaceCandidate candidate, NeoShadowsTypes.SourceContribution contribution) {
         this.addFace(candidate, contribution.weight, contribution.localFaceBounds);
      }

      void addFace(NeoShadowsTypes.FaceCandidate candidate) {
         this.addFace(candidate, 0.0F, candidate.localBounds);
      }

      float weightForReceiverPos(BlockPos pos) {
         return this.faceWeights.get(pos.asLong());
      }

      boolean containsReceiverFace(BlockPos pos) {
         return this.receiverFacesByKey.containsKey(pos.asLong());
      }

      NeoShadowsTypes.PlaneRect localContributionBoundsForReceiverPos(BlockPos pos) {
         NeoShadowsTypes.PlaneRect bounds = (NeoShadowsTypes.PlaneRect)this.faceContributionBounds.get(pos.asLong());
         return bounds != null ? bounds : NeoShadowsProjection.fullFaceRect();
      }

      NeoShadowsTypes.PlaneRect localContributionBoundsForReceiverPosKey(long posKey) {
         NeoShadowsTypes.PlaneRect bounds = (NeoShadowsTypes.PlaneRect)this.faceContributionBounds.get(posKey);
         return bounds != null ? bounds : NeoShadowsProjection.fullFaceRect();
      }

      boolean mayOverlapProjectedBounds(Vec3 source, NeoShadowsTypes.DynamicCaster caster) {
         double minU = Double.POSITIVE_INFINITY;
         double maxU = Double.NEGATIVE_INFINITY;
         double minV = Double.POSITIVE_INFINITY;
         double maxV = Double.NEGATIVE_INFINITY;
         int validCount = 0;

         for (Vec3 corner : caster.boxCorners) {
            NeoShadowsTypes.ProjectionPoint projection = this.plane.projectPoint(source, corner);
            if (projection.uv != null) {
               validCount++;
               minU = Math.min(minU, projection.uv.u);
               maxU = Math.max(maxU, projection.uv.u);
               minV = Math.min(minV, projection.uv.v);
               maxV = Math.max(maxV, projection.uv.v);
            }
         }

         return validCount == 0
            ? true
            : maxU > (double)this.minReceiverCellU
               && minU < (double)this.maxReceiverCellU + 1.0
               && maxV > (double)this.minReceiverCellV
               && minV < (double)this.maxReceiverCellV + 1.0;
      }

      List<NeoShadowsTypes.ReceiverContributionRect> receiverRects() {
         if (this.mergedReceiverRects == null) {
            this.mergedReceiverRects = this.buildReceiverRects();
         }

         return this.mergedReceiverRects;
      }

      private List<NeoShadowsTypes.ReceiverContributionRect> buildReceiverRects() {
         if (this.receiverFacesByKey.isEmpty()) {
            return Collections.emptyList();
         } else {
            Long2LongOpenHashMap fullFacePosByCell = new Long2LongOpenHashMap(this.receiverFacesByKey.size());
            LongOpenHashSet visitedCells = new LongOpenHashSet(this.receiverFacesByKey.size());
            ArrayList<NeoShadowsTypes.ReceiverContributionRect> rects = new ArrayList<>(this.receiverFacesByKey.size());
            fullFacePosByCell.defaultReturnValue(Long.MIN_VALUE);
            ObjectIterator var4 = this.receiverFacesByKey.long2ObjectEntrySet().iterator();

            while (var4.hasNext()) {
               Entry<NeoShadowsTypes.FaceCandidate> entry = (Entry<NeoShadowsTypes.FaceCandidate>)var4.next();
               long receiverKey = entry.getLongKey();
               NeoShadowsTypes.FaceCandidate candidate = (NeoShadowsTypes.FaceCandidate)entry.getValue();
               NeoShadowsTypes.PlaneRect localBounds = this.localContributionBoundsForReceiverPosKey(receiverKey);
               float strength = this.faceWeights.get(receiverKey);
               if (!(strength <= 0.0F)) {
                  BlockPos pos = candidate.pos;
                  int cellU = NeoShadowsProjection.blockPosToPlaneCellU(this.key.face, pos);
                  int cellV = NeoShadowsProjection.blockPosToPlaneCellV(this.key.face, pos);
                  long cellKey = NeoShadowsProjection.receiverCellKey(cellU, cellV);
                  if (NeoShadowsProjection.isMergeableReceiverBounds(localBounds) && NeoShadowsProjection.isMergeablePlaneCoordinate(this.key.planeCoordinate)) {
                     fullFacePosByCell.put(cellKey, receiverKey);
                  } else {
                     rects.add(
                        new NeoShadowsTypes.ReceiverContributionRect(
                           pos, NeoShadowsProjection.faceContributionPlaneRect(this.key.face, pos, localBounds), strength
                        )
                     );
                     visitedCells.add(cellKey);
                  }
               }
            }

            var4 = fullFacePosByCell.long2LongEntrySet().iterator();

            label93:
            while (var4.hasNext()) {
               it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry = (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry)var4.next();
               long startCellKey = entry.getLongKey();
               if (visitedCells.add(startCellKey)) {
                  long startPosKey = entry.getLongValue();
                  float strength = this.faceWeights.get(startPosKey);
                  int startCellU = NeoShadowsProjection.unpackReceiverCellU(startCellKey);
                  int startCellV = NeoShadowsProjection.unpackReceiverCellV(startCellKey);
                  int width = 1;

                  while (true) {
                     long nextCellKey = NeoShadowsProjection.receiverCellKey(startCellU + width, startCellV);
                     long nextPosKey = fullFacePosByCell.get(nextCellKey);
                     if (nextPosKey == Long.MIN_VALUE
                        || visitedCells.contains(nextCellKey)
                        || !NeoShadowsProjection.sameReceiverStrength(strength, this.faceWeights.get(nextPosKey))) {
                        int height = 1;

                        while (true) {
                           int nextV = startCellV + height;
                           boolean rowMatches = true;

                           for (int offsetU = 0; offsetU < width; offsetU++) {
                              long nextCellKeyx = NeoShadowsProjection.receiverCellKey(startCellU + offsetU, nextV);
                              long nextPosKeyx = fullFacePosByCell.get(nextCellKeyx);
                              if (nextPosKeyx == Long.MIN_VALUE
                                 || visitedCells.contains(nextCellKeyx)
                                 || !NeoShadowsProjection.sameReceiverStrength(strength, this.faceWeights.get(nextPosKeyx))) {
                                 rowMatches = false;
                                 break;
                              }
                           }

                           if (!rowMatches) {
                              for (int offsetV = 0; offsetV < height; offsetV++) {
                                 for (int offsetUx = 0; offsetUx < width; offsetUx++) {
                                    visitedCells.add(NeoShadowsProjection.receiverCellKey(startCellU + offsetUx, startCellV + offsetV));
                                 }
                              }

                              BlockPos anchorPos = NeoShadowsProjection.planeCellToBlockPos(this.key, startCellU, startCellV);
                              rects.add(
                                 new NeoShadowsTypes.ReceiverContributionRect(
                                    anchorPos,
                                    new NeoShadowsTypes.PlaneRect(
                                       (double)startCellU, (double)(startCellU + width), (double)startCellV, (double)(startCellV + height)
                                    ),
                                    strength
                                 )
                              );
                              continue label93;
                           }

                           height++;
                        }
                     }

                     width++;
                  }
               }
            }

            return rects;
         }
      }

      void finalizeReceiverSamples() {
         double maxDistance = Math.min(LightRtMath.getMaxDistance(this.sourceEmission), 30.0);
         double maxDistanceSq = maxDistance * maxDistance;
         int faceCount = this.faces.size();
         if (faceCount == 0) {
            this.receiverSamples = NeoShadowsProjection.EMPTY_DOUBLE_ARRAY;
         } else {
            double[] scratch = NeoShadowsProjection.SCRATCH_RECEIVER_SAMPLES.get();
            int writeIndex = 0;
            double sx0 = this.source.x;
            double sy0 = this.source.y;
            double sz0 = this.source.z;
            int emission = this.sourceEmission;

            for (NeoShadowsTypes.FaceCandidate candidate : this.faces) {
               NeoShadowsTypes.ReceiverPlane facePlane = new NeoShadowsTypes.ReceiverPlane(candidate.pos, candidate.face, candidate.planeCoordinate);
               NeoShadowsTypes.PlaneRect localBounds = this.localContributionBoundsForReceiverPosKey(candidate.receiverKey);
               NeoShadowsTypes.UvPoint[] probes = NeoShadowsProjection.buildOcclusionFaceProbes(candidate.face);
               int needed = writeIndex + probes.length * 3;
               if (needed > scratch.length) {
                  int newLength = Math.max(needed, scratch.length * 2);
                  scratch = Arrays.copyOf(scratch, newLength);
                  NeoShadowsProjection.SCRATCH_RECEIVER_SAMPLES.set(scratch);
               }

               for (NeoShadowsTypes.UvPoint probe : probes) {
                  if (NeoShadowsProjection.isProbeInsideRect(probe, localBounds)) {
                     double sx = facePlane.sampleX(probe.u, probe.v);
                     double sy = facePlane.sampleY(probe.u, probe.v);
                     double sz = facePlane.sampleZ(probe.u, probe.v);
                     double dx = sx - sx0;
                     double dy = sy - sy0;
                     double dz = sz - sz0;
                     double distanceSq = dx * dx + dy * dy + dz * dz;
                     if (!(distanceSq > maxDistanceSq)) {
                        double distance = Math.sqrt(distanceSq);
                        if (LightRtMath.getFalloff(emission, distance) > 0) {
                           scratch[writeIndex++] = sx;
                           scratch[writeIndex++] = sy;
                           scratch[writeIndex++] = sz;
                        }
                     }
                  }
               }
            }

            if (writeIndex == 0) {
               this.receiverSamples = NeoShadowsProjection.EMPTY_DOUBLE_ARRAY;
            } else {
               double[] result = new double[writeIndex];
               System.arraycopy(scratch, 0, result, 0, writeIndex);
               this.receiverSamples = result;
            }
         }
      }
   }

   static final class ReceiverPlaneKey {
      final Direction face;
      final double planeCoordinate;
      final long sourceKey;

      private ReceiverPlaneKey(Direction face, double planeCoordinate, long sourceKey) {
         this.face = face;
         this.planeCoordinate = planeCoordinate;
         this.sourceKey = sourceKey;
      }

      static NeoShadowsTypes.ReceiverPlaneKey of(NeoShadowsTypes.FaceCandidate candidate, long sourceKey) {
         return new NeoShadowsTypes.ReceiverPlaneKey(candidate.face, candidate.planeCoordinate, sourceKey);
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         } else {
            return !(other instanceof NeoShadowsTypes.ReceiverPlaneKey key)
               ? false
               : this.face == key.face && Double.compare(this.planeCoordinate, key.planeCoordinate) == 0 && this.sourceKey == key.sourceKey;
         }
      }

      @Override
      public int hashCode() {
         int result = this.face.hashCode();
         result = 31 * result + Double.hashCode(this.planeCoordinate);
         return 31 * result + Long.hashCode(this.sourceKey);
      }
   }

   static final class ShadowPolygon {
      final BlockPos blockPos;
      final Direction face;
      final double[] vertices;
      final double[] triangleVertices;
      final AABB bounds;
      final double faceCenterX;
      final double faceCenterY;
      final double faceCenterZ;
      final int faceNormalX;
      final int faceNormalY;
      final int faceNormalZ;
      final float strength;

      ShadowPolygon(BlockPos blockPos, Direction face, double[] vertices, float strength) {
         this.blockPos = blockPos;
         this.face = face;
         this.vertices = vertices;
         this.triangleVertices = buildTriangleVertices(vertices);
         this.bounds = buildBounds(vertices);
         this.faceCenterX = (double)blockPos.getX() + 0.5 + (double)face.getStepX() * 0.5;
         this.faceCenterY = (double)blockPos.getY() + 0.5 + (double)face.getStepY() * 0.5;
         this.faceCenterZ = (double)blockPos.getZ() + 0.5 + (double)face.getStepZ() * 0.5;
         this.faceNormalX = face.getStepX();
         this.faceNormalY = face.getStepY();
         this.faceNormalZ = face.getStepZ();
         this.strength = Mth.clamp(strength, 0.0F, 1.0F);
      }

      private static AABB buildBounds(double[] vertices) {
         if (vertices.length < 3) {
            return new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
         } else {
            double minX = vertices[0];
            double minY = vertices[1];
            double minZ = vertices[2];
            double maxX = minX;
            double maxY = minY;
            double maxZ = minZ;

            for (int index = 3; index < vertices.length; index += 3) {
               double x = vertices[index];
               double y = vertices[index + 1];
               double z = vertices[index + 2];
               minX = Math.min(minX, x);
               minY = Math.min(minY, y);
               minZ = Math.min(minZ, z);
               maxX = Math.max(maxX, x);
               maxY = Math.max(maxY, y);
               maxZ = Math.max(maxZ, z);
            }

            return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.0015F);
         }
      }

      private static double[] buildTriangleVertices(double[] vertices) {
         int vertexCount = vertices.length / 3;
         if (vertexCount < 3) {
            return NeoShadowsProjection.EMPTY_DOUBLE_ARRAY;
         } else if (vertexCount == 3) {
            return vertices;
         } else {
            double[] triangulated = new double[(vertexCount - 2) * 9];
            int writeIndex = 0;
            double x0 = vertices[0];
            double y0 = vertices[1];
            double z0 = vertices[2];

            for (int index = 1; index < vertexCount - 1; index++) {
               int offsetA = index * 3;
               int offsetB = (index + 1) * 3;
               triangulated[writeIndex++] = x0;
               triangulated[writeIndex++] = y0;
               triangulated[writeIndex++] = z0;
               triangulated[writeIndex++] = vertices[offsetA];
               triangulated[writeIndex++] = vertices[offsetA + 1];
               triangulated[writeIndex++] = vertices[offsetA + 2];
               triangulated[writeIndex++] = vertices[offsetB];
               triangulated[writeIndex++] = vertices[offsetB + 1];
               triangulated[writeIndex++] = vertices[offsetB + 2];
            }

            return triangulated;
         }
      }
   }

   static final class SourceContribution {
      final long sourceKey;
      final long lightSamplePosKey;
      final int sourceEmission;
      final float weight;
      final NeoShadowsTypes.PlaneRect localFaceBounds;

      SourceContribution(long sourceKey, long lightSamplePosKey, int sourceEmission, float weight, NeoShadowsTypes.PlaneRect localFaceBounds) {
         this.sourceKey = sourceKey;
         this.lightSamplePosKey = lightSamplePosKey;
         this.sourceEmission = sourceEmission;
         this.weight = weight;
         this.localFaceBounds = localFaceBounds;
      }
   }

   static final class SourceScore {
      final long sourceKey;
      long representativeSamplePosKey;
      int bestEmission;
      int sampleCount;
      double bestDistanceSq = Double.POSITIVE_INFINITY;
      double totalScore;
      double minProbeU = Double.POSITIVE_INFINITY;
      double maxProbeU = Double.NEGATIVE_INFINITY;
      double minProbeV = Double.POSITIVE_INFINITY;
      double maxProbeV = Double.NEGATIVE_INFINITY;

      SourceScore(long sourceKey, long representativeSamplePosKey, int bestEmission) {
         this.sourceKey = sourceKey;
         this.representativeSamplePosKey = representativeSamplePosKey;
         this.bestEmission = bestEmission;
      }

      void includeProbeBounds(NeoShadowsTypes.PlaneRect rect) {
         this.minProbeU = Math.min(this.minProbeU, rect.minU);
         this.maxProbeU = Math.max(this.maxProbeU, rect.maxU);
         this.minProbeV = Math.min(this.minProbeV, rect.minV);
         this.maxProbeV = Math.max(this.maxProbeV, rect.maxV);
      }

      NeoShadowsTypes.PlaneRect contributionBounds() {
         return Double.isFinite(this.minProbeU) && Double.isFinite(this.minProbeV) && Double.isFinite(this.maxProbeU) && Double.isFinite(this.maxProbeV)
            ? new NeoShadowsTypes.PlaneRect(this.minProbeU, this.maxProbeU, this.minProbeV, this.maxProbeV)
            : NeoShadowsProjection.fullFaceRect();
      }
   }

   static final class SpriteOpaqueMask {
      static final NeoShadowsTypes.SpriteOpaqueMask FULLY_OPAQUE = new NeoShadowsTypes.SpriteOpaqueMask(new NeoShadowsTypes.SpriteRect[0], true, false);
      static final NeoShadowsTypes.SpriteOpaqueMask FULLY_TRANSPARENT = new NeoShadowsTypes.SpriteOpaqueMask(new NeoShadowsTypes.SpriteRect[0], false, true);
      final NeoShadowsTypes.SpriteRect[] rectangles;
      final boolean fullyOpaque;
      final boolean fullyTransparent;

      SpriteOpaqueMask(NeoShadowsTypes.SpriteRect[] rectangles, boolean fullyOpaque, boolean fullyTransparent) {
         this.rectangles = rectangles;
         this.fullyOpaque = fullyOpaque;
         this.fullyTransparent = fullyTransparent;
      }

      static NeoShadowsTypes.SpriteOpaqueMask fullyOpaque() {
         return FULLY_OPAQUE;
      }

      static NeoShadowsTypes.SpriteOpaqueMask fullyTransparent() {
         return FULLY_TRANSPARENT;
      }
   }

   static final class SpritePixelBounds {
      final double minX;
      final double maxX;
      final double minY;
      final double maxY;

      SpritePixelBounds(double minX, double maxX, double minY, double maxY) {
         this.minX = minX;
         this.maxX = maxX;
         this.minY = minY;
         this.maxY = maxY;
      }
   }

   static final class SpriteRect {
      final int minX;
      final int maxX;
      final int minY;
      final int maxY;

      SpriteRect(int minX, int maxX, int minY, int maxY) {
         this.minX = minX;
         this.maxX = maxX;
         this.minY = minY;
         this.maxY = maxY;
      }
   }

   static final class StaticHotPatchOutput {
      static final NeoShadowsTypes.StaticHotPatchOutput EMPTY = new NeoShadowsTypes.StaticHotPatchOutput(new Long2ByteOpenHashMap(0), Collections.emptyList());
      final Long2ByteOpenHashMap affectedFacesByBlock;
      final List<NeoShadowsTypes.ShadowPolygon> shadowPolygons;

      StaticHotPatchOutput(Long2ByteOpenHashMap affectedFacesByBlock, List<NeoShadowsTypes.ShadowPolygon> shadowPolygons) {
         affectedFacesByBlock.defaultReturnValue((byte)0);
         this.affectedFacesByBlock = affectedFacesByBlock;
         this.shadowPolygons = shadowPolygons;
      }

      boolean isEmpty() {
         return this.affectedFacesByBlock.isEmpty() && this.shadowPolygons.isEmpty();
      }
   }

   static final class StaticHotPatchOverlay {
      static final NeoShadowsTypes.StaticHotPatchOverlay EMPTY = new NeoShadowsTypes.StaticHotPatchOverlay(
         new Long2ByteOpenHashMap(0), new Long2ObjectOpenHashMap(0), Collections.emptyList()
      );
      final Long2ByteOpenHashMap replacedFacesByBlock;
      final Long2ObjectOpenHashMap<List<NeoShadowsTypes.ShadowPolygon>[]> polygonsByBlock;
      final List<NeoShadowsTypes.ShadowPolygon> polygons;

      StaticHotPatchOverlay(
         Long2ByteOpenHashMap replacedFacesByBlock,
         Long2ObjectOpenHashMap<List<NeoShadowsTypes.ShadowPolygon>[]> polygonsByBlock,
         List<NeoShadowsTypes.ShadowPolygon> polygons
      ) {
         replacedFacesByBlock.defaultReturnValue((byte)0);
         this.replacedFacesByBlock = replacedFacesByBlock;
         this.polygonsByBlock = polygonsByBlock;
         this.polygons = polygons;
      }

      boolean isEmpty() {
         return this.replacedFacesByBlock.isEmpty() && this.polygons.isEmpty();
      }

      boolean replaces(NeoShadowsTypes.ShadowPolygon polygon) {
         byte mask = this.replacedFacesByBlock.get(polygon.blockPos.asLong());
         return (mask & faceMask(polygon.face)) != 0;
      }

      NeoShadowsTypes.StaticHotPatchOverlay merged(NeoShadowsTypes.StaticHotPatchOutput output) {
         if (output != null && !output.affectedFacesByBlock.isEmpty()) {
            Long2ByteOpenHashMap mergedMasks = new Long2ByteOpenHashMap(Math.max(16, this.replacedFacesByBlock.size() + output.affectedFacesByBlock.size()));
            mergedMasks.defaultReturnValue((byte)0);
            mergedMasks.putAll(this.replacedFacesByBlock);
            Long2ObjectOpenHashMap<List<NeoShadowsTypes.ShadowPolygon>[]> mergedByBlock = new Long2ObjectOpenHashMap(
               Math.max(16, this.polygonsByBlock.size() + output.affectedFacesByBlock.size())
            );
            ObjectIterator var4 = this.polygonsByBlock.long2ObjectEntrySet().iterator();

            while (var4.hasNext()) {
               Entry<List<NeoShadowsTypes.ShadowPolygon>[]> entry = (Entry<List<NeoShadowsTypes.ShadowPolygon>[]>)var4.next();
               mergedByBlock.put(entry.getLongKey(), copyFaceBuckets((List<NeoShadowsTypes.ShadowPolygon>[])entry.getValue()));
            }

            var4 = output.affectedFacesByBlock.long2ByteEntrySet().iterator();

            while (var4.hasNext()) {
               it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry entry = (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry)var4.next();
               long blockKey = entry.getLongKey();
               byte faceMask = entry.getByteValue();
               if (faceMask != 0) {
                  mergedMasks.put(blockKey, (byte)(mergedMasks.get(blockKey) | faceMask));
                  List<NeoShadowsTypes.ShadowPolygon>[] buckets = (List<NeoShadowsTypes.ShadowPolygon>[])mergedByBlock.get(blockKey);
                  if (buckets == null) {
                     buckets = newFaceBuckets();
                  }

                  clearFaceBuckets(buckets, faceMask);
                  if (hasAnyPolygons(buckets)) {
                     mergedByBlock.put(blockKey, buckets);
                  } else {
                     mergedByBlock.remove(blockKey);
                  }
               }
            }

            for (NeoShadowsTypes.ShadowPolygon polygon : output.shadowPolygons) {
               long blockKey = polygon.blockPos.asLong();
               List<NeoShadowsTypes.ShadowPolygon>[] bucketsx = (List<NeoShadowsTypes.ShadowPolygon>[])mergedByBlock.get(blockKey);
               if (bucketsx == null) {
                  bucketsx = newFaceBuckets();
               }

               int faceIndex = polygon.face.ordinal();
               List<NeoShadowsTypes.ShadowPolygon> facePolygons = bucketsx[faceIndex];
               if (facePolygons == null) {
                  facePolygons = new ArrayList<>();
                  bucketsx[faceIndex] = facePolygons;
               }

               facePolygons.add(polygon);
               mergedByBlock.put(blockKey, bucketsx);
            }

            return new NeoShadowsTypes.StaticHotPatchOverlay(mergedMasks, mergedByBlock, flattenPolygons(mergedByBlock));
         } else {
            return this;
         }
      }

      private static byte faceMask(Direction face) {
         return (byte)(1 << face.ordinal());
      }

      private static List<NeoShadowsTypes.ShadowPolygon>[] newFaceBuckets() {
         return new List[NeoShadowsEngine.FACES.length];
      }

      private static List<NeoShadowsTypes.ShadowPolygon>[] copyFaceBuckets(List<NeoShadowsTypes.ShadowPolygon>[] source) {
         List<NeoShadowsTypes.ShadowPolygon>[] copy = newFaceBuckets();
         System.arraycopy(source, 0, copy, 0, source.length);
         return copy;
      }

      private static void clearFaceBuckets(List<NeoShadowsTypes.ShadowPolygon>[] buckets, byte faceMask) {
         for (int faceIndex = 0; faceIndex < buckets.length; faceIndex++) {
            if ((faceMask & 1 << faceIndex) != 0) {
               buckets[faceIndex] = null;
            }
         }
      }

      private static boolean hasAnyPolygons(List<NeoShadowsTypes.ShadowPolygon>[] buckets) {
         for (List<NeoShadowsTypes.ShadowPolygon> bucket : buckets) {
            if (bucket != null && !bucket.isEmpty()) {
               return true;
            }
         }

         return false;
      }

      private static List<NeoShadowsTypes.ShadowPolygon> flattenPolygons(Long2ObjectOpenHashMap<List<NeoShadowsTypes.ShadowPolygon>[]> polygonsByBlock) {
         List<NeoShadowsTypes.ShadowPolygon> flattened = new ArrayList<>();
         ObjectIterator var2 = polygonsByBlock.values().iterator();

         while (var2.hasNext()) {
            List<NeoShadowsTypes.ShadowPolygon>[] buckets = (List<NeoShadowsTypes.ShadowPolygon>[])var2.next();

            for (List<NeoShadowsTypes.ShadowPolygon> bucket : buckets) {
               if (bucket != null && !bucket.isEmpty()) {
                  flattened.addAll(bucket);
               }
            }
         }

         return flattened.isEmpty() ? Collections.emptyList() : List.copyOf(flattened);
      }
   }

   static final class StaticHotPatchRequest {
      final long id;
      final long epoch;
      final long baseStaticResultId;
      final ClientLevel level;
      final LongOpenHashSet changedBlocks;
      final int localRadius;

      StaticHotPatchRequest(long id, long epoch, long baseStaticResultId, ClientLevel level, LongOpenHashSet changedBlocks, int localRadius) {
         this.id = id;
         this.epoch = epoch;
         this.baseStaticResultId = baseStaticResultId;
         this.level = level;
         this.changedBlocks = changedBlocks;
         this.localRadius = localRadius;
      }
   }

   static final class StaticHotPatchResult {
      final long id;
      final long epoch;
      final long baseStaticResultId;
      final NeoShadowsTypes.StaticHotPatchOutput output;

      StaticHotPatchResult(long id, long epoch, long baseStaticResultId, NeoShadowsTypes.StaticHotPatchOutput output) {
         this.id = id;
         this.epoch = epoch;
         this.baseStaticResultId = baseStaticResultId;
         this.output = output;
      }
   }

   static final class StaticRequest {
      final long id;
      final long epoch;
      final ClientLevel level;
      final BlockPos center;

      StaticRequest(long id, long epoch, ClientLevel level, BlockPos center) {
         this.id = id;
         this.epoch = epoch;
         this.level = level;
         this.center = center;
      }
   }

   static final class StaticResult {
      final long id;
      final long epoch;
      final NeoShadowsTypes.RealtimeStaticShadowOutput output;

      StaticResult(long id, long epoch, NeoShadowsTypes.RealtimeStaticShadowOutput output) {
         this.id = id;
         this.epoch = epoch;
         this.output = output;
      }
   }

   static final class TexturedQuad {
      final Vec3[] worldVertices;
      final NeoShadowsTypes.AtlasUv[] atlasUvs;
      final Vec3 normal;

      TexturedQuad(Vec3[] worldVertices, NeoShadowsTypes.AtlasUv[] atlasUvs, Vec3 normal) {
         this.worldVertices = worldVertices;
         this.atlasUvs = atlasUvs;
         this.normal = normal;
      }

      @Nullable
      static NeoShadowsTypes.TexturedQuad from(BakedQuad quad, BlockPos blockPos) {
         int[] vertices = quad.getVertices();
         if (vertices.length < 32) {
            return null;
         } else {
            Vec3[] worldVertices = new Vec3[4];
            NeoShadowsTypes.AtlasUv[] atlasUvs = new NeoShadowsTypes.AtlasUv[4];

            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
               int baseIndex = vertexIndex * 8;
               double x = (double)(Float.intBitsToFloat(vertices[baseIndex]) + (float)blockPos.getX());
               double y = (double)(Float.intBitsToFloat(vertices[baseIndex + 1]) + (float)blockPos.getY());
               double z = (double)(Float.intBitsToFloat(vertices[baseIndex + 2]) + (float)blockPos.getZ());
               double u = (double)Float.intBitsToFloat(vertices[baseIndex + 4]);
               double v = (double)Float.intBitsToFloat(vertices[baseIndex + 5]);
               worldVertices[vertexIndex] = new Vec3(x, y, z);
               atlasUvs[vertexIndex] = new NeoShadowsTypes.AtlasUv(u, v);
            }

            Vec3 normal = worldVertices[1].subtract(worldVertices[0]).cross(worldVertices[2].subtract(worldVertices[0]));
            return normal.lengthSqr() <= 1.0E-12 ? null : new NeoShadowsTypes.TexturedQuad(worldVertices, atlasUvs, normal);
         }
      }

      boolean facesSource(Vec3 source) {
         Vec3 toSource = source.subtract(this.worldVertices[0]);
         return this.normal.dot(toSource) > 1.0E-6;
      }
   }

   static final class UvPoint {
      final double u;
      final double v;

      UvPoint(double u, double v) {
         this.u = u;
         this.v = v;
      }

      boolean equalsApprox(NeoShadowsTypes.UvPoint other) {
         return Math.abs(this.u - other.u) <= 1.0E-6 && Math.abs(this.v - other.v) <= 1.0E-6;
      }
   }

   static final class VisibleCandidateBlockSnapshot {
      final BlockPos center;
      final long frontierSignature;
      final long[] frontierPositions;
      final NeoShadowsTypes.FaceCandidate[] candidates;
      final List<NeoShadowsTypes.FaceCandidate> candidateView;

      VisibleCandidateBlockSnapshot(BlockPos center, long frontierSignature, long[] frontierPositions, NeoShadowsTypes.FaceCandidate[] candidates) {
         this.center = center;
         this.frontierSignature = frontierSignature;
         this.frontierPositions = frontierPositions;
         this.candidates = candidates;
         this.candidateView = candidates.length == 0 ? Collections.emptyList() : Arrays.asList(candidates);
      }

      boolean matches(BlockPos otherCenter, long otherFrontierSignature, long[] otherFrontierPositions) {
         if (this.center.getX() == otherCenter.getX() && this.center.getY() == otherCenter.getY() && this.center.getZ() == otherCenter.getZ()) {
            if (this.frontierPositions.length != otherFrontierPositions.length) {
               return false;
            } else if (this.frontierSignature == otherFrontierSignature) {
               return true;
            } else {
               boolean exactMatch = true;

               for (int index = 0; index < this.frontierPositions.length; index++) {
                  if (this.frontierPositions[index] != otherFrontierPositions[index]) {
                     exactMatch = false;
                     break;
                  }
               }

               if (exactMatch) {
                  return true;
               } else {
                  LongOpenHashSet frontierSet = new LongOpenHashSet(this.frontierPositions.length);

                  for (long frontierPosition : this.frontierPositions) {
                     frontierSet.add(frontierPosition);
                  }

                  for (long frontierPosition : otherFrontierPositions) {
                     if (!frontierSet.contains(frontierPosition)) {
                        return false;
                     }
                  }

                  return true;
               }
            }
         } else {
            return false;
         }
      }

      List<NeoShadowsTypes.FaceCandidate> asList() {
         return this.candidateView;
      }

      static NeoShadowsTypes.VisibleCandidateBlockSnapshot empty(BlockPos center, long frontierSignature, long[] frontierPositions) {
         return new NeoShadowsTypes.VisibleCandidateBlockSnapshot(
            center.immutable(), frontierSignature, Arrays.copyOf(frontierPositions, frontierPositions.length), new NeoShadowsTypes.FaceCandidate[0]
         );
      }
   }
}
