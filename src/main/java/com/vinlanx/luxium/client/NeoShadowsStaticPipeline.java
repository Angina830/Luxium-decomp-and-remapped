package com.vinlanx.luxium.client;

import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.FastColor.ARGB32;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class NeoShadowsStaticPipeline {
   private final NeoShadowsEngine engine;
   private static final ThreadLocal<RandomSource> RANDOM_SOURCE_TL = ThreadLocal.withInitial(RandomSource::create);

   NeoShadowsStaticPipeline(NeoShadowsEngine engine) {
      this.engine = engine;
   }

   NeoShadowsTypes.RealtimeStaticShadowOutput buildRealtimeStaticShadowOutput(ClientLevel level, BlockPos center) {
      return this.buildRealtimeStaticShadowOutput(level, center, null);
   }

   NeoShadowsTypes.RealtimeStaticShadowOutput buildRealtimeStaticShadowOutput(
      ClientLevel level, BlockPos center, @Nullable Consumer<List<NeoShadowsTypes.ShadowPolygon>> progressPublisher
   ) {
      ExposedFaceService exposedFaceService = ExposedFaceService.get();
      NeoShadowsTypes.GeometryBuildState state = new NeoShadowsTypes.GeometryBuildState(
         this.engine, level, center, exposedFaceService, false, DynamicShadowMeshCapture.Snapshot.empty()
      );
      TorchRtxState torchState = TorchRtxState.get();
      List<NeoShadowsTypes.FaceCandidate> candidates = this.engine.visibility.buildVisibleFaceCandidates(level, center, torchState, exposedFaceService, state);
      state.receiverFaceCount = candidates.size();
      List<NeoShadowsTypes.ReceiverPlaneGroup> planeGroups = candidates.isEmpty()
         ? Collections.emptyList()
         : this.buildReceiverPlaneGroups(torchState, candidates, state);
      sortPlaneGroupsByPriority(planeGroups, center);
      int groupCount = planeGroups.size();
      if (groupCount > 0) {
         int workerCount = Math.max(1, Math.min(NeoShadowsEngine.SHADOW_WORKER_COUNT, groupCount));
         NeoShadowsTypes.BuildScratch[] scratches = new NeoShadowsTypes.BuildScratch[workerCount];

         for (int i = 0; i < workerCount; i++) {
            scratches[i] = new NeoShadowsTypes.BuildScratch();
         }

         ExecutorService pool = workerCount > 1 ? NeoShadowsEngine.shadowWorkerPool() : null;
         if (pool == null) {
            NeoShadowsTypes.BuildScratch scratch = scratches[0];
            scratch.reset();

            for (int i = 0; i < groupCount && state.shadowPolygons.size() < 12288; i++) {
               this.processReceiverPlaneGroup(level, planeGroups.get(i), state, scratch);
            }

            NeoShadowsEngine.mergeScratch(state, scratch);
            if (progressPublisher != null) {
               progressPublisher.accept(state.shadowPolygons);
            }
         } else {
            int stripeSize = Math.max(workerCount, Math.max(1, groupCount / 4));

            for (int stripeStart = 0; stripeStart < groupCount && state.shadowPolygons.size() < 12288; stripeStart += stripeSize) {
               int stripeEnd = Math.min(stripeStart + stripeSize, groupCount);
               int activeWorkers = Math.min(workerCount, stripeEnd - stripeStart);
               AtomicInteger nextIdx = new AtomicInteger(stripeStart);
               CountDownLatch stripeLatch = new CountDownLatch(activeWorkers);

               for (int w = 0; w < activeWorkers; w++) {
                  scratches[w].reset();
               }

               for (int w = 0; w < activeWorkers; w++) {
                  NeoShadowsTypes.BuildScratch scratch = scratches[w];
                  pool.execute(() -> {
                     try {
                        int idx;
                        try {
                           while ((idx = nextIdx.getAndIncrement()) < stripeEnd && state.shadowPolygons.size() < 12288) {
                              this.processReceiverPlaneGroup(level, planeGroups.get(idx), state, scratch);
                           }
                        } catch (Throwable var12x) {
                        }
                     } finally {
                        stripeLatch.countDown();
                     }
                  });
               }

               try {
                  stripeLatch.await();
               } catch (InterruptedException var21) {
                  Thread.currentThread().interrupt();
                  break;
               }

               for (int w = 0; w < activeWorkers; w++) {
                  NeoShadowsEngine.mergeScratch(state, scratches[w]);
               }

               if (progressPublisher != null) {
                  progressPublisher.accept(state.shadowPolygons);
               }
            }
         }
      }

      return new NeoShadowsTypes.RealtimeStaticShadowOutput(
         center.immutable(),
         List.copyOf(state.shadowPolygons),
         List.copyOf(planeGroups),
         state.receiverFaceCount,
         state.sourceCount,
         state.occluderHitCount,
         state.shadowPolygonCount
      );
   }

   List<NeoShadowsTypes.ShadowPolygon> buildRealtimeChangedBlockShadowPolygons(ClientLevel level, LongOpenHashSet changedBlocks, int maxBlocks, int localRadius) {
      return this.buildRealtimeChangedBlockShadowOutput(level, changedBlocks, maxBlocks, localRadius).shadowPolygons;
   }

   NeoShadowsTypes.StaticHotPatchOutput buildRealtimeChangedBlockShadowOutput(ClientLevel level, LongOpenHashSet changedBlocks, int maxBlocks, int localRadius) {
      if (level != null && changedBlocks != null && !changedBlocks.isEmpty() && maxBlocks > 0 && localRadius >= 0) {
         ExposedFaceService exposedFaceService = ExposedFaceService.get();
         TorchRtxState torchState = TorchRtxState.get();
         List<NeoShadowsTypes.ShadowPolygon> output = new ArrayList<>();
         Long2ByteOpenHashMap affectedFacesByBlock = new Long2ByteOpenHashMap();
         affectedFacesByBlock.defaultReturnValue((byte)0);
         LongIterator iterator = changedBlocks.iterator();
         int processed = 0;

         for (NeoShadowsTypes.BuildScratch scratch = new NeoShadowsTypes.BuildScratch();
            iterator.hasNext() && processed < maxBlocks && output.size() < 12288;
            processed++
         ) {
            BlockPos center = BlockPos.of(iterator.nextLong());
            markImmediateHotPatchFaces(affectedFacesByBlock, center);
            NeoShadowsTypes.GeometryBuildState state = new NeoShadowsTypes.GeometryBuildState(
               this.engine, level, center, exposedFaceService, false, DynamicShadowMeshCapture.Snapshot.empty()
            );
            List<NeoShadowsTypes.FaceCandidate> candidates = this.engine.visibility.buildLocalFaceCandidates(level, center, localRadius, exposedFaceService);
            state.receiverFaceCount = candidates.size();
            markAffectedReceiverFaces(affectedFacesByBlock, candidates);
            if (!candidates.isEmpty()) {
               for (NeoShadowsTypes.ReceiverPlaneGroup group : this.buildReceiverPlaneGroups(torchState, candidates, state)) {
                  if (output.size() >= 12288) {
                     break;
                  }

                  scratch.reset();
                  this.processReceiverPlaneGroup(level, group, state, scratch);
                  if (!scratch.shadowPolygons.isEmpty()) {
                     output.addAll(scratch.shadowPolygons);
                  }
               }
            }
         }

         return affectedFacesByBlock.isEmpty()
            ? NeoShadowsTypes.StaticHotPatchOutput.EMPTY
            : new NeoShadowsTypes.StaticHotPatchOutput(affectedFacesByBlock, output.isEmpty() ? Collections.emptyList() : List.copyOf(output));
      } else {
         return NeoShadowsTypes.StaticHotPatchOutput.EMPTY;
      }
   }

   private static void markImmediateHotPatchFaces(Long2ByteOpenHashMap affectedFacesByBlock, BlockPos center) {
      byte allFacesMask = (byte)((1 << NeoShadowsEngine.FACES.length) - 1);
      mergeAffectedFaceMask(affectedFacesByBlock, center.asLong(), allFacesMask);

      for (Direction face : NeoShadowsEngine.FACES) {
         mergeAffectedFaceMask(affectedFacesByBlock, center.relative(face).asLong(), allFacesMask);
      }
   }

   private static void markAffectedReceiverFaces(Long2ByteOpenHashMap affectedFacesByBlock, List<NeoShadowsTypes.FaceCandidate> candidates) {
      for (NeoShadowsTypes.FaceCandidate candidate : candidates) {
         mergeAffectedFaceMask(affectedFacesByBlock, candidate.pos.asLong(), (byte)(1 << candidate.face.ordinal()));
      }
   }

   private static void mergeAffectedFaceMask(Long2ByteOpenHashMap affectedFacesByBlock, long blockKey, byte mask) {
      affectedFacesByBlock.put(blockKey, (byte)(affectedFacesByBlock.get(blockKey) | mask));
   }

   List<NeoShadowsTypes.ReceiverPlaneGroup> buildReceiverPlaneGroups(
      TorchRtxState torchState, List<NeoShadowsTypes.FaceCandidate> candidates, NeoShadowsTypes.GeometryBuildState state
   ) {
      int initialCapacity = Math.max(16, Math.min(candidates.size() * 2, 4096));
      LinkedHashMap<NeoShadowsTypes.ReceiverPlaneKey, NeoShadowsTypes.ReceiverPlaneGroup> groups = new LinkedHashMap<>(initialCapacity);
      int n = candidates.size();
      NeoShadowsTypes.FaceLightingInfo[] lightingByIndex = new NeoShadowsTypes.FaceLightingInfo[n];
      int workerCount = Math.max(1, Math.min(NeoShadowsEngine.SHADOW_WORKER_COUNT, n));
      if (workerCount > 1 && n >= 64) {
         ExecutorService pool = NeoShadowsEngine.shadowWorkerPool();
         CountDownLatch latch = new CountDownLatch(workerCount);

         for (int w = 0; w < workerCount; w++) {
            pool.execute(() -> {
               try {
                  int i = w;

                  while (i < n) {
                     NeoShadowsTypes.FaceCandidate candidate = candidates.get(i);
                     lightingByIndex[i] = this.engine.visibility.resolveFaceLightingCached(torchState, state, candidate);
                     i += workerCount;
                  }
               } catch (Throwable var14) {
               } finally {
                  latch.countDown();
               }
            });
         }

         try {
            latch.await();
         } catch (InterruptedException var20) {
            Thread.currentThread().interrupt();
         }
      } else {
         for (int i = 0; i < n; i++) {
            NeoShadowsTypes.FaceCandidate candidate = candidates.get(i);
            lightingByIndex[i] = this.engine.visibility.resolveFaceLightingCached(torchState, state, candidate);
         }
      }

      for (int i = 0; i < n; i++) {
         NeoShadowsTypes.FaceCandidate candidate = candidates.get(i);
         NeoShadowsTypes.FaceLightingInfo lighting = lightingByIndex[i];
         if (lighting == null) {
            lighting = this.engine.visibility.resolveFaceLightingCached(torchState, state, candidate);
         }

         if (!lighting.contributions.isEmpty()) {
            long candidatePosKey = candidate.pos.asLong();

            for (NeoShadowsTypes.SourceContribution contribution : lighting.contributions) {
               if (contribution.sourceKey != Long.MIN_VALUE && contribution.sourceEmission > 0 && contribution.sourceKey != candidatePosKey) {
                  NeoShadowsTypes.ReceiverPlaneKey key = NeoShadowsTypes.ReceiverPlaneKey.of(candidate, contribution.sourceKey);
                  NeoShadowsTypes.ReceiverPlaneGroup group = groups.get(key);
                  if (group == null) {
                     Vec3 source = new Vec3(
                        (double)BlockPos.getX(contribution.sourceKey) + 0.5,
                        (double)BlockPos.getY(contribution.sourceKey) + 0.5,
                        (double)BlockPos.getZ(contribution.sourceKey) + 0.5
                     );
                     group = new NeoShadowsTypes.ReceiverPlaneGroup(key, source, contribution.sourceEmission);
                     groups.put(key, group);
                  }

                  group.addFace(candidate, contribution.weight, contribution.localFaceBounds);
               }
            }
         }
      }

      List<NeoShadowsTypes.ReceiverPlaneGroup> result = new ArrayList<>(groups.values());
      int rgCount = result.size();
      if (rgCount > 1) {
         ExecutorService finalizePool = NeoShadowsEngine.shadowWorkerPool();
         CountDownLatch finalizeLatch = new CountDownLatch(rgCount);

         for (NeoShadowsTypes.ReceiverPlaneGroup group : result) {
            finalizePool.execute(() -> {
               try {
                  group.finalizeReceiverSamples();
               } catch (Throwable var6x) {
               } finally {
                  finalizeLatch.countDown();
               }
            });
         }

         try {
            finalizeLatch.await();
         } catch (InterruptedException var19) {
            Thread.currentThread().interrupt();
         }
      } else if (rgCount == 1) {
         result.get(0).finalizeReceiverSamples();
      }

      return result;
   }

   static void sortPlaneGroupsByPriority(List<NeoShadowsTypes.ReceiverPlaneGroup> planeGroups, BlockPos center) {
      if (planeGroups.size() > 1) {
         Vec3 centerVec = Vec3.atCenterOf(center);
         planeGroups.sort((left, right) -> {
            int receiverCompare = Double.compare(left.nearestReceiverDistanceSq, right.nearestReceiverDistanceSq);
            if (receiverCompare != 0) {
               return receiverCompare;
            } else {
               double da = left.source.distanceToSqr(centerVec);
               double db = right.source.distanceToSqr(centerVec);
               return Double.compare(da, db);
            }
         });
      }
   }

   void processReceiverPlaneGroup(
      ClientLevel level, NeoShadowsTypes.ReceiverPlaneGroup group, NeoShadowsTypes.GeometryBuildState state, NeoShadowsTypes.BuildScratch scratch
   ) {
      if (!group.faces.isEmpty() && scratch.shadowPolygons.size() < 12288) {
         if (!group.plane.isFacingSource(group.source)) {
            this.addBackFacingReceiverShadows(group, scratch);
         } else {
            Long2ObjectLinkedOpenHashMap<NeoShadowsOcclusionPipeline.OccluderHit> hitsByBlock = scratch.reusableGroupHits;
            hitsByBlock.clear();

            for (NeoShadowsTypes.FaceCandidate candidate : group.faces) {
               NeoShadowsTypes.ReceiverPlane facePlane = new NeoShadowsTypes.ReceiverPlane(candidate.pos, candidate.face, candidate.planeCoordinate);
               this.engine
                  .occlusionPipeline
                  .findFaceOccludersInto(level, facePlane, candidate, group, state, group.source, group.key.sourceKey, group.sourceEmission, hitsByBlock);
            }

            if (!hitsByBlock.isEmpty() || state.includeDynamicCasters) {
               scratch.occluderHitCount = scratch.occluderHitCount + hitsByBlock.size();
               LongOpenHashSet mergedFullBlockHits = new LongOpenHashSet();
               this.appendMergedFullBlockOccluderShadows(level, group, hitsByBlock, mergedFullBlockHits, scratch);
               ObjectIterator var16 = hitsByBlock.values().iterator();

               while (var16.hasNext()) {
                  NeoShadowsOcclusionPipeline.OccluderHit hit = (NeoShadowsOcclusionPipeline.OccluderHit)var16.next();
                  if (!mergedFullBlockHits.contains(hit.blockPos.asLong())) {
                     if (this.addModelShadowPolygons(level, group, hit, state, scratch)) {
                        if (scratch.shadowPolygons.size() >= 12288) {
                           return;
                        }
                     } else {
                        for (NeoShadowsTypes.Box bounds : hit.boxes) {
                           List<NeoShadowsTypes.UvPoint> projected = NeoShadowsProjection.collectProjectedBoxPoints(
                              group.plane, group.source, bounds, hit.blockPos
                           );
                           List<NeoShadowsTypes.UvPoint> polygon = NeoShadowsProjection.buildProjectedShadowHullBuffered(projected, scratch);
                           if (polygon != null) {
                              this.addPlaneShadowPolygons(group, polygon, scratch);
                              if (scratch.shadowPolygons.size() >= 12288) {
                                 return;
                              }
                           }
                        }
                     }
                  }
               }

               if (state.includeDynamicCasters && scratch.shadowPolygons.size() < 12288) {
                  this.engine.dynamicPipeline.addDynamicCasterShadows(group, state, scratch);
               }
            }
         }
      }
   }

   void appendMergedFullBlockOccluderShadows(
      ClientLevel level,
      NeoShadowsTypes.ReceiverPlaneGroup group,
      Long2ObjectLinkedOpenHashMap<NeoShadowsOcclusionPipeline.OccluderHit> hitsByBlock,
      LongOpenHashSet consumedHits,
      NeoShadowsTypes.BuildScratch scratch
   ) {
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      ArrayList<BlockPos> cluster = new ArrayList<>();
      LongOpenHashSet clusterKeys = new LongOpenHashSet();
      ObjectIterator var9 = hitsByBlock.values().iterator();

      while (var9.hasNext()) {
         NeoShadowsOcclusionPipeline.OccluderHit seedHit = (NeoShadowsOcclusionPipeline.OccluderHit)var9.next();
         if (scratch.shadowPolygons.size() >= 12288) {
            return;
         }

         long seedKey = seedHit.blockPos.asLong();
         if (!consumedHits.contains(seedKey) && isMergeableFullBlockOccluder(level, seedHit)) {
            queue.clear();
            cluster.clear();
            clusterKeys.clear();
            queue.add(seedHit.blockPos);
            clusterKeys.add(seedKey);
            int minX = seedHit.blockPos.getX();
            int maxX = minX;
            int minY = seedHit.blockPos.getY();
            int maxY = minY;
            int minZ = seedHit.blockPos.getZ();
            int maxZ = minZ;

            while (!queue.isEmpty()) {
               BlockPos current = queue.removeFirst();
               cluster.add(current);
               minX = Math.min(minX, current.getX());
               maxX = Math.max(maxX, current.getX());
               minY = Math.min(minY, current.getY());
               maxY = Math.max(maxY, current.getY());
               minZ = Math.min(minZ, current.getZ());
               maxZ = Math.max(maxZ, current.getZ());

               for (Direction direction : NeoShadowsEngine.FACES) {
                  BlockPos neighbour = current.relative(direction);
                  long neighbourKey = neighbour.asLong();
                  if (!clusterKeys.contains(neighbourKey) && !consumedHits.contains(neighbourKey)) {
                     NeoShadowsOcclusionPipeline.OccluderHit neighbourHit = (NeoShadowsOcclusionPipeline.OccluderHit)hitsByBlock.get(neighbourKey);
                     if (neighbourHit != null && isMergeableFullBlockOccluder(level, neighbourHit)) {
                        clusterKeys.add(neighbourKey);
                        queue.addLast(neighbour);
                     }
                  }
               }
            }

            int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (cluster.size() > 1 && volume == cluster.size()) {
               NeoShadowsTypes.Box mergedWorldBox = new NeoShadowsTypes.Box(
                  (double)minX, (double)minY, (double)minZ, (double)maxX + 1.0, (double)maxY + 1.0, (double)maxZ + 1.0
               );
               List<NeoShadowsTypes.UvPoint> projected = NeoShadowsProjection.collectProjectedBoxPoints(group.plane, group.source, mergedWorldBox);
               List<NeoShadowsTypes.UvPoint> polygon = NeoShadowsProjection.buildProjectedShadowHullBuffered(projected, scratch);
               if (polygon != null) {
                  this.addPlaneShadowPolygons(group, polygon, scratch);
                  consumedHits.addAll(clusterKeys);
               }
            }
         }
      }
   }

   static boolean isMergeableFullBlockOccluder(ClientLevel level, NeoShadowsOcclusionPipeline.OccluderHit hit) {
      if (hit != null && isFullBlockOccluder(hit.boxes)) {
         BlockState blockState = level.getBlockState(hit.blockPos);
         return !blockState.isAir() && blockState.getRenderShape() == RenderShape.MODEL && blockState.isSolidRender(level, hit.blockPos);
      } else {
         return false;
      }
   }

   boolean addModelShadowPolygons(
      ClientLevel level,
      NeoShadowsTypes.ReceiverPlaneGroup group,
      NeoShadowsOcclusionPipeline.OccluderHit hit,
      NeoShadowsTypes.GeometryBuildState state,
      NeoShadowsTypes.BuildScratch scratch
   ) {
      BlockState blockState = level.getBlockState(hit.blockPos);
      if (blockState.getRenderShape() != RenderShape.MODEL) {
         return false;
      } else {
         BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(blockState);
         if (model == null) {
            return false;
         } else {
            List<BakedQuad> quads = collectModelQuads(model, blockState);
            if (quads.isEmpty()) {
               return false;
            } else {
               boolean fullBlockOccluder = isFullBlockOccluder(hit.boxes);
               boolean allSpritesOpaque = true;

               for (BakedQuad quad : quads) {
                  TextureAtlasSprite sprite = quad.getSprite();
                  if (sprite != null) {
                     NeoShadowsTypes.SpriteOpaqueMask mask = this.getSpriteOpaqueMask(sprite);
                     if (!mask.fullyOpaque) {
                        allSpritesOpaque = false;
                        break;
                     }
                  }
               }

               if (fullBlockOccluder && allSpritesOpaque) {
                  return false;
               } else {
                  int polygonsBefore = scratch.shadowPolygons.size();

                  for (BakedQuad quadx : quads) {
                     if (scratch.shadowPolygons.size() >= 12288) {
                        break;
                     }

                     NeoShadowsTypes.TexturedQuad texturedQuad = NeoShadowsTypes.TexturedQuad.from(quadx, hit.blockPos);
                     if (texturedQuad != null && texturedQuad.facesSource(group.source)) {
                        TextureAtlasSprite sprite = quadx.getSprite();
                        if (sprite != null) {
                           NeoShadowsTypes.SpriteOpaqueMask mask = this.getSpriteOpaqueMask(sprite);
                           if (!mask.fullyTransparent) {
                              if (mask.fullyOpaque) {
                                 this.addProjectedQuadShadow(group, texturedQuad.worldVertices, scratch);
                              } else {
                                 NeoShadowsTypes.SpritePixelBounds usedPixels = computeQuadPixelBounds(texturedQuad, sprite);
                                 if (usedPixels != null) {
                                    for (NeoShadowsTypes.SpriteRect rect : mask.rectangles) {
                                       if (scratch.shadowPolygons.size() >= 12288) {
                                          break;
                                       }

                                       double minPixelX = Math.max((double)rect.minX, usedPixels.minX);
                                       double maxPixelX = Math.min((double)rect.maxX, usedPixels.maxX);
                                       double minPixelY = Math.max((double)rect.minY, usedPixels.minY);
                                       double maxPixelY = Math.min((double)rect.maxY, usedPixels.maxY);
                                       if (!(maxPixelX - minPixelX <= 1.0E-6) && !(maxPixelY - minPixelY <= 1.0E-6)) {
                                          Vec3[] opaqueRectVertices = buildOpaqueRectVertices(texturedQuad, sprite, minPixelX, maxPixelX, minPixelY, maxPixelY);
                                          if (opaqueRectVertices != null) {
                                             this.addProjectedQuadShadow(group, opaqueRectVertices, scratch);
                                          }
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }

                  return scratch.shadowPolygons.size() > polygonsBefore;
               }
            }
         }
      }
   }

   static List<BakedQuad> collectModelQuads(BakedModel model, BlockState state) {
      List<BakedQuad> quads = new ArrayList<>();
      RandomSource random = RANDOM_SOURCE_TL.get();

      for (Direction face : NeoShadowsEngine.FACES) {
         random.setSeed(42L);
         quads.addAll(model.getQuads(state, face, random));
      }

      random.setSeed(42L);
      quads.addAll(model.getQuads(state, null, random));
      return quads;
   }

   boolean addProjectedQuadShadow(NeoShadowsTypes.ReceiverPlaneGroup group, Vec3[] quadVertices, NeoShadowsTypes.BuildScratch scratch) {
      List<NeoShadowsTypes.UvPoint> projected = collectProjectedQuadPoints(group.plane, group.source, quadVertices);
      List<NeoShadowsTypes.UvPoint> polygon = NeoShadowsProjection.buildProjectedShadowHullBuffered(projected, scratch);
      if (polygon == null) {
         return false;
      } else {
         int polygonsBefore = scratch.shadowPolygons.size();
         this.addPlaneShadowPolygons(group, polygon, scratch);
         return scratch.shadowPolygons.size() > polygonsBefore;
      }
   }

   static boolean facesSource(Vec3[] quadVertices, Vec3 source) {
      if (quadVertices.length < 3) {
         return false;
      } else {
         Vec3 normal = quadVertices[1].subtract(quadVertices[0]).cross(quadVertices[2].subtract(quadVertices[0]));
         return normal.lengthSqr() <= 1.0E-12 ? false : normal.dot(source.subtract(quadVertices[0])) > 1.0E-6;
      }
   }

   NeoShadowsTypes.SpriteOpaqueMask getSpriteOpaqueMask(TextureAtlasSprite sprite) {
      return this.engine.spriteOpaqueMaskCache.computeIfAbsent(sprite, this::buildSpriteOpaqueMask);
   }

   NeoShadowsTypes.SpriteOpaqueMask buildSpriteOpaqueMask(TextureAtlasSprite sprite) {
      int width = Math.max(1, sprite.contents().width());
      int height = Math.max(1, sprite.contents().height());
      boolean[] opaquePixels = new boolean[width * height];
      boolean anyOpaque = false;
      boolean anyTransparent = false;

      for (int pixelY = 0; pixelY < height; pixelY++) {
         int rowOffset = pixelY * width;

         for (int pixelX = 0; pixelX < width; pixelX++) {
            int alpha = ARGB32.alpha(sprite.getPixelRGBA(0, pixelX, pixelY));
            boolean opaque = alpha >= 16;
            opaquePixels[rowOffset + pixelX] = opaque;
            anyOpaque |= opaque;
            anyTransparent |= !opaque;
         }
      }

      if (!anyOpaque) {
         return NeoShadowsTypes.SpriteOpaqueMask.fullyTransparent();
      } else if (!anyTransparent) {
         return NeoShadowsTypes.SpriteOpaqueMask.fullyOpaque();
      } else {
         boolean[] consumed = new boolean[opaquePixels.length];
         List<NeoShadowsTypes.SpriteRect> rectangles = new ArrayList<>();

         for (int pixelY = 0; pixelY < height; pixelY++) {
            for (int pixelX = 0; pixelX < width; pixelX++) {
               int index = pixelY * width + pixelX;
               if (opaquePixels[index] && !consumed[index]) {
                  int rectWidth;
                  for (rectWidth = 1; pixelX + rectWidth < width; rectWidth++) {
                     int nextIndex = pixelY * width + pixelX + rectWidth;
                     if (!opaquePixels[nextIndex] || consumed[nextIndex]) {
                        break;
                     }
                  }

                  int rectHeight = 1;
                  boolean canGrow = true;

                  while (pixelY + rectHeight < height && canGrow) {
                     int nextRowOffset = (pixelY + rectHeight) * width;
                     int scanX = 0;

                     while (true) {
                        if (scanX < rectWidth) {
                           int nextIndex = nextRowOffset + pixelX + scanX;
                           if (opaquePixels[nextIndex] && !consumed[nextIndex]) {
                              scanX++;
                              continue;
                           }

                           canGrow = false;
                        }

                        if (canGrow) {
                           rectHeight++;
                        }
                        break;
                     }
                  }

                  for (int fillY = 0; fillY < rectHeight; fillY++) {
                     int fillRowOffset = (pixelY + fillY) * width;

                     for (int fillX = 0; fillX < rectWidth; fillX++) {
                        consumed[fillRowOffset + pixelX + fillX] = true;
                     }
                  }

                  rectangles.add(new NeoShadowsTypes.SpriteRect(pixelX, pixelX + rectWidth, pixelY, pixelY + rectHeight));
               }
            }
         }

         return new NeoShadowsTypes.SpriteOpaqueMask(rectangles.toArray(new NeoShadowsTypes.SpriteRect[0]), false, false);
      }
   }

   @Nullable
   static NeoShadowsTypes.SpritePixelBounds computeQuadPixelBounds(NeoShadowsTypes.TexturedQuad quad, TextureAtlasSprite sprite) {
      int spriteWidth = Math.max(1, sprite.contents().width());
      int spriteHeight = Math.max(1, sprite.contents().height());
      double minPixelX = Double.POSITIVE_INFINITY;
      double maxPixelX = Double.NEGATIVE_INFINITY;
      double minPixelY = Double.POSITIVE_INFINITY;
      double maxPixelY = Double.NEGATIVE_INFINITY;

      for (NeoShadowsTypes.AtlasUv uv : quad.atlasUvs) {
         double pixelX = (double)(sprite.getUOffset((float)uv.u) * (float)spriteWidth) / 16.0;
         double pixelY = (double)(sprite.getVOffset((float)uv.v) * (float)spriteHeight) / 16.0;
         minPixelX = Math.min(minPixelX, pixelX);
         maxPixelX = Math.max(maxPixelX, pixelX);
         minPixelY = Math.min(minPixelY, pixelY);
         maxPixelY = Math.max(maxPixelY, pixelY);
      }

      if (Double.isFinite(minPixelX) && Double.isFinite(minPixelY) && Double.isFinite(maxPixelX) && Double.isFinite(maxPixelY)) {
         minPixelX = Mth.clamp(minPixelX, 0.0, (double)spriteWidth);
         maxPixelX = Mth.clamp(maxPixelX, 0.0, (double)spriteWidth);
         minPixelY = Mth.clamp(minPixelY, 0.0, (double)spriteHeight);
         maxPixelY = Mth.clamp(maxPixelY, 0.0, (double)spriteHeight);
         return !(maxPixelX - minPixelX <= 1.0E-6) && !(maxPixelY - minPixelY <= 1.0E-6)
            ? new NeoShadowsTypes.SpritePixelBounds(minPixelX, maxPixelX, minPixelY, maxPixelY)
            : null;
      } else {
         return null;
      }
   }

   @Nullable
   static Vec3[] buildOpaqueRectVertices(
      NeoShadowsTypes.TexturedQuad quad, TextureAtlasSprite sprite, double minPixelX, double maxPixelX, double minPixelY, double maxPixelY
   ) {
      int spriteWidth = Math.max(1, sprite.contents().width());
      int spriteHeight = Math.max(1, sprite.contents().height());
      double minAtlasU = (double)sprite.getU(minPixelX * 16.0 / (double)spriteWidth);
      double maxAtlasU = (double)sprite.getU(maxPixelX * 16.0 / (double)spriteWidth);
      double minAtlasV = (double)sprite.getV(minPixelY * 16.0 / (double)spriteHeight);
      double maxAtlasV = (double)sprite.getV(maxPixelY * 16.0 / (double)spriteHeight);
      Vec3 topLeft = mapAtlasUvToWorld(quad, minAtlasU, minAtlasV);
      Vec3 topRight = mapAtlasUvToWorld(quad, maxAtlasU, minAtlasV);
      Vec3 bottomRight = mapAtlasUvToWorld(quad, maxAtlasU, maxAtlasV);
      Vec3 bottomLeft = mapAtlasUvToWorld(quad, minAtlasU, maxAtlasV);
      return topLeft != null && topRight != null && bottomRight != null && bottomLeft != null ? new Vec3[]{topLeft, topRight, bottomRight, bottomLeft} : null;
   }

   @Nullable
   static Vec3 mapAtlasUvToWorld(NeoShadowsTypes.TexturedQuad quad, double atlasU, double atlasV) {
      Vec3 mapped = mapAtlasUvToWorldTriangle(quad, 0, 1, 2, atlasU, atlasV);
      return mapped != null ? mapped : mapAtlasUvToWorldTriangle(quad, 0, 2, 3, atlasU, atlasV);
   }

   @Nullable
   static Vec3 mapAtlasUvToWorldTriangle(NeoShadowsTypes.TexturedQuad quad, int indexA, int indexB, int indexC, double atlasU, double atlasV) {
      NeoShadowsTypes.AtlasUv uvA = quad.atlasUvs[indexA];
      NeoShadowsTypes.AtlasUv uvB = quad.atlasUvs[indexB];
      NeoShadowsTypes.AtlasUv uvC = quad.atlasUvs[indexC];
      double denominator = (uvB.v - uvC.v) * (uvA.u - uvC.u) + (uvC.u - uvB.u) * (uvA.v - uvC.v);
      if (Math.abs(denominator) <= 1.0E-6) {
         return null;
      } else {
         double weightA = ((uvB.v - uvC.v) * (atlasU - uvC.u) + (uvC.u - uvB.u) * (atlasV - uvC.v)) / denominator;
         double weightB = ((uvC.v - uvA.v) * (atlasU - uvC.u) + (uvA.u - uvC.u) * (atlasV - uvC.v)) / denominator;
         double weightC = 1.0 - weightA - weightB;
         double epsilon = 1.0E-4;
         if (!(weightA < -epsilon) && !(weightB < -epsilon) && !(weightC < -epsilon)) {
            Vec3 vertexA = quad.worldVertices[indexA];
            Vec3 vertexB = quad.worldVertices[indexB];
            Vec3 vertexC = quad.worldVertices[indexC];
            return new Vec3(
               vertexA.x * weightA + vertexB.x * weightB + vertexC.x * weightC,
               vertexA.y * weightA + vertexB.y * weightB + vertexC.y * weightC,
               vertexA.z * weightA + vertexB.z * weightB + vertexC.z * weightC
            );
         } else {
            return null;
         }
      }
   }

   static boolean isFullBlockOccluder(NeoShadowsTypes.Box[] boxes) {
      if (boxes.length != 1) {
         return false;
      } else {
         NeoShadowsTypes.Box box = boxes[0];
         return box.minX <= 1.0E-6 && box.minY <= 1.0E-6 && box.minZ <= 1.0E-6 && box.maxX >= 0.999999 && box.maxY >= 0.999999 && box.maxZ >= 0.999999;
      }
   }

   static List<NeoShadowsTypes.UvPoint> collectProjectedQuadPoints(NeoShadowsTypes.GlobalReceiverPlane plane, Vec3 source, Vec3[] corners) {
      NeoShadowsTypes.ProjectionPoint[] projectedCorners = new NeoShadowsTypes.ProjectionPoint[corners.length];
      List<NeoShadowsTypes.UvPoint> points = new ArrayList<>(corners.length + NeoShadowsEngine.QUAD_EDGE_VERTEX_INDICES.length * 2);

      for (int index = 0; index < corners.length; index++) {
         NeoShadowsTypes.ProjectionPoint projection = plane.projectPoint(source, corners[index]);
         projectedCorners[index] = projection;
         if (projection.uv != null) {
            points.add(projection.uv);
         }
      }

      for (int[] edge : NeoShadowsEngine.QUAD_EDGE_VERTEX_INDICES) {
         appendExtendedQuadEdgePoints(plane, source, corners[edge[0]], corners[edge[1]], projectedCorners[edge[0]], projectedCorners[edge[1]], points);
      }

      return points;
   }

   private static void appendExtendedQuadEdgePoints(
      NeoShadowsTypes.GlobalReceiverPlane plane,
      Vec3 source,
      Vec3 start,
      Vec3 end,
      NeoShadowsTypes.ProjectionPoint startProjection,
      NeoShadowsTypes.ProjectionPoint endProjection,
      List<NeoShadowsTypes.UvPoint> points
   ) {
      boolean startValid = startProjection.uv != null;
      boolean endValid = endProjection.uv != null;
      if (startValid != endValid) {
         double denominatorDelta = endProjection.denominator - startProjection.denominator;
         if (!(Math.abs(denominatorDelta) <= 1.0E-6)) {
            double singularParam = -startProjection.denominator / denominatorDelta;
            if (!(singularParam < -1.0E-6) && !(singularParam > 1.000001)) {
               singularParam = Math.max(0.0, Math.min(1.0, singularParam));
               double outerOffset = startValid ? -0.001 : 0.001;
               double innerOffset = startValid ? -2.5E-4 : 2.5E-4;
               double outerParam = NeoShadowsProjection.clampUnit(singularParam + outerOffset);
               double innerParam = NeoShadowsProjection.clampUnit(singularParam + innerOffset);
               if (!(Math.abs(innerParam - outerParam) <= 1.0E-6)) {
                  NeoShadowsTypes.UvPoint outerUv = plane.projectFinite(source, NeoShadowsProjection.lerp(start, end, outerParam));
                  NeoShadowsTypes.UvPoint innerUv = plane.projectFinite(source, NeoShadowsProjection.lerp(start, end, innerParam));
                  if (outerUv != null && innerUv != null) {
                     points.add(outerUv);
                     points.add(innerUv);
                     double directionU = innerUv.u - outerUv.u;
                     double directionV = innerUv.v - outerUv.v;
                     double directionLengthSq = directionU * directionU + directionV * directionV;
                     if (directionLengthSq <= 1.0E-6) {
                        Vec3 edgeDirection = end.subtract(start);
                        directionU = edgeDirection.dot(plane.axisU);
                        directionV = edgeDirection.dot(plane.axisV);
                        directionLengthSq = directionU * directionU + directionV * directionV;
                        if (directionLengthSq <= 1.0E-6) {
                           return;
                        }
                     }

                     double directionLength = Math.sqrt(directionLengthSq);
                     points.add(new NeoShadowsTypes.UvPoint(innerUv.u + directionU / directionLength * 8.0, innerUv.v + directionV / directionLength * 8.0));
                  }
               }
            }
         }
      }
   }

   void addBackFacingReceiverShadows(NeoShadowsTypes.ReceiverPlaneGroup group, NeoShadowsTypes.BuildScratch scratch) {
      for (NeoShadowsTypes.ReceiverContributionRect rect : group.receiverRects()) {
         if (scratch.shadowPolygons.size() >= 12288) {
            return;
         }

         float strength = rect.strength;
         if (!(strength <= 0.0F)) {
            NeoShadowsTypes.PlaneRect planeRect = rect.planeRect;
            if (!(planeRect.maxU - planeRect.minU <= 1.0E-6) && !(planeRect.maxV - planeRect.minV <= 1.0E-6)) {
               List<NeoShadowsTypes.UvPoint> contributionRect = NeoShadowsProjection.rectShadowPolygon(planeRect);
               double[] vertices = new double[contributionRect.size() * 3];

               for (int index = 0; index < contributionRect.size(); index++) {
                  NeoShadowsTypes.UvPoint uv = contributionRect.get(index);
                  int offset = index * 3;
                  vertices[offset] = group.plane.sampleX(uv.u, uv.v);
                  vertices[offset + 1] = group.plane.sampleY(uv.u, uv.v);
                  vertices[offset + 2] = group.plane.sampleZ(uv.u, uv.v);
               }

               scratch.shadowPolygons.add(new NeoShadowsTypes.ShadowPolygon(rect.anchorPos, group.key.face, vertices, strength));
               scratch.shadowPolygonCount++;
            }
         }
      }
   }

   void addPlaneShadowPolygons(NeoShadowsTypes.ReceiverPlaneGroup group, List<NeoShadowsTypes.UvPoint> planePolygon, NeoShadowsTypes.BuildScratch scratch) {
      double minU = Double.POSITIVE_INFINITY;
      double maxU = Double.NEGATIVE_INFINITY;
      double minV = Double.POSITIVE_INFINITY;
      double maxV = Double.NEGATIVE_INFINITY;

      for (NeoShadowsTypes.UvPoint point : planePolygon) {
         minU = Math.min(minU, point.u);
         maxU = Math.max(maxU, point.u);
         minV = Math.min(minV, point.v);
         maxV = Math.max(maxV, point.v);
      }

      for (NeoShadowsTypes.ReceiverContributionRect receiverRect : group.receiverRects()) {
         if (scratch.shadowPolygons.size() >= 12288) {
            return;
         }

         NeoShadowsTypes.PlaneRect rect = receiverRect.planeRect;
         if (!(rect.maxU - rect.minU <= 1.0E-6)
            && !(rect.maxV - rect.minV <= 1.0E-6)
            && !(rect.maxU <= minU)
            && !(rect.minU >= maxU)
            && !(rect.maxV <= minV)
            && !(rect.minV >= maxV)) {
            if (!(rect.maxU - rect.minU > 1.000001) && !(rect.maxV - rect.minV > 1.000001)) {
               this.appendPlaneShadowPolygon(group, planePolygon, minU, maxU, minV, maxV, rect, receiverRect.anchorPos, receiverRect.strength, scratch);
            } else {
               int minCellU = Mth.floor(rect.minU + 1.0E-6);
               int maxCellU = Mth.ceil(rect.maxU - 1.0E-6) - 1;
               int minCellV = Mth.floor(rect.minV + 1.0E-6);
               int maxCellV = Mth.ceil(rect.maxV - 1.0E-6) - 1;

               for (int cellV = minCellV; cellV <= maxCellV; cellV++) {
                  for (int cellU = minCellU; cellU <= maxCellU; cellU++) {
                     if (scratch.shadowPolygons.size() >= 12288) {
                        return;
                     }

                     NeoShadowsTypes.PlaneRect cellRect = new NeoShadowsTypes.PlaneRect((double)cellU, (double)cellU + 1.0, (double)cellV, (double)cellV + 1.0);
                     BlockPos anchorPos = NeoShadowsProjection.planeCellToBlockPos(group.key, cellU, cellV);
                     this.appendPlaneShadowPolygon(group, planePolygon, minU, maxU, minV, maxV, cellRect, anchorPos, receiverRect.strength, scratch);
                  }
               }
            }
         }
      }
   }

   private void appendRectShadowPolygon(
      NeoShadowsTypes.ReceiverPlaneGroup group, NeoShadowsTypes.PlaneRect rect, BlockPos anchorPos, float strength, NeoShadowsTypes.BuildScratch scratch
   ) {
      List<NeoShadowsTypes.UvPoint> clipped = NeoShadowsProjection.rectShadowPolygon(rect);
      double[] vertices = new double[clipped.size() * 3];

      for (int index = 0; index < clipped.size(); index++) {
         NeoShadowsTypes.UvPoint uv = clipped.get(index);
         int offset = index * 3;
         vertices[offset] = group.plane.sampleX(uv.u, uv.v);
         vertices[offset + 1] = group.plane.sampleY(uv.u, uv.v);
         vertices[offset + 2] = group.plane.sampleZ(uv.u, uv.v);
      }

      scratch.shadowPolygons.add(new NeoShadowsTypes.ShadowPolygon(anchorPos, group.key.face, vertices, strength));
      scratch.shadowPolygonCount++;
   }

   void appendPlaneShadowPolygon(
      NeoShadowsTypes.ReceiverPlaneGroup group,
      List<NeoShadowsTypes.UvPoint> planePolygon,
      double minU,
      double maxU,
      double minV,
      double maxV,
      NeoShadowsTypes.PlaneRect rect,
      BlockPos anchorPos,
      float strength,
      NeoShadowsTypes.BuildScratch scratch
   ) {
      if (!(strength <= 0.0F)) {
         if (!(rect.maxU <= minU) && !(rect.minU >= maxU) && !(rect.maxV <= minV) && !(rect.minV >= maxV)) {
            if (minU <= rect.minU
               && maxU >= rect.maxU
               && minV <= rect.minV
               && maxV >= rect.maxV
               && NeoShadowsProjection.rectFullyInsideConvexPolygon(planePolygon, rect)) {
               this.appendRectShadowPolygon(group, rect, anchorPos, strength, scratch);
            } else {
               List<NeoShadowsTypes.UvPoint> clipped = NeoShadowsProjection.clipToRectangleBuffered(
                  planePolygon, rect.minU, rect.maxU, rect.minV, rect.maxV, scratch
               );
               if (clipped.size() >= 3 && !(NeoShadowsProjection.polygonArea(clipped) <= 1.0E-5)) {
                  clipped = NeoShadowsProjection.buildConvexHull(clipped);
                  if (clipped.size() >= 3 && !(NeoShadowsProjection.polygonArea(clipped) <= 1.0E-5)) {
                     clipped = NeoShadowsProjection.normalizeShadowPolygonToRect(clipped, rect, group.key.face, scratch);
                     if (clipped != null && clipped.size() >= 3 && !(NeoShadowsProjection.polygonArea(clipped) <= 1.0E-5)) {
                        double[] vertices = new double[clipped.size() * 3];

                        for (int index = 0; index < clipped.size(); index++) {
                           NeoShadowsTypes.UvPoint uv = clipped.get(index);
                           int offset = index * 3;
                           vertices[offset] = group.plane.sampleX(uv.u, uv.v);
                           vertices[offset + 1] = group.plane.sampleY(uv.u, uv.v);
                           vertices[offset + 2] = group.plane.sampleZ(uv.u, uv.v);
                        }

                        scratch.shadowPolygons.add(new NeoShadowsTypes.ShadowPolygon(anchorPos, group.key.face, vertices, strength));
                        scratch.shadowPolygonCount++;
                     }
                  }
               }
            }
         }
      }
   }
}
