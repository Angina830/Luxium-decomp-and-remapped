package com.vinlanx.luxium.client;

import com.vinlanx.luxium.rtx.LightRtMath;
import com.vinlanx.luxium.rtx.NeoFloodEngine;
import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class NeoShadowsVisibility {
   private static final int SPARSE_TOUCHED_SECTION_THRESHOLD = 24;
   private static final ThreadLocal<NeoShadowsTypes.ContributorCollector> CONTRIBUTOR_COLLECTOR_TL = ThreadLocal.withInitial(
      NeoShadowsTypes.ContributorCollector::new
   );
   private static final ThreadLocal<NeoShadowsTypes.FaceLightingScratch> FACE_LIGHTING_SCRATCH_TL = ThreadLocal.withInitial(
      NeoShadowsTypes.FaceLightingScratch::new
   );
   private static final ThreadLocal<NeoShadowsVisibility.TouchedSectionScratch> TOUCHED_SECTION_SCRATCH_TL = ThreadLocal.withInitial(
      NeoShadowsVisibility.TouchedSectionScratch::new
   );
   private final NeoShadowsEngine engine;
   static final long LIT_SAMPLE_CACHE_EMPTY = Long.MAX_VALUE;

   NeoShadowsVisibility(NeoShadowsEngine engine) {
      this.engine = engine;
   }

   List<NeoShadowsTypes.FaceCandidate> buildVisibleFaceCandidates(
      ClientLevel level, BlockPos center, TorchRtxState torchState, ExposedFaceService exposedFaceService, NeoShadowsTypes.GeometryBuildState state
   ) {
      double maxLightReach = Math.min(LightRtMath.getMaxDistance(15), 30.0);
      double sourceSearchRadiusSq = (36.0 + maxLightReach) * (36.0 + maxLightReach);
      Vec3 centerVec = Vec3.atCenterOf(center);
      long[] sourcesInRange = torchState.getClosestSources(centerVec, sourceSearchRadiusSq, 256);
      state.sourceCount = sourcesInRange.length;
      if (sourcesInRange.length == 0) {
         return Collections.emptyList();
      } else {
         double renderRadiusSq = 1296.0;
         long[] frontierPositions = torchState.getFrontierPositions(center, sourceSearchRadiusSq);
         if (frontierPositions.length == 0) {
            return Collections.emptyList();
         } else {
            NeoShadowsTypes.VisibleCandidateBlockSnapshot snapshot = this.collectVisibleCandidateBlockSnapshot(
               level, center, exposedFaceService, frontierPositions, renderRadiusSq
            );
            return snapshot.candidates.length == 0 ? Collections.emptyList() : snapshot.asList();
         }
      }
   }

   NeoShadowsTypes.VisibleCandidateBlockSnapshot collectVisibleCandidateBlockSnapshot(
      ClientLevel level, BlockPos center, ExposedFaceService exposedFaceService, long[] frontierPositions, double renderRadiusSq
   ) {
      long frontierSignature = hashFrontierPositions(frontierPositions);
      NeoShadowsTypes.VisibleCandidateBlockSnapshot cached = this.engine.visibleCandidateSnapshot;
      if (cached != null && cached.matches(center, frontierSignature, frontierPositions)) {
         return cached;
      } else {
         int centerX = center.getX();
         int centerY = center.getY();
         int centerZ = center.getZ();
         int limit = 12288;
         Long2ObjectOpenHashMap<NeoShadowsVisibility.TouchedSectionData> touchedSections = this.collectTouchedReceiverSections(
            level, exposedFaceService, frontierPositions, centerX, centerY, centerZ, renderRadiusSq
         );
         if (touchedSections.isEmpty()) {
            NeoShadowsTypes.VisibleCandidateBlockSnapshot empty = NeoShadowsTypes.VisibleCandidateBlockSnapshot.empty(
               center, frontierSignature, frontierPositions
            );
            this.engine.visibleCandidateSnapshot = empty;
            return empty;
         } else {
            NeoShadowsVisibility.NearestFaceCandidateCollector candidates = new NeoShadowsVisibility.NearestFaceCandidateCollector(limit);
            ObjectIterator sortedCandidates = touchedSections.long2ObjectEntrySet().iterator();

            while (sortedCandidates.hasNext()) {
               Entry<NeoShadowsVisibility.TouchedSectionData> entry = (Entry<NeoShadowsVisibility.TouchedSectionData>)sortedCandidates.next();
               this.appendSectionReceiverCandidates(
                  candidates, level, exposedFaceService, entry.getLongKey(), (NeoShadowsVisibility.TouchedSectionData)entry.getValue(), center
               );
            }

            NeoShadowsTypes.FaceCandidate[] sortedCandidatesx = candidates.toSortedArray();
            if (sortedCandidatesx.length == 0) {
               NeoShadowsTypes.VisibleCandidateBlockSnapshot empty = NeoShadowsTypes.VisibleCandidateBlockSnapshot.empty(
                  center, frontierSignature, frontierPositions
               );
               this.engine.visibleCandidateSnapshot = empty;
               return empty;
            } else {
               NeoShadowsTypes.VisibleCandidateBlockSnapshot snapshot = new NeoShadowsTypes.VisibleCandidateBlockSnapshot(
                  center.immutable(), frontierSignature, Arrays.copyOf(frontierPositions, frontierPositions.length), sortedCandidatesx
               );
               this.engine.visibleCandidateSnapshot = snapshot;
               return snapshot;
            }
         }
      }
   }

   Long2ObjectOpenHashMap<NeoShadowsVisibility.TouchedSectionData> collectTouchedReceiverSections(
      ClientLevel level, ExposedFaceService exposedFaceService, long[] frontierPositions, int centerX, int centerY, int centerZ, double renderRadiusSq
   ) {
      NeoShadowsVisibility.TouchedSectionScratch scratch = TOUCHED_SECTION_SCRATCH_TL.get();
      scratch.reset();
      Long2ObjectOpenHashMap<NeoShadowsVisibility.TouchedSectionData> touchedSections = scratch.touchedSections;

      for (long frontierKey : frontierPositions) {
         int frontierX = BlockPos.getX(frontierKey);
         int frontierY = BlockPos.getY(frontierKey);
         int frontierZ = BlockPos.getZ(frontierKey);
         long lastSectionKey = Long.MIN_VALUE;
         NeoShadowsVisibility.TouchedSectionData lastTouchedSection = null;
         ExposedFaceService.SectionFaceSnapshot lastSectionSnapshot = ExposedFaceService.SectionFaceSnapshot.empty();
         boolean lastSectionHasSnapshot = false;
         boolean lastSectionRejectAll = false;

         for (int[] offset : NeoShadowsEngine.RECEIVER_OFFSETS) {
            int bx = frontierX + offset[0];
            int by = frontierY + offset[1];
            int bz = frontierZ + offset[2];
            double dx = (double)(bx - centerX);
            double dy = (double)(by - centerY);
            double dz = (double)(bz - centerZ);
            if (!(dx * dx + dy * dy + dz * dz > renderRadiusSq)) {
               long sectionKey = SectionPos.asLong(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(by), SectionPos.blockToSectionCoord(bz));
               if (sectionKey != lastSectionKey) {
                  lastSectionHasSnapshot = exposedFaceService.isSectionFullyCaptured(sectionKey) && exposedFaceService.hasSectionFaceSnapshot(sectionKey);
                  lastSectionSnapshot = lastSectionHasSnapshot
                     ? exposedFaceService.snapshotSectionFaces(level, sectionKey)
                     : ExposedFaceService.SectionFaceSnapshot.empty();
                  lastSectionRejectAll = lastSectionHasSnapshot && !lastSectionSnapshot.hasCandidateReceivers();
                  lastSectionKey = sectionKey;
                  lastTouchedSection = null;
               }

               if (!lastSectionRejectAll) {
                  int localIndex = localSectionBlockIndex(bx & 15, by & 15, bz & 15);
                  if (!lastSectionHasSnapshot || lastSectionSnapshot.isRenderableSolid(localIndex)) {
                     NeoShadowsVisibility.TouchedSectionData touchedSection = lastTouchedSection;
                     if (lastTouchedSection == null) {
                        touchedSection = (NeoShadowsVisibility.TouchedSectionData)touchedSections.get(sectionKey);
                        if (touchedSection == null) {
                           touchedSection = scratch.rentTouchedSection();
                           touchedSections.put(sectionKey, touchedSection);
                        }

                        lastTouchedSection = touchedSection;
                     }

                     touchedSection.add(localIndex, scratch);
                  }
               }
            }
         }
      }

      return touchedSections;
   }

   void appendSectionReceiverCandidates(
      NeoShadowsVisibility.NearestFaceCandidateCollector candidates,
      ClientLevel level,
      ExposedFaceService exposedFaceService,
      long sectionKey,
      NeoShadowsVisibility.TouchedSectionData touchedSection,
      BlockPos center
   ) {
      int sectionBaseX = SectionPos.x(sectionKey) << 4;
      int sectionBaseY = SectionPos.y(sectionKey) << 4;
      int sectionBaseZ = SectionPos.z(sectionKey) << 4;
      this.appendTouchedFaceMaskFallback(candidates, level, exposedFaceService, sectionBaseX, sectionBaseY, sectionBaseZ, touchedSection, center);
   }

   void appendTouchedFaceMaskFallback(
      NeoShadowsVisibility.NearestFaceCandidateCollector candidates,
      ClientLevel level,
      ExposedFaceService exposedFaceService,
      int sectionBaseX,
      int sectionBaseY,
      int sectionBaseZ,
      NeoShadowsVisibility.TouchedSectionData touchedSection,
      BlockPos center
   ) {
      if (touchedSection.isDense()) {
         NeoShadowsVisibility.DenseTouchedBits denseBits = touchedSection.denseBits();

         for (int dirtyIndex = 0; dirtyIndex < denseBits.dirtyWordCount; dirtyIndex++) {
            int wordIndex = denseBits.dirtyWordIndices[dirtyIndex];
            long remainingBits = denseBits.words[wordIndex];

            while (remainingBits != 0L) {
               int bitIndex = Long.numberOfTrailingZeros(remainingBits);
               remainingBits &= remainingBits - 1L;
               int localIndex = (wordIndex << 6) + bitIndex;
               int localY = localIndex >>> 8;
               int localZ = localIndex >>> 4 & 15;
               int localX = localIndex & 15;
               long blockKey = BlockPos.asLong(sectionBaseX + localX, sectionBaseY + localY, sectionBaseZ + localZ);
               this.appendReceiverSurfaceCandidates(candidates, exposedFaceService, level, blockKey, center);
            }
         }
      } else {
         for (int index = 0; index < touchedSection.sparseCount(); index++) {
            int localIndex = touchedSection.sparseIndexAt(index);
            int localY = localIndex >>> 8;
            int localZ = localIndex >>> 4 & 15;
            int localX = localIndex & 15;
            long blockKey = BlockPos.asLong(sectionBaseX + localX, sectionBaseY + localY, sectionBaseZ + localZ);
            this.appendReceiverSurfaceCandidates(candidates, exposedFaceService, level, blockKey, center);
         }
      }
   }

   void appendReceiverSurfaceCandidates(
      NeoShadowsVisibility.NearestFaceCandidateCollector candidates, ExposedFaceService exposedFaceService, ClientLevel level, long blockKey, BlockPos center
   ) {
      BlockPos pos = BlockPos.of(blockKey);
      double dx = (double)(pos.getX() - center.getX());
      double dy = (double)(pos.getY() - center.getY());
      double dz = (double)(pos.getZ() - center.getZ());
      double distanceSq = dx * dx + dy * dy + dz * dz;
      if (!candidates.canRejectBlock(distanceSq)) {
         exposedFaceService.forEachCompleteReceiverSurface(level, blockKey, (face, planeCoordinate, minU, maxU, minV, maxV, surfaceKey) -> {
            double worldPlaneCoordinate = switch (face) {
               case UP, DOWN -> (double)pos.getY() + planeCoordinate;
               case NORTH, SOUTH -> (double)pos.getZ() + planeCoordinate;
               case WEST, EAST -> (double)pos.getX() + planeCoordinate;
               default -> throw new IncompatibleClassChangeError();
            };
            candidates.offer(pos, face, distanceSq, worldPlaneCoordinate, minU, maxU, minV, maxV, surfaceKey);
         });
      }
   }

   void appendReceiverSurfaceCandidates(
      List<NeoShadowsTypes.FaceCandidate> candidates, ExposedFaceService exposedFaceService, ClientLevel level, long blockKey, BlockPos center
   ) {
      BlockPos pos = BlockPos.of(blockKey);
      double dx = (double)(pos.getX() - center.getX());
      double dy = (double)(pos.getY() - center.getY());
      double dz = (double)(pos.getZ() - center.getZ());
      double distanceSq = dx * dx + dy * dy + dz * dz;
      exposedFaceService.forEachCompleteReceiverSurface(
         level,
         blockKey,
         (face, planeCoordinate, minU, maxU, minV, maxV, surfaceKey) -> {
            double worldPlaneCoordinate = switch (face) {
               case UP, DOWN -> (double)pos.getY() + planeCoordinate;
               case NORTH, SOUTH -> (double)pos.getZ() + planeCoordinate;
               case WEST, EAST -> (double)pos.getX() + planeCoordinate;
               default -> throw new IncompatibleClassChangeError();
            };
            candidates.add(
               new NeoShadowsTypes.FaceCandidate(pos, face, distanceSq, worldPlaneCoordinate, new NeoShadowsTypes.PlaneRect(minU, maxU, minV, maxV), surfaceKey)
            );
         }
      );
   }

   void appendLocalSectionFallbackCandidates(
      List<NeoShadowsTypes.FaceCandidate> candidates,
      ClientLevel level,
      ExposedFaceService exposedFaceService,
      long sectionKey,
      int minX,
      int maxX,
      int minY,
      int maxY,
      int minZ,
      int maxZ,
      BlockPos center
   ) {
      int sectionBaseX = SectionPos.x(sectionKey) << 4;
      int sectionBaseY = SectionPos.y(sectionKey) << 4;
      int sectionBaseZ = SectionPos.z(sectionKey) << 4;
      if (exposedFaceService.isSectionFullyCaptured(sectionKey) && exposedFaceService.hasSectionFaceSnapshot(sectionKey)) {
         this.appendLocalSectionSnapshotCandidates(
            candidates, level, exposedFaceService, sectionKey, sectionBaseX, sectionBaseY, sectionBaseZ, minX, maxX, minY, maxY, minZ, maxZ, center
         );
      } else {
         int sectionMinX = Math.max(minX, sectionBaseX);
         int sectionMaxX = Math.min(maxX, sectionBaseX + 15);
         int sectionMinY = Math.max(minY, sectionBaseY);
         int sectionMaxY = Math.min(maxY, sectionBaseY + 15);
         int sectionMinZ = Math.max(minZ, sectionBaseZ);
         int sectionMaxZ = Math.min(maxZ, sectionBaseZ + 15);

         for (int y = sectionMinY; y <= sectionMaxY; y++) {
            for (int z = sectionMinZ; z <= sectionMaxZ; z++) {
               for (int x = sectionMinX; x <= sectionMaxX; x++) {
                  long blockKey = BlockPos.asLong(x, y, z);
                  this.appendReceiverSurfaceCandidates(candidates, exposedFaceService, level, blockKey, center);
               }
            }
         }
      }
   }

   void appendLocalSectionSnapshotCandidates(
      List<NeoShadowsTypes.FaceCandidate> candidates,
      ClientLevel level,
      ExposedFaceService exposedFaceService,
      long sectionKey,
      int sectionBaseX,
      int sectionBaseY,
      int sectionBaseZ,
      int minX,
      int maxX,
      int minY,
      int maxY,
      int minZ,
      int maxZ,
      BlockPos center
   ) {
      ExposedFaceService.SectionFaceSnapshot snapshot = exposedFaceService.snapshotSectionFaces(level, sectionKey);
      if (snapshot.hasCandidateReceivers()) {
         int sectionMinX = Math.max(minX, sectionBaseX);
         int sectionMaxX = Math.min(maxX, sectionBaseX + 15);
         int sectionMinY = Math.max(minY, sectionBaseY);
         int sectionMaxY = Math.min(maxY, sectionBaseY + 15);
         int sectionMinZ = Math.max(minZ, sectionBaseZ);
         int sectionMaxZ = Math.min(maxZ, sectionBaseZ + 15);
         if (sectionMinX <= sectionMaxX && sectionMinY <= sectionMaxY && sectionMinZ <= sectionMaxZ) {
            for (int wordIndex = 0; wordIndex < snapshot.wordCount(); wordIndex++) {
               long remainingBits = snapshot.word(wordIndex);

               while (remainingBits != 0L) {
                  int bitIndex = Long.numberOfTrailingZeros(remainingBits);
                  remainingBits &= remainingBits - 1L;
                  int localIndex = (wordIndex << 6) + bitIndex;
                  int localY = localIndex >>> 8;
                  int worldY = sectionBaseY + localY;
                  if (worldY >= sectionMinY && worldY <= sectionMaxY) {
                     int localZ = localIndex >>> 4 & 15;
                     int worldZ = sectionBaseZ + localZ;
                     if (worldZ >= sectionMinZ && worldZ <= sectionMaxZ) {
                        int localX = localIndex & 15;
                        int worldX = sectionBaseX + localX;
                        if (worldX >= sectionMinX && worldX <= sectionMaxX) {
                           this.appendReceiverSurfaceCandidates(candidates, exposedFaceService, level, BlockPos.asLong(worldX, worldY, worldZ), center);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   List<NeoShadowsTypes.FaceCandidate> buildLocalFaceCandidates(ClientLevel level, BlockPos center, int localRadius, ExposedFaceService exposedFaceService) {
      List<NeoShadowsTypes.FaceCandidate> candidates = new ArrayList<>();
      int minX = center.getX() - localRadius;
      int maxX = center.getX() + localRadius;
      int minY = center.getY() - localRadius;
      int maxY = center.getY() + localRadius;
      int minZ = center.getZ() - localRadius;
      int maxZ = center.getZ() + localRadius;
      int minSectionX = SectionPos.blockToSectionCoord(minX);
      int maxSectionX = SectionPos.blockToSectionCoord(maxX);
      int minSectionY = SectionPos.blockToSectionCoord(minY);
      int maxSectionY = SectionPos.blockToSectionCoord(maxY);
      int minSectionZ = SectionPos.blockToSectionCoord(minZ);
      int maxSectionZ = SectionPos.blockToSectionCoord(maxZ);

      for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
         for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
               long sectionKey = SectionPos.asLong(sectionX, sectionY, sectionZ);
               this.appendLocalSectionFallbackCandidates(candidates, level, exposedFaceService, sectionKey, minX, maxX, minY, maxY, minZ, maxZ, center);
            }
         }
      }

      return candidates;
   }

   NeoShadowsTypes.FaceLightingInfo resolveFaceLightingCached(
      TorchRtxState torchState, @Nullable NeoShadowsTypes.GeometryBuildState state, NeoShadowsTypes.FaceCandidate candidate
   ) {
      if (state == null) {
         return this.resolveFaceLighting(torchState, candidate, null);
      } else {
         Long2ObjectOpenHashMap<NeoShadowsTypes.FaceLightingInfo> faceCache;
         synchronized (state.faceLightingCache) {
            faceCache = state.faceLightingCache.get(candidate.face);
            if (faceCache == null) {
               faceCache = new Long2ObjectOpenHashMap();
               state.faceLightingCache.put((Enum)candidate.face, faceCache);
            }
         }

         long receiverKey = candidate.receiverKey;
         synchronized (faceCache) {
            NeoShadowsTypes.FaceLightingInfo cached = (NeoShadowsTypes.FaceLightingInfo)faceCache.get(receiverKey);
            if (cached != null) {
               return cached;
            }
         }

         NeoShadowsTypes.FaceLightingInfo resolved = this.resolveFaceLighting(torchState, candidate, state);
         synchronized (faceCache) {
            NeoShadowsTypes.FaceLightingInfo existing = (NeoShadowsTypes.FaceLightingInfo)faceCache.get(receiverKey);
            if (existing != null) {
               return existing;
            } else {
               faceCache.put(receiverKey, resolved);
               return resolved;
            }
         }
      }
   }

   NeoShadowsTypes.FaceLightingInfo resolveFaceLighting(
      TorchRtxState torchState, NeoShadowsTypes.FaceCandidate candidate, @Nullable NeoShadowsTypes.GeometryBuildState state
   ) {
      BlockPos pos = candidate.pos;
      Direction face = candidate.face;
      NeoShadowsTypes.PlaneRect receiverBounds = candidate.localBounds;
      NeoShadowsTypes.FaceLightingScratch lightingScratch = FACE_LIGHTING_SCRATCH_TL.get();
      Long2ObjectOpenHashMap<NeoShadowsTypes.SourceScore> scoresBySource = lightingScratch.scores;
      scoresBySource.clear();
      long ownPosKey = pos.asLong();
      NeoShadowsTypes.UvPoint[] probes = buildLightingFaceProbes(face);
      NeoShadowsTypes.PlaneRect[] probeRects = buildLightingProbeRects(face);
      NeoShadowsTypes.ContributorScoringVisitor visitor = lightingScratch.visitor;
      visitor.bind(scoresBySource, ownPosKey);

      for (int probeIndex = 0; probeIndex < probes.length; probeIndex++) {
         NeoShadowsTypes.UvPoint probe = probes[probeIndex];
         NeoShadowsTypes.PlaneRect probeRect = probeRects[probeIndex];
         if (NeoShadowsProjection.isProbeInsideRect(probe, receiverBounds)) {
            double sampleX = NeoShadowsProjection.facePointX(pos, face, candidate.planeCoordinate, (float)probe.u);
            double sampleY = NeoShadowsProjection.facePointY(pos, face, candidate.planeCoordinate, (float)probe.v);
            double sampleZ = NeoShadowsProjection.facePointZ(pos, face, candidate.planeCoordinate, (float)probe.u, (float)probe.v);
            long lightSamplePosKey = this.resolveLightSamplePosKey(torchState, pos, face, sampleX, sampleY, sampleZ, state);
            if (lightSamplePosKey != Long.MIN_VALUE) {
               visitor.setProbe(sampleX, sampleY, sampleZ, lightSamplePosKey, probeRect);
               this.forEachContributorCached(torchState, lightSamplePosKey, state, visitor);
            }
         }
      }

      NeoShadowsTypes.SourceScore bestScore = null;
      double totalScore = 0.0;
      ObjectIterator var53 = scoresBySource.values().iterator();

      while (var53.hasNext()) {
         NeoShadowsTypes.SourceScore score = (NeoShadowsTypes.SourceScore)var53.next();
         totalScore += score.totalScore;
         if (bestScore == null
            || score.totalScore > bestScore.totalScore
            || score.totalScore == bestScore.totalScore && score.sampleCount > bestScore.sampleCount
            || score.totalScore == bestScore.totalScore && score.sampleCount == bestScore.sampleCount && score.bestDistanceSq < bestScore.bestDistanceSq) {
            bestScore = score;
         }
      }

      if (bestScore == null) {
         float centerU = (float)((receiverBounds.minU + receiverBounds.maxU) * 0.5);
         float centerV = (float)((receiverBounds.minV + receiverBounds.maxV) * 0.5);
         double sampleX = NeoShadowsProjection.facePointX(pos, face, candidate.planeCoordinate, centerU);
         double sampleY = NeoShadowsProjection.facePointY(pos, face, candidate.planeCoordinate, centerV);
         double sampleZ = NeoShadowsProjection.facePointZ(pos, face, candidate.planeCoordinate, centerU, centerV);
         double maxLightReach = Math.min(LightRtMath.getMaxDistance(15), 30.0);
         long[] closestSources = torchState.getClosestSources(new Vec3(sampleX, sampleY, sampleZ), maxLightReach * maxLightReach, 4);
         if (closestSources.length == 0) {
            return NeoShadowsTypes.FaceLightingInfo.empty();
         } else {
            long fakeSampleKey = candidate.receiverKey;
            List<NeoShadowsTypes.SourceContribution> fallbackContribs = new ArrayList<>(closestSources.length);
            double[] fallbackScores = new double[closestSources.length];
            double totalFallbackScore = 0.0;
            long bestFallbackSourceKey = Long.MIN_VALUE;
            int bestFallbackEmission = 0;
            double bestFallbackScore = Double.NEGATIVE_INFINITY;

            for (int index = 0; index < closestSources.length; index++) {
               long sourceKey = closestSources[index];
               int sourceEmission = torchState.getSourceEmission(sourceKey);
               if (sourceEmission > 0) {
                  double dx = (double)BlockPos.getX(sourceKey) + 0.5 - sampleX;
                  double dy = (double)BlockPos.getY(sourceKey) + 0.5 - sampleY;
                  double dz = (double)BlockPos.getZ(sourceKey) + 0.5 - sampleZ;
                  double score = 1.0 / Math.max(0.25, dx * dx + dy * dy + dz * dz);
                  fallbackScores[index] = score;
                  totalFallbackScore += score;
                  if (score > bestFallbackScore) {
                     bestFallbackScore = score;
                     bestFallbackSourceKey = sourceKey;
                     bestFallbackEmission = sourceEmission;
                  }
               }
            }

            if (!(totalFallbackScore <= 0.0) && bestFallbackSourceKey != Long.MIN_VALUE) {
               for (int indexx = 0; indexx < closestSources.length; indexx++) {
                  long sourceKey = closestSources[indexx];
                  double score = fallbackScores[indexx];
                  if (!(score <= 0.0)) {
                     int sourceEmission = torchState.getSourceEmission(sourceKey);
                     if (sourceEmission > 0) {
                        float weight = (float)(score / totalFallbackScore);
                        if (!(weight < 0.03F)) {
                           fallbackContribs.add(new NeoShadowsTypes.SourceContribution(sourceKey, fakeSampleKey, sourceEmission, weight, receiverBounds));
                        }
                     }
                  }
               }

               return fallbackContribs.isEmpty()
                  ? NeoShadowsTypes.FaceLightingInfo.empty()
                  : new NeoShadowsTypes.FaceLightingInfo(fakeSampleKey, bestFallbackSourceKey, bestFallbackEmission, fallbackContribs);
            } else {
               return NeoShadowsTypes.FaceLightingInfo.empty();
            }
         }
      } else {
         List<NeoShadowsTypes.SourceContribution> contributions;
         if (scoresBySource.size() != 1 && !(totalScore <= 0.0)) {
            contributions = new ArrayList<>(scoresBySource.size());
            double keptTotal = 0.0;
            ObjectIterator invKeptTotal = scoresBySource.values().iterator();

            while (invKeptTotal.hasNext()) {
               NeoShadowsTypes.SourceScore score = (NeoShadowsTypes.SourceScore)invKeptTotal.next();
               double weight = score.totalScore / totalScore;
               if (!(weight < 0.03)) {
                  keptTotal += weight;
               }
            }

            if (keptTotal <= 0.0) {
               contributions = Collections.singletonList(
                  new NeoShadowsTypes.SourceContribution(
                     bestScore.sourceKey, bestScore.representativeSamplePosKey, bestScore.bestEmission, 1.0F, receiverBounds
                  )
               );
            } else {
               double invKeptTotalx = 1.0 / keptTotal;
               ObjectIterator var62 = scoresBySource.values().iterator();

               while (var62.hasNext()) {
                  NeoShadowsTypes.SourceScore score = (NeoShadowsTypes.SourceScore)var62.next();
                  double weight = score.totalScore / totalScore;
                  if (!(weight < 0.03)) {
                     contributions.add(
                        new NeoShadowsTypes.SourceContribution(
                           score.sourceKey,
                           score.representativeSamplePosKey,
                           score.bestEmission,
                           (float)(weight * invKeptTotalx),
                           intersectPlaneRects(receiverBounds, score.contributionBounds())
                        )
                     );
                  }
               }
            }
         } else {
            contributions = Collections.singletonList(
               new NeoShadowsTypes.SourceContribution(bestScore.sourceKey, bestScore.representativeSamplePosKey, bestScore.bestEmission, 1.0F, receiverBounds)
            );
         }

         return new NeoShadowsTypes.FaceLightingInfo(bestScore.representativeSamplePosKey, bestScore.sourceKey, bestScore.bestEmission, contributions);
      }
   }

   long resolveLightSamplePosKey(
      TorchRtxState torchState,
      BlockPos pos,
      Direction face,
      double sampleX,
      double sampleY,
      double sampleZ,
      @Nullable NeoShadowsTypes.GeometryBuildState state
   ) {
      long basePosKey = NeoShadowsProjection.sampleRtxPosKey(pos, face, sampleX, sampleY, sampleZ);
      Long2LongOpenHashMap cache = null;
      if (state != null) {
         int ord = face.ordinal();
         cache = state.litSampleCache[ord];
         if (cache == null) {
            synchronized (state.litSampleCache) {
               cache = state.litSampleCache[ord];
               if (cache == null) {
                  cache = new Long2LongOpenHashMap();
                  cache.defaultReturnValue(Long.MAX_VALUE);
                  state.litSampleCache[ord] = cache;
               }
            }
         }

         long cached;
         synchronized (cache) {
            cached = cache.get(basePosKey);
         }

         if (cached != Long.MAX_VALUE) {
            return cached;
         }
      }

      Direction axisU = NeoShadowsProjection.firstFaceTangent(face);
      Direction axisV = NeoShadowsProjection.secondFaceTangent(face);
      int ux = axisU.getStepX();
      int uy = axisU.getStepY();
      int uz = axisU.getStepZ();
      int vx = axisV.getStepX();
      int vy = axisV.getStepY();
      int vz = axisV.getStepZ();
      int searchRadius = NeoShadowsProjection.isHorizontalReceiverFace(face) ? 2 : 1;
      long result = torchState.findNearestLitSample(basePosKey, ux, uy, uz, vx, vy, vz, searchRadius, sampleX, sampleY, sampleZ);
      if (cache != null) {
         synchronized (cache) {
            if (cache.get(basePosKey) == Long.MAX_VALUE) {
               cache.put(basePosKey, result);
            }
         }
      }

      return result;
   }

   void forEachContributorCached(
      TorchRtxState torchState, long posKey, @Nullable NeoShadowsTypes.GeometryBuildState state, NeoFloodEngine.ContributorVisitor visitor
   ) {
      if (state == null) {
         torchState.forEachContributor(posKey, visitor);
      } else {
         NeoShadowsTypes.ContributorList list;
         synchronized (state.contributorCache) {
            list = (NeoShadowsTypes.ContributorList)state.contributorCache.get(posKey);
         }

         if (list == null) {
            NeoShadowsTypes.ContributorCollector collector = CONTRIBUTOR_COLLECTOR_TL.get();
            collector.reset();
            torchState.forEachContributor(posKey, collector);
            list = collector.toList();
            synchronized (state.contributorCache) {
               NeoShadowsTypes.ContributorList existing = (NeoShadowsTypes.ContributorList)state.contributorCache.get(posKey);
               if (existing != null) {
                  list = existing;
               } else {
                  state.contributorCache.put(posKey, list);
               }
            }
         }

         int n = list.count;
         if (n != 0) {
            long[] keys = list.sourceKeys;
            int[] pack = list.packed;

            for (int index = 0; index < n; index++) {
               int p = pack[index];
               int light = p & 0xFF;
               int coverage = p >>> 8 & 0xFF;
               int emission = p >>> 16 & 0xFF;
               visitor.visit(keys[index], light, coverage, emission);
            }
         }
      }
   }

   static long hashFrontierPositions(long[] frontierPositions) {
      if (frontierPositions.length == 0) {
         return 0L;
      } else {
         long sum = -7046029254386353131L;
         long xor = -4417276706812531889L;
         long min = Long.MAX_VALUE;
         long max = Long.MIN_VALUE;

         for (long frontierKey : frontierPositions) {
            long mixed = mixFrontierKey(frontierKey);
            sum += mixed;
            xor ^= mixed;
            if (frontierKey < min) {
               min = frontierKey;
            }

            if (frontierKey > max) {
               max = frontierKey;
            }
         }

         return mixFrontierKey(sum)
            ^ Long.rotateLeft(mixFrontierKey(xor), 17)
            ^ mixFrontierKey(min)
            ^ Long.rotateLeft(mixFrontierKey(max), 33)
            ^ (long)frontierPositions.length;
      }
   }

   static long mixFrontierKey(long value) {
      value ^= value >>> 33;
      value *= -49064778989728563L;
      value ^= value >>> 33;
      value *= -4265267296055464877L;
      return value ^ value >>> 33;
   }

   static int localSectionBlockIndex(int localX, int localY, int localZ) {
      return localY << 8 | localZ << 4 | localX;
   }

   static NeoShadowsTypes.UvPoint[] buildLightingFaceProbes(Direction face) {
      return NeoShadowsProjection.isHorizontalReceiverFace(face) ? NeoShadowsEngine.HORIZONTAL_LIGHTING_FACE_PROBES : NeoShadowsEngine.LIGHTING_FACE_PROBES;
   }

   static NeoShadowsTypes.PlaneRect[] buildLightingProbeRects(Direction face) {
      return NeoShadowsProjection.isHorizontalReceiverFace(face)
         ? NeoShadowsEngine.HORIZONTAL_LIGHTING_FACE_PROBE_RECTS
         : NeoShadowsEngine.LIGHTING_FACE_PROBE_RECTS;
   }

   static NeoShadowsTypes.PlaneRect intersectPlaneRects(NeoShadowsTypes.PlaneRect first, NeoShadowsTypes.PlaneRect second) {
      double minU = Math.max(first.minU, second.minU);
      double maxU = Math.min(first.maxU, second.maxU);
      double minV = Math.max(first.minV, second.minV);
      double maxV = Math.min(first.maxV, second.maxV);
      return !(maxU - minU <= 1.0E-6) && !(maxV - minV <= 1.0E-6) ? new NeoShadowsTypes.PlaneRect(minU, maxU, minV, maxV) : first;
   }

   private static final class DenseTouchedBits {
      private final long[] words = new long[64];
      private final int[] dirtyWordIndices = new int[64];
      private int dirtyWordCount;

      void reset() {
         for (int index = 0; index < this.dirtyWordCount; index++) {
            this.words[this.dirtyWordIndices[index]] = 0L;
         }

         this.dirtyWordCount = 0;
      }

      void set(int localIndex) {
         int wordIndex = localIndex >>> 6;
         long mask = 1L << (localIndex & 63);
         long existing = this.words[wordIndex];
         if ((existing & mask) == 0L) {
            if (existing == 0L) {
               this.dirtyWordIndices[this.dirtyWordCount++] = wordIndex;
            }

            this.words[wordIndex] = existing | mask;
         }
      }
   }

   private static final class NearestFaceCandidateCollector {
      private final NeoShadowsTypes.FaceCandidate[] heap;
      private int size;

      NearestFaceCandidateCollector(int limit) {
         this.heap = new NeoShadowsTypes.FaceCandidate[Math.max(1, limit)];
      }

      boolean canRejectBlock(double distanceSq) {
         return this.size >= this.heap.length && distanceSq > this.heap[0].distanceSq;
      }

      void offer(BlockPos pos, Direction face, double distanceSq, double planeCoordinate, double minU, double maxU, double minV, double maxV, long surfaceKey) {
         NeoShadowsTypes.FaceCandidate candidate = new NeoShadowsTypes.FaceCandidate(
            pos, face, distanceSq, planeCoordinate, new NeoShadowsTypes.PlaneRect(minU, maxU, minV, maxV), surfaceKey
         );
         if (this.size < this.heap.length) {
            this.heap[this.size] = candidate;
            this.siftUp(this.size);
            this.size++;
         } else if (!(distanceSq >= this.heap[0].distanceSq)) {
            this.heap[0] = candidate;
            this.siftDown(0);
         }
      }

      NeoShadowsTypes.FaceCandidate[] toSortedArray() {
         NeoShadowsTypes.FaceCandidate[] result = Arrays.copyOf(this.heap, this.size);
         Arrays.sort(result, Comparator.comparingDouble(value -> value.distanceSq));
         return result;
      }

      private void siftUp(int index) {
         while (index > 0) {
            int parentIndex = index - 1 >>> 1;
            if (this.heap[parentIndex].distanceSq >= this.heap[index].distanceSq) {
               return;
            }

            NeoShadowsTypes.FaceCandidate temp = this.heap[parentIndex];
            this.heap[parentIndex] = this.heap[index];
            this.heap[index] = temp;
            index = parentIndex;
         }
      }

      private void siftDown(int index) {
         while (true) {
            int leftIndex = (index << 1) + 1;
            if (leftIndex >= this.size) {
               return;
            }

            int rightIndex = leftIndex + 1;
            int largestIndex = leftIndex;
            if (rightIndex < this.size && this.heap[rightIndex].distanceSq > this.heap[leftIndex].distanceSq) {
               largestIndex = rightIndex;
            }

            if (this.heap[index].distanceSq >= this.heap[largestIndex].distanceSq) {
               return;
            }

            NeoShadowsTypes.FaceCandidate temp = this.heap[index];
            this.heap[index] = this.heap[largestIndex];
            this.heap[largestIndex] = temp;
            index = largestIndex;
         }
      }
   }

   private static final class TouchedSectionData {
      private final short[] sparseIndices = new short[24];
      private int sparseCount;
      @Nullable
      private NeoShadowsVisibility.DenseTouchedBits denseBits;

      void reset() {
         this.sparseCount = 0;
         this.denseBits = null;
      }

      void add(int localIndex, NeoShadowsVisibility.TouchedSectionScratch scratch) {
         if (this.denseBits != null) {
            this.denseBits.set(localIndex);
         } else {
            short packed = (short)localIndex;

            for (int index = 0; index < this.sparseCount; index++) {
               if (this.sparseIndices[index] == packed) {
                  return;
               }
            }

            if (this.sparseCount < this.sparseIndices.length) {
               this.sparseIndices[this.sparseCount++] = packed;
            } else {
               NeoShadowsVisibility.DenseTouchedBits promoted = scratch.rentDenseBits();

               for (int indexx = 0; indexx < this.sparseCount; indexx++) {
                  promoted.set(this.sparseIndices[indexx] & '\uffff');
               }

               promoted.set(localIndex);
               this.denseBits = promoted;
               this.sparseCount = 0;
            }
         }
      }

      boolean isDense() {
         return this.denseBits != null;
      }

      NeoShadowsVisibility.DenseTouchedBits denseBits() {
         return this.denseBits;
      }

      int sparseCount() {
         return this.sparseCount;
      }

      int sparseIndexAt(int index) {
         return this.sparseIndices[index] & 65535;
      }
   }

   private static final class TouchedSectionScratch {
      final Long2ObjectOpenHashMap<NeoShadowsVisibility.TouchedSectionData> touchedSections = new Long2ObjectOpenHashMap();
      final List<NeoShadowsVisibility.TouchedSectionData> sectionPool = new ArrayList<>();
      final List<NeoShadowsVisibility.DenseTouchedBits> denseBitsetPool = new ArrayList<>();
      int usedSections;
      int usedDenseBitsets;

      NeoShadowsVisibility.TouchedSectionData rentTouchedSection() {
         NeoShadowsVisibility.TouchedSectionData touchedSection;
         if (this.usedSections < this.sectionPool.size()) {
            touchedSection = this.sectionPool.get(this.usedSections);
            touchedSection.reset();
         } else {
            touchedSection = new NeoShadowsVisibility.TouchedSectionData();
            this.sectionPool.add(touchedSection);
         }

         this.usedSections++;
         return touchedSection;
      }

      NeoShadowsVisibility.DenseTouchedBits rentDenseBits() {
         NeoShadowsVisibility.DenseTouchedBits denseBits;
         if (this.usedDenseBitsets < this.denseBitsetPool.size()) {
            denseBits = this.denseBitsetPool.get(this.usedDenseBitsets);
            denseBits.reset();
         } else {
            denseBits = new NeoShadowsVisibility.DenseTouchedBits();
            this.denseBitsetPool.add(denseBits);
         }

         this.usedDenseBitsets++;
         return denseBits;
      }

      void reset() {
         this.touchedSections.clear();
         this.usedSections = 0;
         this.usedDenseBitsets = 0;
      }
   }
}
