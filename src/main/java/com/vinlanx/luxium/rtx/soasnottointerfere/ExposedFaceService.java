package com.vinlanx.luxium.rtx.soasnottointerfere;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.rtx.TorchRtxState;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class ExposedFaceService {
   private static final double SURFACE_EPSILON = 1.0E-6;
   private static final int REQUESTED_SECTION_CAPTURES_PER_TICK = 2;
   private static final int MAX_DIRTY_BLOCKS_PER_TICK = 2048;
   private static final int MAX_ASYNC_COMMITS_PER_TICK = 64;
   private static final ExposedFaceService INSTANCE = new ExposedFaceService();
   private static final Direction[] FACES = Direction.values();
   private final ConcurrentHashMap<Long, ExposedFaceService.SectionFaceData> sectionFaces = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, ExposedFaceService.SectionFaceSnapshot> sectionFaceSnapshots = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, ExposedFaceService.SectionReceiverSnapshot> sectionReceivers = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, ExposedFaceService.SectionCompleteReceiverSurfaceCache> sectionCompleteReceiverSurfaces = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, Boolean> fullyCapturedSections = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<BlockState, ExposedFaceService.ReceiverShapeTemplate> receiverShapeCache = new ConcurrentHashMap<>();
   private final KeySetView<Long, Boolean> pendingDirtyBlocks = ConcurrentHashMap.newKeySet();
   private final ConcurrentHashMap<Long, Double> pendingSectionCaptures = new ConcurrentHashMap<>();
   private final ThreadLocal<ExposedFaceService.SectionCapture> activeCapture = new ThreadLocal<>();
   private final ExecutorService receiverBuildExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "Luxium-ReceiverBuild");
      t.setDaemon(true);
      t.setPriority(3);
      return t;
   });
   private final ConcurrentLinkedQueue<ExposedFaceService.BuiltSectionCommit> completedSectionBuilds = new ConcurrentLinkedQueue<>();
   private final ConcurrentHashMap<Long, Long> latestSectionBuildRevision = new ConcurrentHashMap<>();
   private final AtomicLong sectionBuildRevision = new AtomicLong();
   private static final ExposedFaceService.ReceiverSurface[] EMPTY_RECEIVER_SURFACES = new ExposedFaceService.ReceiverSurface[0];

   private ExposedFaceService() {
   }

   public static ExposedFaceService get() {
      return INSTANCE;
   }

   public boolean hasReceiverGeometryChanged(@Nullable ClientLevel level, BlockPos pos, @Nullable BlockState oldState, @Nullable BlockState newState) {
      if (level == null || pos == null || oldState == null || newState == null) {
         return true;
      } else if (oldState != newState && !oldState.equals(newState)) {
         if (oldState.isAir() == newState.isAir() && oldState.canOcclude() == newState.canOcclude() && oldState.getRenderShape() == newState.getRenderShape()) {
            ExposedFaceService.ReceiverShapeTemplate oldTemplate = this.resolveReceiverShapeTemplate(oldState, level, pos);
            ExposedFaceService.ReceiverShapeTemplate newTemplate = this.resolveReceiverShapeTemplate(newState, level, pos);
            return !oldTemplate.sameGeometry(newTemplate);
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   public void clear() {
      this.activeCapture.remove();
      this.sectionFaces.clear();
      this.sectionFaceSnapshots.clear();
      this.sectionReceivers.clear();
      this.sectionCompleteReceiverSurfaces.clear();
      this.fullyCapturedSections.clear();
      this.pendingDirtyBlocks.clear();
      this.pendingSectionCaptures.clear();
      this.completedSectionBuilds.clear();
      this.latestSectionBuildRevision.clear();
   }

   public void beginSectionCapture(@Nullable BlockPos sectionOrigin) {
      if (!isCacheActive()) {
         this.activeCapture.remove();
      } else if (sectionOrigin == null) {
         this.activeCapture.remove();
      } else {
         this.activeCapture.set(new ExposedFaceService.SectionCapture(sectionKeyFromOrigin(sectionOrigin)));
      }
   }

   public void finishSectionCapture() {
      ExposedFaceService.SectionCapture capture = this.activeCapture.get();
      this.activeCapture.remove();
      if (capture != null) {
         this.scheduleSectionBuild(capture.sectionKey, capture.detachFaceData(), true);
      }
   }

   public void discardSectionCapture() {
      this.activeCapture.remove();
   }

   public void recordFace(BlockPos pos, Direction face, BlockGetter level, BlockState state) {
      ExposedFaceService.SectionCapture capture = this.activeCapture.get();
      if (capture != null && pos != null && face != null) {
         if (this.hasReceiverGeometry(state, level, pos)) {
            capture.recordFace(pos.asLong(), face);
         }
      }
   }

   public List<ExposedFaceService.ReceiverSurface> getCompleteReceiverSurfaces(@Nullable ClientLevel level, long blockKey) {
      if (isCacheActive() && level != null) {
         BlockPos pos = BlockPos.of(blockKey);
         BlockState state = level.getBlockState(pos);
         ExposedFaceService.ReceiverShapeTemplate template = this.resolveReceiverShapeTemplate(state, level, pos);
         if (template.isEmpty()) {
            return Collections.emptyList();
         } else {
            byte visibleBoundaryMask = template.hasBoundaryFaces() ? this.getCompleteExposedFaceMask(level, blockKey, state, pos, template) : -1;
            return template.instantiate(blockKey, visibleBoundaryMask);
         }
      } else {
         return Collections.emptyList();
      }
   }

   public void forEachCompleteReceiverSurface(@Nullable ClientLevel level, long blockKey, ExposedFaceService.ReceiverSurfaceVisitor visitor) {
      if (isCacheActive() && level != null && visitor != null) {
         long sectionKey = sectionKeyForBlock(blockKey);
         if (!this.fullyCapturedSections.containsKey(sectionKey)) {
            BlockPos pos = BlockPos.of(blockKey);
            BlockState state = level.getBlockState(pos);
            ExposedFaceService.ReceiverShapeTemplate template = this.resolveReceiverShapeTemplate(state, level, pos);
            if (!template.isEmpty()) {
               byte visibleBoundaryMask = template.hasBoundaryFaces() ? this.getCompleteExposedFaceMask(level, blockKey, state, pos, template) : -1;
               template.forEachSurface(blockKey, visibleBoundaryMask, visitor);
            }
         } else {
            ExposedFaceService.SectionFaceSnapshot snapshot = this.sectionFaceSnapshots.get(sectionKey);
            if (snapshot != null && snapshot.hasCandidateReceivers()) {
               int localIndex = sectionLocalIndex(BlockPos.getX(blockKey) & 15, BlockPos.getY(blockKey) & 15, BlockPos.getZ(blockKey) & 15);
               byte visibleBoundaryMask = snapshot.faceMask(localIndex);
               if (visibleBoundaryMask != 0) {
                  ExposedFaceService.SectionCompleteReceiverSurfaceCache sectionCache = this.sectionCompleteReceiverSurfaces
                     .computeIfAbsent(sectionKey, ignored -> new ExposedFaceService.SectionCompleteReceiverSurfaceCache());
                  ExposedFaceService.ReceiverSurface[] cached = sectionCache.surfacesByBlock.get(blockKey);
                  if (cached == null) {
                     BlockPos pos = BlockPos.of(blockKey);
                     BlockState state = level.getBlockState(pos);
                     ExposedFaceService.ReceiverShapeTemplate template = this.resolveReceiverShapeTemplate(state, level, pos);
                     if (template.isEmpty()) {
                        return;
                     }

                     cached = template.instantiateArray(blockKey, visibleBoundaryMask);
                     ExposedFaceService.ReceiverSurface[] existing = sectionCache.surfacesByBlock.putIfAbsent(blockKey, cached);
                     if (existing != null) {
                        cached = existing;
                     }
                  }

                  for (ExposedFaceService.ReceiverSurface surface : cached) {
                     visitor.visit(
                        surface.face(), surface.planeCoordinate(), surface.minU(), surface.maxU(), surface.minV(), surface.maxV(), surface.surfaceKey()
                     );
                  }
               }
            }
         }
      }
   }

   public byte getExposedFaceMask(@Nullable ClientLevel level, long blockKey) {
      if (!isCacheActive()) {
         return 0;
      } else {
         long sectionKey = sectionKeyForBlock(blockKey);
         ExposedFaceService.SectionFaceData sectionData = this.sectionFaces.get(sectionKey);
         boolean fullyCaptured = this.fullyCapturedSections.containsKey(sectionKey);
         if (sectionData != null) {
            byte cachedMask = sectionData.faceMask(blockKey);
            return cachedMask == 0 && !fullyCaptured && level != null ? this.computeExposedFaceMask(level, BlockPos.of(blockKey)) : cachedMask;
         } else {
            return !fullyCaptured && level != null ? this.computeExposedFaceMask(level, BlockPos.of(blockKey)) : 0;
         }
      }
   }

   public byte getCompleteExposedFaceMask(@Nullable ClientLevel level, long blockKey) {
      if (!isCacheActive()) {
         return 0;
      } else {
         long sectionKey = sectionKeyForBlock(blockKey);
         if (level != null && !this.fullyCapturedSections.containsKey(sectionKey)) {
            BlockPos pos = BlockPos.of(blockKey);
            BlockState state = level.getBlockState(pos);
            ExposedFaceService.ReceiverShapeTemplate template = this.resolveReceiverShapeTemplate(state, level, pos);
            return template.computeFaceMask(state, level, pos);
         } else {
            return this.getExposedFaceMask(level, blockKey);
         }
      }
   }

   public void requestSectionCapture(long sectionKey, double priorityDistanceSq) {
      if (isCacheActive() && !this.fullyCapturedSections.containsKey(sectionKey)) {
         double priority = Double.isFinite(priorityDistanceSq) ? Math.max(0.0, priorityDistanceSq) : Double.MAX_VALUE;
         this.pendingSectionCaptures.merge(sectionKey, priority, Math::min);
      }
   }

   public void refreshAround(ClientLevel level, BlockPos center) {
      if (isCacheActive() && level != null && center != null) {
         this.pendingDirtyBlocks.add(center.asLong());
      }
   }

   public void refreshAroundNow(ClientLevel level, BlockPos center) {
      if (isCacheActive() && level != null && center != null) {
         Long2ObjectOpenHashMap<Long2ByteOpenHashMap> updatedSections = new Long2ObjectOpenHashMap();
         LongOpenHashSet changedBlocksForNotify = new LongOpenHashSet();
         LongOpenHashSet affectedSections = new LongOpenHashSet();
         changedBlocksForNotify.add(center.asLong());
         affectedSections.add(sectionKeyForBlock(center.asLong()));

         for (Direction face : FACES) {
            affectedSections.add(sectionKeyForBlock(center.relative(face).asLong()));
         }

         LongIterator sectionIt = affectedSections.iterator();

         while (sectionIt.hasNext()) {
            long sectionKey = sectionIt.nextLong();
            this.requestSectionCapture(sectionKey, 0.0);
         }

         this.updateLocalBlock(level, center, updatedSections);

         for (Direction face : FACES) {
            this.updateLocalBlock(level, center.relative(face), updatedSections);
         }

         boolean changed = false;
         ObjectIterator var16 = updatedSections.long2ObjectEntrySet().iterator();

         while (var16.hasNext()) {
            Entry<Long2ByteOpenHashMap> entry = (Entry<Long2ByteOpenHashMap>)var16.next();
            changed |= this.applySection(entry.getLongKey(), new ExposedFaceService.SectionFaceData((Long2ByteOpenHashMap)entry.getValue()));
         }

         if (changed) {
            notifySystemsChanged(changedBlocksForNotify);
         }
      }
   }

   public void bootstrapAround(ClientLevel level, BlockPos center, int horizontalRadius, int verticalRadius) {
      if (isCacheActive() && level != null && center != null && horizontalRadius >= 0 && verticalRadius >= 0) {
         Long2ObjectOpenHashMap<Long2ByteOpenHashMap> updatedSections = new Long2ObjectOpenHashMap();
         MutableBlockPos pos = new MutableBlockPos();
         int radiusSq = horizontalRadius * horizontalRadius;

         for (int y = center.getY() - verticalRadius; y <= center.getY() + verticalRadius; y++) {
            for (int z = center.getZ() - horizontalRadius; z <= center.getZ() + horizontalRadius; z++) {
               int dz = z - center.getZ();

               for (int x = center.getX() - horizontalRadius; x <= center.getX() + horizontalRadius; x++) {
                  int dx = x - center.getX();
                  if (dx * dx + dz * dz <= radiusSq) {
                     pos.set(x, y, z);
                     this.updateLocalBlock(level, pos, updatedSections);
                  }
               }
            }
         }

         boolean changed = false;
         ObjectIterator var14 = updatedSections.long2ObjectEntrySet().iterator();

         while (var14.hasNext()) {
            Entry<Long2ByteOpenHashMap> entry = (Entry<Long2ByteOpenHashMap>)var14.next();
            changed |= this.applySection(entry.getLongKey(), new ExposedFaceService.SectionFaceData((Long2ByteOpenHashMap)entry.getValue()));
         }

         if (changed) {
            notifySystemsChanged();
         }
      }
   }

   public void refreshChangedBlocks(ClientLevel level, LongOpenHashSet changedBlocks, int maxBlocks) {
      if (isCacheActive() && level != null && changedBlocks != null && !changedBlocks.isEmpty()) {
         LongIterator iterator = changedBlocks.iterator();

         while (iterator.hasNext()) {
            this.pendingDirtyBlocks.add(iterator.nextLong());
         }
      }
   }

   public void tick(ClientLevel level) {
      if (isCacheActive() && level != null) {
         this.drainCompletedSectionBuilds();
         this.drainRequestedSectionCaptures(level);
         if (!this.pendingDirtyBlocks.isEmpty()) {
            int maxProcess = 2048;
            LongOpenHashSet batch = new LongOpenHashSet();
            Iterator<Long> it = this.pendingDirtyBlocks.iterator();

            while (it.hasNext() && batch.size() < maxProcess) {
               batch.add(it.next());
               it.remove();
            }

            if (!batch.isEmpty()) {
               Long2ObjectOpenHashMap<Long2ByteOpenHashMap> updatedSections = new Long2ObjectOpenHashMap();
               LongOpenHashSet changedBlocksForNotify = new LongOpenHashSet();
               LongIterator batchIt = batch.iterator();

               while (batchIt.hasNext()) {
                  long blockKey = batchIt.nextLong();
                  BlockPos center = BlockPos.of(blockKey);
                  changedBlocksForNotify.add(blockKey);
                  this.updateLocalBlock(level, center, updatedSections);

                  for (Direction face : FACES) {
                     this.updateLocalBlock(level, center.relative(face), updatedSections);
                  }
               }

               if (!updatedSections.isEmpty()) {
                  ObjectIterator var15 = updatedSections.long2ObjectEntrySet().iterator();

                  while (var15.hasNext()) {
                     Entry<Long2ByteOpenHashMap> entry = (Entry<Long2ByteOpenHashMap>)var15.next();
                     this.fullyCapturedSections.remove(entry.getLongKey());
                     this.scheduleSectionBuild(entry.getLongKey(), new ExposedFaceService.SectionFaceData((Long2ByteOpenHashMap)entry.getValue(), true), false);
                  }

                  notifySystemsChanged(changedBlocksForNotify);
               }
            }
         }
      }
   }

   private void drainRequestedSectionCaptures(ClientLevel level) {
      for (int processed = 0; processed < 2; processed++) {
         long selectedKey = Long.MIN_VALUE;
         double selectedPriority = Double.MAX_VALUE;

         for (java.util.Map.Entry<Long, Double> entry : this.pendingSectionCaptures.entrySet()) {
            double priority = entry.getValue();
            if (priority < selectedPriority) {
               selectedPriority = priority;
               selectedKey = entry.getKey();
            }
         }

         if (selectedKey == Long.MIN_VALUE) {
            return;
         }

         if (!this.pendingSectionCaptures.remove(selectedKey, selectedPriority)) {
            processed--;
         } else if (!this.ensureSectionCapturedNow(level, selectedKey)) {
            this.pendingSectionCaptures.merge(selectedKey, selectedPriority, Math::min);
            return;
         }
      }
   }

   public void removeChunk(int chunkX, int chunkZ) {
      boolean changed = this.removeChunkEntries(chunkX, chunkZ);
      if (changed) {
         notifySystemsChanged();
      }
   }

   public Map<Long, long[]> snapshotExposedBlocksByChunk() {
      if (isCacheActive() && !this.sectionFaces.isEmpty()) {
         Long2ObjectOpenHashMap<LongOpenHashSet> blocksByChunk = new Long2ObjectOpenHashMap();

         for (ExposedFaceService.SectionFaceData sectionData : this.sectionFaces.values()) {
            if (sectionData != null && !sectionData.isEmpty()) {
               ObjectIterator chunkKey = sectionData.facesByBlock.long2ByteEntrySet().iterator();

               while (chunkKey.hasNext()) {
                  it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry entry = (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry)chunkKey.next();
                  if (entry.getByteValue() != 0) {
                     long blockKey = entry.getLongKey();
                     long chunkKeyx = ChunkPos.asLong(BlockPos.getX(blockKey) >> 4, BlockPos.getZ(blockKey) >> 4);
                     ((LongOpenHashSet)blocksByChunk.computeIfAbsent(chunkKeyx, ignored -> new LongOpenHashSet())).add(blockKey);
                  }
               }
            }
         }

         if (blocksByChunk.isEmpty()) {
            return Collections.emptyMap();
         } else {
            Map<Long, long[]> snapshot = new HashMap<>(blocksByChunk.size());
            LongIterator var11 = blocksByChunk.keySet().iterator();

            while (var11.hasNext()) {
               long chunkKey = (Long)var11.next();
               LongOpenHashSet bucket = (LongOpenHashSet)blocksByChunk.get(chunkKey);
               if (bucket != null && !bucket.isEmpty()) {
                  snapshot.put(chunkKey, bucket.toLongArray());
               }
            }

            return snapshot;
         }
      } else {
         return Collections.emptyMap();
      }
   }

   public ExposedFaceService.SectionFaceSnapshot snapshotSectionFaces(@Nullable ClientLevel level, long sectionKey) {
      if (!isCacheActive()) {
         return ExposedFaceService.SectionFaceSnapshot.empty();
      } else {
         ExposedFaceService.SectionFaceSnapshot snapshot = this.sectionFaceSnapshots.get(sectionKey);
         return snapshot != null ? snapshot : ExposedFaceService.SectionFaceSnapshot.empty();
      }
   }

   public boolean hasSectionFaceSnapshot(long sectionKey) {
      return this.sectionFaceSnapshots.containsKey(sectionKey);
   }

   public boolean isSectionFullyCaptured(long sectionKey) {
      return this.fullyCapturedSections.containsKey(sectionKey);
   }

   public ExposedFaceService.SectionReceiverSnapshot snapshotSectionReceivers(long sectionKey) {
      ExposedFaceService.SectionReceiverSnapshot snapshot = this.sectionReceivers.get(sectionKey);
      return snapshot != null ? snapshot : ExposedFaceService.SectionReceiverSnapshot.empty();
   }

   private boolean removeChunkEntries(int chunkX, int chunkZ) {
      boolean changed = false;

      for (Long sectionKey : this.sectionFaces.keySet()) {
         if (SectionPos.x(sectionKey) == chunkX && SectionPos.z(sectionKey) == chunkZ) {
            changed |= this.sectionFaces.remove(sectionKey) != null;
            this.sectionFaceSnapshots.remove(sectionKey);
            this.sectionReceivers.remove(sectionKey);
            this.sectionCompleteReceiverSurfaces.remove(sectionKey);
         }
      }

      for (Long sectionKeyx : this.fullyCapturedSections.keySet()) {
         if (SectionPos.x(sectionKeyx) == chunkX && SectionPos.z(sectionKeyx) == chunkZ) {
            changed |= this.fullyCapturedSections.remove(sectionKeyx) != null;
         }
      }

      for (Long sectionKeyxx : this.pendingSectionCaptures.keySet()) {
         if (SectionPos.x(sectionKeyxx) == chunkX && SectionPos.z(sectionKeyxx) == chunkZ) {
            this.pendingSectionCaptures.remove(sectionKeyxx);
         }
      }

      for (Long sectionKeyxxx : this.latestSectionBuildRevision.keySet()) {
         if (SectionPos.x(sectionKeyxxx) == chunkX && SectionPos.z(sectionKeyxxx) == chunkZ) {
            this.latestSectionBuildRevision.remove(sectionKeyxxx);
         }
      }

      return changed;
   }

   private boolean ensureSectionCapturedNow(ClientLevel level, long sectionKey) {
      if (this.fullyCapturedSections.containsKey(sectionKey)) {
         return true;
      } else {
         int sectionX = SectionPos.x(sectionKey);
         int sectionY = SectionPos.y(sectionKey);
         int sectionZ = SectionPos.z(sectionKey);
         LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
         if (chunk == null) {
            return false;
         } else {
            int baseX = SectionPos.sectionToBlockCoord(sectionX);
            int baseY = SectionPos.sectionToBlockCoord(sectionY);
            int baseZ = SectionPos.sectionToBlockCoord(sectionZ);
            int sectionIndex = baseY - level.getMinBuildHeight() >> 4;
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex >= 0 && sectionIndex < sections.length) {
               LevelChunkSection section = sections[sectionIndex];
               if (section != null && !section.hasOnlyAir()) {
                  Long2ByteOpenHashMap faces = new Long2ByteOpenHashMap();
                  faces.defaultReturnValue((byte)0);
                  MutableBlockPos pos = new MutableBlockPos();

                  for (int localY = 0; localY < 16; localY++) {
                     for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                           BlockState state = section.getBlockState(localX, localY, localZ);
                           if (!state.isAir() && state.getRenderShape() != RenderShape.INVISIBLE) {
                              pos.set(baseX + localX, baseY + localY, baseZ + localZ);
                              ExposedFaceService.ReceiverShapeTemplate template = this.resolveReceiverShapeTemplate(state, level, pos);
                              byte mask = template.computeFaceMask(state, level, pos);
                              if (mask != 0) {
                                 faces.put(pos.asLong(), mask);
                              }
                           }
                        }
                     }
                  }

                  this.scheduleSectionBuild(sectionKey, new ExposedFaceService.SectionFaceData(faces, true), true);
                  return true;
               } else {
                  this.scheduleSectionBuild(sectionKey, new ExposedFaceService.SectionFaceData(new Long2ByteOpenHashMap(), true), true);
                  return true;
               }
            } else {
               this.scheduleSectionBuild(sectionKey, new ExposedFaceService.SectionFaceData(new Long2ByteOpenHashMap(), true), true);
               return true;
            }
         }
      }
   }

   private void updateLocalBlock(ClientLevel level, BlockPos pos, Long2ObjectOpenHashMap<Long2ByteOpenHashMap> updatedSections) {
      long sectionKey = sectionKeyForBlock(pos.asLong());
      Long2ByteOpenHashMap sectionMap = (Long2ByteOpenHashMap)updatedSections.get(sectionKey);
      if (sectionMap == null) {
         ExposedFaceService.SectionFaceData current = this.sectionFaces.get(sectionKey);
         sectionMap = current != null ? current.copyFaces() : new Long2ByteOpenHashMap();
         sectionMap.defaultReturnValue((byte)0);
         updatedSections.put(sectionKey, sectionMap);
      }

      long blockKey = pos.asLong();
      byte faceMask = this.computeExposedFaceMask(level, pos);
      if (faceMask == 0) {
         sectionMap.remove(blockKey);
      } else {
         sectionMap.put(blockKey, faceMask);
      }
   }

   private byte computeExposedFaceMask(ClientLevel level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      ExposedFaceService.ReceiverShapeTemplate template = this.resolveReceiverShapeTemplate(state, level, pos);
      return template.computeFaceMask(state, level, pos);
   }

   private byte getCompleteExposedFaceMask(ClientLevel level, long blockKey, BlockState state, BlockPos pos, ExposedFaceService.ReceiverShapeTemplate template) {
      long sectionKey = sectionKeyForBlock(blockKey);
      return this.fullyCapturedSections.containsKey(sectionKey) ? this.getExposedFaceMask(level, blockKey) : template.computeFaceMask(state, level, pos);
   }

   private boolean hasReceiverGeometry(BlockState state, BlockGetter level, BlockPos pos) {
      return !this.resolveReceiverShapeTemplate(state, level, pos).isEmpty();
   }

   private ExposedFaceService.ReceiverShapeTemplate resolveReceiverShapeTemplate(BlockState state, BlockGetter level, BlockPos pos) {
      if (!state.isAir() && state.getRenderShape() != RenderShape.INVISIBLE) {
         ExposedFaceService.ReceiverShapeTemplate cached = this.receiverShapeCache.get(state);
         if (cached != null) {
            return cached;
         } else {
            ExposedFaceService.ReceiverShapeTemplate built = buildReceiverShapeTemplate(state, level, pos);
            ExposedFaceService.ReceiverShapeTemplate existing = this.receiverShapeCache.putIfAbsent(state, built);
            return existing != null ? existing : built;
         }
      } else {
         return ExposedFaceService.ReceiverShapeTemplate.EMPTY;
      }
   }

   private static ExposedFaceService.ReceiverShapeTemplate buildReceiverShapeTemplate(BlockState state, BlockGetter level, BlockPos pos) {
      VoxelShape shape = state.getShape(level, pos);
      if (shape.isEmpty()) {
         return ExposedFaceService.ReceiverShapeTemplate.EMPTY;
      } else {
         List<AABB> boxes = shape.toAabbs();
         if (boxes.isEmpty()) {
            return ExposedFaceService.ReceiverShapeTemplate.EMPTY;
         } else {
            List<ExposedFaceService.ReceiverSurfaceTemplate> surfaces = new ArrayList<>();

            for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
               AABB box = boxes.get(boxIndex);

               for (Direction face : FACES) {
                  ExposedFaceService.SurfaceRect initialRect = canonicalFaceRect(face, box);
                  if (initialRect != null) {
                     double planeCoordinate = facePlaneCoordinate(face, box);
                     List<ExposedFaceService.SurfaceRect> fragments = new ArrayList<>();
                     fragments.add(initialRect);

                     for (int otherIndex = 0; otherIndex < boxes.size() && !fragments.isEmpty(); otherIndex++) {
                        if (otherIndex != boxIndex) {
                           AABB otherBox = boxes.get(otherIndex);
                           if (coversFacePlane(face, planeCoordinate, otherBox)) {
                              fragments = subtractSurfaceRectList(fragments, canonicalFaceRect(face, otherBox));
                           }
                        }
                     }

                     boolean boundaryFace = isBoundaryFace(face, planeCoordinate);

                     for (ExposedFaceService.SurfaceRect fragment : fragments) {
                        if (fragment != null && !(fragment.width() <= 1.0E-6) && !(fragment.height() <= 1.0E-6)) {
                           ExposedFaceService.ReceiverSurfaceBounds bounds = toLocalFaceBounds(face, fragment);
                           if (!(bounds.width() <= 1.0E-6) && !(bounds.height() <= 1.0E-6)) {
                              surfaces.add(new ExposedFaceService.ReceiverSurfaceTemplate(face, planeCoordinate, bounds, boundaryFace));
                           }
                        }
                     }
                  }
               }
            }

            return surfaces.isEmpty() ? ExposedFaceService.ReceiverShapeTemplate.EMPTY : new ExposedFaceService.ReceiverShapeTemplate(surfaces);
         }
      }
   }

   private static List<ExposedFaceService.SurfaceRect> subtractSurfaceRectList(
      List<ExposedFaceService.SurfaceRect> sourceRects, @Nullable ExposedFaceService.SurfaceRect coverRect
   ) {
      if (coverRect != null && !sourceRects.isEmpty()) {
         List<ExposedFaceService.SurfaceRect> result = new ArrayList<>(sourceRects.size());

         for (ExposedFaceService.SurfaceRect rect : sourceRects) {
            subtractSurfaceRect(rect, coverRect, result);
         }

         return result;
      } else {
         return sourceRects;
      }
   }

   private static void subtractSurfaceRect(
      ExposedFaceService.SurfaceRect sourceRect, ExposedFaceService.SurfaceRect coverRect, List<ExposedFaceService.SurfaceRect> out
   ) {
      double overlapMinU = Math.max(sourceRect.minU, coverRect.minU);
      double overlapMaxU = Math.min(sourceRect.maxU, coverRect.maxU);
      double overlapMinV = Math.max(sourceRect.minV, coverRect.minV);
      double overlapMaxV = Math.min(sourceRect.maxV, coverRect.maxV);
      if (!(overlapMaxU - overlapMinU <= 1.0E-6) && !(overlapMaxV - overlapMinV <= 1.0E-6)) {
         if (overlapMinV - sourceRect.minV > 1.0E-6) {
            out.add(new ExposedFaceService.SurfaceRect(sourceRect.minU, sourceRect.maxU, sourceRect.minV, overlapMinV));
         }

         if (sourceRect.maxV - overlapMaxV > 1.0E-6) {
            out.add(new ExposedFaceService.SurfaceRect(sourceRect.minU, sourceRect.maxU, overlapMaxV, sourceRect.maxV));
         }

         if (overlapMinU - sourceRect.minU > 1.0E-6) {
            out.add(new ExposedFaceService.SurfaceRect(sourceRect.minU, overlapMinU, overlapMinV, overlapMaxV));
         }

         if (sourceRect.maxU - overlapMaxU > 1.0E-6) {
            out.add(new ExposedFaceService.SurfaceRect(overlapMaxU, sourceRect.maxU, overlapMinV, overlapMaxV));
         }
      } else {
         out.add(sourceRect);
      }
   }

   private static boolean coversFacePlane(Direction face, double planeCoordinate, AABB box) {
      return switch (face) {
         case UP -> Math.abs(box.minY - planeCoordinate) <= 1.0E-6;
         case DOWN -> Math.abs(box.maxY - planeCoordinate) <= 1.0E-6;
         case NORTH -> Math.abs(box.maxZ - planeCoordinate) <= 1.0E-6;
         case SOUTH -> Math.abs(box.minZ - planeCoordinate) <= 1.0E-6;
         case WEST -> Math.abs(box.maxX - planeCoordinate) <= 1.0E-6;
         case EAST -> Math.abs(box.minX - planeCoordinate) <= 1.0E-6;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static double facePlaneCoordinate(Direction face, AABB box) {
      return switch (face) {
         case UP -> box.maxY;
         case DOWN -> box.minY;
         case NORTH -> box.minZ;
         case SOUTH -> box.maxZ;
         case WEST -> box.minX;
         case EAST -> box.maxX;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static boolean isBoundaryFace(Direction face, double planeCoordinate) {
      return switch (face) {
         case UP, SOUTH, EAST -> Math.abs(planeCoordinate - 1.0) <= 1.0E-6;
         case DOWN, NORTH, WEST -> Math.abs(planeCoordinate) <= 1.0E-6;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   @Nullable
   private static ExposedFaceService.SurfaceRect canonicalFaceRect(Direction face, AABB box) {
      return switch (face) {
         case UP, DOWN -> surfaceRect(box.minX, box.maxX, box.minZ, box.maxZ);
         case NORTH, SOUTH -> surfaceRect(box.minX, box.maxX, box.minY, box.maxY);
         case WEST, EAST -> surfaceRect(box.minZ, box.maxZ, box.minY, box.maxY);
         default -> throw new IncompatibleClassChangeError();
      };
   }

   @Nullable
   private static ExposedFaceService.SurfaceRect surfaceRect(double minU, double maxU, double minV, double maxV) {
      return !(maxU - minU <= 1.0E-6) && !(maxV - minV <= 1.0E-6) ? new ExposedFaceService.SurfaceRect(minU, maxU, minV, maxV) : null;
   }

   private static ExposedFaceService.ReceiverSurfaceBounds toLocalFaceBounds(Direction face, ExposedFaceService.SurfaceRect rect) {
      return switch (face) {
         case UP, SOUTH, EAST -> new ExposedFaceService.ReceiverSurfaceBounds(rect.minU, rect.maxU, rect.minV, rect.maxV);
         case DOWN -> new ExposedFaceService.ReceiverSurfaceBounds(rect.minU, rect.maxU, 1.0 - rect.maxV, 1.0 - rect.minV);
         case NORTH, WEST -> new ExposedFaceService.ReceiverSurfaceBounds(1.0 - rect.maxU, 1.0 - rect.minU, rect.minV, rect.maxV);
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private void scheduleSectionBuild(long sectionKey, ExposedFaceService.SectionFaceData nextData, boolean fullyCaptured) {
      long revision = this.sectionBuildRevision.incrementAndGet();
      this.latestSectionBuildRevision.put(sectionKey, revision);
      this.receiverBuildExecutor
         .execute(
            () -> {
               try {
                  ExposedFaceService.SectionFaceSnapshot faceSnapshot = this.buildFaceSnapshot(nextData);
                  ExposedFaceService.SectionReceiverSnapshot receiverSnapshot = this.buildReceiverSnapshot(nextData);
                  this.completedSectionBuilds
                     .add(new ExposedFaceService.BuiltSectionCommit(sectionKey, revision, nextData, faceSnapshot, receiverSnapshot, fullyCaptured));
               } catch (Throwable var9) {
               }
            }
         );
   }

   private void drainCompletedSectionBuilds() {
      boolean changed = false;

      for (int i = 0; i < 64; i++) {
         ExposedFaceService.BuiltSectionCommit built = this.completedSectionBuilds.poll();
         if (built == null) {
            break;
         }

         Long latest = this.latestSectionBuildRevision.get(built.sectionKey);
         if (latest != null && latest == built.revision) {
            this.latestSectionBuildRevision.remove(built.sectionKey, built.revision);
            ExposedFaceService.SectionFaceData previous = this.sectionFaces.get(built.sectionKey);
            boolean contentChanged = previous == null ? !built.data.isEmpty() : !previous.sameContent(built.data);
            this.sectionCompleteReceiverSurfaces.remove(built.sectionKey);
            if (built.data.isEmpty()) {
               this.sectionFaces.remove(built.sectionKey);
               this.sectionFaceSnapshots.remove(built.sectionKey);
               this.sectionReceivers.remove(built.sectionKey);
            } else {
               this.sectionFaces.put(built.sectionKey, built.data);
               this.sectionFaceSnapshots.put(built.sectionKey, built.faceSnapshot);
               this.sectionReceivers.put(built.sectionKey, built.receiverSnapshot);
            }

            boolean firstComplete = built.fullyCaptured && this.fullyCapturedSections.put(built.sectionKey, Boolean.TRUE) == null;
            changed |= contentChanged || firstComplete;
         }
      }

      if (changed) {
         notifySystemsChanged();
      }
   }

   private boolean applySection(long sectionKey, ExposedFaceService.SectionFaceData nextData) {
      ExposedFaceService.SectionFaceData previous = this.sectionFaces.get(sectionKey);
      if (previous != null && previous.sameContent(nextData)) {
         return false;
      } else {
         this.scheduleSectionBuild(sectionKey, nextData, false);
         return true;
      }
   }

   private ExposedFaceService.SectionFaceSnapshot buildFaceSnapshot(ExposedFaceService.SectionFaceData sectionData) {
      if (sectionData != null && !sectionData.isEmpty()) {
         long[] renderableSolidBits = new long[64];
         byte[] faceMasks = new byte[4096];
         boolean anyCandidateReceivers = false;
         ObjectIterator var5 = sectionData.facesByBlock.long2ByteEntrySet().iterator();

         while (var5.hasNext()) {
            it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry entry = (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry)var5.next();
            byte faceMask = entry.getByteValue();
            if (faceMask != 0) {
               long blockKey = entry.getLongKey();
               int localIndex = sectionLocalIndex(BlockPos.getX(blockKey) & 15, BlockPos.getY(blockKey) & 15, BlockPos.getZ(blockKey) & 15);
               renderableSolidBits[localIndex >>> 6] = renderableSolidBits[localIndex >>> 6] | 1L << (localIndex & 63);
               faceMasks[localIndex] = faceMask;
               anyCandidateReceivers = true;
            }
         }

         return anyCandidateReceivers
            ? new ExposedFaceService.SectionFaceSnapshot(renderableSolidBits, faceMasks, true)
            : ExposedFaceService.SectionFaceSnapshot.empty();
      } else {
         return ExposedFaceService.SectionFaceSnapshot.empty();
      }
   }

   private ExposedFaceService.SectionReceiverSnapshot buildReceiverSnapshot(ExposedFaceService.SectionFaceData sectionData) {
      if (sectionData != null && !sectionData.isEmpty()) {
         HashMap<Long, Long> faceCells = new HashMap<>();
         ArrayList<ExposedFaceService.ReceiverRect> rects = new ArrayList<>();
         ObjectIterator visited = sectionData.facesByBlock.long2ByteEntrySet().iterator();

         while (visited.hasNext()) {
            it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry entry = (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry)visited.next();
            long blockKey = entry.getLongKey();
            byte faceMask = entry.getByteValue();
            if (faceMask != 0) {
               int localX = BlockPos.getX(blockKey) & 15;
               int localY = BlockPos.getY(blockKey) & 15;
               int localZ = BlockPos.getZ(blockKey) & 15;

               for (Direction face : FACES) {
                  if ((faceMask & 1 << face.ordinal()) != 0) {
                     int cellU = localCellU(face, localX, localZ);
                     int cellV = localCellV(face, localY, localZ);
                     int planeCoordinate = planeCoordinateForFace(face, localX, localY, localZ);
                     faceCells.put(receiverCellKey(face.ordinal(), planeCoordinate, cellU, cellV), blockKey);
                  }
               }
            }
         }

         HashSet<Long> visitedx = new HashSet<>(faceCells.size());

         for (java.util.Map.Entry<Long, Long> entry : faceCells.entrySet()) {
            long packedCell = entry.getKey();
            if (visitedx.add(packedCell)) {
               int faceOrdinal = unpackFaceOrdinal(packedCell);
               int planeCoordinate = unpackPlaneCoordinate(packedCell);
               int startCellU = unpackCellU(packedCell);
               int startCellV = unpackCellV(packedCell);
               int width = 1;

               while (
                  faceCells.containsKey(receiverCellKey(faceOrdinal, planeCoordinate, startCellU + width, startCellV))
                     && !visitedx.contains(receiverCellKey(faceOrdinal, planeCoordinate, startCellU + width, startCellV))
               ) {
                  width++;
               }

               int height = 1;

               while (true) {
                  int nextV = startCellV + height;
                  boolean rowMatches = true;

                  for (int offsetU = 0; offsetU < width; offsetU++) {
                     long nextKey = receiverCellKey(faceOrdinal, planeCoordinate, startCellU + offsetU, nextV);
                     if (!faceCells.containsKey(nextKey) || visitedx.contains(nextKey)) {
                        rowMatches = false;
                        break;
                     }
                  }

                  if (!rowMatches) {
                     for (int offsetV = 0; offsetV < height; offsetV++) {
                        for (int offsetUx = 0; offsetUx < width; offsetUx++) {
                           visitedx.add(receiverCellKey(faceOrdinal, planeCoordinate, startCellU + offsetUx, startCellV + offsetV));
                        }
                     }

                     rects.add(
                        new ExposedFaceService.ReceiverRect(
                           Direction.from3DDataValue(faceOrdinal),
                           planeCoordinate,
                           startCellU,
                           startCellU + width,
                           startCellV,
                           startCellV + height,
                           entry.getValue()
                        )
                     );
                     break;
                  }

                  height++;
               }
            }
         }

         return rects.isEmpty() ? ExposedFaceService.SectionReceiverSnapshot.empty() : new ExposedFaceService.SectionReceiverSnapshot(rects);
      } else {
         return ExposedFaceService.SectionReceiverSnapshot.empty();
      }
   }

   private static long receiverCellKey(int faceOrdinal, int planeCoordinate, int cellU, int cellV) {
      long value = ((long)faceOrdinal & 7L) << 61;
      value |= ((long)planeCoordinate & 2097151L) << 40;
      value |= ((long)cellU & 1048575L) << 20;
      return value | (long)cellV & 1048575L;
   }

   private static int unpackFaceOrdinal(long key) {
      return (int)(key >>> 61 & 7L);
   }

   private static int unpackPlaneCoordinate(long key) {
      int value = (int)(key >>> 40 & 2097151L);
      if ((value & 1048576) != 0) {
         value |= -2097152;
      }

      return value;
   }

   private static int unpackCellU(long key) {
      int value = (int)(key >>> 20 & 1048575L);
      if ((value & 524288) != 0) {
         value |= -1048576;
      }

      return value;
   }

   private static int unpackCellV(long key) {
      int value = (int)(key & 1048575L);
      if ((value & 524288) != 0) {
         value |= -1048576;
      }

      return value;
   }

   private static int planeCoordinateForFace(Direction face, int localX, int localY, int localZ) {
      return switch (face) {
         case UP -> localY + 1;
         case DOWN -> localY;
         case NORTH -> localZ;
         case SOUTH -> localZ + 1;
         case WEST -> localX;
         case EAST -> localX + 1;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static int localCellU(Direction face, int localX, int localZ) {
      return switch (face) {
         case UP, DOWN, NORTH, SOUTH -> localX;
         case WEST, EAST -> localZ;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static int localCellV(Direction face, int localY, int localZ) {
      return switch (face) {
         case UP, DOWN -> localZ;
         case NORTH, SOUTH, WEST, EAST -> localY;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static long sectionKeyFromOrigin(BlockPos sectionOrigin) {
      return SectionPos.asLong(
         SectionPos.blockToSectionCoord(sectionOrigin.getX()),
         SectionPos.blockToSectionCoord(sectionOrigin.getY()),
         SectionPos.blockToSectionCoord(sectionOrigin.getZ())
      );
   }

   private static long sectionKeyForBlock(long blockKey) {
      return SectionPos.asLong(
         SectionPos.blockToSectionCoord(BlockPos.getX(blockKey)),
         SectionPos.blockToSectionCoord(BlockPos.getY(blockKey)),
         SectionPos.blockToSectionCoord(BlockPos.getZ(blockKey))
      );
   }

   private static void notifySystemsChanged() {
      notifySystemsChanged(null);
   }

   private static void notifySystemsChanged(@Nullable LongOpenHashSet changedBlocks) {
      ReflectionSystem.get().markWorldDirty();
      if (changedBlocks != null && !changedBlocks.isEmpty()) {
         NeoShadowsEngine.markWorldDirty(changedBlocks);
      } else {
         NeoShadowsEngine.markWorldDirty();
      }

      Minecraft.getInstance().execute(TorchRtxState.get()::requestReflectiveSnapshotRefresh);
   }

   private static int sectionLocalIndex(int localX, int localY, int localZ) {
      return localY << 8 | localZ << 4 | localX;
   }

   private static boolean isCacheActive() {
      return Config.isFeatureEnabled(Config.CLIENT.realisticShadowsEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.reflectionEnabled);
   }

   private static long computeSurfaceKey(long blockKey, Direction face, double planeCoordinate, ExposedFaceService.ReceiverSurfaceBounds bounds) {
      long hash = 1469598103934665603L;
      hash = mixSurfaceKey(hash, blockKey);
      hash = mixSurfaceKey(hash, (long)face.ordinal());
      hash = mixSurfaceKey(hash, Double.doubleToLongBits(planeCoordinate));
      hash = mixSurfaceKey(hash, Double.doubleToLongBits(bounds.minU));
      hash = mixSurfaceKey(hash, Double.doubleToLongBits(bounds.maxU));
      hash = mixSurfaceKey(hash, Double.doubleToLongBits(bounds.minV));
      return mixSurfaceKey(hash, Double.doubleToLongBits(bounds.maxV));
   }

   private static long mixSurfaceKey(long hash, long value) {
      return (hash ^ value) * 1099511628211L;
   }

   private static final class BuiltSectionCommit {
      final long sectionKey;
      final long revision;
      final ExposedFaceService.SectionFaceData data;
      final ExposedFaceService.SectionFaceSnapshot faceSnapshot;
      final ExposedFaceService.SectionReceiverSnapshot receiverSnapshot;
      final boolean fullyCaptured;

      BuiltSectionCommit(
         long sectionKey,
         long revision,
         ExposedFaceService.SectionFaceData data,
         ExposedFaceService.SectionFaceSnapshot faceSnapshot,
         ExposedFaceService.SectionReceiverSnapshot receiverSnapshot,
         boolean fullyCaptured
      ) {
         this.sectionKey = sectionKey;
         this.revision = revision;
         this.data = data;
         this.faceSnapshot = faceSnapshot;
         this.receiverSnapshot = receiverSnapshot;
         this.fullyCaptured = fullyCaptured;
      }
   }

   public static final class ReceiverRect {
      private final Direction face;
      private final int planeCoordinate;
      private final int minCellU;
      private final int maxCellU;
      private final int minCellV;
      private final int maxCellV;
      private final long anchorBlockKey;

      private ReceiverRect(Direction face, int planeCoordinate, int minCellU, int maxCellU, int minCellV, int maxCellV, long anchorBlockKey) {
         this.face = face;
         this.planeCoordinate = planeCoordinate;
         this.minCellU = minCellU;
         this.maxCellU = maxCellU;
         this.minCellV = minCellV;
         this.maxCellV = maxCellV;
         this.anchorBlockKey = anchorBlockKey;
      }

      public Direction face() {
         return this.face;
      }

      public int planeCoordinate() {
         return this.planeCoordinate;
      }

      public int minCellU() {
         return this.minCellU;
      }

      public int maxCellU() {
         return this.maxCellU;
      }

      public int minCellV() {
         return this.minCellV;
      }

      public int maxCellV() {
         return this.maxCellV;
      }

      public long anchorBlockKey() {
         return this.anchorBlockKey;
      }
   }

   private static final class ReceiverShapeTemplate {
      private static final ExposedFaceService.ReceiverShapeTemplate EMPTY = new ExposedFaceService.ReceiverShapeTemplate(
         new ExposedFaceService.ReceiverSurfaceTemplate[0]
      );
      private final ExposedFaceService.ReceiverSurfaceTemplate[] surfaces;
      private final boolean hasBoundaryFaces;

      private ReceiverShapeTemplate(List<ExposedFaceService.ReceiverSurfaceTemplate> surfaces) {
         this(surfaces.toArray(new ExposedFaceService.ReceiverSurfaceTemplate[0]));
      }

      private ReceiverShapeTemplate(ExposedFaceService.ReceiverSurfaceTemplate[] surfaces) {
         this.surfaces = surfaces;
         boolean anyBoundaryFaces = false;

         for (ExposedFaceService.ReceiverSurfaceTemplate surface : surfaces) {
            if (surface.boundaryFace) {
               anyBoundaryFaces = true;
               break;
            }
         }

         this.hasBoundaryFaces = anyBoundaryFaces;
      }

      private boolean isEmpty() {
         return this.surfaces.length == 0;
      }

      private boolean hasBoundaryFaces() {
         return this.hasBoundaryFaces;
      }

      private boolean sameGeometry(ExposedFaceService.ReceiverShapeTemplate other) {
         if (other != null && this.surfaces.length == other.surfaces.length) {
            for (int i = 0; i < this.surfaces.length; i++) {
               ExposedFaceService.ReceiverSurfaceTemplate a = this.surfaces[i];
               ExposedFaceService.ReceiverSurfaceTemplate b = other.surfaces[i];
               if (a.face != b.face
                  || a.boundaryFace != b.boundaryFace
                  || Double.doubleToLongBits(a.planeCoordinate) != Double.doubleToLongBits(b.planeCoordinate)
                  || Double.doubleToLongBits(a.bounds.minU) != Double.doubleToLongBits(b.bounds.minU)
                  || Double.doubleToLongBits(a.bounds.maxU) != Double.doubleToLongBits(b.bounds.maxU)
                  || Double.doubleToLongBits(a.bounds.minV) != Double.doubleToLongBits(b.bounds.minV)
                  || Double.doubleToLongBits(a.bounds.maxV) != Double.doubleToLongBits(b.bounds.maxV)) {
                  return false;
               }
            }

            return true;
         } else {
            return false;
         }
      }

      private byte computeFaceMask(BlockState state, BlockGetter level, BlockPos pos) {
         if (this.surfaces.length == 0) {
            return 0;
         } else {
            byte mask = 0;
            MutableBlockPos neighbour = null;

            for (ExposedFaceService.ReceiverSurfaceTemplate surface : this.surfaces) {
               if (surface.boundaryFace) {
                  if (neighbour == null) {
                     neighbour = new MutableBlockPos();
                  }

                  neighbour.setWithOffset(pos, surface.face);
                  if (!Block.shouldRenderFace(state, level, pos, surface.face, neighbour)) {
                     continue;
                  }
               }

               mask |= (byte)surface.faceMaskBit;
            }

            return mask;
         }
      }

      private void forEachSurface(long blockKey, byte visibleBoundaryMask, ExposedFaceService.ReceiverSurfaceVisitor visitor) {
         for (ExposedFaceService.ReceiverSurfaceTemplate surface : this.surfaces) {
            if (!surface.boundaryFace || (visibleBoundaryMask & surface.faceMaskBit) != 0) {
               visitor.visit(
                  surface.face,
                  surface.planeCoordinate,
                  surface.bounds.minU,
                  surface.bounds.maxU,
                  surface.bounds.minV,
                  surface.bounds.maxV,
                  ExposedFaceService.computeSurfaceKey(blockKey, surface.face, surface.planeCoordinate, surface.bounds)
               );
            }
         }
      }

      private ExposedFaceService.ReceiverSurface[] instantiateArray(long blockKey, byte visibleBoundaryMask) {
         if (this.surfaces.length == 0) {
            return ExposedFaceService.EMPTY_RECEIVER_SURFACES;
         } else {
            ExposedFaceService.ReceiverSurface[] resolved = new ExposedFaceService.ReceiverSurface[this.surfaces.length];
            int count = 0;

            for (ExposedFaceService.ReceiverSurfaceTemplate surface : this.surfaces) {
               if (!surface.boundaryFace || (visibleBoundaryMask & surface.faceMaskBit) != 0) {
                  resolved[count++] = surface.instantiate(blockKey);
               }
            }

            if (count == 0) {
               return ExposedFaceService.EMPTY_RECEIVER_SURFACES;
            } else if (count == resolved.length) {
               return resolved;
            } else {
               ExposedFaceService.ReceiverSurface[] compact = new ExposedFaceService.ReceiverSurface[count];
               System.arraycopy(resolved, 0, compact, 0, count);
               return compact;
            }
         }
      }

      private List<ExposedFaceService.ReceiverSurface> instantiate(long blockKey, byte visibleBoundaryMask) {
         if (this.surfaces.length == 0) {
            return Collections.emptyList();
         } else {
            ArrayList<ExposedFaceService.ReceiverSurface> resolved = null;

            for (ExposedFaceService.ReceiverSurfaceTemplate surface : this.surfaces) {
               if (!surface.boundaryFace || (visibleBoundaryMask & surface.faceMaskBit) != 0) {
                  if (resolved == null) {
                     resolved = new ArrayList<>(this.surfaces.length);
                  }

                  resolved.add(surface.instantiate(blockKey));
               }
            }

            return (List<ExposedFaceService.ReceiverSurface>)(resolved != null && !resolved.isEmpty() ? resolved : Collections.emptyList());
         }
      }
   }

   public static final class ReceiverSurface {
      private final long blockKey;
      private final Direction face;
      private final double planeCoordinate;
      private final double minU;
      private final double maxU;
      private final double minV;
      private final double maxV;
      private final long surfaceKey;

      private ReceiverSurface(long blockKey, Direction face, double planeCoordinate, double minU, double maxU, double minV, double maxV, long surfaceKey) {
         this.blockKey = blockKey;
         this.face = face;
         this.planeCoordinate = planeCoordinate;
         this.minU = minU;
         this.maxU = maxU;
         this.minV = minV;
         this.maxV = maxV;
         this.surfaceKey = surfaceKey;
      }

      public long blockKey() {
         return this.blockKey;
      }

      public Direction face() {
         return this.face;
      }

      public double planeCoordinate() {
         return this.planeCoordinate;
      }

      public double minU() {
         return this.minU;
      }

      public double maxU() {
         return this.maxU;
      }

      public double minV() {
         return this.minV;
      }

      public double maxV() {
         return this.maxV;
      }

      public long surfaceKey() {
         return this.surfaceKey;
      }
   }

   private static record ReceiverSurfaceBounds(double minU, double maxU, double minV, double maxV) {
      double width() {
         return this.maxU - this.minU;
      }

      double height() {
         return this.maxV - this.minV;
      }
   }

   private static final class ReceiverSurfaceTemplate {
      private final Direction face;
      private final int faceMaskBit;
      private final double planeCoordinate;
      private final ExposedFaceService.ReceiverSurfaceBounds bounds;
      private final boolean boundaryFace;

      private ReceiverSurfaceTemplate(Direction face, double planeCoordinate, ExposedFaceService.ReceiverSurfaceBounds bounds, boolean boundaryFace) {
         this.face = face;
         this.faceMaskBit = 1 << face.ordinal();
         this.planeCoordinate = planeCoordinate;
         this.bounds = bounds;
         this.boundaryFace = boundaryFace;
      }

      private ExposedFaceService.ReceiverSurface instantiate(long blockKey) {
         return new ExposedFaceService.ReceiverSurface(
            blockKey,
            this.face,
            this.planeCoordinate,
            this.bounds.minU,
            this.bounds.maxU,
            this.bounds.minV,
            this.bounds.maxV,
            ExposedFaceService.computeSurfaceKey(blockKey, this.face, this.planeCoordinate, this.bounds)
         );
      }
   }

   @FunctionalInterface
   public interface ReceiverSurfaceVisitor {
      void visit(Direction var1, double var2, double var4, double var6, double var8, double var10, long var12);
   }

   private static final class SectionCapture {
      private final long sectionKey;
      private final Long2ByteOpenHashMap facesByBlock = new Long2ByteOpenHashMap();

      private SectionCapture(long sectionKey) {
         this.sectionKey = sectionKey;
         this.facesByBlock.defaultReturnValue((byte)0);
      }

      private void recordFace(long blockKey, Direction face) {
         this.facesByBlock.put(blockKey, (byte)(this.facesByBlock.get(blockKey) | 1 << face.ordinal()));
      }

      private ExposedFaceService.SectionFaceData detachFaceData() {
         return new ExposedFaceService.SectionFaceData(this.facesByBlock, true);
      }
   }

   private static final class SectionCompleteReceiverSurfaceCache {
      final ConcurrentHashMap<Long, ExposedFaceService.ReceiverSurface[]> surfacesByBlock = new ConcurrentHashMap<>();
   }

   private static final class SectionFaceData {
      private final Long2ByteOpenHashMap facesByBlock;

      private SectionFaceData(Long2ByteOpenHashMap source) {
         this(source, false);
      }

      private SectionFaceData(Long2ByteOpenHashMap source, boolean takeOwnership) {
         this.facesByBlock = takeOwnership ? source : new Long2ByteOpenHashMap(source);
         this.facesByBlock.defaultReturnValue((byte)0);
      }

      private byte faceMask(long blockKey) {
         return this.facesByBlock.get(blockKey);
      }

      private boolean isEmpty() {
         return this.facesByBlock.isEmpty();
      }

      private Long2ByteOpenHashMap copyFaces() {
         return new Long2ByteOpenHashMap(this.facesByBlock);
      }

      private boolean sameContent(ExposedFaceService.SectionFaceData other) {
         if (this.facesByBlock.size() != other.facesByBlock.size()) {
            return false;
         } else {
            ObjectIterator var2 = this.facesByBlock.long2ByteEntrySet().iterator();

            while (var2.hasNext()) {
               it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry entry = (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry)var2.next();
               if (other.facesByBlock.get(entry.getLongKey()) != entry.getByteValue()) {
                  return false;
               }
            }

            return true;
         }
      }
   }

   public static final class SectionFaceSnapshot {
      private static final ExposedFaceService.SectionFaceSnapshot EMPTY = new ExposedFaceService.SectionFaceSnapshot(new long[64], new byte[4096], false);
      private final long[] renderableSolidBits;
      private final byte[] faceMasks;
      private final boolean anyCandidateReceivers;

      private SectionFaceSnapshot(long[] renderableSolidBits, byte[] faceMasks, boolean anyCandidateReceivers) {
         this.renderableSolidBits = renderableSolidBits;
         this.faceMasks = faceMasks;
         this.anyCandidateReceivers = anyCandidateReceivers;
      }

      public boolean hasCandidateReceivers() {
         return this.anyCandidateReceivers;
      }

      public boolean isRenderableSolid(int localIndex) {
         return (this.renderableSolidBits[localIndex >>> 6] & 1L << (localIndex & 63)) != 0L;
      }

      public long word(int wordIndex) {
         return this.renderableSolidBits[wordIndex];
      }

      public int wordCount() {
         return this.renderableSolidBits.length;
      }

      public byte faceMask(int localIndex) {
         return this.faceMasks[localIndex];
      }

      public static ExposedFaceService.SectionFaceSnapshot empty() {
         return EMPTY;
      }
   }

   public static final class SectionReceiverSnapshot {
      private static final ExposedFaceService.SectionReceiverSnapshot EMPTY = new ExposedFaceService.SectionReceiverSnapshot(Collections.emptyList());
      private final List<ExposedFaceService.ReceiverRect> rects;

      private SectionReceiverSnapshot(List<ExposedFaceService.ReceiverRect> rects) {
         this.rects = List.copyOf(rects);
      }

      public List<ExposedFaceService.ReceiverRect> rects() {
         return this.rects;
      }

      public boolean isEmpty() {
         return this.rects.isEmpty();
      }

      public static ExposedFaceService.SectionReceiverSnapshot empty() {
         return EMPTY;
      }
   }

   private static record SurfaceRect(double minU, double maxU, double minV, double maxV) {
      double width() {
         return this.maxU - this.minU;
      }

      double height() {
         return this.maxV - this.minV;
      }
   }
}
