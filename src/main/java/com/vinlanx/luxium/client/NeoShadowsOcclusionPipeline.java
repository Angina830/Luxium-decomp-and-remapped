package com.vinlanx.luxium.client;

import com.vinlanx.luxium.rtx.LightRtMath;
import com.vinlanx.luxium.rtx.SectionSkip;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

final class NeoShadowsOcclusionPipeline {
   private static final double DDA_TIE_EPSILON = 1.0E-9;
   private static final ThreadLocal<NeoShadowsOcclusionPipeline.FaceOcclusionScratch> FACE_OCCLUSION_SCRATCH_TL = ThreadLocal.withInitial(
      NeoShadowsOcclusionPipeline.FaceOcclusionScratch::new
   );
   private static final ThreadLocal<NeoShadowsOcclusionPipeline.OccluderHit> SCRATCH_HIT = ThreadLocal.withInitial(
      () -> new NeoShadowsOcclusionPipeline.OccluderHit(BlockPos.ZERO, new NeoShadowsTypes.Box[0])
   );
   private final NeoShadowsEngine engine;
   private final ConcurrentHashMap<NeoShadowsOcclusionPipeline.OcclusionCacheKey, NeoShadowsOcclusionPipeline.CachedFaceOcclusion> occlusionCache = new ConcurrentHashMap<>();

   NeoShadowsOcclusionPipeline(NeoShadowsEngine engine) {
      this.engine = engine;
   }

   void clearOcclusionCache() {
      this.occlusionCache.clear();
   }

