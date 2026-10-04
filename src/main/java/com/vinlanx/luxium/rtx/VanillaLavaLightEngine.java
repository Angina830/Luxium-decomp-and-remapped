package com.vinlanx.luxium.rtx;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

public final class VanillaLavaLightEngine {
   private static final VanillaLavaLightEngine INSTANCE = new VanillaLavaLightEngine();
   private static final int MAX_COMPLETED_SECTIONS_PER_TICK = 3;
   private static final long SECTION_SCAN_BUDGET_NANOS = 1250000L;
   private static final int SCAN_DEADLINE_CHECK_MASK = 63;
   private static final int MAX_SOURCE_CHECKS_PER_TICK = 1024;
   private static final long SOURCE_CHECK_BUDGET_NANOS = 500000L;
   private static final int MAX_LIGHT_OPERATIONS_PER_TICK = 8000;
   private final LongOpenHashSet loadedChunks = new LongOpenHashSet();
   private final Long2ObjectOpenHashMap<VanillaLavaLightEngine.PendingSectionScan> pendingSectionScans = new Long2ObjectOpenHashMap();
   private final LongArrayFIFOQueue pendingSectionOrder = new LongArrayFIFOQueue();
   private final LongArrayFIFOQueue pendingSourceChecks = new LongArrayFIFOQueue();
   private final LongOpenHashSet pendingSourceCheckSet = new LongOpenHashSet();
   private final Long2ObjectOpenHashMap<LongOpenHashSet> lavaSourcesBySection = new Long2ObjectOpenHashMap();
   private final MutableBlockPos scanCursor = new MutableBlockPos();
   private ClientLevel currentLevel;
   private volatile BlockLightEngine engine;
   private volatile boolean active;

   private VanillaLavaLightEngine() {
   }

   public static VanillaLavaLightEngine get() {
      return INSTANCE;
   }

   public void tick(ClientLevel level, boolean rtxEnabled) {
      if (level != this.currentLevel) {
         this.reset(level);
      }

      boolean shouldBeActive = !NeoGpuVanilla.isConfiguredEnabled()
         && rtxEnabled
         && (!Config.isFeatureEnabled(Config.CLIENT.rtxLavaTracingEnabled) || level.dimension() == Level.NETHER);
      if (shouldBeActive != this.active) {
         this.setActive(shouldBeActive);
      }

      if (this.active) {
         this.scanPendingSections();
         this.drainSourceChecks();
         BlockLightEngine current = this.engine;
         if (current != null && current.hasLightWork()) {
            ((LuxiumLightEngineExtension)current).luxium$runLightUpdatesBudgeted(8000);
         }
      }
   }

   public void reset(@Nullable ClientLevel level) {
      this.active = false;
      this.engine = null;
      this.currentLevel = level;
      this.loadedChunks.clear();
      this.pendingSectionScans.clear();
      this.pendingSectionOrder.clear();
      this.pendingSourceChecks.clear();
      this.pendingSourceCheckSet.clear();
      this.lavaSourcesBySection.clear();
   }

   public void onChunkLoaded(ClientLevel level, int chunkX, int chunkZ) {
      if (level == this.currentLevel) {
         long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
         this.loadedChunks.add(chunkKey);
         if (this.active) {
            this.initializeChunk(chunkX, chunkZ);
            this.queueLoadedNeighborSections(chunkX, chunkZ);
         }
      }
   }

