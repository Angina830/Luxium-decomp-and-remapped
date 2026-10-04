package com.vinlanx.luxium.rtx;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.neocpu.NeoCpuShadowsEngine;
import com.vinlanx.luxium.client.reflections.RainPuddleManager;
import com.vinlanx.luxium.client.reflections.ReflectionMaterialRegistry;
import com.vinlanx.luxium.client.shadows.GpuShadowCache;
import com.vinlanx.luxium.mixin.LightTextureAccessor;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class TorchRtxState {
   private static final TorchRtxState INSTANCE = new TorchRtxState();
   private static final double ENTITY_UPDATE_RADIUS = 48.0;
   private static final int ENTITY_UPDATE_INTERVAL = 1;
   private static final int CLOSEST_SOURCE_CACHE_LIMIT = 20000;
   private static final int DIRECT_LIGHT_COVERAGE_THRESHOLD = 72;
   private static final int RTX_FRONTIER_BAND_RADIUS = 2;
   private static final int MAX_PENDING_LIGHT_CHANGES_PER_TICK = 192;
   private static final int MAX_PENDING_LIGHT_SECTIONS_PER_TICK = 48;
   private static final long LIGHT_CHANGE_BUDGET_NANOS = 1000000L;
   private static final int MAX_PENDING_SECTIONS_PER_TICK = 16;
   private static final Direction[] FACES = Direction.values();
   private static final long CHUNK_LIFECYCLE_BUDGET_NANOS = 1250000L;
   private static final int MAX_POPULATED_CHUNK_SECTIONS_PER_TICK = 2;
   private static final int MAX_CHUNK_UNLOADS_PER_TICK = 8;
   private static final int CHUNK_INVALIDATION_DEBOUNCE_TICKS = 4;
   private static final int CHUNK_INVALIDATION_MAX_WAIT_TICKS = 12;
   private static final int CHUNK_DISCOVERY_BUDGET_PER_TICK = 24;
   private final Long2ByteOpenHashMap lightSources = new Long2ByteOpenHashMap();
   private final Long2ObjectOpenHashMap<LongOpenHashSet> sourcesByChunk = new Long2ObjectOpenHashMap();
   private final LongOpenHashSet dirtySourceChunks = new LongOpenHashSet();
   private final LongOpenHashSet scannedLightChunks = new LongOpenHashSet();
   private final Object chunkLifecycleLock = new Object();
   private final Long2ObjectOpenHashMap<TorchRtxState.PendingChunkScan> pendingChunkScans = new Long2ObjectOpenHashMap();
   private final LongArrayFIFOQueue pendingChunkScanOrder = new LongArrayFIFOQueue();
   private final LongOpenHashSet pendingChunkUnloads = new LongOpenHashSet();
   private final LongArrayFIFOQueue pendingChunkUnloadOrder = new LongArrayFIFOQueue();
   private final LongOpenHashSet pendingLoadedChunkInvalidations = new LongOpenHashSet();
   private final AtomicBoolean loadedChunkInvalidationRunning = new AtomicBoolean(false);
   private int loadedChunkInvalidationDelay = -1;
   private int loadedChunkInvalidationAge = 0;
   private int discoveryCenterChunkX = Integer.MIN_VALUE;
   private int discoveryCenterChunkZ = Integer.MIN_VALUE;
   private int discoveryRadius = -1;
   private int discoveryCursor = 0;
   private int discoveryRestartCooldown = 0;
   private int[] discoveryOffsets = new int[0];
   private static final float GPU_OWNERSHIP_ACQUIRE_WEIGHT = 0.995F;
   private static final float GPU_OWNERSHIP_RELEASE_WEIGHT = 0.9F;
   private volatile LongOpenHashSet gpuDirectOwnedSourceSnapshot = new LongOpenHashSet();
   private volatile boolean gpuDirectHybridApplied;
   private final LongOpenHashSet reflectiveSources = new LongOpenHashSet();
   private final Long2ObjectOpenHashMap<LongOpenHashSet> reflectiveByChunk = new Long2ObjectOpenHashMap();
   private final LongOpenHashSet dirtyReflectiveChunks = new LongOpenHashSet();
   private volatile Map<Long, long[]> reflectiveSnapshot = Collections.emptyMap();
   private final NeoFloodEngine engine = new NeoFloodEngine();
   private final AtomicBoolean sourceRefreshQueued = new AtomicBoolean(false);
   private final AtomicBoolean reflectiveRefreshQueued = new AtomicBoolean(false);
   private final Map<Long, BlockPos> closestTorchCache = new ConcurrentHashMap<>();
   private volatile Map<Long, long[]> sourceIndexSnapshot = Collections.emptyMap();
   private final Object pendingLightChangesLock = new Object();
   private final Long2ObjectOpenHashMap<long[]> pendingLightChangeBitsBySection = new Long2ObjectOpenHashMap();
   private int pendingLightChangeCount = 0;
   private final Object pendingRendererSectionsLock = new Object();
   private final Long2LongOpenHashMap pendingRendererSections = new Long2LongOpenHashMap();
   private final Long2LongOpenHashMap rendererSectionCooldownUntil = new Long2LongOpenHashMap();
   private static final long SECTION_REBUILD_COOLDOWN_MS = 100L;
   private static final long SECTION_UPDATE_DEBOUNCE_FAR_MS = 250L;
   private static final long SECTION_UPDATE_DEBOUNCE_MID_MS = 100L;
   private static final long SECTION_UPDATE_DEBOUNCE_NEAR_MS = 40L;
   private static final double SECTION_UPDATE_NEAR_DISTANCE_SQ = 1024.0;
   private static final double SECTION_UPDATE_MID_DISTANCE_SQ = 4096.0;
   private static final double SECTION_UPDATE_FAR_DISTANCE_SQ = 12544.0;
   private static final ExecutorService FRONTIER_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "Luxium-Frontier");
      thread.setDaemon(true);
      return thread;
   });
   private static final int MAX_FRONTIER_CHECKS_PER_BATCH = 4096;
   private final Object frontierQueueLock = new Object();
   private final LongOpenHashSet pendingFrontierChecks = new LongOpenHashSet();
   private final AtomicBoolean frontierWorkerRunning = new AtomicBoolean(false);
   private final AtomicInteger frontierEpoch = new AtomicInteger(1);
   private volatile LongOpenHashSet frontierBlockSnapshot = new LongOpenHashSet();
   private volatile Map<Long, long[]> frontierSnapshot = Collections.emptyMap();
   private volatile boolean enabled = false;
   private int appliedWorldTracingWorkers = -1;
   private int appliedFloodRevision = -1;
   private boolean lastScanLight = false;
   private boolean lastScanReflective = false;
   private boolean lastLavaTracingEnabled = false;
   private boolean lastNeoGpuVanillaConfigured = false;
   private int entityUpdateCounter = 0;
   private ClientLevel lastLevel = null;
   private int warmupTicks = 0;

   private TorchRtxState() {
      this.lightSources.defaultReturnValue((byte)0);
      this.engine.setOnSectionsChanged(this::enqueueSectionsDirty);
      this.engine.setOnImmediateSectionsChanged(this::enqueueSectionsDirtyImmediate);
      this.engine.setOnLightChanges(this::enqueueLightChanges);
      this.engine.setOnHighPriorityTraceDone(() -> NeoShadowsEngine.get().notifyHighPriorityTraceDone());
   }

   public static TorchRtxState get() {
      return INSTANCE;
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public boolean isFloodEnabled() {
      return this.enabled;
   }

   public void setEnabled(boolean enabled) {
      if (this.enabled != enabled) {
         this.enabled = enabled;
         this.clearSourceCaches();
         this.clearFrontierCache();
         if (!enabled) {
            this.appliedWorldTracingWorkers = -1;
            this.appliedFloodRevision = -1;
            this.engine.clear();
         } else {
            this.warmupTicks = 40;
            this.syncFloodRtSettings();
            this.applyConfiguredWorldTracingWorkers((Integer)Config.CLIENT.rtxWorldTracingWorkers.get());
            LongIterator it = this.lightSources.keySet().iterator();

            while (it.hasNext()) {
               long k = it.nextLong();
               if (this.shouldTraceSource(k)) {
                  this.engine.addOrUpdateSource(k, this.lightSources.get(k) & 255, false);
               }
            }
         }

         this.triggerGlobalUpdate();
      }
   }

   public void tick(@Nullable ClientLevel level) {
      if (level == null) {
         this.lastLevel = null;
      } else {
         boolean levelChanged = level != this.lastLevel;
         if (levelChanged) {
            this.lastLevel = level;
            this.warmupTicks = 40;
            if (NeoGpuVanilla.isConfiguredEnabled()) {
               this.clearLightRegistry();
               this.lastScanLight = false;
            }
         }

         boolean gpuLightingEnabled = Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled);
         Config.GpuLocalLightingMode gpuMode = (Config.GpuLocalLightingMode)Config.CLIENT.gpuLocalLightingMode.get();
         boolean standaloneNeoFlood = Config.isFeatureEnabled(Config.CLIENT.rtxEnabled) && !gpuLightingEnabled;
         boolean hybridNeoFlood = gpuLightingEnabled && gpuMode == Config.GpuLocalLightingMode.HYBRID && Config.isFeatureEnabled(Config.CLIENT.rtxEnabled);
         boolean gpuNeoFloodOnly = gpuLightingEnabled
            && gpuMode == Config.GpuLocalLightingMode.NEOFLOOD_ONLY
            && Config.isFeatureEnabled(Config.CLIENT.rtxEnabled);
         boolean gpuFastSpreadVisibility = false;
         boolean realisticNeoFlood = Config.isFeatureEnabled(Config.CLIENT.realisticShadowsEnabled)
            && (!gpuLightingEnabled || gpuMode == Config.GpuLocalLightingMode.NEOFLOOD_ONLY);
         boolean cfgEnabled = !NeoGpuVanilla.isConfiguredEnabled()
            && (
               standaloneNeoFlood
                  || hybridNeoFlood
                  || gpuNeoFloodOnly
                  || gpuFastSpreadVisibility
                  || realisticNeoFlood
                  || Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled)
            );
         boolean cfgScanLight = shouldScanLightSources();
         boolean cfgReflection = Config.isFeatureEnabled(Config.CLIENT.reflectionEnabled);
         boolean cfgLavaTracing = shouldTraceLava(level);
         int cfgWorldTracingWorkers = (Integer)Config.CLIENT.rtxWorldTracingWorkers.get();
         boolean neoGpuVanillaConfigured = NeoGpuVanilla.isConfiguredEnabled();
         if (this.lastNeoGpuVanillaConfigured != neoGpuVanillaConfigured) {
            this.lastNeoGpuVanillaConfigured = neoGpuVanillaConfigured;
            if (neoGpuVanillaConfigured) {
               this.forceNeoGpuVanillaSourceBootstrap(level);
            }
         } else if (levelChanged && neoGpuVanillaConfigured) {
            this.forceNeoGpuVanillaSourceBootstrap(level);
         }

         if (this.lastScanLight != cfgScanLight || this.lastScanReflective != cfgReflection) {
            boolean prevScanLight = this.lastScanLight;
            boolean prevScanReflective = this.lastScanReflective;
            this.lastScanLight = cfgScanLight;
            this.lastScanReflective = cfgReflection;
            this.handleScanModeChanged(level, prevScanLight, cfgScanLight, prevScanReflective, cfgReflection);
         }

         if (this.lastLavaTracingEnabled != cfgLavaTracing) {
            this.lastLavaTracingEnabled = cfgLavaTracing;
            if (cfgScanLight) {
               this.rescanLoadedChunks(level, true, cfgReflection);
            }

            this.triggerGlobalUpdate();
         }

         if (this.enabled != cfgEnabled) {
            this.setEnabled(cfgEnabled);
         }

         if (this.enabled && this.appliedWorldTracingWorkers != cfgWorldTracingWorkers) {
            this.applyConfiguredWorldTracingWorkers(cfgWorldTracingWorkers);
         }

         if (this.enabled) {
            this.syncFloodRtSettings();
         }

         Minecraft mc = Minecraft.getInstance();
         if (this.enabled) {
            this.engine.setLevel(level);
            Vec3 camPos = null;
            if (mc.player != null) {
               camPos = mc.player.position();
            } else if (mc.cameraEntity != null) {
               camPos = mc.cameraEntity.position();
            } else if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
               camPos = mc.gameRenderer.getMainCamera().getPosition();
            }

            if (camPos != null) {
               this.engine.setPlayerPos(camPos);
            }

            if (this.warmupTicks > 0) {
               this.warmupTicks--;
               this.engine.boostNearestWorldSources(16);
            }

            this.discoverLoadedChunkSources(level, camPos);
            this.drainChunkLifecycle(level);
            this.tickLoadedChunkInvalidations();
            this.engine.tick();
         } else {
            Vec3 registryCenter = mc.player != null
               ? mc.player.position()
               : (
                  mc.cameraEntity != null
                     ? mc.cameraEntity.position()
                     : (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null ? mc.gameRenderer.getMainCamera().getPosition() : null)
               );
            if (NeoGpuVanilla.isConfiguredEnabled()) {
               this.discoverLoadedChunkSources(level, registryCenter);
            }

            this.drainChunkLifecycle(level);
         }

         this.drainPendingSectionNotifications();
         this.drainPendingLightChanges();
         if (this.enabled) {
            if (this.usesEntityOcclusionShadows() && ++this.entityUpdateCounter >= 1) {
               this.entityUpdateCounter = 0;
               if (mc.cameraEntity != null) {
                  EntityShadowManager.get().updateAsync(level, mc.cameraEntity.position(), 48.0);
               }
            }
         }
      }
   }

   public boolean isImmediateStateChange(BlockPos pos) {
      Minecraft mc = Minecraft.getInstance();
      Vec3 observer = null;
      if (mc.player != null) {
         observer = mc.player.position();
      } else if (mc.cameraEntity != null) {
         observer = mc.cameraEntity.position();
      } else if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
         observer = mc.gameRenderer.getMainCamera().getPosition();
      }

      if (observer == null) {
         return false;
      } else {
         double dx = (double)pos.getX() + 0.5 - observer.x;
         double dy = (double)pos.getY() + 0.5 - observer.y;
         double dz = (double)pos.getZ() + 0.5 - observer.z;
         return dx * dx + dy * dy + dz * dz <= 4096.0;
      }
   }

   public void trackBlockTransition(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState, boolean immediate, boolean geometryChanged) {
      if (level != null && pos != null && newState != null) {
         boolean scanLight = shouldScanLightSources();
         boolean scanReflective = shouldScanReflectiveSources();
         int oldEmission = oldState == null ? 0 : oldState.getLightEmission(level, pos);
         int emission = newState.getLightEmission(level, pos);
         boolean oldRtxLightSource = oldState != null && oldEmission > 0 && shouldRegisterRtxSource(level, oldState);
         boolean rtxLightSource = emission > 0 && shouldRegisterRtxSource(level, newState);
         long key = pos.asLong();
         boolean registeredBefore = this.lightSources.containsKey(key);
         boolean vanillaEmissionChanged = oldEmission != emission;
         boolean vanillaLightPropertiesChanged = geometryChanged
            && NeoGpuVanilla.isConfiguredEnabled()
            && LightEngine.hasDifferentLightProperties(level, pos, oldState, newState);
         RainPuddleManager.get().removeIfUnsupported(level, pos, newState);
         boolean reflChanged = false;
         boolean reflNow = scanReflective && ReflectionMaterialRegistry.find(newState) != null;
         boolean reflWas = this.reflectiveSources.contains(key);
         if (reflNow && !reflWas) {
            this.reflectiveSources.add(key);
            this.indexReflectiveSource(key);
            reflChanged = true;
         } else if (!reflNow && reflWas) {
            this.reflectiveSources.remove(key);
            this.unindexReflectiveSource(key);
            reflChanged = true;
         }

         boolean posChanged = false;
         boolean emissionChanged = false;
         if (scanLight && rtxLightSource) {
            byte current = this.lightSources.get(key);
            if (current != (byte)emission) {
               this.lightSources.put(key, (byte)emission);
               if (current == this.lightSources.defaultReturnValue()) {
                  this.indexSource(key);
                  posChanged = true;
               }

               emissionChanged = true;
            }
         } else if (scanLight && this.lightSources.containsKey(key)) {
            this.lightSources.remove(key);
            this.unindexSource(key);
            posChanged = true;
            emissionChanged = true;
         }

         if (posChanged) {
            this.clearSourceCaches();
            this.refreshSourceSnapshot();
         }

         if (reflChanged) {
            this.refreshReflectiveSnapshot();
         }

         if (posChanged || emissionChanged || reflChanged) {
            ReflectionSystem.get().markWorldDirty();
         }

         if (posChanged || emissionChanged) {
            NeoShadowsEngine.markLightingDirty();
         }

         if (geometryChanged) {
            NeoShadowsEngine.markGeometryDirty();
            if (NeoGpuVanilla.isConfiguredEnabled()) {
               NeoGpuVanilla.onGeometryChanged(pos, vanillaLightPropertiesChanged);
            } else {
               GpuShadowCache.get().onGeometryChanged(pos);
            }

            if (this.enabled) {
               this.engine.onBlockChanged(pos, level, immediate);
            }
         }

         if (NeoGpuVanilla.isConfiguredEnabled() && vanillaEmissionChanged) {
            if (oldRtxLightSource) {
               GpuShadowCache.get().onSourceRemoved(key);
            }

            NeoGpuVanilla.onSourceRegistryChanged();
         }

         if (this.enabled) {
            if (rtxLightSource && this.shouldTraceSource(key)) {
               if (vanillaEmissionChanged || emissionChanged || !registeredBefore || geometryChanged) {
                  this.engine.ensureSourceScheduled(key, emission, immediate);
               }
            } else if (oldRtxLightSource || registeredBefore || emissionChanged) {
               GpuShadowCache.get().onSourceRemoved(key);
               this.engine.removeSource(key, immediate);
            }
         }
      }
   }

   public void trackBlock(BlockPos pos, BlockState state, boolean immediate) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null) {
         this.trackBlockTransition(level, pos, null, state, immediate, true);
      }
   }

   public void addChunkLightSources(ClientLevel level, LevelChunk chunk) {
      if (level != null && chunk != null && shouldScanAnySources()) {
         if (NeoGpuVanilla.isConfiguredEnabled() && shouldScanLightSources()) {
            this.registerVanillaChunkLightSourcesNow(level, chunk);
            if (shouldScanReflectiveSources()) {
               this.queueChunkScan(level, chunk, true, false, true);
            }
         } else {
            this.queueChunkScan(level, chunk, true);
         }
      }
   }

   private void registerVanillaChunkLightSourcesNow(ClientLevel level, LevelChunk chunk) {
      if (level != null && chunk != null && NeoGpuVanilla.isConfiguredEnabled()) {
         long chunkKey = ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z);
         Long2ByteOpenHashMap discovered = new Long2ByteOpenHashMap();
         discovered.defaultReturnValue((byte)0);
         chunk.findBlockLightSources((pos, state) -> {
            int emissionx = state.getLightEmission(level, pos);
            if (emissionx > 0 && shouldRegisterRtxSource(level, state)) {
               discovered.put(pos.asLong(), (byte)emissionx);
            }
         });
         LongOpenHashSet previous = (LongOpenHashSet)this.sourcesByChunk.get(chunkKey);
         LongOpenHashSet next = new LongOpenHashSet(discovered.keySet());
         LongOpenHashSet removed = previous == null ? new LongOpenHashSet() : new LongOpenHashSet(previous);
         removed.removeAll(next);
         boolean changed = !removed.isEmpty();
         if (!removed.isEmpty()) {
            LongIterator it = removed.iterator();

            while (it.hasNext()) {
               this.lightSources.remove(it.nextLong());
            }

            GpuShadowCache.get().onSourcesRemoved(removed);
         }

         ObjectIterator<Entry> it = discovered.long2ByteEntrySet().fastIterator();

         while (it.hasNext()) {
            Entry entry = (Entry)it.next();
            long key = entry.getLongKey();
            byte emission = entry.getByteValue();
            int oldEmission = this.lightSources.containsKey(key) ? this.lightSources.get(key) & 255 : -1;
            if (oldEmission != (emission & 255) || previous == null || !previous.contains(key)) {
               changed = true;
            }

            this.lightSources.put(key, emission);
         }

         if (next.isEmpty()) {
            this.sourcesByChunk.remove(chunkKey);
         } else {
            this.sourcesByChunk.put(chunkKey, next);
         }

         this.scannedLightChunks.add(chunkKey);
         if (changed) {
            this.dirtySourceChunks.add(chunkKey);
            this.clearSourceCaches();
            this.refreshSourceSnapshot();
            NeoShadowsEngine.markLightingDirty();
            ReflectionSystem.get().markWorldDirty();
            NeoGpuVanilla.onChunkSourceRegistryChanged();
         }
      }
   }

   public void removeChunkLightSources(int chunkX, int chunkZ) {
      long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
      synchronized (this.chunkLifecycleLock) {
         this.pendingChunkScans.remove(chunkKey);
         this.scannedLightChunks.remove(chunkKey);
         if (this.pendingChunkUnloads.add(chunkKey)) {
            this.pendingChunkUnloadOrder.enqueue(chunkKey);
         }
      }
   }

   public int sampleLight(BlockPos pos) {
      return this.sampleLight(pos.asLong());
   }

   public int sampleLight(long posKey) {
      if (!this.enabled) {
         return -1;
      } else {
         return !this.engine.isReady() ? -1 : this.engine.getLight(posKey);
      }
   }

   public boolean fillLightVolume(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] output) {
      if (output != null && sizeX > 0 && sizeY > 0 && sizeZ > 0) {
         int required = sizeX * sizeY * sizeZ;
         if (output.length < required) {
            throw new IllegalArgumentException("Output buffer is smaller than requested light volume");
         } else if (this.enabled && this.engine.isReady()) {
            this.engine.fillLightVolume(minX, minY, minZ, sizeX, sizeY, sizeZ, output);
            return true;
         } else {
            Arrays.fill(output, 0, required, (byte)0);
            return false;
         }
      } else {
         return false;
      }
   }

   public long getLightSnapshotVersion() {
      return this.engine.getLightSnapshotVersion();
   }

   public boolean hasAnyLight(long posKey) {
      return this.enabled && this.engine.isReady() ? this.engine.hasAnyLight(posKey) : false;
   }

   public boolean anySourceNear(long posKey, int extraRadius) {
      return this.enabled && this.engine.isReady() ? this.engine.anySourceNear(posKey, extraRadius) : false;
   }

   public long findNearestLitSample(
      long basePosKey, int axisUX, int axisUY, int axisUZ, int axisVX, int axisVY, int axisVZ, int searchRadius, double sampleX, double sampleY, double sampleZ
   ) {
      return this.enabled && this.engine.isReady()
         ? this.engine.findNearestLitSample(basePosKey, axisUX, axisUY, axisUZ, axisVX, axisVY, axisVZ, searchRadius, sampleX, sampleY, sampleZ)
         : Long.MIN_VALUE;
   }

   public int sampleCoverage(BlockPos pos) {
      return this.sampleCoverage(pos.asLong());
   }

   public int sampleCoverage(long posKey) {
      if (!this.enabled) {
         return -1;
      } else {
         return !this.engine.isReady() ? -1 : this.engine.getCoverage(posKey);
      }
   }

   public int getRegisteredLightSourceCount() {
      return this.lightSources.size();
   }

   public void warmUpNearestSources(ClientLevel level, BlockPos center) {
      if (this.enabled && level != null) {
         this.engine.traceNearestSourcesSync(16, level, Vec3.atCenterOf(center));
      }
   }

   public long getDominantSource(BlockPos pos) {
      if (!this.enabled) {
         return Long.MIN_VALUE;
      } else {
         return !this.engine.isReady() ? Long.MIN_VALUE : this.engine.getDominantSource(pos);
      }
   }

   public long getDominantSource(long posKey) {
      if (!this.enabled) {
         return Long.MIN_VALUE;
      } else {
         return !this.engine.isReady() ? Long.MIN_VALUE : this.engine.getDominantSource(posKey);
      }
   }

   public void forEachContributor(long posKey, NeoFloodEngine.ContributorVisitor visitor) {
      if (this.enabled && this.engine.isReady() && visitor != null) {
         this.engine.forEachContributor(posKey, visitor);
      }
   }

   public boolean sourceContributesTo(long sourceKey, long posKey) {
      return this.enabled && this.engine.isReady() ? this.engine.sourceContributesTo(sourceKey, posKey) : false;
   }

   public boolean isOccludedByEntity(long targetKey) {
      return !this.usesEntityOcclusionShadows() ? false : EntityShadowManager.get().isBlockInShadow(targetKey);
   }

   public int sampleFloodLight(BlockPos pos) {
      return this.sampleLight(pos);
   }

   public boolean isDirectlyLit(BlockPos pos) {
      return this.isDirectlyLit(pos.asLong());
   }

   public boolean isDirectlyLit(long posKey) {
      return this.enabled && this.engine.isReady() && this.engine.isDirectlyLit(posKey, 72);
   }

   public boolean isRtxFrontier(BlockPos pos) {
      return this.enabled && this.engine.isReady() && this.frontierBlockSnapshot.contains(pos.asLong());
   }

   public boolean isRtxFrontier(long posKey) {
      return this.enabled && this.engine.isReady() && this.frontierBlockSnapshot.contains(posKey);
   }

   public boolean isNearRtxFrontier(BlockPos pos) {
      if (this.enabled && this.engine.isReady() && !this.frontierBlockSnapshot.isEmpty()) {
         if (this.frontierBlockSnapshot.contains(pos.asLong())) {
            return true;
         } else {
            MutableBlockPos cursor = new MutableBlockPos();

            for (int dy = -2; dy <= 2; dy++) {
               for (int dz = -2; dz <= 2; dz++) {
                  for (int dx = -2; dx <= 2; dx++) {
                     if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2) {
                        cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                        if (this.frontierBlockSnapshot.contains(cursor.asLong())) {
                           return true;
                        }
                     }
                  }
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   public boolean isNearRtxFrontier(long posKey) {
      if (this.enabled && this.engine.isReady() && !this.frontierBlockSnapshot.isEmpty()) {
         if (this.frontierBlockSnapshot.contains(posKey)) {
            return true;
         } else {
            int baseX = BlockPos.getX(posKey);
            int baseY = BlockPos.getY(posKey);
            int baseZ = BlockPos.getZ(posKey);

            for (int dy = -2; dy <= 2; dy++) {
               for (int dz = -2; dz <= 2; dz++) {
                  for (int dx = -2; dx <= 2; dx++) {
                     if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2) {
                        long nearbyKey = BlockPos.asLong(baseX + dx, baseY + dy, baseZ + dz);
                        if (this.frontierBlockSnapshot.contains(nearbyKey)) {
                           return true;
                        }
                     }
                  }
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   public long[] getFrontierPositions(BlockPos origin, double maxDistanceSq) {
      Map<Long, long[]> snap = this.frontierSnapshot;
      if (this.enabled && this.engine.isReady() && !snap.isEmpty()) {
         int centerChunkX = SectionPos.blockToSectionCoord(origin.getX());
         int centerChunkZ = SectionPos.blockToSectionCoord(origin.getZ());
         int chunkRadius = Math.max(1, (int)Math.ceil(Math.sqrt(maxDistanceSq) / 16.0));
         double ox = (double)origin.getX() + 0.5;
         double oy = (double)origin.getY() + 0.5;
         double oz = (double)origin.getZ() + 0.5;
         LongArrayList matches = new LongArrayList();

         for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
               long[] bucket = snap.get(ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz));
               if (bucket != null && bucket.length != 0) {
                  for (long key : bucket) {
                     double ddx = (double)BlockPos.getX(key) + 0.5 - ox;
                     double ddy = (double)BlockPos.getY(key) + 0.5 - oy;
                     double ddz = (double)BlockPos.getZ(key) + 0.5 - oz;
                     if (ddx * ddx + ddy * ddy + ddz * ddz <= maxDistanceSq) {
                        matches.add(key);
                     }
                  }
               }
            }
         }

         return matches.toLongArray();
      } else {
         return new long[0];
      }
   }

   public boolean usesEntityOcclusionShadows() {
      return this.enabled && shouldUseEntityOcclusionShadows();
   }

   public void handleBlockRemoval(BlockPos pos) {
      long key = pos.asLong();
      if (this.lightSources.containsKey(key)) {
         this.lightSources.remove(key);
         this.unindexSource(key);
         this.clearSourceCaches();
         this.refreshSourceSnapshot();
         NeoShadowsEngine.markLightingDirty();
      }

      GpuShadowCache.get().onSourceRemoved(key);
      if (this.enabled) {
         this.engine.removeSource(key);
      }
   }

   @Nullable
   public BlockPos getClosestSource(BlockPos origin, double maxDistanceSq) {
      long cacheKey = origin.asLong();
      BlockPos cached = this.closestTorchCache.get(cacheKey);
      if (cached != null) {
         return cached;
      } else {
         Map<Long, long[]> snap = this.sourceIndexSnapshot;
         if (snap.isEmpty()) {
            return null;
         } else {
            BlockPos closest = null;
            double best = maxDistanceSq;
            int ccX = SectionPos.blockToSectionCoord(origin.getX());
            int ccZ = SectionPos.blockToSectionCoord(origin.getZ());
            int cr = Math.max(1, (int)Math.ceil(Math.sqrt(maxDistanceSq) / 16.0));
            double ox = (double)origin.getX();
            double oy = (double)origin.getY();
            double oz = (double)origin.getZ();

            label51:
            for (int dz = -cr; dz <= cr; dz++) {
               for (int dx = -cr; dx <= cr; dx++) {
                  long[] b = snap.get(ChunkPos.asLong(ccX + dx, ccZ + dz));
                  if (b != null) {
                     for (long sk : b) {
                        double sx = (double)BlockPos.getX(sk) - ox;
                        double sy = (double)BlockPos.getY(sk) - oy;
                        double sz = (double)BlockPos.getZ(sk) - oz;
                        double d = sx * sx + sy * sy + sz * sz;
                        if (d < best) {
                           best = d;
                           closest = BlockPos.of(sk);
                           if (d < 1.0) {
                              break label51;
                           }
                        }
                     }
                  }
               }
            }

            if (closest != null && this.closestTorchCache.size() < 20000) {
               this.closestTorchCache.put(cacheKey, closest);
            }

            return closest;
         }
      }
   }

   public LongOpenHashSet getTorchPositionsView() {
      LongOpenHashSet s = new LongOpenHashSet();
      s.addAll(this.lightSources.keySet());
      return s;
   }

   public Map<Long, long[]> getLightSourceSnapshotView() {
      return this.sourceIndexSnapshot;
   }

   public synchronized void setGpuDirectSources(long[] sourceKeys, float[] weights, int count) {
      LongOpenHashSet previous = this.gpuDirectOwnedSourceSnapshot;
      boolean hybridTracing = this.enabled
         && Config.isFeatureEnabled(Config.CLIENT.rtxEnabled)
         && Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled)
         && Config.CLIENT.gpuLocalLightingMode.get() == Config.GpuLocalLightingMode.HYBRID;
      LongOpenHashSet next = new LongOpenHashSet();
      if (hybridTracing && sourceKeys != null && weights != null) {
         int limit = Math.min(Math.max(count, 0), Math.min(sourceKeys.length, weights.length));

         for (int i = 0; i < limit; i++) {
            long sourceKey = sourceKeys[i];
            float weight = Mth.clamp(weights[i], 0.0F, 1.0F);
            float threshold = previous.contains(sourceKey) ? 0.9F : 0.995F;
            if (weight >= threshold) {
               next.add(sourceKey);
            }
         }
      }

      if (!previous.equals(next) || this.gpuDirectHybridApplied != hybridTracing) {
         if (this.enabled) {
            LongIterator oldIterator = previous.iterator();

            while (oldIterator.hasNext()) {
               long sourceKey = oldIterator.nextLong();
               if (!next.contains(sourceKey) && this.lightSources.containsKey(sourceKey)) {
                  this.engine.addOrUpdateSource(sourceKey, this.lightSources.get(sourceKey) & 255, false);
               }
            }

            LongIterator newIterator = next.iterator();

            while (newIterator.hasNext()) {
               long sourceKey = newIterator.nextLong();
               if (!previous.contains(sourceKey)) {
                  this.engine.removeSource(sourceKey);
               }
            }
         }

         this.gpuDirectOwnedSourceSnapshot = next;
         this.gpuDirectHybridApplied = hybridTracing;
      }
   }

   public void clearGpuDirectSources() {
      this.setGpuDirectSources(null, null, 0);
   }

   public int sampleLightForRender(BlockPos pos) {
      return this.sampleLight(pos);
   }

   public int sampleLightForRender(long posKey) {
      return this.sampleLight(posKey);
   }

   public int getSourceEmission(long sourceKey) {
      return this.lightSources.get(sourceKey) & 0xFF;
   }

   public long[] getClosestSources(Vec3 origin, double maxDistanceSq, int limit) {
      if (limit <= 0) {
         return new long[0];
      } else if (NeoGpuVanilla.isConfiguredEnabled()) {
         return this.getClosestSourcesLive(origin, maxDistanceSq, limit);
      } else {
         Map<Long, long[]> snap = this.sourceIndexSnapshot;
         if (snap.isEmpty()) {
            return new long[0];
         } else {
            long[] bestKeys = new long[limit];
            double[] bestDistances = new double[limit];
            int size = 0;
            int centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(origin.x));
            int centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(origin.z));
            int chunkRadius = Math.max(1, (int)Math.ceil(Math.sqrt(maxDistanceSq) / 16.0));

            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
               for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                  long[] bucket = snap.get(ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz));
                  if (bucket != null) {
                     for (long sourceKey : bucket) {
                        double sx = (double)BlockPos.getX(sourceKey) + 0.5 - origin.x;
                        double sy = (double)BlockPos.getY(sourceKey) + 0.5 - origin.y;
                        double sz = (double)BlockPos.getZ(sourceKey) + 0.5 - origin.z;
                        double distanceSq = sx * sx + sy * sy + sz * sz;
                        if (!(distanceSq > maxDistanceSq)) {
                           if (size < limit) {
                              bestKeys[size] = sourceKey;
                              bestDistances[size] = distanceSq;
                              size++;
                           } else {
                              int worstIndex = 0;
                              double worstDistance = bestDistances[0];
                              long worstKey = bestKeys[0];

                              for (int index = 1; index < size; index++) {
                                 if (bestDistances[index] > worstDistance
                                    || Double.compare(bestDistances[index], worstDistance) == 0 && bestKeys[index] > worstKey) {
                                    worstDistance = bestDistances[index];
                                    worstKey = bestKeys[index];
                                    worstIndex = index;
                                 }
                              }

                              if (distanceSq < worstDistance || Double.compare(distanceSq, worstDistance) == 0 && sourceKey < worstKey) {
                                 bestKeys[worstIndex] = sourceKey;
                                 bestDistances[worstIndex] = distanceSq;
                              }
                           }
                        }
                     }
                  }
               }
            }

            for (int i = 0; i < size - 1; i++) {
               for (int j = i + 1; j < size; j++) {
                  if (bestDistances[j] < bestDistances[i] || Double.compare(bestDistances[j], bestDistances[i]) == 0 && bestKeys[j] < bestKeys[i]) {
                     double tempDistance = bestDistances[i];
                     bestDistances[i] = bestDistances[j];
                     bestDistances[j] = tempDistance;
                     long tempKey = bestKeys[i];
                     bestKeys[i] = bestKeys[j];
                     bestKeys[j] = tempKey;
                  }
               }
            }

            long[] result = new long[size];
            System.arraycopy(bestKeys, 0, result, 0, size);
            return result;
         }
      }
   }

   private long[] getClosestSourcesLive(Vec3 origin, double maxDistanceSq, int limit) {
      if (this.sourcesByChunk.isEmpty()) {
         return new long[0];
      } else {
         long[] bestKeys = new long[limit];
         double[] bestDistances = new double[limit];
         int size = 0;
         int centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(origin.x));
         int centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(origin.z));
         int chunkRadius = Math.max(1, (int)Math.ceil(Math.sqrt(maxDistanceSq) / 16.0));

         for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
               LongOpenHashSet bucket = (LongOpenHashSet)this.sourcesByChunk.get(ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz));
               if (bucket != null && !bucket.isEmpty()) {
                  LongIterator it = bucket.iterator();

                  while (it.hasNext()) {
                     long sourceKey = it.nextLong();
                     double sx = (double)BlockPos.getX(sourceKey) + 0.5 - origin.x;
                     double sy = (double)BlockPos.getY(sourceKey) + 0.5 - origin.y;
                     double sz = (double)BlockPos.getZ(sourceKey) + 0.5 - origin.z;
                     double distanceSq = sx * sx + sy * sy + sz * sz;
                     if (!(distanceSq > maxDistanceSq)) {
                        if (size < limit) {
                           bestKeys[size] = sourceKey;
                           bestDistances[size] = distanceSq;
                           size++;
                        } else {
                           int worstIndex = 0;
                           double worstDistance = bestDistances[0];
                           long worstKey = bestKeys[0];

                           for (int index = 1; index < size; index++) {
                              if (bestDistances[index] > worstDistance
                                 || Double.compare(bestDistances[index], worstDistance) == 0 && bestKeys[index] > worstKey) {
                                 worstDistance = bestDistances[index];
                                 worstKey = bestKeys[index];
                                 worstIndex = index;
                              }
                           }

                           if (distanceSq < worstDistance || Double.compare(distanceSq, worstDistance) == 0 && sourceKey < worstKey) {
                              bestKeys[worstIndex] = sourceKey;
                              bestDistances[worstIndex] = distanceSq;
                           }
                        }
                     }
                  }
               }
            }
         }

         for (int i = 0; i < size - 1; i++) {
            for (int j = i + 1; j < size; j++) {
               if (bestDistances[j] < bestDistances[i] || Double.compare(bestDistances[j], bestDistances[i]) == 0 && bestKeys[j] < bestKeys[i]) {
                  double td = bestDistances[i];
                  bestDistances[i] = bestDistances[j];
                  bestDistances[j] = td;
                  long tk = bestKeys[i];
                  bestKeys[i] = bestKeys[j];
                  bestKeys[j] = tk;
               }
            }
         }

         return Arrays.copyOf(bestKeys, size);
      }
   }

   public NeoFloodEngine getEngine() {
      return this.engine;
   }

   public Map<Long, long[]> getReflectiveSnapshotView() {
      return this.reflectiveSnapshot;
   }

   private void applySectionsDirty(LongOpenHashSet sections) {
      if (!sections.isEmpty()) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.levelRenderer != null) {
            LongIterator si = sections.iterator();

            while (si.hasNext()) {
               long sec = si.nextLong();
               int bx = SectionPos.sectionToBlockCoord(SectionPos.x(sec));
               int by = SectionPos.sectionToBlockCoord(SectionPos.y(sec));
               int bz = SectionPos.sectionToBlockCoord(SectionPos.z(sec));
               mc.levelRenderer.setBlocksDirty(bx, by, bz, bx + 15, by + 15, bz + 15);
            }
         }
      }
   }

   private void enqueueSectionsDirty(LongOpenHashSet sections) {
      if (sections != null && !sections.isEmpty()) {
         long now = System.currentTimeMillis();
         Minecraft mc = Minecraft.getInstance();
         Vec3 viewer = mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null
            ? mc.gameRenderer.getMainCamera().getPosition()
            : (mc.player != null ? mc.player.position() : Vec3.ZERO);
         synchronized (this.pendingRendererSectionsLock) {
            LongIterator it = sections.iterator();

            while (it.hasNext()) {
               long section = it.nextLong();
               long cooldown = this.rendererSectionCooldownUntil.getOrDefault(section, 0L);
               if (cooldown <= now) {
                  this.rendererSectionCooldownUntil.remove(section);
                  cooldown = 0L;
               }

               long eligibleAt = Math.max(now + rendererDelayForSection(section, viewer), cooldown);
               long previous = this.pendingRendererSections.getOrDefault(section, Long.MAX_VALUE);
               if (eligibleAt < previous) {
                  this.pendingRendererSections.put(section, eligibleAt);
               }
            }
         }
      }
   }

   private void enqueueSectionsDirtyImmediate(LongOpenHashSet sections) {
      if (sections != null && !sections.isEmpty()) {
         Minecraft mc = Minecraft.getInstance();
         LongOpenHashSet atomicSections = new LongOpenHashSet(sections);
         mc.execute(() -> {
            long now = System.currentTimeMillis();
            synchronized (this.pendingRendererSectionsLock) {
               LongIterator it = atomicSections.iterator();

               while (it.hasNext()) {
                  long section = it.nextLong();
                  this.pendingRendererSections.remove(section);
                  this.rendererSectionCooldownUntil.put(section, now + 100L);
               }
            }

            this.applySectionsDirty(atomicSections);
            if (mc.gameRenderer != null && mc.gameRenderer.lightTexture() != null) {
               ((LightTextureAccessor)mc.gameRenderer.lightTexture()).luxium$setUpdateLightTexture(true);
            }
         });
      }
   }

   private static long rendererDelayForSection(long section, Vec3 viewer) {
      double distanceSq = sectionDistanceSq(section, viewer);
      if (distanceSq <= 1024.0) {
         return 0L;
      } else if (distanceSq <= 4096.0) {
         return 40L;
      } else {
         return distanceSq <= 12544.0 ? 100L : 250L;
      }
   }

   private static double sectionDistanceSq(long section, Vec3 viewer) {
      double cx = (double)SectionPos.sectionToBlockCoord(SectionPos.x(section)) + 8.0 - viewer.x;
      double cy = (double)SectionPos.sectionToBlockCoord(SectionPos.y(section)) + 8.0 - viewer.y;
      double cz = (double)SectionPos.sectionToBlockCoord(SectionPos.z(section)) + 8.0 - viewer.z;
      return cx * cx + cy * cy + cz * cz;
   }

   private void enqueueLightChanges(NeoFloodEngine.LightChangeBatch changes) {
      if (changes != null && changes.sectionCount() != 0) {
         synchronized (this.pendingLightChangesLock) {
            changes.forEachSection((sectionKey, incomingWords) -> {
               long[] target = (long[])this.pendingLightChangeBitsBySection.get(sectionKey);
               if (target == null) {
                  target = new long[64];
                  this.pendingLightChangeBitsBySection.put(sectionKey, target);
               }

               for (int i = 0; i < 64; i++) {
                  long added = incomingWords[i] & ~target[i];
                  if (added != 0L) {
                     target[i] |= added;
                     this.pendingLightChangeCount = this.pendingLightChangeCount + Long.bitCount(added);
                  }
               }
            });
         }
      }
   }

   private void drainPendingSectionNotifications() {
      long now = System.currentTimeMillis();
      LongOpenHashSet batch = new LongOpenHashSet(16);
      synchronized (this.pendingRendererSectionsLock) {
         if (this.pendingRendererSections.isEmpty()) {
            return;
         }

         ObjectIterator<it.unimi.dsi.fastutil.longs.Long2LongMap.Entry> it = this.pendingRendererSections.long2LongEntrySet().fastIterator();

         while (it.hasNext() && batch.size() < 16) {
            it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry = (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry)it.next();
            if (now >= entry.getLongValue()) {
               batch.add(entry.getLongKey());
               this.rendererSectionCooldownUntil.put(entry.getLongKey(), now + 100L);
               it.remove();
            }
         }
      }

      if (!batch.isEmpty()) {
         this.applySectionsDirty(batch);
      }
   }

   private void drainPendingLightChanges() {
      long started = System.nanoTime();
      long extractionDeadline = started + Math.min(250000L, 250000L);
      long deadline = started + 1000000L;
      LongArrayList changed = new LongArrayList(192);
      synchronized (this.pendingLightChangesLock) {
         if (this.pendingLightChangeCount <= 0 || this.pendingLightChangeBitsBySection.isEmpty()) {
            return;
         }

         int sectionsProcessed = 0;

         for (ObjectIterator<it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<long[]>> sectionIt = this.pendingLightChangeBitsBySection
               .long2ObjectEntrySet()
               .fastIterator();
            sectionIt.hasNext() && sectionsProcessed < 48 && changed.size() < 192 && System.nanoTime() < extractionDeadline;
            sectionsProcessed++
         ) {
            it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<long[]> entry = (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<long[]>)sectionIt.next();
            long sectionKey = entry.getLongKey();
            long[] words = (long[])entry.getValue();
            int baseX = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey));
            int baseY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
            int baseZ = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey));

            for (int wordIndex = 0; wordIndex < 64 && changed.size() < 192 && System.nanoTime() < extractionDeadline; wordIndex++) {
               for (long bits = words[wordIndex]; bits != 0L && changed.size() < 192 && System.nanoTime() < extractionDeadline; this.pendingLightChangeCount--) {
                  int bit = Long.numberOfTrailingZeros(bits);
                  int localIndex = (wordIndex << 6) + bit;
                  changed.add(BlockPos.asLong(baseX + (localIndex & 15), baseY + (localIndex >>> 8 & 15), baseZ + (localIndex >>> 4 & 15)));
                  words[wordIndex] &= ~(1L << bit);
                  bits &= bits - 1L;
               }
            }

            boolean empty = true;
            long[] var27 = words;
            int var20 = words.length;
            int var28 = 0;

            while (true) {
               if (var28 < var20) {
                  long word = var27[var28];
                  if (word == 0L) {
                     var28++;
                     continue;
                  }

                  empty = false;
               }

               if (empty) {
                  sectionIt.remove();
               }
               break;
            }
         }

         if (this.pendingLightChangeCount < 0) {
            this.pendingLightChangeCount = 0;
         }
      }

      if (!changed.isEmpty()) {
         this.applyLightChangesBudgeted(changed, deadline);
      }
   }

   private void applyLightChangesBudgeted(LongArrayList changedBlocks, long deadline) {
      if (this.enabled && this.engine.isReady()) {
         LongOpenHashSet affected = new LongOpenHashSet(changedBlocks.size() * 7);

         int processed;
         for (processed = 0; processed < changedBlocks.size() && (processed <= 0 || System.nanoTime() < deadline); processed++) {
            long key = changedBlocks.getLong(processed);
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            affected.add(key);

            for (Direction dir : FACES) {
               affected.add(BlockPos.asLong(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ()));
            }
         }

         if (processed < changedBlocks.size()) {
            synchronized (this.pendingLightChangesLock) {
               for (int i = processed; i < changedBlocks.size(); i++) {
                  this.requeueLightChangeLocked(changedBlocks.getLong(i));
               }
            }
         }

         if (!affected.isEmpty()) {
            this.enqueueFrontierChecks(affected);
            NeoShadowsEngine.get().onLightingChanged(affected, false);
            NeoCpuShadowsEngine.onLightDataChanged();
         }
      } else {
         this.clearFrontierCache();
         NeoShadowsEngine.get().onLightingChanged();
      }
   }

   private void requeueLightChangeLocked(long posKey) {
      long sectionKey = SectionPos.asLong(BlockPos.getX(posKey) >> 4, BlockPos.getY(posKey) >> 4, BlockPos.getZ(posKey) >> 4);
      long[] words = (long[])this.pendingLightChangeBitsBySection.get(sectionKey);
      if (words == null) {
         words = new long[64];
         this.pendingLightChangeBitsBySection.put(sectionKey, words);
      }

      int localIndex = (BlockPos.getY(posKey) & 15) << 8 | (BlockPos.getZ(posKey) & 15) << 4 | BlockPos.getX(posKey) & 15;
      int wordIndex = localIndex >>> 6;
      long mask = 1L << (localIndex & 63);
      if ((words[wordIndex] & mask) == 0L) {
         words[wordIndex] |= mask;
         this.pendingLightChangeCount++;
      }
   }

   public void scanChunkLightSources(ClientLevel level, LevelChunk chunk) {
      this.queueChunkScan(level, chunk, true);
   }

   private void queueChunkScan(ClientLevel level, LevelChunk chunk, boolean force) {
      this.queueChunkScan(level, chunk, force, shouldScanLightSources(), shouldScanReflectiveSources());
   }

   private void queueChunkScan(ClientLevel level, LevelChunk chunk, boolean force, boolean scanLight, boolean scanReflective) {
      if (level != null && chunk != null && (scanLight || scanReflective)) {
         long chunkKey = ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z);
         synchronized (this.chunkLifecycleLock) {
            if (force || (!scanLight || !this.scannedLightChunks.contains(chunkKey)) && !this.pendingChunkScans.containsKey(chunkKey)) {
               boolean alreadyQueued = this.pendingChunkScans.containsKey(chunkKey);
               this.pendingChunkScans.put(chunkKey, new TorchRtxState.PendingChunkScan(level, chunk, scanLight, scanReflective));
               if (scanLight) {
                  this.scannedLightChunks.remove(chunkKey);
               }

               if (!alreadyQueued) {
                  this.pendingChunkScanOrder.enqueue(chunkKey);
               }
            }
         }
      }
   }

   private void drainChunkLifecycle(ClientLevel level) {
      long deadline = System.nanoTime() + 1250000L;
      TorchRtxState.ChunkLifecycleBatch batch = new TorchRtxState.ChunkLifecycleBatch();
      int unloads = 0;

      while (unloads < 8 && System.nanoTime() < deadline) {
         long chunkKey;
         synchronized (this.chunkLifecycleLock) {
            if (this.pendingChunkUnloadOrder.isEmpty()) {
               break;
            }

            chunkKey = this.pendingChunkUnloadOrder.dequeueLong();
            if (!this.pendingChunkUnloads.remove(chunkKey)) {
               continue;
            }
         }

         this.applyQueuedChunkUnload(chunkKey, batch);
         unloads++;
      }

      int populatedSections = 0;
      int queueVisits = 0;

      while (populatedSections < 2 && System.nanoTime() < deadline) {
         TorchRtxState.PendingChunkScan task;
         long chunkKey;
         synchronized (this.chunkLifecycleLock) {
            if (this.pendingChunkScanOrder.isEmpty()) {
               break;
            }

            chunkKey = this.pendingChunkScanOrder.dequeueLong();
            task = (TorchRtxState.PendingChunkScan)this.pendingChunkScans.get(chunkKey);
         }

         if (task != null) {
            if (task.level == level && level.getChunkSource().getChunkNow(task.chunkX, task.chunkZ) == task.chunk) {
               int scannedPopulated = this.scanNextChunkSection(task);
               populatedSections += scannedPopulated;
               queueVisits++;
               if (task.sectionIndex >= task.chunk.getSections().length) {
                  synchronized (this.chunkLifecycleLock) {
                     if (this.pendingChunkScans.get(chunkKey) == task) {
                        this.pendingChunkScans.remove(chunkKey);
                     }
                  }

                  this.finishChunkScan(task, batch);
               } else {
                  synchronized (this.chunkLifecycleLock) {
                     if (this.pendingChunkScans.get(chunkKey) == task) {
                        this.pendingChunkScanOrder.enqueue(chunkKey);
                     }
                  }
               }

               if (queueVisits >= 64) {
                  break;
               }
            } else {
               synchronized (this.chunkLifecycleLock) {
                  if (this.pendingChunkScans.get(chunkKey) == task) {
                     this.pendingChunkScans.remove(chunkKey);
                  }
               }
            }
         }
      }

      this.finishChunkLifecycleBatch(batch);
   }

   private int scanNextChunkSection(TorchRtxState.PendingChunkScan task) {
      LevelChunkSection[] sections = task.chunk.getSections();
      if (task.sectionIndex >= sections.length) {
         return 0;
      } else {
         int sectionIndex = task.sectionIndex++;
         LevelChunkSection section = sections[sectionIndex];
         if (section != null && !section.hasOnlyAir()) {
            boolean maybeLight = task.scanLight && section.maybeHas(statex -> statex.getLightEmission(task.level, BlockPos.ZERO) != 0);
            boolean maybeReflective = task.scanReflective
               && shouldScanReflectiveSources()
               && section.maybeHas(statex -> ReflectionMaterialRegistry.find(statex) != null);
            if (!maybeLight && !maybeReflective) {
               return 0;
            } else {
               int baseX = task.chunk.getPos().getMinBlockX();
               int baseY = SectionPos.sectionToBlockCoord(task.chunk.getSectionYFromSectionIndex(sectionIndex));
               int baseZ = task.chunk.getPos().getMinBlockZ();
               MutableBlockPos pos = task.mutablePos;

               for (int y = 0; y < 16; y++) {
                  for (int z = 0; z < 16; z++) {
                     for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        pos.set(baseX + x, baseY + y, baseZ + z);
                        if (maybeLight) {
                           int emission = state.getLightEmission(task.level, pos);
                           if (emission > 0 && shouldRegisterRtxSource(task.level, state)) {
                              task.sources.put(pos.asLong(), (byte)emission);
                           }
                        }

                        if (maybeReflective && ReflectionMaterialRegistry.find(state) != null) {
                           task.reflections.add(pos.asLong());
                        }
                     }
                  }
               }

               return 1;
            }
         } else {
            return 0;
         }
      }
   }

   private void applyQueuedChunkUnload(long chunkKey, TorchRtxState.ChunkLifecycleBatch batch) {
      this.scannedLightChunks.remove(chunkKey);
      LongOpenHashSet sourceBucket = (LongOpenHashSet)this.sourcesByChunk.remove(chunkKey);
      if (sourceBucket != null && !sourceBucket.isEmpty()) {
         this.dirtySourceChunks.add(chunkKey);
         LongIterator it = sourceBucket.iterator();

         while (it.hasNext()) {
            long sourceKey = it.nextLong();
            this.lightSources.remove(sourceKey);
            batch.removedSources.add(sourceKey);
         }

         batch.sourceRegistryChanged = true;
      }

      LongOpenHashSet reflectionBucket = (LongOpenHashSet)this.reflectiveByChunk.remove(chunkKey);
      if (reflectionBucket != null && !reflectionBucket.isEmpty()) {
         this.dirtyReflectiveChunks.add(chunkKey);
         LongIterator it = reflectionBucket.iterator();

         while (it.hasNext()) {
            this.reflectiveSources.remove(it.nextLong());
         }

         batch.reflectionRegistryChanged = true;
      }
   }

   private void finishChunkScan(TorchRtxState.PendingChunkScan task, TorchRtxState.ChunkLifecycleBatch batch) {
      long chunkKey = task.chunkKey;
      if (task.scanLight) {
         LongOpenHashSet oldSources = (LongOpenHashSet)this.sourcesByChunk.get(chunkKey);
         LongOpenHashSet newSources = new LongOpenHashSet();
         newSources.addAll(task.sources.keySet());
         boolean sourceChunkChanged = false;
         if (oldSources != null && !oldSources.isEmpty()) {
            LongOpenHashSet removed = new LongOpenHashSet(oldSources);
            removed.removeAll(newSources);
            if (!removed.isEmpty()) {
               LongIterator it = removed.iterator();

               while (it.hasNext()) {
                  long key = it.nextLong();
                  this.lightSources.remove(key);
                  batch.removedSources.add(key);
               }

               sourceChunkChanged = true;
            }
         }

         ObjectIterator<Entry> sourceIt = task.sources.long2ByteEntrySet().fastIterator();

         while (sourceIt.hasNext()) {
            Entry entry = (Entry)sourceIt.next();
            long key = entry.getLongKey();
            byte emission = entry.getByteValue();
            int previous = this.lightSources.containsKey(key) ? this.lightSources.get(key) & 255 : -1;
            this.lightSources.put(key, emission);
            if (oldSources == null || !oldSources.contains(key) || previous != (emission & 255)) {
               if (this.shouldTraceSource(key)) {
                  batch.addedOrChangedSources.put(key, emission);
               }

               ExposedFaceService.get()
                  .requestSectionCapture(SectionPos.asLong(BlockPos.getX(key) >> 4, BlockPos.getY(key) >> 4, BlockPos.getZ(key) >> 4), 0.0);
               sourceChunkChanged = true;
            }
         }

         if (newSources.isEmpty()) {
            this.sourcesByChunk.remove(chunkKey);
         } else {
            this.sourcesByChunk.put(chunkKey, newSources);
         }

         if (sourceChunkChanged) {
            this.dirtySourceChunks.add(chunkKey);
            batch.sourceRegistryChanged = true;
         }

         this.scannedLightChunks.add(chunkKey);
      }

      if (task.scanReflective) {
         LongOpenHashSet oldReflections = (LongOpenHashSet)this.reflectiveByChunk.get(chunkKey);
         boolean reflectionChunkChanged = oldReflections == null ? !task.reflections.isEmpty() : !oldReflections.equals(task.reflections);
         if (reflectionChunkChanged) {
            if (oldReflections != null) {
               LongIterator it = oldReflections.iterator();

               while (it.hasNext()) {
                  this.reflectiveSources.remove(it.nextLong());
               }
            }

            if (task.reflections.isEmpty()) {
               this.reflectiveByChunk.remove(chunkKey);
            } else {
               this.reflectiveByChunk.put(chunkKey, new LongOpenHashSet(task.reflections));
               LongIterator it = task.reflections.iterator();

               while (it.hasNext()) {
                  this.reflectiveSources.add(it.nextLong());
               }
            }

            this.dirtyReflectiveChunks.add(chunkKey);
            batch.reflectionRegistryChanged = true;
         }
      }

      if (this.enabled) {
         batch.loadedChunks.add(chunkKey);
      }
   }

   private void finishChunkLifecycleBatch(TorchRtxState.ChunkLifecycleBatch batch) {
      if (!batch.isEmpty()) {
         LongOpenHashSet replaced = new LongOpenHashSet(batch.removedSources);
         replaced.retainAll(batch.addedOrChangedSources.keySet());
         if (!replaced.isEmpty()) {
            batch.removedSources.removeAll(replaced);
         }

         if (!batch.removedSources.isEmpty()) {
            GpuShadowCache.get().onSourcesRemoved(batch.removedSources);
            if (this.enabled) {
               this.engine.removeSourcesAsync(batch.removedSources);
            }
         }

         if (this.enabled && !batch.addedOrChangedSources.isEmpty()) {
            this.engine.addOrUpdateWorldSources(batch.addedOrChangedSources);
         }

         if (this.enabled && !replaced.isEmpty()) {
            this.engine.markDirty(replaced);
         }

         if (batch.sourceRegistryChanged) {
            this.clearSourceCaches();
            this.refreshSourceSnapshot();
            NeoShadowsEngine.markLightingDirty();
            if (NeoGpuVanilla.isConfiguredEnabled()) {
               NeoGpuVanilla.onChunkSourceRegistryChanged();
            }
         }

         if (batch.reflectionRegistryChanged) {
            this.requestReflectiveSnapshotRefresh();
            ReflectionSystem.get().markWorldDirty();
         }

         if (!batch.loadedChunks.isEmpty()) {
            this.pendingLoadedChunkInvalidations.addAll(batch.loadedChunks);
            if (this.loadedChunkInvalidationDelay < 0) {
               this.loadedChunkInvalidationAge = 0;
            }

            this.loadedChunkInvalidationDelay = 4;
         }
      }
   }

   private void tickLoadedChunkInvalidations() {
      if (!this.pendingLoadedChunkInvalidations.isEmpty()) {
         this.loadedChunkInvalidationAge++;
         if (this.loadedChunkInvalidationDelay > 0) {
            this.loadedChunkInvalidationDelay--;
         }

         if (this.loadedChunkInvalidationDelay <= 0 || this.loadedChunkInvalidationAge >= 12) {
            if (this.loadedChunkInvalidationRunning.compareAndSet(false, true)) {
               LongOpenHashSet loadedChunks = new LongOpenHashSet(this.pendingLoadedChunkInvalidations);
               this.pendingLoadedChunkInvalidations.clear();
               this.loadedChunkInvalidationDelay = -1;
               this.loadedChunkInvalidationAge = 0;
               Map<Long, long[]> sourceSnapshot = this.sourceIndexSnapshot;
               CompletableFuture.runAsync(() -> {
                  try {
                     LongOpenHashSet nearby = collectSourcesNearLoadedChunks(loadedChunks, sourceSnapshot);
                     if (!nearby.isEmpty()) {
                        this.engine.markDirty(nearby);
                     }
                  } finally {
                     this.loadedChunkInvalidationRunning.set(false);
                  }
               });
            }
         }
      }
   }

   private static LongOpenHashSet collectSourcesNearLoadedChunks(LongOpenHashSet loadedChunks, Map<Long, long[]> sourceSnapshot) {
      LongOpenHashSet nearby = new LongOpenHashSet();
      if (!loadedChunks.isEmpty() && !sourceSnapshot.isEmpty()) {
         double maxDist = LightRtMath.getMaxDistance(15);
         double maxSq = maxDist * maxDist;
         int chunkRadius = Math.max(1, (int)Math.ceil((maxDist + 16.0) / 16.0));
         LongIterator chunkIt = loadedChunks.iterator();

         while (chunkIt.hasNext()) {
            long loadedKey = chunkIt.nextLong();
            int chunkX = ChunkPos.getX(loadedKey);
            int chunkZ = ChunkPos.getZ(loadedKey);
            double minX = (double)(chunkX << 4);
            double maxX = minX + 16.0;
            double minZ = (double)(chunkZ << 4);
            double maxZ = minZ + 16.0;

            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
               for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                  long[] bucket = sourceSnapshot.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                  if (bucket != null) {
                     for (long sourceKey : bucket) {
                        long sourceChunkKey = ChunkPos.asLong(BlockPos.getX(sourceKey) >> 4, BlockPos.getZ(sourceKey) >> 4);
                        if (!loadedChunks.contains(sourceChunkKey)) {
                           double sourceX = (double)BlockPos.getX(sourceKey) + 0.5;
                           double sourceZ = (double)BlockPos.getZ(sourceKey) + 0.5;
                           double distX = sourceX < minX ? minX - sourceX : (sourceX > maxX ? sourceX - maxX : 0.0);
                           double distZ = sourceZ < minZ ? minZ - sourceZ : (sourceZ > maxZ ? sourceZ - maxZ : 0.0);
                           if (distX * distX + distZ * distZ <= maxSq) {
                              nearby.add(sourceKey);
                           }
                        }
                     }
                  }
               }
            }
         }

         return nearby;
      } else {
         return nearby;
      }
   }

   private void discoverLoadedChunkSources(ClientLevel level, Vec3 center) {
      if (shouldScanLightSources() && center != null) {
         Minecraft mc = Minecraft.getInstance();
         int centerX = SectionPos.blockToSectionCoord(Mth.floor(center.x));
         int centerZ = SectionPos.blockToSectionCoord(Mth.floor(center.z));
         int radius = Math.max(1, mc.options.getEffectiveRenderDistance());
         if (radius != this.discoveryRadius) {
            this.discoveryRadius = radius;
            this.discoveryOffsets = buildNearestChunkOffsets(radius);
            this.discoveryCursor = 0;
            this.discoveryRestartCooldown = 0;
         }

         if (centerX != this.discoveryCenterChunkX || centerZ != this.discoveryCenterChunkZ) {
            this.discoveryCenterChunkX = centerX;
            this.discoveryCenterChunkZ = centerZ;
            this.discoveryCursor = 0;
            this.discoveryRestartCooldown = 0;
         }

         if (this.discoveryOffsets.length != 0) {
            if (this.discoveryCursor >= this.discoveryOffsets.length) {
               if (this.discoveryRestartCooldown++ < 20) {
                  return;
               }

               this.discoveryRestartCooldown = 0;
               this.discoveryCursor = 0;
            }

            int attempts = 0;

            while (attempts < 24 && this.discoveryCursor < this.discoveryOffsets.length) {
               int packed = this.discoveryOffsets[this.discoveryCursor++];
               attempts++;
               int dx = (short)(packed >>> 16);
               int dz = (short)packed;
               int chunkX = centerX + dx;
               int chunkZ = centerZ + dz;
               long key = ChunkPos.asLong(chunkX, chunkZ);
               if (!this.scannedLightChunks.contains(key)) {
                  LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                  if (chunk != null) {
                     if (NeoGpuVanilla.isConfiguredEnabled()) {
                        this.registerVanillaChunkLightSourcesNow(level, chunk);
                        if (shouldScanReflectiveSources()) {
                           this.queueChunkScan(level, chunk, false, false, true);
                        }
                     } else {
                        this.queueChunkScan(level, chunk, false);
                     }
                  }
               }
            }
         }
      }
   }

   private static int[] buildNearestChunkOffsets(int radius) {
      List<int[]> offsets = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));

      for (int dz = -radius; dz <= radius; dz++) {
         for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dz * dz <= radius * radius) {
               offsets.add(new int[]{dx, dz});
            }
         }
      }

      offsets.sort(Comparator.comparingInt(vx -> vx[0] * vx[0] + vx[1] * vx[1]));
      int[] packed = new int[offsets.size()];

      for (int i = 0; i < offsets.size(); i++) {
         int[] v = offsets.get(i);
         packed[i] = (v[0] & 65535) << 16 | v[1] & 65535;
      }

      return packed;
   }

   private boolean shouldTraceSource(long sourceKey) {
      return !Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled)
         || Config.CLIENT.gpuLocalLightingMode.get() != Config.GpuLocalLightingMode.HYBRID
         || !this.gpuDirectOwnedSourceSnapshot.contains(sourceKey);
   }

   private static boolean shouldScanLightSources() {
      return NeoGpuVanilla.isConfiguredEnabled()
         || Config.isFeatureEnabled(Config.CLIENT.rtxEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.realisticShadowsEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled);
   }

   private static boolean shouldTraceLava(ClientLevel level) {
      return !NeoGpuVanilla.isConfiguredEnabled() && Config.isFeatureEnabled(Config.CLIENT.rtxLavaTracingEnabled) && level.dimension() != Level.NETHER;
   }

   private static boolean shouldRegisterRtxSource(ClientLevel level, BlockState state) {
      return NeoGpuVanilla.isConfiguredEnabled() && state.getFluidState().is(FluidTags.LAVA)
         ? false
         : !state.getFluidState().is(FluidTags.LAVA) || shouldTraceLava(level);
   }

   private static boolean shouldScanReflectiveSources() {
      return Config.isFeatureEnabled(Config.CLIENT.reflectionEnabled);
   }

   private static boolean shouldScanAnySources() {
      return shouldScanLightSources() || shouldScanReflectiveSources();
   }

   private static boolean shouldUseEntityOcclusionShadows() {
      return Config.isFeatureEnabled(Config.CLIENT.rtxEnabled)
         && Config.isFeatureEnabled(Config.CLIENT.entityShadowsEnabled)
         && !Config.isFeatureEnabled(Config.CLIENT.realisticShadowsEnabled)
         && !Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled)
         && !Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled);
   }

   private void handleScanModeChanged(@Nullable ClientLevel level, boolean wasScanLight, boolean scanLight, boolean wasScanReflective, boolean scanReflective) {
      if (!scanLight) {
         this.clearLightRegistry();
      }

      if (!scanReflective) {
         this.clearReflectiveRegistry();
      }

      if (scanLight && !wasScanLight || scanReflective && !wasScanReflective) {
         this.rescanLoadedChunks(level, scanLight, scanReflective);
      }

      ReflectionSystem.get().markWorldDirty();
      NeoShadowsEngine.markWorldDirty();
   }

   private void syncFloodRtSettings() {
      boolean changed = FloodRtSettings.apply(
         (Integer)Config.CLIENT.floodRadiusCap.get(),
         (Double)Config.CLIENT.floodPenumbraSoftness.get(),
         (Double)Config.CLIENT.floodCornerSeal.get(),
         (Double)Config.CLIENT.floodDirectionalBias.get(),
         (Integer)Config.CLIENT.floodUpdateBudget.get()
      );
      int revision = FloodRtSettings.revision();
      if (changed || this.appliedFloodRevision != revision) {
         this.appliedFloodRevision = revision;
         this.clearSourceCaches();
         this.clearFrontierCache();
         if (!this.lightSources.isEmpty()) {
            this.engine.markDirty(new LongOpenHashSet(this.lightSources.keySet()));
         }
      }
   }

   private void applyConfiguredWorldTracingWorkers(int workerCount) {
      if (this.appliedWorldTracingWorkers != workerCount) {
         this.engine.setWorldTracingWorkers(workerCount);
         this.appliedWorldTracingWorkers = workerCount;
      }
   }

   private void forceNeoGpuVanillaSourceBootstrap(@Nullable ClientLevel level) {
      if (level != null && NeoGpuVanilla.isConfiguredEnabled()) {
         this.scannedLightChunks.clear();
         this.discoveryRadius = -1;
         this.discoveryCursor = 0;
         this.discoveryRestartCooldown = 0;
         this.discoveryOffsets = new int[0];
         GpuShadowCache.get().markAllDirty();
         NeoGpuVanilla.onChunkSourceRegistryChanged();
         this.rescanLoadedChunks(level, true, shouldScanReflectiveSources());
      }
   }

   private void rescanLoadedChunks(@Nullable ClientLevel level, boolean scanLight, boolean scanReflective) {
      if (level != null && (scanLight || scanReflective)) {
         Minecraft mc = Minecraft.getInstance();
         Vec3 scanCenter = mc.player != null ? mc.player.position() : mc.gameRenderer.getMainCamera().getPosition();
         int centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(scanCenter.x));
         int centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(scanCenter.z));
         double blockRadius = 0.0;
         if (scanLight) {
            blockRadius = Math.max(blockRadius, LightRtMath.getMaxDistance(15) + 16.0);
            if (NeoGpuVanilla.isConfiguredEnabled()) {
               blockRadius = Math.max(blockRadius, (double)((Integer)Config.CLIENT.neoGpuVanillaDistance.get()).intValue() + 16.0);
            }

            if (shouldTraceLava(level)) {
               blockRadius = Math.max(blockRadius, (double)mc.options.getEffectiveRenderDistance() * 16.0);
            }
         }

         if (scanReflective) {
            blockRadius = Math.max(blockRadius, (double)mc.options.getEffectiveRenderDistance() * 16.0);
         }

         int chunkRadius = Math.max(1, (int)Math.ceil(blockRadius / 16.0));
         boolean rescannedAny = false;
         int[] offsets = buildNearestChunkOffsets(chunkRadius);

         for (int packedOffset : offsets) {
            int dx = (short)(packedOffset >>> 16);
            int dz = (short)packedOffset;
            LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
            if (chunk != null) {
               if (scanLight && NeoGpuVanilla.isConfiguredEnabled()) {
                  this.registerVanillaChunkLightSourcesNow(level, chunk);
                  if (scanReflective) {
                     this.queueChunkScan(level, chunk, true, false, true);
                  }
               } else {
                  this.queueChunkScan(level, chunk, true, scanLight, scanReflective);
               }

               rescannedAny = true;
            }
         }

         if (scanReflective) {
            ReflectionSystem.get().forceImmediateUpdate();
         }

         if (scanLight && rescannedAny && !NeoGpuVanilla.isConfiguredEnabled()) {
            this.engine.boostNearestWorldSources(2);
         }

         if (rescannedAny) {
            this.triggerGlobalUpdate();
         }
      }
   }

   private void clearLightRegistry() {
      this.lightSources.clear();
      this.sourcesByChunk.clear();
      this.scannedLightChunks.clear();
      this.dirtySourceChunks.clear();
      this.discoveryCursor = 0;
      this.discoveryRestartCooldown = 0;
      this.discoveryOffsets = new int[0];
      synchronized (this.chunkLifecycleLock) {
         this.pendingChunkScans.clear();
         this.pendingChunkScanOrder.clear();
         this.pendingChunkUnloads.clear();
         this.pendingChunkUnloadOrder.clear();
      }

      this.pendingLoadedChunkInvalidations.clear();
      this.loadedChunkInvalidationDelay = -1;
      this.loadedChunkInvalidationAge = 0;
      this.sourceIndexSnapshot = Collections.emptyMap();
      synchronized (this.pendingLightChangesLock) {
         this.pendingLightChangeBitsBySection.clear();
         this.pendingLightChangeCount = 0;
      }

      synchronized (this.pendingRendererSectionsLock) {
         this.pendingRendererSections.clear();
         this.rendererSectionCooldownUntil.clear();
      }

      this.clearSourceCaches();
      this.clearFrontierCache();
      this.engine.clear();
   }

   private void clearSourceCaches() {
      this.closestTorchCache.clear();
   }

   private void clearReflectiveRegistry() {
      this.reflectiveSources.clear();
      this.reflectiveByChunk.clear();
      this.reflectiveSnapshot = Collections.emptyMap();
      this.dirtyReflectiveChunks.clear();
   }

   private void indexSource(long k) {
      long chunkKey = ChunkPos.asLong(BlockPos.getX(k) >> 4, BlockPos.getZ(k) >> 4);
      ((LongOpenHashSet)this.sourcesByChunk.computeIfAbsent(chunkKey, x -> new LongOpenHashSet())).add(k);
      this.dirtySourceChunks.add(chunkKey);
   }

   private void unindexSource(long k) {
      long ck = ChunkPos.asLong(BlockPos.getX(k) >> 4, BlockPos.getZ(k) >> 4);
      LongOpenHashSet b = (LongOpenHashSet)this.sourcesByChunk.get(ck);
      if (b != null) {
         b.remove(k);
         if (b.isEmpty()) {
            this.sourcesByChunk.remove(ck);
         }

         this.dirtySourceChunks.add(ck);
      }
   }

   private void indexReflectiveSource(long k) {
      long ck = ChunkPos.asLong(BlockPos.getX(k) >> 4, BlockPos.getZ(k) >> 4);
      ((LongOpenHashSet)this.reflectiveByChunk.computeIfAbsent(ck, x -> new LongOpenHashSet())).add(k);
      this.dirtyReflectiveChunks.add(ck);
   }

   private void unindexReflectiveSource(long k) {
      long ck = ChunkPos.asLong(BlockPos.getX(k) >> 4, BlockPos.getZ(k) >> 4);
      LongOpenHashSet b = (LongOpenHashSet)this.reflectiveByChunk.get(ck);
      if (b != null) {
         b.remove(k);
         if (b.isEmpty()) {
            this.reflectiveByChunk.remove(ck);
         }

         this.dirtyReflectiveChunks.add(ck);
      }
   }

   private void refreshSourceSnapshot() {
      if (!this.dirtySourceChunks.isEmpty()) {
         if (this.sourceRefreshQueued.compareAndSet(false, true)) {
            int dirtyCount = this.dirtySourceChunks.size();
            long[] updatedChunkKeys = new long[dirtyCount];
            long[][] updatedBucketArrays = new long[dirtyCount][];
            int count = 0;

            for (LongIterator ci = this.dirtySourceChunks.iterator(); ci.hasNext(); count++) {
               long chunkKey = ci.nextLong();
               LongOpenHashSet bucket = (LongOpenHashSet)this.sourcesByChunk.get(chunkKey);
               updatedChunkKeys[count] = chunkKey;
               if (bucket != null && !bucket.isEmpty()) {
                  long[] values = new long[bucket.size()];
                  int index = 0;
                  LongIterator bi = bucket.iterator();

                  while (bi.hasNext()) {
                     values[index++] = bi.nextLong();
                  }

                  updatedBucketArrays[count] = values;
               } else {
                  updatedBucketArrays[count] = null;
               }
            }

            this.dirtySourceChunks.clear();
            Map<Long, long[]> previousSnapshot = this.sourceIndexSnapshot;
            int finalCount = count;
            CompletableFuture.runAsync(() -> {
               Map<Long, long[]> nextSnapshot = new HashMap<>(previousSnapshot.size() + finalCount);
               nextSnapshot.putAll(previousSnapshot);

               for (int i = 0; i < finalCount; i++) {
                  long[] valuesx = updatedBucketArrays[i];
                  if (valuesx == null) {
                     nextSnapshot.remove(updatedChunkKeys[i]);
                  } else {
                     nextSnapshot.put(updatedChunkKeys[i], valuesx);
                  }
               }

               this.sourceIndexSnapshot = nextSnapshot.isEmpty() ? Collections.emptyMap() : nextSnapshot;
            }).whenComplete((unused, error) -> Minecraft.getInstance().execute(() -> {
                  this.sourceRefreshQueued.set(false);
                  if (!this.dirtySourceChunks.isEmpty()) {
                     this.refreshSourceSnapshot();
                  }
               }));
         }
      }
   }

   private void refreshReflectiveSnapshot() {
      if (!this.dirtyReflectiveChunks.isEmpty()) {
         int dirtyCount = this.dirtyReflectiveChunks.size();
         long[] updatedChunkKeys = new long[dirtyCount];
         long[][] updatedBucketArrays = new long[dirtyCount][];
         int count = 0;

         for (LongIterator ci = this.dirtyReflectiveChunks.iterator(); ci.hasNext(); count++) {
            long chunkKey = ci.nextLong();
            LongOpenHashSet bucket = (LongOpenHashSet)this.reflectiveByChunk.get(chunkKey);
            updatedChunkKeys[count] = chunkKey;
            if (bucket != null && !bucket.isEmpty()) {
               long[] values = new long[bucket.size()];
               int index = 0;
               LongIterator bi = bucket.iterator();

               while (bi.hasNext()) {
                  values[index++] = bi.nextLong();
               }

               updatedBucketArrays[count] = values;
            } else {
               updatedBucketArrays[count] = null;
            }
         }

         this.dirtyReflectiveChunks.clear();
         Map<Long, long[]> previousSnapshot = this.reflectiveSnapshot;
         int finalCount = count;
         CompletableFuture.runAsync(() -> {
            Map<Long, long[]> nextSnapshot = new HashMap<>(previousSnapshot.size() + finalCount);
            nextSnapshot.putAll(previousSnapshot);

            for (int i = 0; i < finalCount; i++) {
               long[] valuesx = updatedBucketArrays[i];
               if (valuesx == null) {
                  nextSnapshot.remove(updatedChunkKeys[i]);
               } else {
                  nextSnapshot.put(updatedChunkKeys[i], valuesx);
               }
            }

            this.reflectiveSnapshot = nextSnapshot.isEmpty() ? Collections.emptyMap() : nextSnapshot;
         });
      }
   }

   public void requestReflectiveSnapshotRefresh() {
      if (!shouldScanReflectiveSources()) {
         this.clearReflectiveRegistry();
      } else if (this.reflectiveRefreshQueued.compareAndSet(false, true)) {
         Minecraft.getInstance().execute(() -> {
            this.reflectiveRefreshQueued.set(false);
            this.refreshReflectiveSnapshot();
         });
      }
   }

   private void triggerGlobalUpdate() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.levelRenderer != null) {
         mc.levelRenderer.allChanged();
      }
   }

   private void enqueueFrontierChecks(LongOpenHashSet positions) {
      if (positions != null && !positions.isEmpty() && this.enabled) {
         synchronized (this.frontierQueueLock) {
            this.pendingFrontierChecks.addAll(positions);
         }

         this.scheduleFrontierWorker();
      }
   }

   private void scheduleFrontierWorker() {
      if (this.enabled && this.frontierWorkerRunning.compareAndSet(false, true)) {
         int epoch = this.frontierEpoch.get();
         FRONTIER_EXECUTOR.execute(() -> this.drainFrontierChecks(epoch));
      }
   }

   private void drainFrontierChecks(int epoch) {
      try {
         while (this.enabled && this.frontierEpoch.get() == epoch) {
            LongArrayList batch = new LongArrayList(4096);
            synchronized (this.frontierQueueLock) {
               LongIterator iterator = this.pendingFrontierChecks.iterator();

               while (iterator.hasNext() && batch.size() < 4096) {
                  long key = iterator.nextLong();
                  iterator.remove();
                  batch.add(key);
               }
            }

            if (batch.isEmpty()) {
               return;
            }

            LongOpenHashSet nextBlocks = new LongOpenHashSet(this.frontierBlockSnapshot);
            LongOpenHashSet dirtyChunks = new LongOpenHashSet();
            LongOpenHashSet changedMembership = new LongOpenHashSet();

            for (int i = 0; i < batch.size(); i++) {
               if (this.frontierEpoch.get() != epoch || !this.enabled) {
                  return;
               }

               long key = batch.getLong(i);
               boolean shouldBeFrontier = this.engine.isReady() && this.engine.isFrontier(key, 72);
               boolean wasFrontier = nextBlocks.contains(key);
               if (shouldBeFrontier != wasFrontier) {
                  if (shouldBeFrontier) {
                     nextBlocks.add(key);
                  } else {
                     nextBlocks.remove(key);
                  }

                  dirtyChunks.add(ChunkPos.asLong(BlockPos.getX(key) >> 4, BlockPos.getZ(key) >> 4));
                  changedMembership.add(key);
               }
            }

            if (!dirtyChunks.isEmpty()) {
               Map<Long, long[]> nextByChunk = new HashMap<>(this.frontierSnapshot.size() + dirtyChunks.size());
               nextByChunk.putAll(this.frontierSnapshot);
               LongIterator chunkIterator = dirtyChunks.iterator();

               while (chunkIterator.hasNext()) {
                  long chunkKey = chunkIterator.nextLong();
                  int chunkX = ChunkPos.getX(chunkKey);
                  int chunkZ = ChunkPos.getZ(chunkKey);
                  LongArrayList values = new LongArrayList();
                  LongIterator blockIterator = nextBlocks.iterator();

                  while (blockIterator.hasNext()) {
                     long key = blockIterator.nextLong();
                     if (BlockPos.getX(key) >> 4 == chunkX && BlockPos.getZ(key) >> 4 == chunkZ) {
                        values.add(key);
                     }
                  }

                  if (values.isEmpty()) {
                     nextByChunk.remove(chunkKey);
                  } else {
                     nextByChunk.put(chunkKey, values.toLongArray());
                  }
               }

               if (this.frontierEpoch.get() != epoch || !this.enabled) {
                  return;
               }

               this.frontierBlockSnapshot = nextBlocks;
               this.frontierSnapshot = nextByChunk.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(nextByChunk);
               if (!changedMembership.isEmpty()) {
                  LongOpenHashSet notification = new LongOpenHashSet(changedMembership);
                  Minecraft.getInstance().execute(() -> {
                     if (this.enabled && this.frontierEpoch.get() == epoch) {
                        NeoShadowsEngine.get().onLightingChanged(notification, true);
                        NeoCpuShadowsEngine.onLightDataChanged();
                     }
                  });
               }
            }
         }
      } finally {
         this.frontierWorkerRunning.set(false);
         boolean hasPending;
         synchronized (this.frontierQueueLock) {
            hasPending = !this.pendingFrontierChecks.isEmpty();
         }

         if (this.enabled && hasPending) {
            this.scheduleFrontierWorker();
         }
      }
   }

   private void clearFrontierCache() {
      this.frontierEpoch.incrementAndGet();
      synchronized (this.frontierQueueLock) {
         this.pendingFrontierChecks.clear();
      }

      this.frontierBlockSnapshot = new LongOpenHashSet();
      this.frontierSnapshot = Collections.emptyMap();
   }

   private static final class ChunkLifecycleBatch {
      final Long2ByteOpenHashMap addedOrChangedSources = new Long2ByteOpenHashMap();
      final LongOpenHashSet removedSources = new LongOpenHashSet();
      final LongOpenHashSet loadedChunks = new LongOpenHashSet();
      boolean sourceRegistryChanged;
      boolean reflectionRegistryChanged;

      ChunkLifecycleBatch() {
         this.addedOrChangedSources.defaultReturnValue((byte)0);
      }

      boolean isEmpty() {
         return this.addedOrChangedSources.isEmpty()
            && this.removedSources.isEmpty()
            && this.loadedChunks.isEmpty()
            && !this.sourceRegistryChanged
            && !this.reflectionRegistryChanged;
      }
   }

   private static final class PendingChunkScan {
      final ClientLevel level;
      final LevelChunk chunk;
      final long chunkKey;
      final int chunkX;
      final int chunkZ;
      final boolean scanLight;
      final boolean scanReflective;
      final Long2ByteOpenHashMap sources = new Long2ByteOpenHashMap();
      final LongOpenHashSet reflections = new LongOpenHashSet();
      final MutableBlockPos mutablePos = new MutableBlockPos();
      int sectionIndex;

      PendingChunkScan(ClientLevel level, LevelChunk chunk, boolean scanLight, boolean scanReflective) {
         this.level = level;
         this.chunk = chunk;
         this.chunkX = chunk.getPos().x;
         this.chunkZ = chunk.getPos().z;
         this.chunkKey = ChunkPos.asLong(this.chunkX, this.chunkZ);
         this.scanLight = scanLight;
         this.scanReflective = scanReflective;
         this.sources.defaultReturnValue((byte)0);
      }
   }
}