   void findFaceOccludersInto(
      ClientLevel level,
      NeoShadowsTypes.ReceiverPlane plane,
      NeoShadowsTypes.FaceCandidate candidate,
      NeoShadowsTypes.ReceiverPlaneGroup group,
      NeoShadowsTypes.GeometryBuildState state,
      Vec3 source,
      long sourceKey,
      int sourceEmission,
      Long2ObjectLinkedOpenHashMap<NeoShadowsOcclusionPipeline.OccluderHit> hitsByBlock
   ) {
      double maxDist = Math.min(LightRtMath.getMaxDistance(sourceEmission), 30.0);
      NeoShadowsTypes.PlaneRect localBounds = group.localContributionBoundsForReceiverPosKey(candidate.receiverKey);
      long candidateKey = candidate.receiverKey;
      NeoShadowsOcclusionPipeline.OcclusionCacheKey cacheKey = new NeoShadowsOcclusionPipeline.OcclusionCacheKey(
         candidateKey, candidate.face.ordinal(), sourceKey
      );
      NeoShadowsOcclusionPipeline.CachedFaceOcclusion cached = this.occlusionCache.get(cacheKey);
      if (cached != null) {
         int n = cached.hitKeys.length;

         for (int i = 0; i < n; i++) {
            long hitKey = cached.hitKeys[i];
            NeoShadowsOcclusionPipeline.OccluderHit mergedHit = (NeoShadowsOcclusionPipeline.OccluderHit)hitsByBlock.get(hitKey);
            if (mergedHit != null) {
               NeoShadowsTypes.UvPoint[] probes = cached.probesPerHit[i];

               for (NeoShadowsTypes.UvPoint probe : probes) {
                  mergedHit.addReceiverSample(probe);
               }
            } else {
               NeoShadowsOcclusionPipeline.OccluderHit persisted = new NeoShadowsOcclusionPipeline.OccluderHit(BlockPos.of(hitKey), cached.hitBoxes[i]);
               NeoShadowsTypes.UvPoint[] probes = cached.probesPerHit[i];

               for (NeoShadowsTypes.UvPoint probe : probes) {
                  persisted.addReceiverSample(probe);
               }

               hitsByBlock.put(hitKey, persisted);
            }
         }
      } else {
         NeoShadowsOcclusionPipeline.FaceOcclusionScratch faceScratch = FACE_OCCLUSION_SCRATCH_TL.get();
         faceScratch.reset();
         NeoShadowsTypes.UvPoint[] occlusionProbes = buildOcclusionFaceProbes(candidate.face);
         NeoShadowsOcclusionPipeline.ProbeFirstHitCache firstHitCache = state.firstHitScalarCache(sourceKey, candidateKey, occlusionProbes.length);

         for (int probeIndex = 0; probeIndex < occlusionProbes.length; probeIndex++) {
            NeoShadowsTypes.UvPoint probe = occlusionProbes[probeIndex];
            if (NeoShadowsProjection.isProbeInsideRect(probe, localBounds)) {
               double sx = plane.sampleX(probe.u, probe.v);
               double sy = plane.sampleY(probe.u, probe.v);
               double sz = plane.sampleZ(probe.u, probe.v);
               double ddx = sx - source.x;
               double ddy = sy - source.y;
               double ddz = sz - source.z;
               double distance = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
               if (!(distance > maxDist) && LightRtMath.getFalloff(sourceEmission, distance) > 0) {
                  NeoShadowsOcclusionPipeline.CachedFirstScalarHit cachedHit = firstHitCache.get(probeIndex);
                  if (cachedHit == null) {
                     cachedHit = firstHitCache.findReusableHit(source.x, source.y, source.z, sx, sy, sz);
                  }

                  if (cachedHit == null) {
                     NeoShadowsOcclusionPipeline.OccluderHit hit = SCRATCH_HIT.get();
                     hit.reset();
                     cachedHit = NeoShadowsOcclusionPipeline.SolidOccluderCache.firstHitScalar(level, source, sx, sy, sz, sourceKey, candidateKey, hit)
                        ? NeoShadowsOcclusionPipeline.CachedFirstScalarHit.hit(hit.blockKey, hit.boxes)
                        : NeoShadowsOcclusionPipeline.CachedFirstScalarHit.MISS;
                     cachedHit = firstHitCache.storeIfAbsent(probeIndex, cachedHit);
                  }

                  if (cachedHit.hit) {
                     firstHitCache.remember(cachedHit);
                     long hitKey = cachedHit.blockKey;
                     NeoShadowsOcclusionPipeline.OccluderHit mergedHit = (NeoShadowsOcclusionPipeline.OccluderHit)hitsByBlock.get(hitKey);
                     if (mergedHit == null) {
                        NeoShadowsOcclusionPipeline.OccluderHit persisted = new NeoShadowsOcclusionPipeline.OccluderHit(BlockPos.of(hitKey), cachedHit.boxes);
                        persisted.addReceiverSample(probe);
                        hitsByBlock.put(hitKey, persisted);
                     } else {
                        mergedHit.addReceiverSample(probe);
                     }

                     List<NeoShadowsTypes.UvPoint> probesForHit = (List<NeoShadowsTypes.UvPoint>)faceScratch.probesByHit.get(hitKey);
                     if (probesForHit == null) {
                        probesForHit = faceScratch.rentList();
                        faceScratch.probesByHit.put(hitKey, probesForHit);
                        faceScratch.boxesByHit.put(hitKey, cachedHit.boxes);
                     }

                     probesForHit.add(probe);
                  }
               }
            }
         }

         int n = faceScratch.probesByHit.size();
         if (n == 0) {
            this.occlusionCache.put(cacheKey, NeoShadowsOcclusionPipeline.CachedFaceOcclusion.EMPTY);
         } else {
            long[] hitKeys = new long[n];
            NeoShadowsTypes.Box[][] hitBoxes = new NeoShadowsTypes.Box[n][];
            NeoShadowsTypes.UvPoint[][] probesPerHit = new NeoShadowsTypes.UvPoint[n][];
            int ix = 0;

            for (ObjectBidirectionalIterator var26 = faceScratch.probesByHit.long2ObjectEntrySet().iterator(); var26.hasNext(); ix++) {
               Entry<List<NeoShadowsTypes.UvPoint>> entry = (Entry<List<NeoShadowsTypes.UvPoint>>)var26.next();
               long key = entry.getLongKey();
               hitKeys[ix] = key;
               hitBoxes[ix] = (NeoShadowsTypes.Box[])faceScratch.boxesByHit.get(key);
               List<NeoShadowsTypes.UvPoint> probes = (List<NeoShadowsTypes.UvPoint>)entry.getValue();
               probesPerHit[ix] = probes.toArray(new NeoShadowsTypes.UvPoint[0]);
            }

            this.occlusionCache.put(cacheKey, new NeoShadowsOcclusionPipeline.CachedFaceOcclusion(hitKeys, hitBoxes, probesPerHit));
         }
      }
   }

