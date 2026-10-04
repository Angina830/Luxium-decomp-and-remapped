package com.vinlanx.luxium.client.neocpu;

import com.vinlanx.luxium.rtx.LightRtMath;
import com.vinlanx.luxium.rtx.NeoFloodEngine;
import com.vinlanx.luxium.rtx.TorchRtxState;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class NeoCpuFloodShadowBuilder {
   private static final int MAX_POLYGONS = 8192;
   private static final int MAX_HITS_PER_RECEIVER = 9;
   private static final int MAX_HITS_PER_PLANE = 1024;
   private static final int RECEIVER_CONTRIBUTION_KEY_COUNT = 9;
   private static final double SHADOW_EPSILON = 1.0E-6;
   private static final double FACE_EPSILON = 0.0015;
   private static final double SURFACE_OFFSET = 0.0015;
   private static final double DDA_TIE_EPSILON = 1.0E-9;
   private static final double MIN_SOURCE_HIT_DISTANCE = 0.035;
   private static final double RECEIVER_HIT_EPSILON = 1.0E-4;
   private static final double SELF_SURFACE_EPSILON = 1.0E-4;
   private static final double MIN_RECEIVER_FACING_DOT = 1.0E-4;
   private static final double ADAPTIVE_GRAZING_ALIGNMENT_THRESHOLD = 0.26;
   private static final double ADAPTIVE_LARGE_RECEIVER_SIZE = 0.72;
   private static final double ADAPTIVE_NEAR_SOURCE_FRACTION = 0.18;
   private static final double ADAPTIVE_NEAR_RECEIVER_FRACTION = 0.82;
   private static final double EDGE_PROJECT_EPSILON = 1.0E-6;
   private static final double PROJECTION_DENOMINATOR_EPSILON = 1.0E-9;
   private static final double EXTENDED_PROJECTION_DELTA = 0.001;
   private static final double EXTENDED_PROJECTION_FAR = 8.0;
   private static final double POLYGON_AREA_EPSILON = 1.0E-4;
   private static final double FULL_RECT_SHADOW_AREA_THRESHOLD = 0.988;
   private static final double SHADOW_POLYGON_DILATION = 0.016;
   private static final double HORIZONTAL_SHADOW_POLYGON_DILATION = 0.024;
   private static final double SHADOW_EDGE_SNAP_EPSILON = 0.022;
   private static final double HORIZONTAL_SHADOW_EDGE_SNAP_EPSILON = 0.03;
   private static final int[][] BOX_EDGE_VERTEX_INDICES = new int[][]{
      {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3}, {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
   };
   private static final double[] CROSS_PROBE_LOCAL_U = new double[]{0.18, 0.5, 0.82, 0.5};
   private static final double[] CROSS_PROBE_LOCAL_V = new double[]{0.5, 0.18, 0.5, 0.82};
   private static final double[] CORNER_PROBE_LOCAL_U = new double[]{0.18, 0.82, 0.18, 0.82};
   private static final double[] CORNER_PROBE_LOCAL_V = new double[]{0.18, 0.18, 0.82, 0.82};
   private final ClientLevel level;
   private final NeoCpuShadowShapeCache shapeCache = new NeoCpuShadowShapeCache();
   private final ArrayList<NeoCpuShadowTypes.Uv> projected = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> hull = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> polygonBuf = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> clipA = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> clipB = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> sortBuf = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> uniqueBuf = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> lowerBuf = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> upperBuf = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> localPolygon = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> dilatedPolygon = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> snappedPolygon = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> normalizedPolygon = new ArrayList<>(32);
   private final ArrayList<NeoCpuShadowTypes.Uv> rectPolygon = new ArrayList<>(4);
   private final double[][] boxCornerScratch = new double[8][3];
   private final NeoCpuFloodShadowBuilder.CpuProjection[] boxCornerProjScratch = new NeoCpuFloodShadowBuilder.CpuProjection[8];
   private final NeoCpuFloodShadowBuilder.CpuProjection[] quadCornerProjScratch = new NeoCpuFloodShadowBuilder.CpuProjection[4];
   private final Long2ObjectOpenHashMap<NeoCpuShadowTypes.Occluder[]> occluderCache = new Long2ObjectOpenHashMap();
   private final Long2ObjectOpenHashMap<NeoCpuFloodShadowBuilder.PlaneGroup> planeGroupsByKey = new Long2ObjectOpenHashMap();
   private final ArrayList<NeoCpuFloodShadowBuilder.PlaneGroup> planeGroups = new ArrayList<>(64);
   private final Long2ObjectOpenHashMap<Long2ByteOpenHashMap> sourceContributionCacheBySource = new Long2ObjectOpenHashMap();
   private final Long2ObjectOpenHashMap<Long> dominantSourceCache = new Long2ObjectOpenHashMap();
   private final Long2ByteOpenHashMap sampleCoverageCache = new Long2ByteOpenHashMap();
   private final long[] receiverContributionKeys = new long[9];
   private final Long2ObjectOpenHashMap<NeoCpuFloodShadowBuilder.ContributionData> contributionDataCache = new Long2ObjectOpenHashMap();
   private final NeoCpuFloodShadowBuilder.ContributionCollector contributionCollector = new NeoCpuFloodShadowBuilder.ContributionCollector();
   private final NeoCpuFloodShadowBuilder.SourceDebugStats debugStats = new NeoCpuFloodShadowBuilder.SourceDebugStats();

   public NeoCpuFloodShadowBuilder(ClientLevel level) {
      this.level = level;
      this.sampleCoverageCache.defaultReturnValue((byte)0);
   }

   public List<NeoCpuShadowTypes.ShadowPolygon> build(long[] sourceKeys, int[] sourceEmissions) {
      return this.build(sourceKeys, sourceEmissions, NeoCpuEntityShadows.CaptureState.empty());
   }

   public List<NeoCpuShadowTypes.ShadowPolygon> build(long[] sourceKeys, int[] sourceEmissions, @Nullable NeoCpuEntityShadows.CaptureState entityShadowState) {
      NeoCpuLocalBlockAccessor accessor = new NeoCpuLocalBlockAccessor(this.level, this.shapeCache);
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons = new ArrayList<>(1024);

      for (int sourceIndex = 0; sourceIndex < sourceKeys.length && polygons.size() < 8192; sourceIndex++) {
         long sourceKey = sourceKeys[sourceIndex];
         int emission = sourceEmissions[sourceIndex];
         if (sourceKey != Long.MIN_VALUE && emission > 0) {
            this.buildSource(accessor, sourceKey, emission, entityShadowState, polygons);
         }
      }

      return polygons.isEmpty() ? List.of() : List.copyOf(polygons);
   }

   public NeoCpuShadowTypes.StaticBuildOutput buildStatic(long[] sourceKeys, int[] sourceEmissions) {
      NeoCpuLocalBlockAccessor accessor = new NeoCpuLocalBlockAccessor(this.level, this.shapeCache);
      ArrayList<NeoCpuShadowTypes.PerSourceStatic> sources = new ArrayList<>(sourceKeys.length);
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> allBlockPolygons = new ArrayList<>(1024);

      for (int sourceIndex = 0; sourceIndex < sourceKeys.length; sourceIndex++) {
         long sourceKey = sourceKeys[sourceIndex];
         int emission = sourceEmissions[sourceIndex];
         if (sourceKey != Long.MIN_VALUE && emission > 0) {
            NeoCpuShadowTypes.PerSourceStatic perSource = this.buildStaticSource(accessor, sourceKey, emission, allBlockPolygons);
            if (perSource != null) {
               sources.add(perSource);
            }

            if (allBlockPolygons.size() >= 8192) {
               break;
            }
         }
      }

      return sources.isEmpty() ? NeoCpuShadowTypes.StaticBuildOutput.EMPTY : new NeoCpuShadowTypes.StaticBuildOutput(List.copyOf(sources));
   }

   public List<NeoCpuShadowTypes.ShadowPolygon> buildDynamicOverlay(
      NeoCpuShadowTypes.StaticBuildOutput staticOutput, NeoCpuEntityShadows.CaptureState entityState
   ) {
      if (!staticOutput.isEmpty() && entityState != null && !entityState.isEmpty()) {
         ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons = new ArrayList<>(256);

         for (NeoCpuShadowTypes.PerSourceStatic perSource : staticOutput.sources()) {
            if (polygons.size() >= 8192) {
               break;
            }

            if (!perSource.groups().isEmpty()) {
               NeoCpuEntityShadows.appendEntityShadows(this, perSource.source(), perSource.emission(), entityState, perSource.groups(), polygons);
            }
         }

         return polygons.isEmpty() ? List.of() : List.copyOf(polygons);
      } else {
         return List.of();
      }
   }

   @Nullable
   private NeoCpuShadowTypes.PerSourceStatic buildStaticSource(
      NeoCpuLocalBlockAccessor accessor, long sourceKey, int emission, ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      this.occluderCache.clear();
      double sourceX = (double)BlockPos.getX(sourceKey) + 0.5;
      double sourceY = (double)BlockPos.getY(sourceKey) + 0.5;
      double sourceZ = (double)BlockPos.getZ(sourceKey) + 0.5;
      Vec3 source = new Vec3(sourceX, sourceY, sourceZ);
      double radius = LightRtMath.getBlockShadowRadius(emission);
      TorchRtxState torchState = TorchRtxState.get();
      NeoCpuFloodLightVolume volume = NeoCpuFloodLightVolume.build(accessor, sourceKey, emission);
      if (volume.boundaryFaces().isEmpty()) {
         return null;
      } else {
         this.debugStats.reset(sourceKey, emission, volume.boundaryFaces().size());
         this.sourceContributionCacheBySource.clear();
         this.dominantSourceCache.clear();
         this.sampleCoverageCache.clear();
         this.contributionDataCache.clear();
         this.planeGroupsByKey.clear();
         this.planeGroups.clear();

         for (NeoCpuFloodBoundaryFace boundaryFace : volume.boundaryFaces()) {
            NeoCpuShadowTypes.ReceiverSurface receiver = boundaryFace.receiver();
            NeoCpuFloodShadowBuilder.ContributionData contribution = this.resolveReceiverContribution(torchState, receiver, sourceKey);
            if (contribution != null && contribution.accepted()) {
               this.debugStats.acceptedReceivers++;
               float strength = this.computeReceiverStrength(sourceX, sourceY, sourceZ, emission, receiver, contribution.weightScale());
               if (!(strength <= 0.0F)) {
                  this.addReceiverToPlaneGroup(receiver, strength);
               }
            } else {
               this.debugStats.rejectedReceivers++;
            }
         }

         ArrayList<NeoCpuShadowTypes.ReceiverGroupEntry> groups = new ArrayList<>(this.planeGroups.size());
         int polygonsBefore = polygons.size();

         for (NeoCpuFloodShadowBuilder.PlaneGroup group : this.planeGroups) {
            if (polygons.size() >= 8192) {
               this.debugStats.hitPolygonCap = true;
               break;
            }

            ArrayList<NeoCpuShadowTypes.ReceiverWithStrength> rwsList = new ArrayList<>(group.receivers.size());

            for (NeoCpuFloodShadowBuilder.ReceiverEntry entry : group.receivers) {
               rwsList.add(new NeoCpuShadowTypes.ReceiverWithStrength(entry.receiver(), entry.strength()));
            }

            groups.add(new NeoCpuShadowTypes.ReceiverGroupEntry(List.copyOf(rwsList)));
            this.buildPlaneGroup(accessor, sourceKey, source, radius, group, polygons);
         }

         List<NeoCpuShadowTypes.ShadowPolygon> blockPolygons = polygons.size() > polygonsBefore
            ? List.copyOf(polygons.subList(polygonsBefore, polygons.size()))
            : List.of();
         return groups.isEmpty() ? null : new NeoCpuShadowTypes.PerSourceStatic(sourceKey, emission, source, List.copyOf(groups), blockPolygons);
      }
   }

   private void buildSource(
      NeoCpuLocalBlockAccessor accessor,
      long sourceKey,
      int emission,
      @Nullable NeoCpuEntityShadows.CaptureState entityShadowState,
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      this.occluderCache.clear();
      double sourceX = (double)BlockPos.getX(sourceKey) + 0.5;
      double sourceY = (double)BlockPos.getY(sourceKey) + 0.5;
      double sourceZ = (double)BlockPos.getZ(sourceKey) + 0.5;
      Vec3 source = new Vec3(sourceX, sourceY, sourceZ);
      double radius = LightRtMath.getBlockShadowRadius(emission);
      TorchRtxState torchState = TorchRtxState.get();
      int polygonsBefore = polygons.size();
      NeoCpuFloodLightVolume volume = NeoCpuFloodLightVolume.build(accessor, sourceKey, emission);
      if (!volume.boundaryFaces().isEmpty()) {
         this.debugStats.reset(sourceKey, emission, volume.boundaryFaces().size());
         this.sourceContributionCacheBySource.clear();
         this.dominantSourceCache.clear();
         this.sampleCoverageCache.clear();
         this.contributionDataCache.clear();
         this.planeGroupsByKey.clear();
         this.planeGroups.clear();

         for (NeoCpuFloodBoundaryFace boundaryFace : volume.boundaryFaces()) {
            NeoCpuShadowTypes.ReceiverSurface receiver = boundaryFace.receiver();
            NeoCpuFloodShadowBuilder.ContributionData contribution = this.resolveReceiverContribution(torchState, receiver, sourceKey);
            if (contribution != null && contribution.accepted()) {
               this.debugStats.acceptedReceivers++;
               float strength = this.computeReceiverStrength(sourceX, sourceY, sourceZ, emission, receiver, contribution.weightScale());
               if (!(strength <= 0.0F)) {
                  this.addReceiverToPlaneGroup(receiver, strength);
               }
            } else {
               this.debugStats.rejectedReceivers++;
            }
         }

         for (NeoCpuFloodShadowBuilder.PlaneGroup group : this.planeGroups) {
            if (polygons.size() >= 8192) {
               this.debugStats.hitPolygonCap = true;
               return;
            }

            this.buildPlaneGroup(accessor, sourceKey, source, radius, group, polygons);
         }

         if (entityShadowState != null && !entityShadowState.isEmpty() && !this.planeGroups.isEmpty() && polygons.size() < 8192) {
            ArrayList<NeoCpuShadowTypes.ReceiverGroupEntry> receiverGroups = new ArrayList<>(this.planeGroups.size());

            for (NeoCpuFloodShadowBuilder.PlaneGroup group : this.planeGroups) {
               if (!group.receivers.isEmpty()) {
                  ArrayList<NeoCpuShadowTypes.ReceiverWithStrength> receivers = new ArrayList<>(group.receivers.size());

                  for (NeoCpuFloodShadowBuilder.ReceiverEntry entry : group.receivers) {
                     receivers.add(new NeoCpuShadowTypes.ReceiverWithStrength(entry.receiver(), entry.strength()));
                  }

                  receiverGroups.add(new NeoCpuShadowTypes.ReceiverGroupEntry(List.copyOf(receivers)));
               }
            }

            if (!receiverGroups.isEmpty()) {
               NeoCpuEntityShadows.appendEntityShadows(this, source, emission, entityShadowState, receiverGroups, polygons);
            }
         }
      }
   }

   private float computeReceiverStrength(
      double sourceX, double sourceY, double sourceZ, int emission, NeoCpuShadowTypes.ReceiverSurface receiver, float weightScale
   ) {
      double receiverDistance = Math.sqrt(receiver.distanceToSourceSq(sourceX, sourceY, sourceZ));
      int lightAtReceiver = LightRtMath.getFalloff(emission, receiverDistance);
      return lightAtReceiver <= 0 ? 0.0F : Mth.clamp((0.28F + 0.52F * ((float)lightAtReceiver / Math.max(1.0F, (float)emission))) * weightScale, 0.18F, 0.78F);
   }

   private void addReceiverToPlaneGroup(NeoCpuShadowTypes.ReceiverSurface receiver, float strength) {
      long key = planeGroupKey(receiver.face(), receiver.plane());
      NeoCpuFloodShadowBuilder.PlaneGroup group = (NeoCpuFloodShadowBuilder.PlaneGroup)this.planeGroupsByKey.get(key);
      if (group == null) {
         group = new NeoCpuFloodShadowBuilder.PlaneGroup();
         this.planeGroupsByKey.put(key, group);
         this.planeGroups.add(group);
      }

      group.receivers.add(new NeoCpuFloodShadowBuilder.ReceiverEntry(receiver, strength));
   }

   private NeoCpuFloodShadowBuilder.ContributionData resolveReceiverContribution(
      TorchRtxState torchState, NeoCpuShadowTypes.ReceiverSurface receiver, long sourceKey
   ) {
      if (receiver.blockKey() == sourceKey) {
         return NeoCpuFloodShadowBuilder.ContributionData.REJECTED;
      } else {
         NeoCpuFloodShadowBuilder.ContributionData cached = (NeoCpuFloodShadowBuilder.ContributionData)this.contributionDataCache
            .get(receiver.blockKey() ^ (long)receiver.face().ordinal() << 58 ^ planeGroupKey(receiver.face(), receiver.plane()));
         if (cached != null) {
            return cached;
         } else {
            double sampleX = receiver.centerX();
            double sampleY = receiver.centerY();
            double sampleZ = receiver.centerZ();
            long basePosKey = sampleRtxPosKey(receiver, sampleX, sampleY, sampleZ);
            if (basePosKey == Long.MIN_VALUE) {
               this.debugStats.noSampleRejects++;
               return this.cacheContribution(receiver, NeoCpuFloodShadowBuilder.ContributionData.REJECTED);
            } else {
               int keyCount = this.fillReceiverContributionKeys(receiver, basePosKey, this.receiverContributionKeys);
               NeoCpuFloodShadowBuilder.ContributionData data = this.buildContributionData(
                  torchState, receiver, sourceKey, this.receiverContributionKeys, keyCount, sampleX, sampleY, sampleZ
               );
               return this.cacheContribution(receiver, data);
            }
         }
      }
   }

   private NeoCpuFloodShadowBuilder.ContributionData cacheContribution(
      NeoCpuShadowTypes.ReceiverSurface receiver, NeoCpuFloodShadowBuilder.ContributionData data
   ) {
      this.contributionDataCache.put(receiver.blockKey() ^ (long)receiver.face().ordinal() << 58 ^ planeGroupKey(receiver.face(), receiver.plane()), data);
      return data;
   }

   private NeoCpuFloodShadowBuilder.ContributionData buildContributionData(
      TorchRtxState torchState,
      NeoCpuShadowTypes.ReceiverSurface receiver,
      long sourceKey,
      long[] candidateKeys,
      int candidateKeyCount,
      double sampleX,
      double sampleY,
      double sampleZ
   ) {
      for (int index = 0; index < candidateKeyCount; index++) {
         long sampleKey = candidateKeys[index];
         if (sampleKey != Long.MIN_VALUE) {
            boolean hasCoverage = this.hasPositiveCoverageAt(torchState, sampleKey);
            if (hasCoverage) {
               if (this.dominantSourceAt(torchState, sampleKey) == sourceKey) {
                  this.debugStats.dominantBaseAccepts++;
                  return new NeoCpuFloodShadowBuilder.ContributionData(true, 1.0F);
               }

               if (this.sourceContributesCached(torchState, sourceKey, sampleKey)) {
                  this.contributionCollector.reset(sourceKey, receiver, sampleX, sampleY, sampleZ);
                  torchState.forEachContributor(sampleKey, this.contributionCollector);
                  if (this.contributionCollector.accepted()) {
                     this.debugStats.contributorAccepts++;
                     return new NeoCpuFloodShadowBuilder.ContributionData(true, this.contributionCollector.weightScale());
                  }

                  this.debugStats.contributorAccepts++;
                  return new NeoCpuFloodShadowBuilder.ContributionData(true, 0.85F);
               }
            }
         }
      }

      this.debugStats.contributorRejects++;
      return NeoCpuFloodShadowBuilder.ContributionData.REJECTED;
   }

   private int fillReceiverContributionKeys(NeoCpuShadowTypes.ReceiverSurface receiver, long basePosKey, long[] out) {
      out[0] = basePosKey;
      Direction axisU = firstFaceTangent(receiver.face());
      Direction axisV = secondFaceTangent(receiver.face());
      out[1] = offsetBlockKey(basePosKey, axisU.getStepX(), axisU.getStepY(), axisU.getStepZ());
      out[2] = offsetBlockKey(basePosKey, -axisU.getStepX(), -axisU.getStepY(), -axisU.getStepZ());
      out[3] = offsetBlockKey(basePosKey, axisV.getStepX(), axisV.getStepY(), axisV.getStepZ());
      out[4] = offsetBlockKey(basePosKey, -axisV.getStepX(), -axisV.getStepY(), -axisV.getStepZ());
      out[5] = offsetBlockKey(basePosKey, axisU.getStepX() + axisV.getStepX(), axisU.getStepY() + axisV.getStepY(), axisU.getStepZ() + axisV.getStepZ());
      out[6] = offsetBlockKey(basePosKey, axisU.getStepX() - axisV.getStepX(), axisU.getStepY() - axisV.getStepY(), axisU.getStepZ() - axisV.getStepZ());
      out[7] = offsetBlockKey(basePosKey, -axisU.getStepX() + axisV.getStepX(), -axisU.getStepY() + axisV.getStepY(), -axisU.getStepZ() + axisV.getStepZ());
      out[8] = offsetBlockKey(basePosKey, -axisU.getStepX() - axisV.getStepX(), -axisU.getStepY() - axisV.getStepY(), -axisU.getStepZ() - axisV.getStepZ());
      return 9;
   }

   private static long offsetBlockKey(long basePosKey, int dx, int dy, int dz) {
      return BlockPos.asLong(BlockPos.getX(basePosKey) + dx, BlockPos.getY(basePosKey) + dy, BlockPos.getZ(basePosKey) + dz);
   }

   private boolean sourceContributesCached(TorchRtxState torchState, long sourceKey, long sampleKey) {
      Long2ByteOpenHashMap cache = (Long2ByteOpenHashMap)this.sourceContributionCacheBySource.get(sourceKey);
      if (cache == null) {
         cache = new Long2ByteOpenHashMap();
         cache.defaultReturnValue((byte)0);
         this.sourceContributionCacheBySource.put(sourceKey, cache);
      } else {
         byte cached = cache.get(sampleKey);
         if (cached != 0) {
            return cached > 0;
         }
      }

      boolean contributes = torchState.sourceContributesTo(sourceKey, sampleKey);
      cache.put(sampleKey, (byte)(contributes ? 1 : -1));
      return contributes;
   }

   private long dominantSourceAt(TorchRtxState torchState, long sampleKey) {
      if (this.dominantSourceCache.containsKey(sampleKey)) {
         return (Long)this.dominantSourceCache.get(sampleKey);
      } else {
         long dominant = torchState.getDominantSource(sampleKey);
         this.dominantSourceCache.put(sampleKey, dominant);
         return dominant;
      }
   }

   private boolean hasPositiveCoverageAt(TorchRtxState torchState, long sampleKey) {
      byte cached = this.sampleCoverageCache.get(sampleKey);
      if (cached != 0) {
         return cached > 0;
      } else {
         boolean positive = torchState.sampleCoverage(sampleKey) > 0;
         this.sampleCoverageCache.put(sampleKey, (byte)(positive ? 1 : -1));
         return positive;
      }
   }

   private void buildPlaneGroup(
      NeoCpuLocalBlockAccessor accessor,
      long sourceKey,
      Vec3 source,
      double radius,
      NeoCpuFloodShadowBuilder.PlaneGroup group,
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      if (!group.receivers.isEmpty()) {
         group.hitsByBlock.clear();

         for (NeoCpuFloodShadowBuilder.ReceiverEntry entry : group.receivers) {
            if (group.hitsByBlock.size() >= 1024) {
               break;
            }

            this.sampleReceiver(accessor, sourceKey, source, entry.receiver(), radius, group.hitsByBlock);
         }

         ObjectIterator var16 = group.hitsByBlock.values().iterator();

         while (var16.hasNext()) {
            NeoCpuFloodShadowBuilder.Hit hit = (NeoCpuFloodShadowBuilder.Hit)var16.next();
            this.debugStats.hitBlocks++;
            NeoCpuShadowTypes.Occluder[] occluders = this.occludersAt(accessor, hit.occluder().blockKey());
            if (occluders != null && occluders.length != 0) {
               for (NeoCpuShadowTypes.Occluder occluder : occluders) {
                  this.appendProjectedOccluderShadow(source, group, occluder, polygons);
                  if (polygons.size() >= 8192) {
                     return;
                  }
               }
            } else {
               this.appendProjectedOccluderShadow(source, group, hit.occluder(), polygons);
               if (polygons.size() >= 8192) {
                  return;
               }
            }
         }
      }
   }

   private void sampleReceiver(
      NeoCpuLocalBlockAccessor accessor,
      long sourceKey,
      Vec3 source,
      NeoCpuShadowTypes.ReceiverSurface receiver,
      double radius,
      Long2ObjectOpenHashMap<NeoCpuFloodShadowBuilder.Hit> hitsByBlock
   ) {
      double minU = receiver.minU();
      double maxU = receiver.maxU();
      double minV = receiver.minV();
      double maxV = receiver.maxV();
      ArrayList<NeoCpuFloodShadowBuilder.Hit> localHits = new ArrayList<>(9);
      NeoCpuFloodShadowBuilder.ProbeStats probeStats = new NeoCpuFloodShadowBuilder.ProbeStats();
      NeoCpuFloodShadowBuilder.ProbeSample center = this.sampleReceiverPoint(
         accessor, sourceKey, source, receiver, (minU + maxU) * 0.5, (minV + maxV) * 0.5, radius, localHits
      );
      probeStats.recordCenter(center);
      if (!shouldContinueReceiverSampling(probeStats, false)) {
         mergeReceiverHits(hitsByBlock, localHits);
      } else {
         for (int index = 0; index < CROSS_PROBE_LOCAL_U.length && localHits.size() < 9; index++) {
            NeoCpuFloodShadowBuilder.ProbeSample cross = this.sampleReceiverPoint(
               accessor,
               sourceKey,
               source,
               receiver,
               minU + (maxU - minU) * CROSS_PROBE_LOCAL_U[index],
               minV + (maxV - minV) * CROSS_PROBE_LOCAL_V[index],
               radius,
               localHits
            );
            probeStats.recordCross(cross);
            if (!shouldContinueReceiverSampling(probeStats, false)) {
               mergeReceiverHits(hitsByBlock, localHits);
               return;
            }
         }

         if (!shouldContinueReceiverSampling(probeStats, true)) {
            mergeReceiverHits(hitsByBlock, localHits);
         } else {
            for (int indexx = 0; indexx < CORNER_PROBE_LOCAL_U.length && localHits.size() < 9; indexx++) {
               NeoCpuFloodShadowBuilder.ProbeSample corner = this.sampleReceiverPoint(
                  accessor,
                  sourceKey,
                  source,
                  receiver,
                  minU + (maxU - minU) * CORNER_PROBE_LOCAL_U[indexx],
                  minV + (maxV - minV) * CORNER_PROBE_LOCAL_V[indexx],
                  radius,
                  localHits
               );
               probeStats.recordCorner(corner);
               if (!shouldContinueReceiverSampling(probeStats, true)) {
                  break;
               }
            }

            mergeReceiverHits(hitsByBlock, localHits);
         }
      }
   }

   private static void mergeReceiverHits(Long2ObjectOpenHashMap<NeoCpuFloodShadowBuilder.Hit> hitsByBlock, ArrayList<NeoCpuFloodShadowBuilder.Hit> localHits) {
      for (NeoCpuFloodShadowBuilder.Hit hit : localHits) {
         if (hitsByBlock.size() >= 1024) {
            return;
         }

         long key = hit.occluder().blockKey();
         if (!hitsByBlock.containsKey(key)) {
            hitsByBlock.put(key, hit);
         }
      }
   }

   private NeoCpuFloodShadowBuilder.ProbeSample sampleReceiverPoint(
      NeoCpuLocalBlockAccessor accessor,
      long sourceKey,
      Vec3 source,
      NeoCpuShadowTypes.ReceiverSurface receiver,
      double u,
      double v,
      double radius,
      ArrayList<NeoCpuFloodShadowBuilder.Hit> hits
   ) {
      double targetX = receiver.sampleX(u, v) + receiver.normalX() * 0.0015;
      double targetY = receiver.sampleY(u, v) + receiver.normalY() * 0.0015;
      double targetZ = receiver.sampleZ(u, v) + receiver.normalZ() * 0.0015;
      double dx = targetX - source.x;
      double dy = targetY - source.y;
      double dz = targetZ - source.z;
      double distanceSq = dx * dx + dy * dy + dz * dz;
      if (!(distanceSq <= 1.0E-4) && !(distanceSq > radius * radius)) {
         double distance = Math.sqrt(distanceSq);
         NeoCpuFloodShadowBuilder.Hit hit = this.traceFirstHit(accessor, source.x, source.y, source.z, targetX, targetY, targetZ, sourceKey, receiver);
         if (hit != null) {
            addUniqueHit(hits, hit);
         }

         return new NeoCpuFloodShadowBuilder.ProbeSample(hit, isSuspiciousProbe(receiver, dx, dy, dz, distance, hit));
      } else {
         return NeoCpuFloodShadowBuilder.ProbeSample.NONE;
      }
   }

   private static void addUniqueHit(ArrayList<NeoCpuFloodShadowBuilder.Hit> hits, NeoCpuFloodShadowBuilder.Hit hit) {
      for (NeoCpuFloodShadowBuilder.Hit existing : hits) {
         if (existing.occluder() == hit.occluder()) {
            return;
         }
      }

      if (hits.size() < 9) {
         hits.add(hit);
      }
   }

   private NeoCpuFloodShadowBuilder.Hit traceFirstHit(
      NeoCpuLocalBlockAccessor accessor,
      double sourceX,
      double sourceY,
      double sourceZ,
      double targetX,
      double targetY,
      double targetZ,
      long sourceKey,
      NeoCpuShadowTypes.ReceiverSurface receiver
   ) {
      double dx = targetX - sourceX;
      double dy = targetY - sourceY;
      double dz = targetZ - sourceZ;
      double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (distance <= 1.0E-6) {
         return null;
      } else {
         double dirX = dx / distance;
         double dirY = dy / distance;
         double dirZ = dz / distance;
         double invDirX = Math.abs(dirX) <= 1.0E-6 ? Double.POSITIVE_INFINITY : 1.0 / dirX;
         double invDirY = Math.abs(dirY) <= 1.0E-6 ? Double.POSITIVE_INFINITY : 1.0 / dirY;
         double invDirZ = Math.abs(dirZ) <= 1.0E-6 ? Double.POSITIVE_INFINITY : 1.0 / dirZ;
         int blockX = Mth.floor(sourceX);
         int blockY = Mth.floor(sourceY);
         int blockZ = Mth.floor(sourceZ);
         int stepX = dirX > 0.0 ? 1 : (dirX < 0.0 ? -1 : 0);
         int stepY = dirY > 0.0 ? 1 : (dirY < 0.0 ? -1 : 0);
         int stepZ = dirZ > 0.0 ? 1 : (dirZ < 0.0 ? -1 : 0);
         double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? (double)blockX + 1.0 : (double)blockX) - sourceX) / dirX;
         double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : ((stepY > 0 ? (double)blockY + 1.0 : (double)blockY) - sourceY) / dirY;
         double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? (double)blockZ + 1.0 : (double)blockZ) - sourceZ) / dirZ;
         double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(invDirX);
         double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(invDirY);
         double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(invDirZ);
         double traveled = 0.0;
         double bestT = distance;

         while (traveled < bestT - 1.0E-4) {
            long blockKey = BlockPos.asLong(blockX, blockY, blockZ);
            if (blockKey != sourceKey || receiver.blockKey() == sourceKey) {
               NeoCpuShadowTypes.Occluder[] occluders = this.occludersAt(accessor, blockKey);
               if (occluders != null) {
                  for (NeoCpuShadowTypes.Occluder occluder : occluders) {
                     double hitT = intersectOccluder(occluder, sourceX, sourceY, sourceZ, dirX, dirY, dirZ, invDirX, invDirY, invDirZ, bestT);
                     if ((blockKey != receiver.blockKey() || !isReceiverSurfaceSelfHit(receiver, sourceX, sourceY, sourceZ, dirX, dirY, dirZ, hitT))
                        && occluder.opacity() > 0.0F
                        && hitT > 0.035
                        && hitT < bestT - 1.0E-4) {
                        return new NeoCpuFloodShadowBuilder.Hit(occluder, hitT);
                     }
                  }
               }
            }

            double next = Math.min(bestT, Math.min(tMaxX, Math.min(tMaxY, tMaxZ)));
            if (next <= traveled + 1.0E-7) {
               break;
            }

            traveled = next;
            boolean advanceX = tMaxX <= next + 1.0E-9;
            boolean advanceY = tMaxY <= next + 1.0E-9;
            boolean advanceZ = tMaxZ <= next + 1.0E-9;
            if (advanceX) {
               blockX += stepX;
               tMaxX += tDeltaX;
            }

            if (advanceY) {
               blockY += stepY;
               tMaxY += tDeltaY;
            }

            if (advanceZ) {
               blockZ += stepZ;
               tMaxZ += tDeltaZ;
            }
         }

         return null;
      }
   }

   private NeoCpuShadowTypes.Occluder[] occludersAt(NeoCpuLocalBlockAccessor accessor, long blockKey) {
      if (this.occluderCache.containsKey(blockKey)) {
         return (NeoCpuShadowTypes.Occluder[])this.occluderCache.get(blockKey);
      } else {
         NeoCpuShadowShape shape = accessor.shapeAt(blockKey);
         NeoCpuShadowTypes.Occluder[] occluders = null;
         if (shape.hasOccluders()) {
            occluders = shape.instantiateOccluders(blockKey, BlockPos.getX(blockKey), BlockPos.getY(blockKey), BlockPos.getZ(blockKey));
         }

         this.occluderCache.put(blockKey, occluders);
         return occluders;
      }
   }

   void appendProjectedOccluderShadow(
      Vec3 source,
      List<NeoCpuFloodShadowBuilder.ReceiverEntry> receivers,
      NeoCpuShadowTypes.Occluder occluder,
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      if (!receivers.isEmpty()) {
         this.projected.clear();
         NeoCpuShadowTypes.ReceiverSurface reference = receivers.get(0).receiver();
         if (occluder instanceof NeoCpuShadowTypes.Box box) {
            this.appendProjectedBoxShadow(source, reference, box);
         } else {
            if (!(occluder instanceof NeoCpuShadowTypes.QuadOccluder quad)) {
               return;
            }

            this.appendProjectedQuadShadow(source, reference, quad.vertices());
         }

         if (this.projected.size() >= 3) {
            this.buildHull(this.projected, this.hull);
            if (this.hull.size() >= 3) {
               double minU = Double.POSITIVE_INFINITY;
               double maxU = Double.NEGATIVE_INFINITY;
               double minV = Double.POSITIVE_INFINITY;
               double maxV = Double.NEGATIVE_INFINITY;

               for (NeoCpuShadowTypes.Uv point : this.hull) {
                  minU = Math.min(minU, point.u());
                  maxU = Math.max(maxU, point.u());
                  minV = Math.min(minV, point.v());
                  maxV = Math.max(maxV, point.v());
               }

               float strength = occluder.opacity();

               for (NeoCpuFloodShadowBuilder.ReceiverEntry entry : receivers) {
                  if (polygons.size() >= 8192) {
                     return;
                  }

                  this.appendProjectedShadowToReceiver(entry.receiver(), this.hull, minU, maxU, minV, maxV, entry.strength() * strength, polygons);
               }
            }
         }
      }
   }

   void appendProjectedOccluderShadowFromStaticGroup(
      Vec3 source,
      List<NeoCpuShadowTypes.ReceiverWithStrength> receivers,
      NeoCpuShadowTypes.Occluder occluder,
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      if (!receivers.isEmpty()) {
         this.projected.clear();
         NeoCpuShadowTypes.ReceiverSurface reference = receivers.get(0).receiver();
         if (occluder instanceof NeoCpuShadowTypes.Box box) {
            this.appendProjectedBoxShadow(source, reference, box);
         } else {
            if (!(occluder instanceof NeoCpuShadowTypes.QuadOccluder quad)) {
               return;
            }

            this.appendProjectedQuadShadow(source, reference, quad.vertices());
         }

         if (this.projected.size() >= 3) {
            this.buildHull(this.projected, this.hull);
            if (this.hull.size() >= 3) {
               double minU = Double.POSITIVE_INFINITY;
               double maxU = Double.NEGATIVE_INFINITY;
               double minV = Double.POSITIVE_INFINITY;
               double maxV = Double.NEGATIVE_INFINITY;

               for (NeoCpuShadowTypes.Uv point : this.hull) {
                  minU = Math.min(minU, point.u());
                  maxU = Math.max(maxU, point.u());
                  minV = Math.min(minV, point.v());
                  maxV = Math.max(maxV, point.v());
               }

               float strength = occluder.opacity();

               for (NeoCpuShadowTypes.ReceiverWithStrength entry : receivers) {
                  if (polygons.size() >= 8192) {
                     return;
                  }

                  this.appendProjectedShadowToReceiver(entry.receiver(), this.hull, minU, maxU, minV, maxV, entry.strength() * strength, polygons);
               }
            }
         }
      }
   }

   private void appendProjectedOccluderShadow(
      Vec3 source, NeoCpuFloodShadowBuilder.PlaneGroup group, NeoCpuShadowTypes.Occluder occluder, ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      this.appendProjectedOccluderShadow(source, group.receivers, occluder, polygons);
   }

   private void appendProjectedShadowToReceiver(
      NeoCpuShadowTypes.ReceiverSurface receiver,
      List<NeoCpuShadowTypes.Uv> planePolygon,
      double minU,
      double maxU,
      double minV,
      double maxV,
      float strength,
      ArrayList<NeoCpuShadowTypes.ShadowPolygon> polygons
   ) {
      if (!(strength <= 0.0F)) {
         if (!(receiver.maxU() <= minU) && !(receiver.minU() >= maxU) && !(receiver.maxV() <= minV) && !(receiver.minV() >= maxV)) {
            if (minU <= receiver.minU()
               && maxU >= receiver.maxU()
               && minV <= receiver.minV()
               && maxV >= receiver.maxV()
               && rectFullyInsideConvexPolygon(planePolygon, receiver)) {
               double[] vertices = this.triangulate(receiver, this.rectPolygon(receiver));
               if (vertices.length >= 9) {
                  polygons.add(new NeoCpuShadowTypes.ShadowPolygon(vertices, receiver.normalX(), receiver.normalY(), receiver.normalZ(), strength));
               }
            } else {
               this.polygonBuf.clear();
               this.polygonBuf.addAll(planePolygon);
               List<NeoCpuShadowTypes.Uv> clipped = this.clipPolygonToRect(receiver.minU(), receiver.maxU(), receiver.minV(), receiver.maxV());
               if (clipped.size() >= 3 && !(polygonArea(clipped) <= 1.0E-4)) {
                  this.buildHull(clipped, this.polygonBuf);
                  if (this.polygonBuf.size() >= 3 && !(polygonArea(this.polygonBuf) <= 1.0E-4)) {
                     List<NeoCpuShadowTypes.Uv> normalized = this.normalizeShadowPolygonToReceiver(this.polygonBuf, receiver);
                     if (normalized.size() >= 3 && !(polygonArea(normalized) <= 1.0E-4)) {
                        double[] triangleVertices = this.triangulate(receiver, normalized);
                        if (triangleVertices.length >= 9) {
                           polygons.add(
                              new NeoCpuShadowTypes.ShadowPolygon(triangleVertices, receiver.normalX(), receiver.normalY(), receiver.normalZ(), strength)
                           );
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void appendProjectedBoxShadow(Vec3 source, NeoCpuShadowTypes.ReceiverSurface receiver, NeoCpuShadowTypes.Box box) {
      this.boxCornerScratch[0][0] = box.minX();
      this.boxCornerScratch[0][1] = box.minY();
      this.boxCornerScratch[0][2] = box.minZ();
      this.boxCornerScratch[1][0] = box.maxX();
      this.boxCornerScratch[1][1] = box.minY();
      this.boxCornerScratch[1][2] = box.minZ();
      this.boxCornerScratch[2][0] = box.minX();
      this.boxCornerScratch[2][1] = box.maxY();
      this.boxCornerScratch[2][2] = box.minZ();
      this.boxCornerScratch[3][0] = box.maxX();
      this.boxCornerScratch[3][1] = box.maxY();
      this.boxCornerScratch[3][2] = box.minZ();
      this.boxCornerScratch[4][0] = box.minX();
      this.boxCornerScratch[4][1] = box.minY();
      this.boxCornerScratch[4][2] = box.maxZ();
      this.boxCornerScratch[5][0] = box.maxX();
      this.boxCornerScratch[5][1] = box.minY();
      this.boxCornerScratch[5][2] = box.maxZ();
      this.boxCornerScratch[6][0] = box.minX();
      this.boxCornerScratch[6][1] = box.maxY();
      this.boxCornerScratch[6][2] = box.maxZ();
      this.boxCornerScratch[7][0] = box.maxX();
      this.boxCornerScratch[7][1] = box.maxY();
      this.boxCornerScratch[7][2] = box.maxZ();

      for (int i = 0; i < 8; i++) {
         double[] corner = this.boxCornerScratch[i];
         this.boxCornerProjScratch[i] = projectWorldPoint(source, receiver, corner[0], corner[1], corner[2]);
         NeoCpuShadowTypes.Uv uv = this.boxCornerProjScratch[i].uv();
         if (uv != null) {
            this.projected.add(uv);
         }
      }

      for (int[] edge : BOX_EDGE_VERTEX_INDICES) {
         this.appendExtendedEdgeUv(
            source,
            receiver,
            this.boxCornerScratch[edge[0]],
            this.boxCornerScratch[edge[1]],
            this.boxCornerProjScratch[edge[0]],
            this.boxCornerProjScratch[edge[1]]
         );
      }
   }

   private void appendProjectedQuadShadow(Vec3 source, NeoCpuShadowTypes.ReceiverSurface receiver, Vec3[] vertices) {
      if (vertices.length >= 4) {
         for (int i = 0; i < 4; i++) {
            Vec3 vertex = vertices[i];
            this.quadCornerProjScratch[i] = projectWorldPoint(source, receiver, vertex.x, vertex.y, vertex.z);
            NeoCpuShadowTypes.Uv uv = this.quadCornerProjScratch[i].uv();
            if (uv != null) {
               this.projected.add(uv);
            }
         }

         for (int ix = 0; ix < 4; ix++) {
            int next = ix + 1 & 3;
            Vec3 start = vertices[ix];
            Vec3 end = vertices[next];
            this.appendExtendedEdgeUv(
               source,
               receiver,
               new double[]{start.x, start.y, start.z},
               new double[]{end.x, end.y, end.z},
               this.quadCornerProjScratch[ix],
               this.quadCornerProjScratch[next]
            );
         }
      }
   }

   private void appendExtendedEdgeUv(
      Vec3 source,
      NeoCpuShadowTypes.ReceiverSurface receiver,
      double[] start,
      double[] end,
      NeoCpuFloodShadowBuilder.CpuProjection startProjection,
      NeoCpuFloodShadowBuilder.CpuProjection endProjection
   ) {
      boolean startValid = startProjection.uv() != null;
      boolean endValid = endProjection.uv() != null;
      if (startValid != endValid) {
         double denominatorDelta = endProjection.denominator() - startProjection.denominator();
         if (!(Math.abs(denominatorDelta) <= 1.0E-6)) {
            double singularParam = -startProjection.denominator() / denominatorDelta;
            if (!(singularParam < -1.0E-6) && !(singularParam > 1.000001)) {
               singularParam = Math.max(0.0, Math.min(1.0, singularParam));
               double outerOffset = startValid ? -0.001 : 0.001;
               double innerOffset = startValid ? -2.5E-4 : 2.5E-4;
               double outerParam = clampUnitEdge(singularParam + outerOffset);
               double innerParam = clampUnitEdge(singularParam + innerOffset);
               if (!(Math.abs(innerParam - outerParam) <= 1.0E-6)) {
                  double ox = start[0] + (end[0] - start[0]) * outerParam;
                  double oy = start[1] + (end[1] - start[1]) * outerParam;
                  double oz = start[2] + (end[2] - start[2]) * outerParam;
                  double ix = start[0] + (end[0] - start[0]) * innerParam;
                  double iy = start[1] + (end[1] - start[1]) * innerParam;
                  double iz = start[2] + (end[2] - start[2]) * innerParam;
                  NeoCpuFloodShadowBuilder.CpuProjection outerP = projectWorldPoint(source, receiver, ox, oy, oz);
                  NeoCpuFloodShadowBuilder.CpuProjection innerP = projectWorldPoint(source, receiver, ix, iy, iz);
                  NeoCpuShadowTypes.Uv outerUv = outerP.uv();
                  NeoCpuShadowTypes.Uv innerUv = innerP.uv();
                  if (outerUv != null && innerUv != null) {
                     this.projected.add(outerUv);
                     this.projected.add(innerUv);
                     double directionU = innerUv.u() - outerUv.u();
                     double directionV = innerUv.v() - outerUv.v();
                     double directionLengthSq = directionU * directionU + directionV * directionV;
                     if (!(directionLengthSq <= 1.0E-6)) {
                        double directionLength = Math.sqrt(directionLengthSq);
                        this.projected
                           .add(new NeoCpuShadowTypes.Uv(innerUv.u() + directionU / directionLength * 8.0, innerUv.v() + directionV / directionLength * 8.0));
                     }
                  }
               }
            }
         }
      }
   }

   private static NeoCpuFloodShadowBuilder.CpuProjection projectWorldPoint(
      Vec3 source, NeoCpuShadowTypes.ReceiverSurface receiver, double x, double y, double z
   ) {
      double planeCoordinate = receiver.plane();

      double denominator = switch (receiver.face()) {
         case UP, DOWN -> y - source.y;
         case NORTH, SOUTH -> z - source.z;
         case WEST, EAST -> x - source.x;
         default -> throw new IncompatibleClassChangeError();
      };
      if (Math.abs(denominator) <= 1.0E-9) {
         return new NeoCpuFloodShadowBuilder.CpuProjection(denominator, Double.NaN, null);
      } else {
         double t = switch (receiver.face()) {
            case UP, DOWN -> (planeCoordinate - source.y) / denominator;
            case NORTH, SOUTH -> (planeCoordinate - source.z) / denominator;
            case WEST, EAST -> (planeCoordinate - source.x) / denominator;
            default -> throw new IncompatibleClassChangeError();
         };
         if (t <= 0.0) {
            return new NeoCpuFloodShadowBuilder.CpuProjection(denominator, t, null);
         } else {
            double projectedX = source.x + (x - source.x) * t;
            double projectedY = source.y + (y - source.y) * t;
            double projectedZ = source.z + (z - source.z) * t;
            return new NeoCpuFloodShadowBuilder.CpuProjection(denominator, t, receiver.worldToUv(projectedX, projectedY, projectedZ));
         }
      }
   }

   private void buildHull(List<NeoCpuShadowTypes.Uv> input, ArrayList<NeoCpuShadowTypes.Uv> output) {
      output.clear();
      this.sortBuf.clear();
      this.sortBuf.addAll(input);
      this.sortBuf.sort(Comparator.comparingDouble(NeoCpuShadowTypes.Uv::u).thenComparingDouble(NeoCpuShadowTypes.Uv::v));
      this.uniqueBuf.clear();

      for (NeoCpuShadowTypes.Uv point : this.sortBuf) {
         if (this.uniqueBuf.isEmpty() || !point.closeTo(this.uniqueBuf.get(this.uniqueBuf.size() - 1))) {
            this.uniqueBuf.add(point);
         }
      }

      if (this.uniqueBuf.size() < 3) {
         output.addAll(this.uniqueBuf);
      } else {
         this.lowerBuf.clear();

         for (NeoCpuShadowTypes.Uv pointx : this.uniqueBuf) {
            while (
               this.lowerBuf.size() >= 2 && cross(this.lowerBuf.get(this.lowerBuf.size() - 2), this.lowerBuf.get(this.lowerBuf.size() - 1), pointx) <= 1.0E-6
            ) {
               this.lowerBuf.remove(this.lowerBuf.size() - 1);
            }

            this.lowerBuf.add(pointx);
         }

         this.upperBuf.clear();

         for (int i = this.uniqueBuf.size() - 1; i >= 0; i--) {
            NeoCpuShadowTypes.Uv pointx = this.uniqueBuf.get(i);

            while (
               this.upperBuf.size() >= 2 && cross(this.upperBuf.get(this.upperBuf.size() - 2), this.upperBuf.get(this.upperBuf.size() - 1), pointx) <= 1.0E-6
            ) {
               this.upperBuf.remove(this.upperBuf.size() - 1);
            }

            this.upperBuf.add(pointx);
         }

         for (int i = 0; i < this.lowerBuf.size() - 1; i++) {
            output.add(this.lowerBuf.get(i));
         }

         for (int i = 0; i < this.upperBuf.size() - 1; i++) {
            output.add(this.upperBuf.get(i));
         }
      }
   }

   private List<NeoCpuShadowTypes.Uv> dilate(List<NeoCpuShadowTypes.Uv> polygon, double amount, ArrayList<NeoCpuShadowTypes.Uv> out) {
      if (!(amount <= 1.0E-6) && !polygon.isEmpty()) {
         double centerU = 0.0;
         double centerV = 0.0;

         for (NeoCpuShadowTypes.Uv point : polygon) {
            centerU += point.u();
            centerV += point.v();
         }

         centerU /= (double)polygon.size();
         centerV /= (double)polygon.size();
         out.clear();

         for (NeoCpuShadowTypes.Uv point : polygon) {
            double du = point.u() - centerU;
            double dv = point.v() - centerV;
            double length = Math.sqrt(du * du + dv * dv);
            if (length <= 1.0E-6) {
               out.add(point);
            } else {
               out.add(new NeoCpuShadowTypes.Uv(point.u() + du / length * amount, point.v() + dv / length * amount));
            }
         }

         return out;
      } else {
         return polygon;
      }
   }

   private List<NeoCpuShadowTypes.Uv> normalizeShadowPolygonToReceiver(List<NeoCpuShadowTypes.Uv> polygon, NeoCpuShadowTypes.ReceiverSurface receiver) {
      double rectWidth = receiver.width();
      double rectHeight = receiver.height();
      if (!(rectWidth <= 1.0E-6) && !(rectHeight <= 1.0E-6)) {
         double rectArea = rectWidth * rectHeight;
         if (rectArea <= 1.0E-4) {
            return polygon;
         } else if (polygonArea(polygon) / rectArea >= 0.988) {
            return this.rectPolygon(receiver);
         } else {
            this.localPolygon.clear();

            for (NeoCpuShadowTypes.Uv point : polygon) {
               this.localPolygon.add(new NeoCpuShadowTypes.Uv((point.u() - receiver.minU()) / rectWidth, (point.v() - receiver.minV()) / rectHeight));
            }

            List<NeoCpuShadowTypes.Uv> dilated = this.dilate(this.localPolygon, shadowPolygonDilation(receiver.face()), this.dilatedPolygon);
            List<NeoCpuShadowTypes.Uv> snapped = this.snapPolygonToEdges(dilated, shadowEdgeSnapEpsilon(receiver.face()), this.snappedPolygon);
            this.polygonBuf.clear();
            this.polygonBuf.addAll(snapped);
            List<NeoCpuShadowTypes.Uv> clippedLocal = this.clipPolygonToRect(0.0, 1.0, 0.0, 1.0);
            if (clippedLocal.size() < 3) {
               return polygon;
            } else {
               this.buildHull(clippedLocal, this.normalizedPolygon);
               if (this.normalizedPolygon.size() < 3) {
                  return polygon;
               } else if (polygonArea(this.normalizedPolygon) >= 0.988) {
                  return this.rectPolygon(receiver);
               } else {
                  for (int i = 0; i < this.normalizedPolygon.size(); i++) {
                     NeoCpuShadowTypes.Uv point = this.normalizedPolygon.get(i);
                     this.normalizedPolygon.set(i, new NeoCpuShadowTypes.Uv(receiver.minU() + point.u() * rectWidth, receiver.minV() + point.v() * rectHeight));
                  }

                  return this.normalizedPolygon;
               }
            }
         }
      } else {
         return polygon;
      }
   }

   private List<NeoCpuShadowTypes.Uv> snapPolygonToEdges(List<NeoCpuShadowTypes.Uv> polygon, double epsilon, ArrayList<NeoCpuShadowTypes.Uv> out) {
      if (!polygon.isEmpty() && !(epsilon <= 1.0E-6)) {
         out.clear();

         for (NeoCpuShadowTypes.Uv point : polygon) {
            out.add(new NeoCpuShadowTypes.Uv(snapUvCoordinate(point.u(), epsilon), snapUvCoordinate(point.v(), epsilon)));
         }

         return out;
      } else {
         return polygon;
      }
   }

   private List<NeoCpuShadowTypes.Uv> clipPolygonToRect(double minU, double maxU, double minV, double maxV) {
      this.clipA.clear();
      List<NeoCpuShadowTypes.Uv> input = this.polygonBuf;
      if (input.isEmpty()) {
         return this.clipA;
      } else {
         NeoCpuShadowTypes.Uv prev = input.get(input.size() - 1);
         boolean prevIn = prev.u() >= minU;

         for (NeoCpuShadowTypes.Uv cur : input) {
            boolean curIn = cur.u() >= minU;
            if (curIn) {
               if (!prevIn) {
                  double delta = cur.u() - prev.u();
                  double t = Math.abs(delta) <= 1.0E-9 ? 0.0 : (minU - prev.u()) / delta;
                  this.clipA.add(new NeoCpuShadowTypes.Uv(minU, prev.v() + (cur.v() - prev.v()) * t));
               }

               this.clipA.add(cur);
            } else if (prevIn) {
               double delta = cur.u() - prev.u();
               double t = Math.abs(delta) <= 1.0E-9 ? 0.0 : (minU - prev.u()) / delta;
               this.clipA.add(new NeoCpuShadowTypes.Uv(minU, prev.v() + (cur.v() - prev.v()) * t));
            }

            prev = cur;
            prevIn = curIn;
         }

         if (this.clipA.isEmpty()) {
            return this.clipA;
         } else {
            this.clipB.clear();
            input = this.clipA;
            prev = input.get(input.size() - 1);
            prevIn = prev.u() <= maxU;

            for (NeoCpuShadowTypes.Uv cur : input) {
               boolean curIn = cur.u() <= maxU;
               if (curIn) {
                  if (!prevIn) {
                     double delta = cur.u() - prev.u();
                     double t = Math.abs(delta) <= 1.0E-9 ? 0.0 : (maxU - prev.u()) / delta;
                     this.clipB.add(new NeoCpuShadowTypes.Uv(maxU, prev.v() + (cur.v() - prev.v()) * t));
                  }

                  this.clipB.add(cur);
               } else if (prevIn) {
                  double delta = cur.u() - prev.u();
                  double t = Math.abs(delta) <= 1.0E-9 ? 0.0 : (maxU - prev.u()) / delta;
                  this.clipB.add(new NeoCpuShadowTypes.Uv(maxU, prev.v() + (cur.v() - prev.v()) * t));
               }

               prev = cur;
               prevIn = curIn;
            }

            if (this.clipB.isEmpty()) {
               return this.clipB;
            } else {
               this.clipA.clear();
               input = this.clipB;
               prev = input.get(input.size() - 1);
               prevIn = prev.v() >= minV;

               for (NeoCpuShadowTypes.Uv cur : input) {
                  boolean curIn = cur.v() >= minV;
                  if (curIn) {
                     if (!prevIn) {
                        double delta = cur.v() - prev.v();
                        double t = Math.abs(delta) <= 1.0E-9 ? 0.0 : (minV - prev.v()) / delta;
                        this.clipA.add(new NeoCpuShadowTypes.Uv(prev.u() + (cur.u() - prev.u()) * t, minV));
                     }

                     this.clipA.add(cur);
                  } else if (prevIn) {
                     double delta = cur.v() - prev.v();
                     double t = Math.abs(delta) <= 1.0E-9 ? 0.0 : (minV - prev.v()) / delta;
                     this.clipA.add(new NeoCpuShadowTypes.Uv(prev.u() + (cur.u() - prev.u()) * t, minV));
                  }

                  prev = cur;
                  prevIn = curIn;
               }

               if (this.clipA.isEmpty()) {
                  return this.clipA;
               } else {
                  this.clipB.clear();
                  input = this.clipA;
                  prev = input.get(input.size() - 1);
                  prevIn = prev.v() <= maxV;

                  for (NeoCpuShadowTypes.Uv cur : input) {
                     boolean curIn = cur.v() <= maxV;
                     if (curIn) {
                        if (!prevIn) {
                           double delta = cur.v() - prev.v();
                           double t = Math.abs(delta) <= 1.0E-9 ? 0.0 : (maxV - prev.v()) / delta;
                           this.clipB.add(new NeoCpuShadowTypes.Uv(prev.u() + (cur.u() - prev.u()) * t, maxV));
                        }

                        this.clipB.add(cur);
                     } else if (prevIn) {
                        double delta = cur.v() - prev.v();
                        double t = Math.abs(delta) <= 1.0E-9 ? 0.0 : (maxV - prev.v()) / delta;
                        this.clipB.add(new NeoCpuShadowTypes.Uv(prev.u() + (cur.u() - prev.u()) * t, maxV));
                     }

                     prev = cur;
                     prevIn = curIn;
                  }

                  return this.clipB;
               }
            }
         }
      }
   }

   private double[] triangulate(NeoCpuShadowTypes.ReceiverSurface receiver, List<NeoCpuShadowTypes.Uv> polygon) {
      int triangleCount = polygon.size() - 2;
      double[] vertices = new double[triangleCount * 9];
      NeoCpuShadowTypes.Uv first = polygon.get(0);
      int offset = 0;

      for (int i = 1; i < polygon.size() - 1; i++) {
         offset = writeVertex(receiver, first, vertices, offset);
         offset = writeVertex(receiver, polygon.get(i), vertices, offset);
         offset = writeVertex(receiver, polygon.get(i + 1), vertices, offset);
      }

      return vertices;
   }

   private static int writeVertex(NeoCpuShadowTypes.ReceiverSurface receiver, NeoCpuShadowTypes.Uv point, double[] vertices, int offset) {
      vertices[offset] = receiver.sampleX(point.u(), point.v()) + receiver.normalX() * 0.0015;
      vertices[offset + 1] = receiver.sampleY(point.u(), point.v()) + receiver.normalY() * 0.0015;
      vertices[offset + 2] = receiver.sampleZ(point.u(), point.v()) + receiver.normalZ() * 0.0015;
      return offset + 3;
   }

   private static boolean rectFullyInsideConvexPolygon(List<NeoCpuShadowTypes.Uv> polygon, NeoCpuShadowTypes.ReceiverSurface receiver) {
      return polygon.size() < 3
         ? false
         : convexPolygonContainsPoint(polygon, receiver.minU(), receiver.minV())
            && convexPolygonContainsPoint(polygon, receiver.maxU(), receiver.minV())
            && convexPolygonContainsPoint(polygon, receiver.maxU(), receiver.maxV())
            && convexPolygonContainsPoint(polygon, receiver.minU(), receiver.maxV());
   }

   private static boolean convexPolygonContainsPoint(List<NeoCpuShadowTypes.Uv> polygon, double u, double v) {
      double sign = 0.0;
      int index = 0;

      for (int size = polygon.size(); index < size; index++) {
         NeoCpuShadowTypes.Uv current = polygon.get(index);
         NeoCpuShadowTypes.Uv next = polygon.get((index + 1) % size);
         double cross = (next.u() - current.u()) * (v - current.v()) - (next.v() - current.v()) * (u - current.u());
         if (!(Math.abs(cross) <= 1.0E-6)) {
            if (sign == 0.0) {
               sign = Math.copySign(1.0, cross);
            } else if (cross * sign < -1.0E-6) {
               return false;
            }
         }
      }

      return true;
   }

   private List<NeoCpuShadowTypes.Uv> rectPolygon(NeoCpuShadowTypes.ReceiverSurface receiver) {
      this.rectPolygon.clear();
      this.rectPolygon.add(new NeoCpuShadowTypes.Uv(receiver.minU(), receiver.minV()));
      this.rectPolygon.add(new NeoCpuShadowTypes.Uv(receiver.maxU(), receiver.minV()));
      this.rectPolygon.add(new NeoCpuShadowTypes.Uv(receiver.maxU(), receiver.maxV()));
      this.rectPolygon.add(new NeoCpuShadowTypes.Uv(receiver.minU(), receiver.maxV()));
      return this.rectPolygon;
   }

   private static double polygonArea(List<NeoCpuShadowTypes.Uv> polygon) {
      double area = 0.0;

      for (int i = 0; i < polygon.size(); i++) {
         NeoCpuShadowTypes.Uv a = polygon.get(i);
         NeoCpuShadowTypes.Uv b = polygon.get((i + 1) % polygon.size());
         area += a.u() * b.v() - b.u() * a.v();
      }

      return Math.abs(area) * 0.5;
   }

   private static double cross(NeoCpuShadowTypes.Uv a, NeoCpuShadowTypes.Uv b, NeoCpuShadowTypes.Uv c) {
      return (b.u() - a.u()) * (c.v() - a.v()) - (b.v() - a.v()) * (c.u() - a.u());
   }

   private static double shadowPolygonDilation(Direction face) {
      return isHorizontalFace(face) ? 0.024 : 0.016;
   }

   private static double shadowEdgeSnapEpsilon(Direction face) {
      return isHorizontalFace(face) ? 0.03 : 0.022;
   }

   private static boolean isHorizontalFace(Direction face) {
      return face == Direction.UP || face == Direction.DOWN;
   }

   private static double snapUvCoordinate(double value, double epsilon) {
      if (value <= epsilon) {
         return 0.0;
      } else {
         return value >= 1.0 - epsilon ? 1.0 : value;
      }
   }

   private static double clampUnitEdge(double value) {
      return Mth.clamp(value, 1.0E-6, 0.999999);
   }

   private static long planeGroupKey(Direction face, double plane) {
      long quantizedPlane = Math.round(plane * 1000000.0);
      return (long)face.ordinal() << 56 ^ quantizedPlane & 72057594037927935L;
   }

   private static long sampleRtxPosKey(NeoCpuShadowTypes.ReceiverSurface receiver, double sampleX, double sampleY, double sampleZ) {
      double outwardX = receiver.normalX() * 0.05;
      double outwardY = receiver.normalY() * 0.05;
      double outwardZ = receiver.normalZ() * 0.05;
      int sampleBlockX = Mth.floor(sampleX + outwardX);
      int sampleBlockY = Mth.floor(sampleY + outwardY);
      int sampleBlockZ = Mth.floor(sampleZ + outwardZ);
      long sampleKey = BlockPos.asLong(sampleBlockX, sampleBlockY, sampleBlockZ);
      return sampleKey == receiver.blockKey()
         ? BlockPos.asLong(
            BlockPos.getX(receiver.blockKey()) + (int)receiver.normalX(),
            BlockPos.getY(receiver.blockKey()) + (int)receiver.normalY(),
            BlockPos.getZ(receiver.blockKey()) + (int)receiver.normalZ()
         )
         : sampleKey;
   }

   private static Direction firstFaceTangent(Direction face) {
      return switch (face) {
         case UP, DOWN, NORTH, SOUTH -> Direction.EAST;
         case WEST, EAST -> Direction.SOUTH;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static Direction secondFaceTangent(Direction face) {
      return switch (face) {
         case UP, DOWN -> Direction.SOUTH;
         case NORTH, SOUTH, WEST, EAST -> Direction.UP;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static boolean isReceiverSurfaceSelfHit(
      NeoCpuShadowTypes.ReceiverSurface receiver, double sourceX, double sourceY, double sourceZ, double dirX, double dirY, double dirZ, double hitT
   ) {
      if (!Double.isFinite(hitT)) {
         return false;
      } else {
         double hitX = sourceX + dirX * hitT;
         double hitY = sourceY + dirY * hitT;
         double hitZ = sourceZ + dirZ * hitT;

         double hitPlane = switch (receiver.face()) {
            case UP, DOWN -> hitY;
            case NORTH, SOUTH -> hitZ;
            case WEST, EAST -> hitX;
            default -> throw new IncompatibleClassChangeError();
         };
         if (Math.abs(hitPlane - receiver.plane()) > 1.0E-4) {
            return false;
         } else {
            NeoCpuShadowTypes.Uv hitUv = receiver.worldToUv(hitX, hitY, hitZ);
            NeoCpuShadowTypes.Uv sourceUv = receiver.worldToUv(sourceX, sourceY, sourceZ);
            double du = hitUv.u() - sourceUv.u();
            double dv = hitUv.v() - sourceUv.v();
            return du * du + dv * dv <= 1.0E-8;
         }
      }
   }

   private static boolean isSuspiciousProbe(
      NeoCpuShadowTypes.ReceiverSurface receiver, double dx, double dy, double dz, double distance, NeoCpuFloodShadowBuilder.Hit hit
   ) {
      if (distance <= 1.0E-6) {
         return false;
      } else {
         double alignment = Math.abs((dx * receiver.normalX() + dy * receiver.normalY() + dz * receiver.normalZ()) / distance);
         boolean largeReceiver = Math.max(receiver.width(), receiver.height()) >= 0.72;
         if (hit == null) {
            return largeReceiver && alignment <= 0.26;
         } else if (!(hit.occluder() instanceof NeoCpuShadowTypes.Box)) {
            return true;
         } else {
            double travelFraction = hit.distance() / distance;
            return alignment <= 0.26 || travelFraction <= 0.18 || travelFraction >= 0.82;
         }
      }
   }

   private static boolean shouldContinueReceiverSampling(NeoCpuFloodShadowBuilder.ProbeStats probeStats, boolean cornersStarted) {
      NeoCpuFloodShadowBuilder.ProbeSample center = probeStats.center();
      if (center == null) {
         return false;
      } else if (center.hit() == null) {
         int cleanThreshold = center.suspicious() ? 4 : 3;
         if (probeStats.hitCount() == 0 && probeStats.totalProbes() >= cleanThreshold) {
            return false;
         } else if (!cornersStarted) {
            return probeStats.crossProbes() < CROSS_PROBE_LOCAL_U.length ? true : probeStats.hitCount() > 0;
         } else {
            return probeStats.cornerProbes() < CORNER_PROBE_LOCAL_U.length;
         }
      } else if (!center.suspicious()
         && center.hit().occluder() instanceof NeoCpuShadowTypes.Box
         && probeStats.missCount() == 0
         && probeStats.differentHitCount() == 0
         && probeStats.totalProbes() >= 2) {
         return false;
      } else {
         int stableThreshold = center.suspicious() ? 4 : 3;
         if (probeStats.sameAsCenterHitCount() >= stableThreshold && probeStats.missCount() == 0 && probeStats.differentHitCount() == 0) {
            return false;
         } else if (!cornersStarted) {
            return probeStats.crossProbes() < CROSS_PROBE_LOCAL_U.length ? true : probeStats.missCount() > 0 || probeStats.differentHitCount() > 0;
         } else {
            return probeStats.cornerProbes() < CORNER_PROBE_LOCAL_U.length;
         }
      }
   }

   private static double intersectOccluder(
      NeoCpuShadowTypes.Occluder occluder,
      double sourceX,
      double sourceY,
      double sourceZ,
      double dirX,
      double dirY,
      double dirZ,
      double invDirX,
      double invDirY,
      double invDirZ,
      double maxDistance
   ) {
      if (occluder instanceof NeoCpuShadowTypes.Box box) {
         return intersectBox(box, sourceX, sourceY, sourceZ, invDirX, invDirY, invDirZ, maxDistance);
      } else {
         return occluder instanceof NeoCpuShadowTypes.QuadOccluder quad
            ? intersectQuad(quad, sourceX, sourceY, sourceZ, dirX, dirY, dirZ, maxDistance)
            : Double.POSITIVE_INFINITY;
      }
   }

   private static double intersectBox(
      NeoCpuShadowTypes.Box box, double sourceX, double sourceY, double sourceZ, double invDirX, double invDirY, double invDirZ, double maxDistance
   ) {
      double t1 = (box.minX() - sourceX) * invDirX;
      double t2 = (box.maxX() - sourceX) * invDirX;
      double t3 = (box.minY() - sourceY) * invDirY;
      double t4 = (box.maxY() - sourceY) * invDirY;
      double t5 = (box.minZ() - sourceZ) * invDirZ;
      double t6 = (box.maxZ() - sourceZ) * invDirZ;
      double tMin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
      double tMax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));
      if (!(tMax < 0.0) && !(tMin > tMax) && !(tMin >= maxDistance)) {
         return tMin >= 0.0 ? tMin : tMax;
      } else {
         return Double.POSITIVE_INFINITY;
      }
   }

   private static double intersectQuad(
      NeoCpuShadowTypes.QuadOccluder quad, double sourceX, double sourceY, double sourceZ, double dirX, double dirY, double dirZ, double maxDistance
   ) {
      Vec3[] vertices = quad.vertices();
      if (vertices.length < 4) {
         return Double.POSITIVE_INFINITY;
      } else {
         double hitA = intersectTriangle(vertices[0], vertices[1], vertices[2], sourceX, sourceY, sourceZ, dirX, dirY, dirZ, maxDistance);
         double hitB = intersectTriangle(vertices[0], vertices[2], vertices[3], sourceX, sourceY, sourceZ, dirX, dirY, dirZ, maxDistance);
         return Math.min(hitA, hitB);
      }
   }

   private static double intersectTriangle(
      Vec3 a, Vec3 b, Vec3 c, double sourceX, double sourceY, double sourceZ, double dirX, double dirY, double dirZ, double maxDistance
   ) {
      double edge1X = b.x - a.x;
      double edge1Y = b.y - a.y;
      double edge1Z = b.z - a.z;
      double edge2X = c.x - a.x;
      double edge2Y = c.y - a.y;
      double edge2Z = c.z - a.z;
      double pvecX = dirY * edge2Z - dirZ * edge2Y;
      double pvecY = dirZ * edge2X - dirX * edge2Z;
      double pvecZ = dirX * edge2Y - dirY * edge2X;
      double det = edge1X * pvecX + edge1Y * pvecY + edge1Z * pvecZ;
      if (Math.abs(det) <= 1.0E-6) {
         return Double.POSITIVE_INFINITY;
      } else {
         double invDet = 1.0 / det;
         double tvecX = sourceX - a.x;
         double tvecY = sourceY - a.y;
         double tvecZ = sourceZ - a.z;
         double u = (tvecX * pvecX + tvecY * pvecY + tvecZ * pvecZ) * invDet;
         if (!(u < -1.0E-6) && !(u > 1.000001)) {
            double qvecX = tvecY * edge1Z - tvecZ * edge1Y;
            double qvecY = tvecZ * edge1X - tvecX * edge1Z;
            double qvecZ = tvecX * edge1Y - tvecY * edge1X;
            double v = (dirX * qvecX + dirY * qvecY + dirZ * qvecZ) * invDet;
            if (!(v < -1.0E-6) && !(u + v > 1.000001)) {
               double t = (edge2X * qvecX + edge2Y * qvecY + edge2Z * qvecZ) * invDet;
               return !(t <= 1.0E-6) && !(t >= maxDistance) ? t : Double.POSITIVE_INFINITY;
            } else {
               return Double.POSITIVE_INFINITY;
            }
         } else {
            return Double.POSITIVE_INFINITY;
         }
      }
   }

   private static final class ContributionCollector implements NeoFloodEngine.ContributorVisitor {
      private long sourceKey;
      private NeoCpuShadowTypes.ReceiverSurface receiver;
      private double sampleX;
      private double sampleY;
      private double sampleZ;
      private double totalScore;
      private double sourceScore;

      void reset(long sourceKey, NeoCpuShadowTypes.ReceiverSurface receiver, double sampleX, double sampleY, double sampleZ) {
         this.sourceKey = sourceKey;
         this.receiver = receiver;
         this.sampleX = sampleX;
         this.sampleY = sampleY;
         this.sampleZ = sampleZ;
         this.totalScore = 0.0;
         this.sourceScore = 0.0;
      }

      @Override
      public void visit(long candidateSourceKey, int light, int coverage, int sourceEmission) {
         if (sourceEmission > 0 && light > 0 && coverage > 0) {
            double dx = (double)BlockPos.getX(candidateSourceKey) + 0.5 - this.sampleX;
            double dy = (double)BlockPos.getY(candidateSourceKey) + 0.5 - this.sampleY;
            double dz = (double)BlockPos.getZ(candidateSourceKey) + 0.5 - this.sampleZ;
            double facing = dx * this.receiver.normalX() + dy * this.receiver.normalY() + dz * this.receiver.normalZ();
            if (!(facing <= 1.0E-4)) {
               double distanceSq = dx * dx + dy * dy + dz * dz;
               double coverageFactor = Math.max(0.15, (double)coverage / 255.0);
               double score = (double)light * coverageFactor / Math.max(0.25, distanceSq + 0.25);
               this.totalScore += score;
               if (candidateSourceKey == this.sourceKey) {
                  this.sourceScore += score;
               }
            }
         }
      }

      boolean accepted() {
         return this.sourceScore > 0.0 && (this.totalScore <= 0.0 || this.sourceScore / this.totalScore >= 0.2);
      }

      float weightScale() {
         return this.totalScore <= 0.0 ? 1.0F : Mth.clamp((float)Math.sqrt(this.sourceScore / this.totalScore), 0.35F, 1.0F);
      }
   }

   private static record ContributionData(boolean accepted, float weightScale) {
      private static final NeoCpuFloodShadowBuilder.ContributionData REJECTED = new NeoCpuFloodShadowBuilder.ContributionData(false, 0.0F);
   }

   private static record CpuProjection(double denominator, double t, NeoCpuShadowTypes.Uv uv) {
   }

   private static record Hit(NeoCpuShadowTypes.Occluder occluder, double distance) {
   }

   private static final class PlaneGroup {
      private final ArrayList<NeoCpuFloodShadowBuilder.ReceiverEntry> receivers = new ArrayList<>(64);
      private final Long2ObjectOpenHashMap<NeoCpuFloodShadowBuilder.Hit> hitsByBlock = new Long2ObjectOpenHashMap(32);
   }

   private static record ProbeSample(NeoCpuFloodShadowBuilder.Hit hit, boolean suspicious) {
      private static final NeoCpuFloodShadowBuilder.ProbeSample NONE = new NeoCpuFloodShadowBuilder.ProbeSample(null, false);
   }

   private static final class ProbeStats {
      private NeoCpuFloodShadowBuilder.ProbeSample center;
      private NeoCpuFloodShadowBuilder.Hit centerHit;
      private int totalProbes;
      private int crossProbes;
      private int cornerProbes;
      private int hitCount;
      private int missCount;
      private int sameAsCenterHitCount;
      private int differentHitCount;

      void recordCenter(NeoCpuFloodShadowBuilder.ProbeSample sample) {
         this.center = sample;
         this.centerHit = sample.hit();
         this.record(sample, true);
      }

      void recordCross(NeoCpuFloodShadowBuilder.ProbeSample sample) {
         this.crossProbes++;
         this.record(sample, false);
      }

      void recordCorner(NeoCpuFloodShadowBuilder.ProbeSample sample) {
         this.cornerProbes++;
         this.record(sample, false);
      }

      private void record(NeoCpuFloodShadowBuilder.ProbeSample sample, boolean isCenter) {
         this.totalProbes++;
         if (sample.hit() == null) {
            this.missCount++;
         } else {
            this.hitCount++;
            if (!isCenter) {
               if (this.centerHit != null && this.centerHit.occluder() == sample.hit().occluder()) {
                  this.sameAsCenterHitCount++;
               } else {
                  this.differentHitCount++;
               }
            }
         }
      }

      NeoCpuFloodShadowBuilder.ProbeSample center() {
         return this.center;
      }

      int totalProbes() {
         return this.totalProbes;
      }

      int crossProbes() {
         return this.crossProbes;
      }

      int cornerProbes() {
         return this.cornerProbes;
      }

      int hitCount() {
         return this.hitCount;
      }

      int missCount() {
         return this.missCount;
      }

      int sameAsCenterHitCount() {
         return this.sameAsCenterHitCount;
      }

      int differentHitCount() {
         return this.differentHitCount;
      }
   }

   static record ReceiverEntry(NeoCpuShadowTypes.ReceiverSurface receiver, float strength) {
   }

   private static final class SourceDebugStats {
      private long sourceKey;
      private int emission;
      private int boundaryFaces;
      private int acceptedReceivers;
      private int rejectedReceivers;
      private int dominantBaseAccepts;
      private int dominantSampleAccepts;
      private int contributorAccepts;
      private int noSampleRejects;
      private int contributorRejects;
      private int hitBlocks;
      private boolean hitPolygonCap;

      void reset(long sourceKey, int emission, int boundaryFaces) {
         this.sourceKey = sourceKey;
         this.emission = emission;
         this.boundaryFaces = boundaryFaces;
         this.acceptedReceivers = 0;
         this.rejectedReceivers = 0;
         this.dominantBaseAccepts = 0;
         this.dominantSampleAccepts = 0;
         this.contributorAccepts = 0;
         this.noSampleRejects = 0;
         this.contributorRejects = 0;
         this.hitBlocks = 0;
         this.hitPolygonCap = false;
      }
   }
}