   public void onChunkUnloaded(ClientLevel level, int chunkX, int chunkZ) {
      if (level == this.currentLevel) {
         long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
         this.loadedChunks.remove(chunkKey);
         BlockLightEngine current = this.engine;
         boolean canQueueChecks = this.active && current != null;

         for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
            long sectionKey = SectionPos.asLong(chunkX, sectionY, chunkZ);
            this.pendingSectionScans.remove(sectionKey);
            LongOpenHashSet knownSources = (LongOpenHashSet)this.lavaSourcesBySection.remove(sectionKey);
            if (canQueueChecks && knownSources != null) {
               LongIterator iterator = knownSources.iterator();

               while (iterator.hasNext()) {
                  this.enqueueSourceCheck(iterator.nextLong());
               }
            }
         }

         if (canQueueChecks) {
            current.setLightEnabled(new ChunkPos(chunkX, chunkZ), false);

            for (int sectionYx = level.getMinSection(); sectionYx < level.getMaxSection(); sectionYx++) {
               current.updateSectionStatus(SectionPos.of(chunkX, sectionYx, chunkZ), true);
            }
         }
      }
   }

   public void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
      BlockLightEngine current = this.engine;
      if (this.active && current != null && level == this.currentLevel) {
         LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
         if (chunk != null) {
            int sectionIndex = level.getSectionIndex(pos.getY());
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex >= 0 && sectionIndex < sections.length) {
               current.updateSectionStatus(SectionPos.of(pos), sections[sectionIndex].hasOnlyAir());
            }
         }

         boolean oldLava = oldState.getFluidState().is(FluidTags.LAVA);
         boolean newLava = newState.getFluidState().is(FluidTags.LAVA);
         if (oldLava != newLava) {
            long sectionKey = SectionPos.asLong(pos);
            LongOpenHashSet bucket = (LongOpenHashSet)this.lavaSourcesBySection.get(sectionKey);
            if (newLava) {
               if (bucket == null) {
                  bucket = new LongOpenHashSet();
                  this.lavaSourcesBySection.put(sectionKey, bucket);
               }

               bucket.add(pos.asLong());
            } else if (bucket != null) {
               bucket.remove(pos.asLong());
               if (bucket.isEmpty()) {
                  this.lavaSourcesBySection.remove(sectionKey);
               }
            }
         }

         if (oldLava != newLava || LightEngine.hasDifferentLightProperties(level, pos, oldState, newState)) {
            current.checkBlock(pos);
         }
      }
   }

   public int sampleLight(BlockPos pos) {
      BlockLightEngine current = this.engine;
      return this.active && current != null ? current.getLightValue(pos) : 0;
   }

   public boolean isActive() {
      return this.active && this.engine != null;
   }

   private void setActive(boolean enabled) {
      if (!enabled) {
         ClientLevel level = this.currentLevel;
         this.active = false;
         this.engine = null;
         this.pendingSectionScans.clear();
         this.pendingSectionOrder.clear();
         this.pendingSourceChecks.clear();
         this.pendingSourceCheckSet.clear();
         this.lavaSourcesBySection.clear();
         if (level != null) {
            this.markLoadedSectionsDirty(level);
         }
      } else {
         ClientLevel level = this.currentLevel;
         if (level != null) {
            BlockLightEngine created = new BlockLightEngine(level.getChunkSource());
            ((LuxiumBlockLightEngineExtension)created).luxium$setLavaOnly(true);
            this.engine = created;
            this.active = true;
            LongIterator iterator = this.loadedChunks.iterator();

            while (iterator.hasNext()) {
               long chunkKey = iterator.nextLong();
               int chunkX = ChunkPos.getX(chunkKey);
               int chunkZ = ChunkPos.getZ(chunkKey);
               this.initializeChunk(chunkX, chunkZ);
               this.queueChunkSections(chunkX, chunkZ);
            }
         }
      }
   }

   private void queueLoadedNeighborSections(int chunkX, int chunkZ) {
      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            int nearbyX = chunkX + dx;
            int nearbyZ = chunkZ + dz;
            if (this.loadedChunks.contains(ChunkPos.asLong(nearbyX, nearbyZ))) {
               this.queueChunkSections(nearbyX, nearbyZ);
            }
         }
      }
   }

   private void queueChunkSections(int chunkX, int chunkZ) {
      ClientLevel level = this.currentLevel;
      if (level != null) {
         for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
            this.queueSection(SectionPos.asLong(chunkX, sectionY, chunkZ));
         }
      }
   }

   private void queueSection(long sectionKey) {
      if (!this.pendingSectionScans.containsKey(sectionKey)) {
         this.pendingSectionScans.put(sectionKey, new VanillaLavaLightEngine.PendingSectionScan(sectionKey));
         this.pendingSectionOrder.enqueue(sectionKey);
      }
   }

   private void initializeChunk(int chunkX, int chunkZ) {
      ClientLevel level = this.currentLevel;
      BlockLightEngine current = this.engine;
      if (level != null && current != null) {
         LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
         if (chunk != null) {
            current.setLightEnabled(new ChunkPos(chunkX, chunkZ), true);
            LevelChunkSection[] sections = chunk.getSections();

            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
               int sectionY = level.getSectionYFromSectionIndex(sectionIndex);
               current.updateSectionStatus(SectionPos.of(chunkX, sectionY, chunkZ), sections[sectionIndex].hasOnlyAir());
            }
         }
      }
   }

   private void scanPendingSections() {
      long deadline = System.nanoTime() + 1250000L;
      int completed = 0;

      while (completed < 3 && !this.pendingSectionOrder.isEmpty() && System.nanoTime() < deadline) {
         long sectionKey = this.pendingSectionOrder.dequeueLong();
         VanillaLavaLightEngine.PendingSectionScan scan = (VanillaLavaLightEngine.PendingSectionScan)this.pendingSectionScans.get(sectionKey);
         if (scan != null) {
            if (!this.scanSectionSlice(scan, deadline)) {
               this.pendingSectionOrder.enqueue(sectionKey);
               break;
            }

            this.pendingSectionScans.remove(sectionKey);
            this.publishCompletedSectionScan(scan);
            completed++;
         }
      }
   }

   private boolean scanSectionSlice(VanillaLavaLightEngine.PendingSectionScan scan, long deadline) {
      ClientLevel level = this.currentLevel;
      if (level != null && this.loadedChunks.contains(scan.chunkKey)) {
         LevelChunk chunk = level.getChunkSource().getChunkNow(scan.chunkX, scan.chunkZ);
         if (chunk == null) {
            return true;
         } else {
            int sectionIndex = scan.sectionY - level.getMinSection();
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex >= 0 && sectionIndex < sections.length) {
               LevelChunkSection section = sections[sectionIndex];
               if (!scan.paletteChecked) {
                  scan.paletteChecked = true;
                  if (section.hasOnlyAir() || !section.maybeHas(statex -> statex.getFluidState().is(FluidTags.LAVA))) {
                     scan.noLavaPossible = true;
                     return true;
                  }
               }

               if (scan.noLavaPossible) {
                  return true;
               } else {
                  int baseX = scan.chunkX << 4;
                  int baseY = scan.sectionY << 4;
                  int baseZ = scan.chunkZ << 4;

                  while (scan.cursor < 4096) {
                     int index = scan.cursor++;
                     int localX = index & 15;
                     int localZ = index >>> 4 & 15;
                     int localY = index >>> 8 & 15;
                     BlockState state = section.getBlockState(localX, localY, localZ);
                     if (state.getFluidState().is(FluidTags.LAVA)) {
                        scan.foundSources.add(BlockPos.asLong(baseX + localX, baseY + localY, baseZ + localZ));
                     }

                     if ((index & 63) == 63 && System.nanoTime() >= deadline) {
                        return false;
                     }
                  }

                  return true;
               }
            } else {
               return true;
            }
         }
      } else {
         return true;
      }
   }

   private void publishCompletedSectionScan(VanillaLavaLightEngine.PendingSectionScan scan) {
      LongOpenHashSet previous = (LongOpenHashSet)this.lavaSourcesBySection.remove(scan.sectionKey);
      if (!scan.noLavaPossible && !scan.foundSources.isEmpty()) {
         LongOpenHashSet immutableWorkingSet = new LongOpenHashSet(scan.foundSources);
         this.lavaSourcesBySection.put(scan.sectionKey, immutableWorkingSet);
         this.enqueueAll(immutableWorkingSet);
         if (previous != null) {
            LongIterator iterator = previous.iterator();

            while (iterator.hasNext()) {
               long key = iterator.nextLong();
               if (!immutableWorkingSet.contains(key)) {
                  this.enqueueSourceCheck(key);
               }
            }
         }
      } else {
         if (previous != null) {
            this.enqueueAll(previous);
         }
      }
   }

   private void enqueueAll(LongOpenHashSet values) {
      LongIterator iterator = values.iterator();

      while (iterator.hasNext()) {
         this.enqueueSourceCheck(iterator.nextLong());
      }
   }

   private void enqueueSourceCheck(long key) {
      if (this.pendingSourceCheckSet.add(key)) {
         this.pendingSourceChecks.enqueue(key);
      }
   }

   private void drainSourceChecks() {
      BlockLightEngine current = this.engine;
      if (current != null && !this.pendingSourceChecks.isEmpty()) {
         long deadline = System.nanoTime() + 500000L;

         for (int checked = 0; checked < 1024 && !this.pendingSourceChecks.isEmpty() && System.nanoTime() < deadline; checked++) {
            long key = this.pendingSourceChecks.dequeueLong();
            this.pendingSourceCheckSet.remove(key);
            this.scanCursor.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            current.checkBlock(this.scanCursor);
         }
      }
   }

   private void markLoadedSectionsDirty(ClientLevel level) {
      LongIterator iterator = this.loadedChunks.iterator();

      while (iterator.hasNext()) {
         long chunkKey = iterator.nextLong();
         int chunkX = ChunkPos.getX(chunkKey);
         int chunkZ = ChunkPos.getZ(chunkKey);

         for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
            level.getChunkSource().onLightUpdate(LightLayer.BLOCK, SectionPos.of(chunkX, sectionY, chunkZ));
         }
      }
   }

   private static final class PendingSectionScan {
      final long sectionKey;
      final int chunkX;
      final int chunkZ;
      final int sectionY;
      final long chunkKey;
      final LongOpenHashSet foundSources = new LongOpenHashSet();
      int cursor;
      boolean paletteChecked;
      boolean noLavaPossible;

      PendingSectionScan(long sectionKey) {
         this.sectionKey = sectionKey;
         this.chunkX = SectionPos.x(sectionKey);
         this.chunkZ = SectionPos.z(sectionKey);
         this.sectionY = SectionPos.y(sectionKey);
         this.chunkKey = ChunkPos.asLong(this.chunkX, this.chunkZ);
      }
   }
}