   static NeoShadowsTypes.UvPoint[] createFaceProbes(int gridSize, boolean includeCenterFirst, double inset) {
      double min = inset;
      double max = 1.0 - inset;
      double step = gridSize <= 1 ? 0.0 : (max - inset) / (double)(gridSize - 1);
      List<NeoShadowsTypes.UvPoint> probes = new ArrayList<>(gridSize * gridSize + 1);
      if (includeCenterFirst) {
         probes.add(new NeoShadowsTypes.UvPoint(0.5, 0.5));
      }

      for (int vIndex = 0; vIndex < gridSize; vIndex++) {
         double v = gridSize <= 1 ? 0.5 : min + step * (double)vIndex;

         for (int uIndex = 0; uIndex < gridSize; uIndex++) {
            double u = gridSize <= 1 ? 0.5 : min + step * (double)uIndex;
            if (!includeCenterFirst || !(Math.abs(u - 0.5) <= 1.0E-6) || !(Math.abs(v - 0.5) <= 1.0E-6)) {
               probes.add(new NeoShadowsTypes.UvPoint(u, v));
            }
         }
      }

      return probes.toArray(new NeoShadowsTypes.UvPoint[0]);
   }

   static NeoShadowsTypes.PlaneRect[] createProbeSupportRects(NeoShadowsTypes.UvPoint[] probes) {
      List<Double> uniqueU = collectUniqueProbeCoordinates(probes, true);
      List<Double> uniqueV = collectUniqueProbeCoordinates(probes, false);
      NeoShadowsTypes.PlaneRect[] rects = new NeoShadowsTypes.PlaneRect[probes.length];

      for (int index = 0; index < probes.length; index++) {
         NeoShadowsTypes.UvPoint probe = probes[index];
         int uIndex = findProbeCoordinateIndex(uniqueU, probe.u);
         int vIndex = findProbeCoordinateIndex(uniqueV, probe.v);
         double minU = uIndex <= 0 ? 0.0 : (uniqueU.get(uIndex - 1) + probe.u) * 0.5;
         double maxU = uIndex >= uniqueU.size() - 1 ? 1.0 : (probe.u + uniqueU.get(uIndex + 1)) * 0.5;
         double minV = vIndex <= 0 ? 0.0 : (uniqueV.get(vIndex - 1) + probe.v) * 0.5;
         double maxV = vIndex >= uniqueV.size() - 1 ? 1.0 : (probe.v + uniqueV.get(vIndex + 1)) * 0.5;
         rects[index] = new NeoShadowsTypes.PlaneRect(minU, maxU, minV, maxV);
      }

      return rects;
   }

   static List<Double> collectUniqueProbeCoordinates(NeoShadowsTypes.UvPoint[] probes, boolean useU) {
      List<Double> values = new ArrayList<>(probes.length);

      for (NeoShadowsTypes.UvPoint probe : probes) {
         double value = useU ? probe.u : probe.v;
         boolean exists = false;

         for (double existing : values) {
            if (Math.abs(existing - value) <= 1.0E-6) {
               exists = true;
               break;
            }
         }

         if (!exists) {
            values.add(value);
         }
      }

      values.sort(Double::compare);
      return values;
   }

   static int findProbeCoordinateIndex(List<Double> coordinates, double value) {
      for (int index = 0; index < coordinates.size(); index++) {
         if (Math.abs(coordinates.get(index) - value) <= 1.0E-6) {
            return index;
         }
      }

      return -1;
   }

   static NeoShadowsTypes.UvPoint[] buildOcclusionFaceProbes(Direction face) {
      return NeoShadowsProjection.isHorizontalReceiverFace(face) ? NeoShadowsEngine.HORIZONTAL_OCCLUSION_FACE_PROBES : NeoShadowsEngine.OCCLUSION_FACE_PROBES;
   }

   private static final class CachedFaceOcclusion {
      static final NeoShadowsOcclusionPipeline.CachedFaceOcclusion EMPTY = new NeoShadowsOcclusionPipeline.CachedFaceOcclusion(
         new long[0], new NeoShadowsTypes.Box[0][], new NeoShadowsTypes.UvPoint[0][]
      );
      final long[] hitKeys;
      final NeoShadowsTypes.Box[][] hitBoxes;
      final NeoShadowsTypes.UvPoint[][] probesPerHit;

