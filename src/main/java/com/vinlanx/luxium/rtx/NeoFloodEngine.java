package com.vinlanx.luxium.rtx;

import com.vinlanx.luxium.Config;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class NeoFloodEngine {
   private static final Direction[] FACES = Direction.values();
   private static final int SNAPSHOT_SHARDS = 64;
   private static final int SNAPSHOT_SHARD_MASK = 63;
   private static final int DIRTY_BLOCK_RETRACE_DELAY_TICKS = 2;
   private static final int MAX_SOURCE_REACH_BLOCKS = (int)Math.ceil(LightRtMath.getMaxDistance(15));
   private static final int SOURCE_CHUNK_RADIUS = MAX_SOURCE_REACH_BLOCKS + 15 >> 4;
   private static final int MAX_WORLD_TRACE_BATCH = 16;
   private static final long BACKGROUND_BATCH_INTERVAL_NANOS = 50000000L;
   private final Object stateLock = new Object();
   private final Object dirtyBlocksLock = new Object();
   private final ConcurrentHashMap<Long, NeoFloodEngine.SourceState> sources = new ConcurrentHashMap<>();
   private final Long2ObjectOpenHashMap<LongOpenHashSet> sourcesByChunk = new Long2ObjectOpenHashMap();
   private final LongOpenHashSet worldDirtyQueue = new LongOpenHashSet();
   private final LongOpenHashSet pendingDirtyBlocks = new LongOpenHashSet();
   private final LongOpenHashSet selectionCandidates = new LongOpenHashSet();
   private final Long2ObjectOpenHashMap<LongOpenHashSet> sourcesBySection = new Long2ObjectOpenHashMap();
   private final ExecutorService playerExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "NeoFlood-Player");
      t.setDaemon(true);
      t.setPriority(6);
      return t;
   });
   private final ExecutorService worldExecutor = Executors.newFixedThreadPool(Math.max(1, Config.MAX_RTX_WORLD_TRACING_WORKERS), r -> {
      Thread t = new Thread(r, "NeoFlood-World");
      t.setDaemon(true);
      t.setPriority(3);
      return t;
   });
   private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "NeoFlood-Dispatch");
      t.setDaemon(true);
      t.setPriority(3);
      return t;
   });
   private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "NeoFlood-Publish");
      t.setDaemon(true);
      t.setPriority(4);
      return t;
   });
   private final AtomicBoolean dispatching = new AtomicBoolean();
   private final AtomicBoolean backgroundBatchInFlight = new AtomicBoolean();
   private final AtomicInteger epoch = new AtomicInteger();
   private final AtomicLong visibilityRevision = new AtomicLong();
   private final AtomicLong snapshotVersion = new AtomicLong();
   private volatile NeoFloodEngine.Snapshot snapshot = NeoFloodEngine.Snapshot.empty();
   private volatile NeoFloodEngine.SourceState[] activeSourceSnapshot = new NeoFloodEngine.SourceState[0];
   private volatile ClientLevel currentLevel;
   private volatile Vec3 playerPos = Vec3.ZERO;
   private volatile int worldTracingWorkers = 1;
   private volatile long nextBackgroundBatchNanos;
   private volatile boolean ready;
   private boolean activeSourceSnapshotDirty;
   private int dirtyBlockDelay = -1;
   private final NeoFloodEngine.SectionBuildScratch sectionBuildScratch = new NeoFloodEngine.SectionBuildScratch();
   private volatile Consumer<LongOpenHashSet> onSectionsChanged;
   private volatile Consumer<LongOpenHashSet> onImmediateSectionsChanged;
   private volatile Consumer<NeoFloodEngine.LightChangeBatch> onLightChanges;
   private volatile Runnable onHighPriorityTraceDone;

   public void setLevel(ClientLevel level) {
      this.currentLevel = level;
   }

   public void setPlayerPos(Vec3 pos) {
      this.playerPos = pos == null ? Vec3.ZERO : pos;
   }

   public void setOnSectionsChanged(Consumer<LongOpenHashSet> callback) {
      this.onSectionsChanged = callback;
   }

   public void setOnImmediateSectionsChanged(Consumer<LongOpenHashSet> callback) {
      this.onImmediateSectionsChanged = callback;
   }

   public void setOnLightChanges(Consumer<NeoFloodEngine.LightChangeBatch> callback) {
      this.onLightChanges = callback;
   }

   public void setOnHighPriorityTraceDone(Runnable callback) {
      this.onHighPriorityTraceDone = callback;
   }

   public void setWorldTracingWorkers(int count) {
      this.worldTracingWorkers = clamp(count, 1, Math.max(1, Config.MAX_RTX_WORLD_TRACING_WORKERS));
   }

   public void addOrUpdateSource(long key, int emission, boolean playerPlaced) {
      ClientLevel level = this.currentLevel;
      if (level != null && emission > 0) {
         synchronized (this.stateLock) {
            NeoFloodEngine.SourceState state = this.sources.get(key);
            boolean membershipChanged = false;
            if (state == null) {
               state = new NeoFloodEngine.SourceState(key);
               this.sources.put(key, state);
               this.indexSourceLocked(state);
               membershipChanged = true;
            } else if (!state.active) {
               membershipChanged = true;
            }

            state.active = true;
            state.emission = emission;
            state.maxReachBlocks = sourceReach(emission);
            state.generation++;
            if (membershipChanged) {
               this.activeSourceSnapshotDirty = true;
            }

            if (!playerPlaced) {
               this.worldDirtyQueue.add(key);
            }
         }

         if (playerPlaced) {
            this.submitTrace(key, level, true);
         } else {
            this.scheduleWorldDispatch();
         }
      }
   }

   public void ensureSourceScheduled(long key, int emission, boolean highPriority) {
      this.addOrUpdateSource(key, emission, highPriority);
   }

   public void addOrUpdateWorldSources(Long2ByteOpenHashMap sourceBatch) {
      if (sourceBatch != null && !sourceBatch.isEmpty()) {
         synchronized (this.stateLock) {
            boolean membershipChanged = false;
            ObjectIterator<Entry> it = sourceBatch.long2ByteEntrySet().fastIterator();

            while (it.hasNext()) {
               Entry entry = (Entry)it.next();
               int emission = entry.getByteValue() & 255;
               if (emission > 0) {
                  long key = entry.getLongKey();
                  NeoFloodEngine.SourceState state = this.sources.get(key);
                  if (state == null) {
                     state = new NeoFloodEngine.SourceState(key);
                     this.sources.put(key, state);
                     this.indexSourceLocked(state);
                     membershipChanged = true;
                  } else if (!state.active) {
                     membershipChanged = true;
                  }

                  if (!state.active || state.emission != emission || state.result == null) {
                     state.active = true;
                     state.emission = emission;
                     state.maxReachBlocks = sourceReach(emission);
                     state.generation++;
                     this.worldDirtyQueue.add(key);
                  }
               }
            }

            if (membershipChanged) {
               this.activeSourceSnapshotDirty = true;
            }
         }

         this.scheduleWorldDispatch();
      }
   }

   public void removeSource(long key) {
      this.removeSource(key, false);
   }

   public void removeSource(long key, boolean playerAction) {
      LongOpenHashSet keys = new LongOpenHashSet();
      keys.add(key);
      this.removeSourcesInternal(keys, playerAction);
   }

   public void removeSources(LongOpenHashSet keys) {
      this.removeSourcesInternal(keys, false);
   }

   public void removeSourcesAsync(LongOpenHashSet keys) {
      this.removeSourcesInternal(keys, false);
   }

   private void removeSourcesInternal(LongOpenHashSet keys, boolean highPriority) {
      if (keys != null && !keys.isEmpty()) {
         ArrayList<NeoFloodEngine.SourceState> removed = new ArrayList<>(keys.size());
         synchronized (this.stateLock) {
            LongIterator it = keys.iterator();

            while (it.hasNext()) {
               long key = it.nextLong();
               NeoFloodEngine.SourceState state = this.sources.get(key);
               if (state != null && state.active) {
                  state.active = false;
                  state.generation++;
                  this.worldDirtyQueue.remove(key);
                  this.unindexSourceLocked(state);
                  this.sources.remove(key, state);
                  state.removedResult = state.result;
                  removed.add(state);
               }
            }

            if (!removed.isEmpty()) {
               this.activeSourceSnapshotDirty = true;
            }
         }

         if (!removed.isEmpty()) {
            int removalEpoch = this.epoch.get();
            this.publishExecutor.execute(() -> this.publishRemovedSources(removed, highPriority, removalEpoch));
         }
      }
   }

   public void markDirty(LongOpenHashSet keys) {
      if (keys != null && !keys.isEmpty()) {
         synchronized (this.stateLock) {
            LongIterator it = keys.iterator();

            while (it.hasNext()) {
               long key = it.nextLong();
               NeoFloodEngine.SourceState state = this.sources.get(key);
               if (state != null && state.active) {
                  state.maxReachBlocks = sourceReach(state.emission);
                  state.generation++;
                  this.worldDirtyQueue.add(key);
               }
            }
         }
      }
   }

   public void clear() {
      this.epoch.incrementAndGet();
      ArrayList<NeoFloodEngine.SourceState> oldStates = new ArrayList<>();
      LongOpenHashSet oldSections = new LongOpenHashSet();
      synchronized (this.stateLock) {
         for (NeoFloodEngine.SourceState state : this.sources.values()) {
            state.active = false;
            state.generation++;
            oldStates.add(state);
         }

         NeoFloodEngine.Snapshot old = this.snapshot;
         old.collectSectionKeys(oldSections);
         this.sources.clear();
         this.sourcesByChunk.clear();
         this.worldDirtyQueue.clear();
         this.activeSourceSnapshot = new NeoFloodEngine.SourceState[0];
         this.activeSourceSnapshotDirty = false;
         this.ready = false;
         this.snapshot = NeoFloodEngine.Snapshot.empty();
         this.snapshotVersion.incrementAndGet();
      }

      synchronized (this.dirtyBlocksLock) {
         this.pendingDirtyBlocks.clear();
         this.dirtyBlockDelay = -1;
      }

      this.publishExecutor.execute(() -> {
         this.sourcesBySection.clear();

         for (NeoFloodEngine.SourceState statex : oldStates) {
            NeoFloodEngine.PackedSourceData old;
            synchronized (statex) {
               old = statex.result;
               statex.result = null;
               statex.visibilityRevision = 0L;
            }

            if (old != null) {
               NeoFloodRtEngine.recycleVisibilityVolume(old.visibilityVolume);
            }
         }
      });
      if (!oldSections.isEmpty()) {
         Consumer<LongOpenHashSet> callback = this.onSectionsChanged;
         if (callback != null) {
            callback.accept(oldSections);
         }
      }
   }

   public void onBlockChanged(BlockPos pos, ClientLevel level, boolean playerAction) {
      if (pos != null && level != null) {
         if (playerAction) {
            LongOpenHashSet blocks = new LongOpenHashSet(1);
            blocks.add(pos.asLong());
            this.dispatchExecutor.execute(() -> this.processDirtyBlocks(blocks));
         } else {
            synchronized (this.dirtyBlocksLock) {
               this.pendingDirtyBlocks.add(pos.asLong());
               this.dirtyBlockDelay = 2;
            }
         }
      }
   }

   public void tick() {
      this.tickPendingDirtyBlocks();
      this.scheduleWorldDispatch();
   }

   public void boostNearestWorldSources(int limit) {
      if (limit > 0) {
         this.scheduleWorldDispatch();
      }
   }

   public void traceNearestSourcesSync(int maxCount, ClientLevel level, Vec3 origin) {
      if (maxCount > 0 && level != null) {
         if (origin != null) {
            this.setPlayerPos(origin);
         }

         this.scheduleWorldDispatch();
      }
   }

   public int getLight(BlockPos pos) {
      return pos == null ? 0 : this.getLight(pos.asLong());
   }

   public int getLight(long key) {
      return this.snapshot.getLight(key);
   }

   public boolean hasAnyLight(long key) {
      return this.getLight(key) > 0;
   }

   public int getCoverage(BlockPos pos) {
      return pos == null ? 0 : this.getCoverage(pos.asLong());
   }

   public int getCoverage(long key) {
      return this.snapshot.getCoverage(key);
   }

   public boolean anySourceNear(long posKey, int extraRadius) {
      int x = BlockPos.getX(posKey);
      int y = BlockPos.getY(posKey);
      int z = BlockPos.getZ(posKey);

      for (NeoFloodEngine.SourceState source : this.activeSourceSnapshot) {
         int reach = source.maxReachBlocks + Math.max(0, extraRadius);
         if (Math.abs(x - source.x) <= reach && Math.abs(y - source.y) <= reach && Math.abs(z - source.z) <= reach) {
            return true;
         }
      }

      return false;
   }

   public long findNearestLitSample(
      long basePosKey, int axisUX, int axisUY, int axisUZ, int axisVX, int axisVY, int axisVZ, int searchRadius, double sampleX, double sampleY, double sampleZ
   ) {
      if (this.getLight(basePosKey) > 0) {
         return basePosKey;
      } else {
         int baseX = BlockPos.getX(basePosKey);
         int baseY = BlockPos.getY(basePosKey);
         int baseZ = BlockPos.getZ(basePosKey);
         long best = Long.MIN_VALUE;
         double bestDistance = Double.POSITIVE_INFINITY;

         for (int dv = -searchRadius; dv <= searchRadius; dv++) {
            for (int du = -searchRadius; du <= searchRadius; du++) {
               if (du != 0 || dv != 0) {
                  int x = baseX + axisUX * du + axisVX * dv;
                  int y = baseY + axisUY * du + axisVY * dv;
                  int z = baseZ + axisUZ * du + axisVZ * dv;
                  long key = BlockPos.asLong(x, y, z);
                  if (this.getLight(key) > 0) {
                     double dx = (double)x + 0.5 - sampleX;
                     double dy = (double)y + 0.5 - sampleY;
                     double dz = (double)z + 0.5 - sampleZ;
                     double distance = dx * dx + dy * dy + dz * dz;
                     if (distance < bestDistance) {
                        bestDistance = distance;
                        best = key;
                     }
                  }
               }
            }
         }

         return best;
      }
   }

   public long getDominantSource(BlockPos pos) {
      return pos == null ? Long.MIN_VALUE : this.getDominantSource(pos.asLong());
   }

   public long getDominantSource(long posKey) {
      NeoFloodEngine.SectionLightData section = this.snapshot.getSection(sectionKeyForBlock(posKey));
      if (section != null && section.sourceKeys.length != 0) {
         long bestKey = Long.MIN_VALUE;
         int bestLight = 0;
         int bestCoverage = 0;
         double bestDistance = Double.POSITIVE_INFINITY;

         for (long sourceKey : section.sourceKeys) {
            NeoFloodEngine.SourceState state = this.sources.get(sourceKey);
            NeoFloodEngine.PackedSourceData data = state != null && state.active ? state.result : null;
            if (data != null) {
               int light = data.getLight(posKey);
               if (light > 0) {
                  int coverage = data.getCoverage(posKey);
                  double distance = distanceSq(sourceKey, posKey);
                  if (light > bestLight
                     || light == bestLight && coverage > bestCoverage
                     || light == bestLight && coverage == bestCoverage && distance < bestDistance
                     || light == bestLight && coverage == bestCoverage && distance == bestDistance && sourceKey < bestKey) {
                     bestKey = sourceKey;
                     bestLight = light;
                     bestCoverage = coverage;
                     bestDistance = distance;
                  }
               }
            }
         }

         return bestKey;
      } else {
         return Long.MIN_VALUE;
      }
   }

   public void forEachContributor(long posKey, NeoFloodEngine.ContributorVisitor visitor) {
      if (visitor != null) {
         NeoFloodEngine.SectionLightData section = this.snapshot.getSection(sectionKeyForBlock(posKey));
         if (section != null) {
            for (long sourceKey : section.sourceKeys) {
               this.visitContributor(sourceKey, posKey, visitor);
            }
         }
      }
   }

   public void visitContributor(long sourceKey, long posKey, NeoFloodEngine.ContributorVisitor visitor) {
      if (visitor != null) {
         NeoFloodEngine.SourceState state = this.sources.get(sourceKey);
         NeoFloodEngine.PackedSourceData data = state != null && state.active ? state.result : null;
         if (data != null) {
            int light = data.getLight(posKey);
            if (light > 0) {
               visitor.visit(sourceKey, light, data.getCoverage(posKey), state.emission);
            }
         }
      }
   }

   public boolean sourceContributesTo(long sourceKey, long posKey) {
      NeoFloodEngine.SourceState state = this.sources.get(sourceKey);
      NeoFloodEngine.PackedSourceData data = state != null && state.active ? state.result : null;
      return data != null && data.getLight(posKey) > 0 && data.getCoverage(posKey) > 0;
   }

   public long getSourceVisibilityRevision(long sourceKey) {
      NeoFloodEngine.SourceState state = this.sources.get(sourceKey);
      return state != null && state.active ? state.visibilityRevision : 0L;
   }

   public long copySourceVisibilityVolume(long sourceKey, int radius, byte[] output) {
      if (output != null && radius >= 0) {
         int side = radius * 2 + 1;
         int required = side * side * side;
         if (output.length < required) {
            throw new IllegalArgumentException("Output buffer is smaller than requested source volume");
         } else {
            Arrays.fill(output, 0, required, (byte)0);
            NeoFloodEngine.SourceState state = this.sources.get(sourceKey);
            if (state != null && state.active) {
               synchronized (state) {
                  NeoFloodEngine.PackedSourceData data = state.result;
                  long revision = state.visibilityRevision;
                  if (data != null && revision > 0L) {
                     data.copyVisibility(radius, output);
                     return revision;
                  } else {
                     return 0L;
                  }
               }
            } else {
               return 0L;
            }
         }
      } else {
         return 0L;
      }
   }

   public boolean isDirectlyLit(long key, int coverageThreshold) {
      return this.getLight(key) > 0 && this.getCoverage(key) >= coverageThreshold;
   }

   public boolean isFrontier(long key, int coverageThreshold) {
      if (!this.isDirectlyLit(key, coverageThreshold)) {
         return false;
      } else {
         int x = BlockPos.getX(key);
         int y = BlockPos.getY(key);
         int z = BlockPos.getZ(key);

         for (Direction face : FACES) {
            long neighbour = BlockPos.asLong(x + face.getStepX(), y + face.getStepY(), z + face.getStepZ());
            if (this.getLight(neighbour) <= 0 || this.getCoverage(neighbour) < coverageThreshold) {
               return true;
            }
         }

         return false;
      }
   }

   public long getLightSnapshotVersion() {
      return this.snapshotVersion.get();
   }

   public void fillLightVolume(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] output) {
      if (output != null && sizeX > 0 && sizeY > 0 && sizeZ > 0) {
         int required = sizeX * sizeY * sizeZ;
         if (output.length < required) {
            throw new IllegalArgumentException("Output buffer is smaller than requested light volume");
         } else {
            NeoFloodEngine.Snapshot read = this.snapshot;
            int index = 0;

            for (int y = 0; y < sizeY; y++) {
               int worldY = minY + y;

               for (int z = 0; z < sizeZ; z++) {
                  int worldZ = minZ + z;

                  for (int x = 0; x < sizeX; x++) {
                     output[index++] = (byte)read.getLight(BlockPos.asLong(minX + x, worldY, worldZ));
                  }
               }
            }
         }
      }
   }

   public boolean isReady() {
      return this.ready;
   }

   private void tickPendingDirtyBlocks() {
      LongOpenHashSet blocks = new LongOpenHashSet();
      synchronized (this.dirtyBlocksLock) {
         if (this.dirtyBlockDelay < 0) {
            return;
         }

         if (this.dirtyBlockDelay-- > 0) {
            return;
         }

         blocks.addAll(this.pendingDirtyBlocks);
         this.pendingDirtyBlocks.clear();
         this.dirtyBlockDelay = -1;
      }

      if (!blocks.isEmpty()) {
         this.dispatchExecutor.execute(() -> this.processDirtyBlocks(blocks));
      }
   }

   private void processDirtyBlocks(LongOpenHashSet blocks) {
      synchronized (this.stateLock) {
         LongOpenHashSet affected = new LongOpenHashSet();
         LongIterator blockIt = blocks.iterator();

         while (blockIt.hasNext()) {
            long blockKey = blockIt.nextLong();
            this.collectCandidateSourcesNearBlockLocked(BlockPos.of(blockKey), affected);
            affected.remove(blockKey);
         }

         LongIterator sourceIt = affected.iterator();

         while (sourceIt.hasNext()) {
            long key = sourceIt.nextLong();
            NeoFloodEngine.SourceState state = this.sources.get(key);
            if (state != null && state.active) {
               state.maxReachBlocks = sourceReach(state.emission);
               state.generation++;
               this.worldDirtyQueue.add(key);
            }
         }
      }

      this.scheduleWorldDispatch();
   }

   private void scheduleWorldDispatch() {
      if (!this.backgroundBatchInFlight.get()) {
         if (System.nanoTime() >= this.nextBackgroundBatchNanos) {
            if (this.hasQueuedWorldSources()) {
               if (this.dispatching.compareAndSet(false, true)) {
                  this.dispatchExecutor.execute(() -> {
                     try {
                        this.dispatchWorldSources();
                     } finally {
                        this.dispatching.set(false);
                     }
                  });
               }
            }
         }
      }
   }

   private boolean hasQueuedWorldSources() {
      synchronized (this.stateLock) {
         return !this.worldDirtyQueue.isEmpty();
      }
   }

   private void dispatchWorldSources() {
      ClientLevel level = this.currentLevel;
      if (level != null) {
         long now = System.nanoTime();
         if (now >= this.nextBackgroundBatchNanos) {
            if (this.backgroundBatchInFlight.compareAndSet(false, true)) {
               int configuredBatch = Math.min(Math.max(1, FloodRtSettings.updateBudgetPerTick()), Math.max(1, this.worldTracingWorkers));
               ArrayList<NeoFloodEngine.TraceCapture> captures = this.selectTraceBatch(Math.min(16, configuredBatch));
               if (captures.isEmpty()) {
                  this.backgroundBatchInFlight.set(false);
               } else {
                  this.nextBackgroundBatchNanos = now + 50000000L;
                  this.submitTraceBatch(captures, level, false);
               }
            }
         }
      }
   }

   private ArrayList<NeoFloodEngine.TraceCapture> selectTraceBatch(int limit) {
      ArrayList<NeoFloodEngine.TraceCapture> captures = new ArrayList<>(Math.max(1, limit));
      synchronized (this.stateLock) {
         if (this.worldDirtyQueue.isEmpty()) {
            return captures;
         } else {
            long[] nearest = this.selectNearestLocked(this.worldDirtyQueue, this.playerPos, limit);

            for (long key : nearest) {
               NeoFloodEngine.SourceState state = this.sources.get(key);
               if (state == null || !state.active || state.emission <= 0) {
                  this.worldDirtyQueue.remove(key);
               } else if (state.submittedGeneration >= state.generation) {
                  this.worldDirtyQueue.remove(key);
               } else {
                  state.submittedGeneration = state.generation;
                  captures.add(new NeoFloodEngine.TraceCapture(this.epoch.get(), state, state.generation, state.emission));
                  this.worldDirtyQueue.remove(key);
               }
            }

            return captures;
         }
      }
   }

   private void submitTraceBatch(ArrayList<NeoFloodEngine.TraceCapture> captures, ClientLevel level, boolean highPriority) {
      if (captures.isEmpty()) {
         if (!highPriority) {
            this.backgroundBatchInFlight.set(false);
         }
      } else {
         ExecutorService executor = highPriority ? this.playerExecutor : this.worldExecutor;
         int lanes = highPriority ? 1 : Math.min(Math.max(1, this.worldTracingWorkers), captures.size());
         NeoFloodEngine.CompletedTrace[] completed = new NeoFloodEngine.CompletedTrace[captures.size()];
         AtomicInteger nextIndex = new AtomicInteger();
         AtomicInteger remainingLanes = new AtomicInteger(lanes);

         for (int lane = 0; lane < lanes; lane++) {
            executor.execute(() -> {
               int index;
               try {
                  while ((index = nextIndex.getAndIncrement()) < captures.size()) {
                     NeoFloodEngine.TraceCapture capture = captures.get(index);

                     try {
                        NeoFloodRtEngine.Result solved = NeoFloodRtEngine.solve(capture.state.key, capture.emission, level);
                        completed[index] = new NeoFloodEngine.CompletedTrace(capture, NeoFloodEngine.PackedSourceData.from(capture.state.key, solved));
                     } catch (Throwable var13) {
                        this.restoreFailedTrace(capture);
                     }
                  }
               } finally {
                  if (remainingLanes.decrementAndGet() == 0) {
                     this.publishExecutor.execute(() -> {
                        try {
                           this.publishTraceBatch(completed, highPriority);
                        } finally {
                           if (!highPriority) {
                              this.backgroundBatchInFlight.set(false);
                           }
                        }
                     });
                  }
               }
            });
         }
      }
   }

   private void restoreFailedTrace(NeoFloodEngine.TraceCapture capture) {
      synchronized (this.stateLock) {
         NeoFloodEngine.SourceState state = capture.state;
         if (this.isCaptureCurrent(capture)) {
            state.submittedGeneration = capture.generation - 1;
            this.worldDirtyQueue.add(state.key);
         }
      }
   }

   private boolean submitTrace(long key, ClientLevel level, boolean highPriority) {
      if (!highPriority) {
         synchronized (this.stateLock) {
            NeoFloodEngine.SourceState state = this.sources.get(key);
            if (state == null || !state.active) {
               return false;
            }

            this.worldDirtyQueue.add(key);
         }

         this.scheduleWorldDispatch();
         return true;
      } else {
         NeoFloodEngine.TraceCapture capture = this.captureTrace(key);
         if (capture == null) {
            return false;
         } else {
            ArrayList<NeoFloodEngine.TraceCapture> captures = new ArrayList<>(1);
            captures.add(capture);
            this.submitTraceBatch(captures, level, true);
            return true;
         }
      }
   }

   private NeoFloodEngine.TraceCapture captureTrace(long key) {
      synchronized (this.stateLock) {
         NeoFloodEngine.SourceState state = this.sources.get(key);
         if (state == null || !state.active || state.emission <= 0) {
            return null;
         } else if (state.submittedGeneration >= state.generation) {
            return null;
         } else {
            state.submittedGeneration = state.generation;
            return new NeoFloodEngine.TraceCapture(this.epoch.get(), state, state.generation, state.emission);
         }
      }
   }

   private void publishTraceBatch(NeoFloodEngine.CompletedTrace[] completed, boolean highPriority) {
      ArrayList<NeoFloodEngine.CompletedTrace> pending = new ArrayList<>(completed.length);

      for (NeoFloodEngine.CompletedTrace trace : completed) {
         if (trace != null) {
            pending.add(trace);
         }
      }

      if (!pending.isEmpty()) {
         while (!pending.isEmpty()) {
            Long2ObjectOpenHashMap<NeoFloodEngine.PackedSourceData> replacements = new Long2ObjectOpenHashMap(pending.size());
            Long2ObjectOpenHashMap<NeoFloodEngine.PackedSourceData> oldDataBySource = new Long2ObjectOpenHashMap(pending.size());
            LongOpenHashSet affected = new LongOpenHashSet();
            ArrayList<NeoFloodEngine.CompletedTrace> current = new ArrayList<>(pending.size());
            synchronized (this.stateLock) {
               for (NeoFloodEngine.CompletedTrace tracex : pending) {
                  if (!this.isCaptureCurrent(tracex.capture)) {
                     NeoFloodRtEngine.recycleVisibilityVolume(tracex.data.visibilityVolume);
                  } else {
                     long sourceKey = tracex.capture.state.key;
                     NeoFloodEngine.PackedSourceData oldData = tracex.capture.state.result;
                     current.add(tracex);
                     replacements.put(sourceKey, tracex.data);
                     if (oldData != null) {
                        oldDataBySource.put(sourceKey, oldData);
                        affected.addAll(oldData.sectionBits.keySet());
                     }

                     affected.addAll(tracex.data.sectionBits.keySet());
                  }
               }
            }

            pending.clear();
            if (current.isEmpty()) {
               return;
            }

            NeoFloodEngine.PublishResult rebuilt = this.rebuildSectionsBatch(affected, replacements, null);
            boolean allCurrent = true;
            synchronized (this.stateLock) {
               for (NeoFloodEngine.CompletedTrace tracexx : current) {
                  if (!this.isCaptureCurrent(tracexx.capture)) {
                     allCurrent = false;
                     break;
                  }
               }

               if (allCurrent) {
                  for (NeoFloodEngine.CompletedTrace tracexxx : current) {
                     NeoFloodEngine.SourceState state = tracexxx.capture.state;
                     NeoFloodEngine.PackedSourceData oldData = (NeoFloodEngine.PackedSourceData)oldDataBySource.get(state.key);
                     synchronized (state) {
                        state.result = tracexxx.data;
                        state.visibilityRevision = this.visibilityRevision.incrementAndGet();
                     }

                     this.updateReverseIndex(state.key, oldData, tracexxx.data);
                  }

                  if (this.activeSourceSnapshotDirty) {
                     this.rebuildActiveSourceSnapshotLocked();
                  }

                  this.applyRebuiltSnapshot(rebuilt);
                  this.ready = true;
               }
            }

            if (allCurrent) {
               for (NeoFloodEngine.CompletedTrace tracexxx : current) {
                  NeoFloodEngine.PackedSourceData oldData = (NeoFloodEngine.PackedSourceData)oldDataBySource.get(tracexxx.capture.state.key);
                  if (oldData != null && oldData.visibilityVolume != tracexxx.data.visibilityVolume) {
                     NeoFloodRtEngine.recycleVisibilityVolume(oldData.visibilityVolume);
                  }
               }

               this.notifyRebuiltSections(rebuilt, highPriority);
               if (highPriority) {
                  Runnable callback = this.onHighPriorityTraceDone;
                  if (callback != null) {
                     callback.run();
                  }
               }

               return;
            }

            for (NeoFloodEngine.CompletedTrace tracexxxx : current) {
               if (this.isCaptureCurrent(tracexxxx.capture)) {
                  pending.add(tracexxxx);
               } else {
                  NeoFloodRtEngine.recycleVisibilityVolume(tracexxxx.data.visibilityVolume);
               }
            }
         }
      }
   }

   private boolean isCaptureCurrent(NeoFloodEngine.TraceCapture capture) {
      NeoFloodEngine.SourceState state = capture.state;
      return capture.epoch == this.epoch.get()
         && this.sources.get(state.key) == state
         && state.active
         && state.generation == capture.generation
         && state.emission == capture.emission;
   }

   private void publishRemovedSources(List<NeoFloodEngine.SourceState> removed, boolean highPriority, int removalEpoch) {
      LongOpenHashSet removedKeys = new LongOpenHashSet(removed.size());
      LongOpenHashSet affected = new LongOpenHashSet();

      for (NeoFloodEngine.SourceState state : removed) {
         removedKeys.add(state.key);
         NeoFloodEngine.PackedSourceData old = state.result;
         if (old != null) {
            affected.addAll(old.sectionBits.keySet());
         }
      }

      NeoFloodEngine.PublishResult rebuilt = this.rebuildSectionsBatch(affected, null, removedKeys);
      boolean publish;
      synchronized (this.stateLock) {
         publish = removalEpoch == this.epoch.get();
         if (publish) {
            for (NeoFloodEngine.SourceState statex : removed) {
               this.updateReverseIndex(statex.key, statex.result, null);
            }

            if (this.activeSourceSnapshotDirty) {
               this.rebuildActiveSourceSnapshotLocked();
            }

            this.applyRebuiltSnapshot(rebuilt);
         }

         for (NeoFloodEngine.SourceState statex : removed) {
            synchronized (statex) {
               statex.result = null;
               statex.visibilityRevision = 0L;
            }

            this.sources.remove(statex.key, statex);
         }
      }

      for (NeoFloodEngine.SourceState statex : removed) {
         NeoFloodEngine.PackedSourceData old = statex.removedResult;
         statex.removedResult = null;
         if (old != null) {
            NeoFloodRtEngine.recycleVisibilityVolume(old.visibilityVolume);
         }
      }

      if (publish) {
         this.notifyRebuiltSections(rebuilt, highPriority);
      }

      if (publish && highPriority) {
         Runnable callback = this.onHighPriorityTraceDone;
         if (callback != null) {
            callback.run();
         }
      }
   }

   private NeoFloodEngine.PublishResult rebuildSectionsBatch(
      LongOpenHashSet affected, Long2ObjectOpenHashMap<NeoFloodEngine.PackedSourceData> replacements, LongOpenHashSet removedKeys
   ) {
      NeoFloodEngine.Snapshot oldSnapshot = this.snapshot;
      Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData> rebuilt = new Long2ObjectOpenHashMap();
      LongOpenHashSet emptySections = new LongOpenHashSet();
      NeoFloodEngine.ChangedCells changed = new NeoFloodEngine.ChangedCells();
      LongOpenHashSet changedSections = new LongOpenHashSet();
      LongOpenHashSet candidates = new LongOpenHashSet();
      LongIterator sectionIt = affected.iterator();

      while (sectionIt.hasNext()) {
         long sectionKey = sectionIt.nextLong();
         candidates.clear();
         LongOpenHashSet indexed = (LongOpenHashSet)this.sourcesBySection.get(sectionKey);
         if (indexed != null) {
            candidates.addAll(indexed);
         }

         if (removedKeys != null) {
            candidates.removeAll(removedKeys);
         }

         if (replacements != null) {
            ObjectIterator<it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<NeoFloodEngine.PackedSourceData>> replacementIt = replacements.long2ObjectEntrySet()
               .fastIterator();

            while (replacementIt.hasNext()) {
               it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<NeoFloodEngine.PackedSourceData> entry = (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<NeoFloodEngine.PackedSourceData>)replacementIt.next();
               if (((NeoFloodEngine.PackedSourceData)entry.getValue()).sectionBits.containsKey(sectionKey)) {
                  candidates.add(entry.getLongKey());
               } else {
                  candidates.remove(entry.getLongKey());
               }
            }
         }

         NeoFloodEngine.SectionLightData previous = oldSnapshot.getSection(sectionKey);
         NeoFloodEngine.SectionBuildOutput output = this.buildSection(sectionKey, candidates, replacements, previous);
         if (output.dirtyWords != null) {
            changed.sections.put(sectionKey, output.dirtyWords);
            changedSections.add(sectionKey);
         }

         if (output.next == null) {
            emptySections.add(sectionKey);
         } else {
            rebuilt.put(sectionKey, output.next);
         }
      }

      return new NeoFloodEngine.PublishResult(affected, rebuilt, emptySections, changed, changedSections);
   }

   private NeoFloodEngine.SectionBuildOutput buildSection(
      long sectionKey,
      LongOpenHashSet candidates,
      Long2ObjectOpenHashMap<NeoFloodEngine.PackedSourceData> replacements,
      NeoFloodEngine.SectionLightData previous
   ) {
      NeoFloodEngine.SectionBuildScratch scratch = this.sectionBuildScratch;
      scratch.reset(candidates.size());
      int baseX = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey));
      int baseY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
      int baseZ = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey));
      LongIterator sourceIt = candidates.iterator();

      while (sourceIt.hasNext()) {
         long sourceKey = sourceIt.nextLong();
         NeoFloodEngine.PackedSourceData data;
         if (replacements != null && replacements.containsKey(sourceKey)) {
            data = (NeoFloodEngine.PackedSourceData)replacements.get(sourceKey);
         } else {
            NeoFloodEngine.SourceState state = this.sources.get(sourceKey);
            data = state != null && state.active ? state.result : null;
         }

         if (data != null) {
            long[] bitsForSection = (long[])data.sectionBits.get(sectionKey);
            if (bitsForSection != null) {
               scratch.sourceKeys[scratch.sourceCount++] = sourceKey;

               for (int word = 0; word < 64; word++) {
                  for (long bits = bitsForSection[word]; bits != 0L; bits &= bits - 1L) {
                     int bit = Long.numberOfTrailingZeros(bits);
                     int localIndex = (word << 6) + bit;
                     int worldX = baseX + (localIndex & 15);
                     int worldZ = baseZ + (localIndex >>> 4 & 15);
                     int worldY = baseY + (localIndex >>> 8 & 15);
                     int candidateLight = data.getLight(worldX, worldY, worldZ);
                     int candidateCoverage = data.getCoverage(worldX, worldY, worldZ);
                     int currentLight = scratch.light[localIndex] & 255;
                     int currentCoverage = scratch.coverage[localIndex] & 255;
                     if (candidateLight > currentLight || candidateLight == currentLight && candidateCoverage > currentCoverage) {
                        scratch.light[localIndex] = (byte)candidateLight;
                        scratch.coverage[localIndex] = (byte)candidateCoverage;
                     }
                  }
               }
            }
         }
      }

      Arrays.sort(scratch.sourceKeys, 0, scratch.sourceCount);
      long[] dirtyWords = diffSection(previous, scratch.light, scratch.coverage);
      boolean sameSources = previous != null && Arrays.equals(previous.sourceKeys, 0, previous.sourceKeys.length, scratch.sourceKeys, 0, scratch.sourceCount);
      if (scratch.sourceCount == 0) {
         return new NeoFloodEngine.SectionBuildOutput(null, dirtyWords);
      } else if (dirtyWords == null && sameSources) {
         return new NeoFloodEngine.SectionBuildOutput(previous, null);
      } else {
         byte[] nextLight = dirtyWords == null && previous != null ? previous.light : Arrays.copyOf(scratch.light, scratch.light.length);
         byte[] nextCoverage = dirtyWords == null && previous != null ? previous.coverage : Arrays.copyOf(scratch.coverage, scratch.coverage.length);
         long[] nextSources = sameSources ? previous.sourceKeys : Arrays.copyOf(scratch.sourceKeys, scratch.sourceCount);
         return new NeoFloodEngine.SectionBuildOutput(new NeoFloodEngine.SectionLightData(nextLight, nextCoverage, nextSources), dirtyWords);
      }
   }

   private static long[] diffSection(NeoFloodEngine.SectionLightData previous, byte[] light, byte[] coverage) {
      long[] words = null;

      for (int i = 0; i < 4096; i++) {
         int oldLight = previous == null ? 0 : previous.light[i] & 255;
         int oldCoverage = previous == null ? 0 : previous.coverage[i] & 255;
         if (oldLight != (light[i] & 255) || oldCoverage != (coverage[i] & 255)) {
            if (words == null) {
               words = new long[64];
            }

            words[i >>> 6] = words[i >>> 6] | 1L << (i & 63);
         }
      }

      return words;
   }

   private void applyRebuiltSnapshot(NeoFloodEngine.PublishResult rebuilt) {
      if (!rebuilt.affected.isEmpty()) {
         NeoFloodEngine.Snapshot old = this.snapshot;
         Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData>[] nextShards = (Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData>[])old.shards.clone();
         boolean[] copied = new boolean[64];
         LongIterator it = rebuilt.affected.iterator();

         while (it.hasNext()) {
            long sectionKey = it.nextLong();
            int shardIndex = snapshotShard(sectionKey);
            if (!copied[shardIndex]) {
               nextShards[shardIndex] = new Long2ObjectOpenHashMap(nextShards[shardIndex]);
               copied[shardIndex] = true;
            }

            if (rebuilt.emptySections.contains(sectionKey)) {
               nextShards[shardIndex].remove(sectionKey);
            } else {
               NeoFloodEngine.SectionLightData section = (NeoFloodEngine.SectionLightData)rebuilt.rebuilt.get(sectionKey);
               if (section != null) {
                  nextShards[shardIndex].put(sectionKey, section);
               }
            }
         }

         this.snapshot = new NeoFloodEngine.Snapshot(nextShards);
         this.snapshotVersion.incrementAndGet();
      }
   }

   private void notifyRebuiltSections(NeoFloodEngine.PublishResult rebuilt, boolean highPriority) {
      if (!rebuilt.changed.isEmpty()) {
         Consumer<NeoFloodEngine.LightChangeBatch> lightCallback = this.onLightChanges;
         if (lightCallback != null) {
            lightCallback.accept(new NeoFloodEngine.LightChangeBatch(rebuilt.changed.sections));
         }
      }

      if (!rebuilt.changedSections.isEmpty()) {
         Consumer<LongOpenHashSet> sectionCallback = highPriority ? this.onImmediateSectionsChanged : this.onSectionsChanged;
         if (sectionCallback != null) {
            sectionCallback.accept(rebuilt.changedSections);
         }
      }
   }

   private void updateReverseIndex(long sourceKey, NeoFloodEngine.PackedSourceData oldData, NeoFloodEngine.PackedSourceData newData) {
      if (oldData != null) {
         LongIterator it = oldData.sectionBits.keySet().iterator();

         while (it.hasNext()) {
            long sectionKey = it.nextLong();
            if (newData == null || !newData.sectionBits.containsKey(sectionKey)) {
               LongOpenHashSet bucket = (LongOpenHashSet)this.sourcesBySection.get(sectionKey);
               if (bucket != null) {
                  bucket.remove(sourceKey);
                  if (bucket.isEmpty()) {
                     this.sourcesBySection.remove(sectionKey);
                  }
               }
            }
         }
      }

      if (newData != null) {
         LongIterator it = newData.sectionBits.keySet().iterator();

         while (it.hasNext()) {
            long sectionKey = it.nextLong();
            LongOpenHashSet bucket = (LongOpenHashSet)this.sourcesBySection.get(sectionKey);
            if (bucket == null) {
               bucket = new LongOpenHashSet();
               this.sourcesBySection.put(sectionKey, bucket);
            }

            bucket.add(sourceKey);
         }
      }
   }

   private void collectCandidateSourcesNearBlockLocked(BlockPos pos, LongOpenHashSet output) {
      int chunkX = pos.getX() >> 4;
      int chunkZ = pos.getZ() >> 4;
      int blockX = pos.getX();
      int blockY = pos.getY();
      int blockZ = pos.getZ();

      for (int dz = -SOURCE_CHUNK_RADIUS; dz <= SOURCE_CHUNK_RADIUS; dz++) {
         for (int dx = -SOURCE_CHUNK_RADIUS; dx <= SOURCE_CHUNK_RADIUS; dx++) {
            LongOpenHashSet bucket = (LongOpenHashSet)this.sourcesByChunk.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
            if (bucket != null) {
               LongIterator it = bucket.iterator();

               while (it.hasNext()) {
                  long key = it.nextLong();
                  NeoFloodEngine.SourceState source = this.sources.get(key);
                  if (source != null && source.active) {
                     int sx = blockX - source.x;
                     int sy = blockY - source.y;
                     int sz = blockZ - source.z;
                     int reach = source.maxReachBlocks;
                     if (sx * sx + sy * sy + sz * sz <= reach * reach) {
                        output.add(key);
                     }
                  }
               }
            }
         }
      }
   }

   private long[] selectNearestLocked(LongOpenHashSet keys, Vec3 origin, int limit) {
      int capacity = Math.min(Math.max(0, limit), keys.size());
      if (capacity == 0) {
         return new long[0];
      } else {
         LongOpenHashSet selection = keys;
         if (keys == this.worldDirtyQueue && keys.size() > capacity * 2) {
            this.selectionCandidates.clear();
            int centerChunkX = Mth.floor(origin.x) >> 4;
            int centerChunkZ = Mth.floor(origin.z) >> 4;
            int target = Math.min(keys.size(), Math.max(capacity * 4, capacity));
            int maxRing = 48;

            for (int ring = 0; ring <= 48 && this.selectionCandidates.size() < target; ring++) {
               this.collectDirtySourcesOnChunkRingLocked(centerChunkX, centerChunkZ, ring, keys, this.selectionCandidates);
            }

            if (this.selectionCandidates.size() >= capacity) {
               selection = this.selectionCandidates;
            }
         }

         long[] bestKeys = new long[capacity];
         double[] bestDistances = new double[capacity];
         Arrays.fill(bestDistances, Double.POSITIVE_INFINITY);
         int count = 0;
         LongIterator it = selection.iterator();

         while (it.hasNext()) {
            long key = it.nextLong();
            NeoFloodEngine.SourceState state = this.sources.get(key);
            if (state != null && state.active) {
               double dx = (double)state.x + 0.5 - origin.x;
               double dy = (double)state.y + 0.5 - origin.y;
               double dz = (double)state.z + 0.5 - origin.z;
               double distance = dx * dx + dy * dy + dz * dz;

               int insert;
               for (insert = Math.min(count, capacity - 1); insert > 0 && distance < bestDistances[insert - 1]; insert--) {
                  bestDistances[insert] = bestDistances[insert - 1];
                  bestKeys[insert] = bestKeys[insert - 1];
               }

               if (distance < bestDistances[insert]) {
                  bestDistances[insert] = distance;
                  bestKeys[insert] = key;
                  if (count < capacity) {
                     count++;
                  }
               }
            }
         }

         return count == capacity ? bestKeys : Arrays.copyOf(bestKeys, count);
      }
   }

   private void collectDirtySourcesOnChunkRingLocked(int centerChunkX, int centerChunkZ, int ring, LongOpenHashSet dirty, LongOpenHashSet output) {
      if (ring == 0) {
         this.collectDirtySourcesFromChunkLocked(centerChunkX, centerChunkZ, dirty, output);
      } else {
         int minX = centerChunkX - ring;
         int maxX = centerChunkX + ring;
         int minZ = centerChunkZ - ring;
         int maxZ = centerChunkZ + ring;

         for (int x = minX; x <= maxX; x++) {
            this.collectDirtySourcesFromChunkLocked(x, minZ, dirty, output);
            this.collectDirtySourcesFromChunkLocked(x, maxZ, dirty, output);
         }

         for (int z = minZ + 1; z < maxZ; z++) {
            this.collectDirtySourcesFromChunkLocked(minX, z, dirty, output);
            this.collectDirtySourcesFromChunkLocked(maxX, z, dirty, output);
         }
      }
   }

   private void collectDirtySourcesFromChunkLocked(int chunkX, int chunkZ, LongOpenHashSet dirty, LongOpenHashSet output) {
      LongOpenHashSet bucket = (LongOpenHashSet)this.sourcesByChunk.get(ChunkPos.asLong(chunkX, chunkZ));
      if (bucket != null) {
         LongIterator it = bucket.iterator();

         while (it.hasNext()) {
            long key = it.nextLong();
            if (dirty.contains(key)) {
               output.add(key);
            }
         }
      }
   }

   private void indexSourceLocked(NeoFloodEngine.SourceState state) {
      long chunkKey = ChunkPos.asLong(state.x >> 4, state.z >> 4);
      LongOpenHashSet bucket = (LongOpenHashSet)this.sourcesByChunk.get(chunkKey);
      if (bucket == null) {
         bucket = new LongOpenHashSet();
         this.sourcesByChunk.put(chunkKey, bucket);
      }

      bucket.add(state.key);
   }

   private void unindexSourceLocked(NeoFloodEngine.SourceState state) {
      long chunkKey = ChunkPos.asLong(state.x >> 4, state.z >> 4);
      LongOpenHashSet bucket = (LongOpenHashSet)this.sourcesByChunk.get(chunkKey);
      if (bucket != null) {
         bucket.remove(state.key);
         if (bucket.isEmpty()) {
            this.sourcesByChunk.remove(chunkKey);
         }
      }
   }

   private void rebuildActiveSourceSnapshotLocked() {
      NeoFloodEngine.SourceState[] buffer = new NeoFloodEngine.SourceState[this.sources.size()];
      int count = 0;

      for (NeoFloodEngine.SourceState state : this.sources.values()) {
         if (state.active) {
            buffer[count++] = state;
         }
      }

      this.activeSourceSnapshot = count == buffer.length ? buffer : Arrays.copyOf(buffer, count);
      this.activeSourceSnapshotDirty = false;
   }

   private static int sourceReach(int emission) {
      return Math.min(36, Math.min(FloodRtSettings.radiusCap(), (int)Math.ceil(LightRtMath.getMaxDistance(emission))));
   }

   private static long sectionKeyForBlock(long posKey) {
      return SectionPos.asLong(BlockPos.getX(posKey) >> 4, BlockPos.getY(posKey) >> 4, BlockPos.getZ(posKey) >> 4);
   }

   private static int localSectionIndex(long posKey) {
      return (BlockPos.getY(posKey) & 15) << 8 | (BlockPos.getZ(posKey) & 15) << 4 | BlockPos.getX(posKey) & 15;
   }

   private static int snapshotShard(long sectionKey) {
      long value = sectionKey ^ sectionKey >>> 33;
      value *= -49064778989728563L;
      value ^= value >>> 33;
      value *= -4265267296055464877L;
      value ^= value >>> 33;
      return (int)value & 63;
   }

   private static double distanceSq(long a, long b) {
      double dx = (double)(BlockPos.getX(a) - BlockPos.getX(b));
      double dy = (double)(BlockPos.getY(a) - BlockPos.getY(b));
      double dz = (double)(BlockPos.getZ(a) - BlockPos.getZ(b));
      return dx * dx + dy * dy + dz * dz;
   }

   private static int clamp(int value, int min, int max) {
      return value < min ? min : Math.min(value, max);
   }

   private static final class ChangedCells {
      final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap();

      long[] wordsFor(long sectionKey) {
         long[] words = (long[])this.sections.get(sectionKey);
         if (words == null) {
            words = new long[64];
            this.sections.put(sectionKey, words);
         }

         return words;
      }

      boolean isEmpty() {
         return this.sections.isEmpty();
      }
   }

   private static final class CompletedTrace {
      final NeoFloodEngine.TraceCapture capture;
      final NeoFloodEngine.PackedSourceData data;

      CompletedTrace(NeoFloodEngine.TraceCapture capture, NeoFloodEngine.PackedSourceData data) {
         this.capture = capture;
         this.data = data;
      }
   }

   @FunctionalInterface
   public interface ContributorVisitor {
      void visit(long var1, int var3, int var4, int var5);
   }

   public static final class LightChangeBatch {
      private final Long2ObjectOpenHashMap<long[]> sections;

      private LightChangeBatch(Long2ObjectOpenHashMap<long[]> sections) {
         this.sections = sections;
      }

      public int sectionCount() {
         return this.sections.size();
      }

      public void forEachSection(NeoFloodEngine.SectionChangeVisitor visitor) {
         if (visitor != null) {
            ObjectIterator<it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<long[]>> it = this.sections.long2ObjectEntrySet().fastIterator();

            while (it.hasNext()) {
               it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<long[]> entry = (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<long[]>)it.next();
               visitor.visit(entry.getLongKey(), (long[])entry.getValue());
            }
         }
      }
   }

   private static final class PackedSourceData {
      final int originX;
      final int originY;
      final int originZ;
      final byte[] packedLight;
      final byte[] visibilityVolume;
      final Long2ObjectOpenHashMap<long[]> sectionBits;

      PackedSourceData(int originX, int originY, int originZ, byte[] packedLight, byte[] visibilityVolume, Long2ObjectOpenHashMap<long[]> sectionBits) {
         this.originX = originX;
         this.originY = originY;
         this.originZ = originZ;
         this.packedLight = packedLight;
         this.visibilityVolume = visibilityVolume;
         this.sectionBits = sectionBits;
      }

      static NeoFloodEngine.PackedSourceData from(long sourceKey, NeoFloodRtEngine.Result result) {
         int originX = BlockPos.getX(sourceKey);
         int originY = BlockPos.getY(sourceKey);
         int originZ = BlockPos.getZ(sourceKey);
         Long2ObjectOpenHashMap<long[]> sectionBits = new Long2ObjectOpenHashMap();

         for (int wordIndex = 0; wordIndex < result.touchedBits.length; wordIndex++) {
            for (long bits = result.touchedBits[wordIndex]; bits != 0L; bits &= bits - 1L) {
               int volumeIndex = (wordIndex << 6) + Long.numberOfTrailingZeros(bits);
               if (volumeIndex >= 389017) {
                  break;
               }

               int xIndex = volumeIndex % 73;
               int yz = volumeIndex / 73;
               int zIndex = yz % 73;
               int yIndex = yz / 73;
               int worldX = originX + xIndex - 36;
               int worldY = originY + yIndex - 36;
               int worldZ = originZ + zIndex - 36;
               long posKey = BlockPos.asLong(worldX, worldY, worldZ);
               long sectionKey = NeoFloodEngine.sectionKeyForBlock(posKey);
               long[] localBits = (long[])sectionBits.get(sectionKey);
               if (localBits == null) {
                  localBits = new long[64];
                  sectionBits.put(sectionKey, localBits);
               }

               int localIndex = NeoFloodEngine.localSectionIndex(posKey);
               localBits[localIndex >>> 6] = localBits[localIndex >>> 6] | 1L << (localIndex & 63);
            }
         }

         return new NeoFloodEngine.PackedSourceData(originX, originY, originZ, result.packedLight, result.visibilityVolume, sectionBits);
      }

      int getLight(long posKey) {
         return this.getLight(BlockPos.getX(posKey), BlockPos.getY(posKey), BlockPos.getZ(posKey));
      }

      int getLight(int worldX, int worldY, int worldZ) {
         int index = this.indexFor(worldX, worldY, worldZ);
         if (index < 0) {
            return 0;
         } else {
            int packed = this.packedLight[index >>> 1] & 255;
            return (index & 1) == 0 ? packed & 15 : packed >>> 4 & 15;
         }
      }

      int getCoverage(long posKey) {
         return this.getCoverage(BlockPos.getX(posKey), BlockPos.getY(posKey), BlockPos.getZ(posKey));
      }

      int getCoverage(int worldX, int worldY, int worldZ) {
         int index = this.indexFor(worldX, worldY, worldZ);
         return index < 0 ? 0 : this.visibilityVolume[index] & 0xFF;
      }

      int indexFor(int worldX, int worldY, int worldZ) {
         int dx = worldX - this.originX;
         int dy = worldY - this.originY;
         int dz = worldZ - this.originZ;
         return dx >= -36 && dx <= 36 && dy >= -36 && dy <= 36 && dz >= -36 && dz <= 36 ? (dy + 36) * 5329 + (dz + 36) * 73 + dx + 36 : -1;
      }

      void copyVisibility(int radius, byte[] output) {
         if (radius == 36) {
            System.arraycopy(this.visibilityVolume, 0, output, 0, 389017);
         } else {
            int side = radius * 2 + 1;
            int copyRadius = Math.min(radius, 36);
            int targetPlane = side * side;

            for (int y = -copyRadius; y <= copyRadius; y++) {
               int sourceY = y + 36;
               int targetY = y + radius;

               for (int z = -copyRadius; z <= copyRadius; z++) {
                  int sourceZ = z + 36;
                  int targetZ = z + radius;
                  int sourceOffset = sourceY * 5329 + sourceZ * 73 + (36 - copyRadius);
                  int targetOffset = targetY * targetPlane + targetZ * side + (radius - copyRadius);
                  System.arraycopy(this.visibilityVolume, sourceOffset, output, targetOffset, copyRadius * 2 + 1);
               }
            }
         }
      }
   }

   private static final class PublishResult {
      final LongOpenHashSet affected;
      final Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData> rebuilt;
      final LongOpenHashSet emptySections;
      final NeoFloodEngine.ChangedCells changed;
      final LongOpenHashSet changedSections;

      PublishResult(
         LongOpenHashSet affected,
         Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData> rebuilt,
         LongOpenHashSet emptySections,
         NeoFloodEngine.ChangedCells changed,
         LongOpenHashSet changedSections
      ) {
         this.affected = affected;
         this.rebuilt = rebuilt;
         this.emptySections = emptySections;
         this.changed = changed;
         this.changedSections = changedSections;
      }
   }

   private static final class SectionBuildOutput {
      final NeoFloodEngine.SectionLightData next;
      final long[] dirtyWords;

      SectionBuildOutput(NeoFloodEngine.SectionLightData next, long[] dirtyWords) {
         this.next = next;
         this.dirtyWords = dirtyWords;
      }
   }

   private static final class SectionBuildScratch {
      final byte[] light = new byte[4096];
      final byte[] coverage = new byte[4096];
      long[] sourceKeys = new long[16];
      int sourceCount;

      void reset(int sourceCapacity) {
         Arrays.fill(this.light, (byte)0);
         Arrays.fill(this.coverage, (byte)0);
         this.sourceCount = 0;
         if (sourceCapacity > this.sourceKeys.length) {
            int capacity = this.sourceKeys.length;

            while (capacity < sourceCapacity) {
               capacity <<= 1;
            }

            this.sourceKeys = new long[capacity];
         }
      }
   }

   @FunctionalInterface
   public interface SectionChangeVisitor {
      void visit(long var1, long[] var3);
   }

   private static final class SectionLightData {
      final byte[] light;
      final byte[] coverage;
      final long[] sourceKeys;

      SectionLightData(byte[] light, byte[] coverage, long[] sourceKeys) {
         this.light = light;
         this.coverage = coverage;
         this.sourceKeys = sourceKeys;
      }
   }

   private static final class Snapshot {
      final Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData>[] shards;

      Snapshot(Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData>[] shards) {
         this.shards = shards;
      }

      static NeoFloodEngine.Snapshot empty() {
         Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData>[] shards = new Long2ObjectOpenHashMap[64];

         for (int i = 0; i < shards.length; i++) {
            shards[i] = new Long2ObjectOpenHashMap();
         }

         return new NeoFloodEngine.Snapshot(shards);
      }

      NeoFloodEngine.SectionLightData getSection(long sectionKey) {
         return (NeoFloodEngine.SectionLightData)this.shards[NeoFloodEngine.snapshotShard(sectionKey)].get(sectionKey);
      }

      int getLight(long posKey) {
         NeoFloodEngine.SectionLightData section = this.getSection(NeoFloodEngine.sectionKeyForBlock(posKey));
         return section == null ? 0 : section.light[NeoFloodEngine.localSectionIndex(posKey)] & 0xFF;
      }

      int getCoverage(long posKey) {
         NeoFloodEngine.SectionLightData section = this.getSection(NeoFloodEngine.sectionKeyForBlock(posKey));
         return section == null ? 0 : section.coverage[NeoFloodEngine.localSectionIndex(posKey)] & 0xFF;
      }

      void collectSectionKeys(LongOpenHashSet output) {
         for (Long2ObjectOpenHashMap<NeoFloodEngine.SectionLightData> shard : this.shards) {
            output.addAll(shard.keySet());
         }
      }
   }

   private static final class SourceState {
      final long key;
      final int x;
      final int y;
      final int z;
      volatile boolean active;
      volatile int emission;
      volatile int maxReachBlocks;
      volatile int generation;
      volatile int submittedGeneration = -1;
      volatile NeoFloodEngine.PackedSourceData result;
      volatile NeoFloodEngine.PackedSourceData removedResult;
      volatile long visibilityRevision;

      SourceState(long key) {
         this.key = key;
         this.x = BlockPos.getX(key);
         this.y = BlockPos.getY(key);
         this.z = BlockPos.getZ(key);
      }
   }

   private static final class TraceCapture {
      final int epoch;
      final NeoFloodEngine.SourceState state;
      final int generation;
      final int emission;

      TraceCapture(int epoch, NeoFloodEngine.SourceState state, int generation, int emission) {
         this.epoch = epoch;
         this.state = state;
         this.generation = generation;
         this.emission = emission;
      }
   }
}