      CachedFaceOcclusion(long[] hitKeys, NeoShadowsTypes.Box[][] hitBoxes, NeoShadowsTypes.UvPoint[][] probesPerHit) {
         this.hitKeys = hitKeys;
         this.hitBoxes = hitBoxes;
         this.probesPerHit = probesPerHit;
      }
   }

   static final class CachedFirstScalarHit {
      static final NeoShadowsOcclusionPipeline.CachedFirstScalarHit MISS = new NeoShadowsOcclusionPipeline.CachedFirstScalarHit(false, Long.MIN_VALUE, null);
      final boolean hit;
      final long blockKey;
      final int blockX;
      final int blockY;
      final int blockZ;
      final NeoShadowsTypes.Box[] boxes;

      private CachedFirstScalarHit(boolean hit, long blockKey, @Nullable NeoShadowsTypes.Box[] boxes) {
         this.hit = hit;
         this.blockKey = blockKey;
         this.blockX = hit ? BlockPos.getX(blockKey) : 0;
         this.blockY = hit ? BlockPos.getY(blockKey) : 0;
         this.blockZ = hit ? BlockPos.getZ(blockKey) : 0;
         this.boxes = boxes;
      }

      static NeoShadowsOcclusionPipeline.CachedFirstScalarHit hit(long blockKey, NeoShadowsTypes.Box[] boxes) {
         return new NeoShadowsOcclusionPipeline.CachedFirstScalarHit(true, blockKey, boxes);
      }

      boolean intersectsSegment(double startX, double startY, double startZ, double endX, double endY, double endZ) {
         if (this.hit && this.boxes != null && this.boxes.length != 0) {
            if (this.boxes.length == 1) {
               return this.boxes[0].intersectsSegmentWithOffset(startX, startY, startZ, endX, endY, endZ, this.blockX, this.blockY, this.blockZ);
            } else {
               for (NeoShadowsTypes.Box box : this.boxes) {
                  if (box.intersectsSegmentWithOffset(startX, startY, startZ, endX, endY, endZ, this.blockX, this.blockY, this.blockZ)) {
                     return true;
                  }
               }

               return false;
            }
         } else {
            return false;
         }
      }
   }

   private static final class FaceOcclusionScratch {
      final Long2ObjectLinkedOpenHashMap<List<NeoShadowsTypes.UvPoint>> probesByHit = new Long2ObjectLinkedOpenHashMap(8);
      final Long2ObjectOpenHashMap<NeoShadowsTypes.Box[]> boxesByHit = new Long2ObjectOpenHashMap(8);
      final List<List<NeoShadowsTypes.UvPoint>> listPool = new ArrayList<>();
      int listPoolUsed;

      List<NeoShadowsTypes.UvPoint> rentList() {
         List<NeoShadowsTypes.UvPoint> list;
         if (this.listPoolUsed < this.listPool.size()) {
            list = this.listPool.get(this.listPoolUsed);
            list.clear();
         } else {
            list = new ArrayList<>(4);
            this.listPool.add(list);
         }

         this.listPoolUsed++;
         return list;
      }

      void reset() {
         this.probesByHit.clear();
         this.boxesByHit.clear();
         this.listPoolUsed = 0;
      }
   }

   static final class OccluderHit {
      final BlockPos blockPos;
      long blockKey;
      NeoShadowsTypes.Box[] boxes;
      final List<NeoShadowsTypes.UvPoint> receiverSamples = new ArrayList<>(4);

      OccluderHit(BlockPos blockPos, NeoShadowsTypes.Box[] boxes) {
         this.blockPos = blockPos;
         this.blockKey = blockPos.asLong();
         this.boxes = boxes;
      }

      void reset() {
         this.blockKey = 0L;
         this.boxes = null;
         this.receiverSamples.clear();
      }

      void addReceiverSample(NeoShadowsTypes.UvPoint sample) {
         this.receiverSamples.add(sample);
      }
   }

   private static record OcclusionCacheKey(long receiverPosKey, int faceOrdinal, long sourceKey) {
   }

   static final class ProbeFirstHitCache {
      private final AtomicReferenceArray<NeoShadowsOcclusionPipeline.CachedFirstScalarHit> cachedHits;
      @Nullable
      private volatile NeoShadowsOcclusionPipeline.CachedFirstScalarHit lastResolvedHit;

      ProbeFirstHitCache(int probeCount) {
         this.cachedHits = new AtomicReferenceArray<>(probeCount);
      }

      NeoShadowsOcclusionPipeline.CachedFirstScalarHit get(int probeIndex) {
         return this.cachedHits.get(probeIndex);
      }

      NeoShadowsOcclusionPipeline.CachedFirstScalarHit storeIfAbsent(int probeIndex, NeoShadowsOcclusionPipeline.CachedFirstScalarHit hit) {
         if (this.cachedHits.compareAndSet(probeIndex, null, hit)) {
            return hit;
         } else {
            NeoShadowsOcclusionPipeline.CachedFirstScalarHit existing = this.cachedHits.get(probeIndex);
            return existing != null ? existing : hit;
         }
      }

      @Nullable
      NeoShadowsOcclusionPipeline.CachedFirstScalarHit findReusableHit(double startX, double startY, double startZ, double endX, double endY, double endZ) {
         NeoShadowsOcclusionPipeline.CachedFirstScalarHit lastHit = this.lastResolvedHit;
         if (lastHit != null && lastHit.hit) {
            return lastHit.intersectsSegment(startX, startY, startZ, endX, endY, endZ) ? lastHit : null;
         } else {
            return null;
         }
      }

      void remember(NeoShadowsOcclusionPipeline.CachedFirstScalarHit hit) {
         if (hit.hit) {
            this.lastResolvedHit = hit;
         }
      }
   }

   static final class SolidOccluderCache {
      private static final ConcurrentHashMap<BlockState, NeoShadowsTypes.Box[]> BOX_CACHE = new ConcurrentHashMap<>();
      private static final NeoShadowsTypes.Box[] NO_BOXES = new NeoShadowsTypes.Box[0];
      private static final NeoShadowsTypes.Box[] FULL_BLOCK_BOXES = new NeoShadowsTypes.Box[]{new NeoShadowsTypes.Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)};
      private static final ThreadLocal<MutableBlockPos> CURSOR_TL = ThreadLocal.withInitial(MutableBlockPos::new);

      private SolidOccluderCache() {
      }

      static boolean firstHitScalar(
         ClientLevel level,
         Vec3 start,
         double endX,
         double endY,
         double endZ,
         long ignoredSource,
         long ignoredReceiver,
         NeoShadowsOcclusionPipeline.OccluderHit out
      ) {
         double deltaX = endX - start.x;
         double deltaY = endY - start.y;
         double deltaZ = endZ - start.z;
         double maxDistance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
         if (maxDistance <= 1.0E-6) {
            return false;
         } else {
            double dx = deltaX / maxDistance;
            double dy = deltaY / maxDistance;
            double dz = deltaZ / maxDistance;
            SectionSkip.TraversalState traversal = SectionSkip.rentState()
               .reset(start.x, start.y, start.z, dx, dy, dz, Mth.floor(start.x), Mth.floor(start.y), Mth.floor(start.z));
            int minBuildHeight = level.getMinBuildHeight();
            ClientChunkCache chunkSource = level.getChunkSource();
            int cachedChunkX = Integer.MIN_VALUE;
            int cachedChunkZ = Integer.MIN_VALUE;
            int cachedSectionIndex = Integer.MIN_VALUE;
            LevelChunk cachedChunk = null;
            LevelChunkSection[] cachedSections = null;
            LevelChunkSection cachedSection = null;
            boolean sectionIsAir = false;
            MutableBlockPos cursor = CURSOR_TL.get();

            while (traversal.traveled <= maxDistance) {
               double posX = traversal.currentX();
               double posY = traversal.currentY();
               double posZ = traversal.currentZ();
               long currentBlockKey = BlockPos.asLong(traversal.blockX, traversal.blockY, traversal.blockZ);
               if (currentBlockKey != ignoredSource && currentBlockKey != ignoredReceiver) {
                  int chunkX = traversal.blockX >> 4;
                  int chunkZ = traversal.blockZ >> 4;
                  int sectionIndex = traversal.blockY - minBuildHeight >> 4;
                  if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
                     cachedChunk = chunkSource.getChunkNow(chunkX, chunkZ);
                     cachedSections = cachedChunk != null ? cachedChunk.getSections() : null;
                     cachedChunkX = chunkX;
                     cachedChunkZ = chunkZ;
                     cachedSectionIndex = Integer.MIN_VALUE;
                  }

                  if (cachedChunk != null) {
                     if (sectionIndex != cachedSectionIndex) {
                        if (cachedSections != null && sectionIndex >= 0 && sectionIndex < cachedSections.length) {
                           cachedSection = cachedSections[sectionIndex];
                           sectionIsAir = cachedSection == null || cachedSection.hasOnlyAir();
                        } else {
                           cachedSection = null;
                           sectionIsAir = true;
                        }

                        cachedSectionIndex = sectionIndex;
                     }

                     if (sectionIsAir) {
                        double nextTraveled = SectionSkip.skipEmptySection(traversal, chunkX, chunkZ, sectionIndex, minBuildHeight, maxDistance, 0.01);
                        if (nextTraveled >= maxDistance) {
                           break;
                        }
                        continue;
                     }

                     if (!sectionIsAir && cachedSection != null) {
                        BlockState state = cachedSection.getBlockState(traversal.blockX & 15, traversal.blockY & 15, traversal.blockZ & 15);
                        if (!state.isAir()) {
                           NeoShadowsTypes.Box[] boxes = BOX_CACHE.get(state);
                           if (boxes == null) {
                              cursor.set(traversal.blockX, traversal.blockY, traversal.blockZ);
                              NeoShadowsTypes.Box[] built = buildBoxes(state, level, cursor);
                              NeoShadowsTypes.Box[] existing = BOX_CACHE.putIfAbsent(state, built);
                              boxes = existing != null ? existing : built;
                           }

                           if (boxes == FULL_BLOCK_BOXES) {
                              out.blockKey = BlockPos.asLong(traversal.blockX, traversal.blockY, traversal.blockZ);
                              out.boxes = boxes;
                              return true;
                           }

                           if (boxes.length == 1) {
                              if (boxes[0]
                                 .intersectsSegmentWithOffset(start.x, start.y, start.z, endX, endY, endZ, traversal.blockX, traversal.blockY, traversal.blockZ)
                                 )
                               {
                                 out.blockKey = BlockPos.asLong(traversal.blockX, traversal.blockY, traversal.blockZ);
                                 out.boxes = boxes;
                                 return true;
                              }
                           } else if (boxes.length > 1) {
                              for (NeoShadowsTypes.Box box : boxes) {
                                 if (box.intersectsSegmentWithOffset(
                                    start.x, start.y, start.z, endX, endY, endZ, traversal.blockX, traversal.blockY, traversal.blockZ
                                 )) {
                                    out.blockKey = BlockPos.asLong(traversal.blockX, traversal.blockY, traversal.blockZ);
                                    out.boxes = boxes;
                                    return true;
                                 }
                              }
                           }
                        }
                     }
                  }
               }

               double nextTraveled = SectionSkip.nextBoundaryDistance(traversal);
               SectionSkip.advanceTo(traversal, nextTraveled, 1.0E-9);
            }

            return false;
         }
      }

      private static NeoShadowsTypes.Box[] buildBoxes(BlockState state, ClientLevel level, BlockPos pos) {
         VoxelShape shape = state.getCollisionShape(level, pos);
         if (shape.isEmpty()) {
            return NO_BOXES;
         } else if (state.isCollisionShapeFullBlock(level, pos)) {
            return FULL_BLOCK_BOXES;
         } else {
            List<NeoShadowsTypes.Box> boxes = new ArrayList<>();
            shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
               if (maxX - minX > 1.0E-6 && maxY - minY > 1.0E-6 && maxZ - minZ > 1.0E-6) {
                  boxes.add(new NeoShadowsTypes.Box(minX, minY, minZ, maxX, maxY, maxZ));
               }
            });
            return boxes.isEmpty() ? NO_BOXES : boxes.toArray(new NeoShadowsTypes.Box[0]);
         }
      }
   }
}
