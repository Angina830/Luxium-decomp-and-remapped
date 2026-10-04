package com.vinlanx.luxium.rtx.neogpuvanilla;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.shadows.GpuLocalLightResolver;
import com.vinlanx.luxium.client.shadows.GpuShadowCache;
import com.vinlanx.luxium.rtx.TorchRtxState;
import java.util.Arrays;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class NeoGpuVanilla {
   public static final int MAX_SOURCES = 40;
   private static final NeoGpuVanillaVolume VOLUME = new NeoGpuVanillaVolume();
   private static final float[] SOURCE_DATA = new float[160];
   private static final float[] SOURCE_GEOMETRY = new float[160];
   private static final float[] SOURCE_COLORS = new float[160];
   private static final float[] ATLAS_RECTS = new float[960];
   private static final long[] VISIBLE_KEYS = new long[40];
   private static final int[] VISIBLE_EMISSIONS = new int[40];
   private static ClientLevel trackedLevel;
   private static long[] stagedSources = new long[0];
   private static int[] stagedEmissions = new int[0];
   private static long stagedSignature;
   private static long stagedSourceSignature;
   private static long bakedSignature = Long.MIN_VALUE;
   private static long observedContentGeneration = Long.MIN_VALUE;
   private static long requestedVanillaGeneration;
   private static long bakedVanillaGeneration = Long.MIN_VALUE;
   private static boolean mainPassActive;
   private static int pendingVanillaSettleFrames;
   private static boolean sourceSetChangePending;
   private static int sourceSetQuietFrames;
   private static int sourceSetChangeAgeFrames;
   private static long frameVersion;
   private static NeoGpuVanillaFrameState frameState = NeoGpuVanillaFrameState.disabled(0L);

   private NeoGpuVanilla() {
   }

   public static boolean isConfiguredEnabled() {
      return Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaEnabled);
   }

   public static boolean isActive() {
      Minecraft mc = Minecraft.getInstance();
      return isConfiguredEnabled() && mc.level != null && mc.player != null;
   }

   public static boolean isMainPassActive() {
      return mainPassActive;
   }

   public static NeoGpuVanillaFrameState frameState() {
      return frameState;
   }

   public static void prepareShadowAtlas(Minecraft mc, Camera camera) {
      if (!isConfiguredEnabled()) {
         if (trackedLevel != null || VOLUME.hasResources()) {
            releaseResources();
         }

         clearFrame();
      } else if (isActive() && mc.level != null && camera != null) {
         if (trackedLevel != mc.level) {
            trackedLevel = mc.level;
            stagedSources = new long[0];
            stagedEmissions = new int[0];
            stagedSignature = 0L;
            stagedSourceSignature = 0L;
            bakedSignature = Long.MIN_VALUE;
            observedContentGeneration = Long.MIN_VALUE;
            requestedVanillaGeneration = 0L;
            bakedVanillaGeneration = Long.MIN_VALUE;
            sourceSetChangePending = false;
            sourceSetQuietFrames = 0;
            sourceSetChangeAgeFrames = 0;
            VOLUME.release();
            GpuShadowCache.get().release();
         }

         Vec3 cameraPos = camera.getPosition();
         if (VOLUME.updateAnchor(cameraPos.x, cameraPos.y, cameraPos.z)) {
            bakedSignature = Long.MIN_VALUE;
         }

         GpuShadowCache cache = GpuShadowCache.get();
         int configuredLimit = Math.max(1, Math.min(40, (Integer)Config.CLIENT.neoGpuVanillaMaxSources.get()));
         int limit = Math.min(configuredLimit, cache.getSourceCapacity());
         double distance = (double)((Integer)Config.CLIENT.neoGpuVanillaDistance.get()).intValue();
         TorchRtxState registry = TorchRtxState.get();
         Vec3 selectionOrigin = new Vec3((double)VOLUME.buildAnchorX() + 24.0, (double)VOLUME.buildAnchorY() + 16.0, (double)VOLUME.buildAnchorZ() + 24.0);
         stagedSources = registry.getClosestSources(selectionOrigin, distance * distance, limit);
         stagedEmissions = new int[stagedSources.length];

         for (int i = 0; i < stagedSources.length; i++) {
            stagedEmissions[i] = registry.getSourceEmission(stagedSources[i]);
         }

         long nextSourceSignature = signature(stagedSources, stagedEmissions, 0, 0, 0, (Integer)Config.CLIENT.neoGpuVanillaCaptureResolution.get());
         if (nextSourceSignature != stagedSourceSignature) {
            stagedSourceSignature = nextSourceSignature;
            if (!sourceSetChangePending) {
               sourceSetChangeAgeFrames = 0;
            }

            sourceSetChangePending = false;
            sourceSetQuietFrames = 0;
            sourceSetChangeAgeFrames = 0;
         }

         stagedSignature = signature(
            stagedSources,
            stagedEmissions,
            VOLUME.buildAnchorX(),
            VOLUME.buildAnchorY(),
            VOLUME.buildAnchorZ(),
            (Integer)Config.CLIENT.neoGpuVanillaCaptureResolution.get()
         );
         cache.stageVisibleEntries(mc.level, stagedSources, stagedEmissions, limit);
      } else {
         clearFrame();
      }
   }

   public static boolean hasStagedLights() {
      return isActive() && stagedSources.length > 0;
   }

   public static void prepareMainWorldRender(Minecraft mc, Camera camera) {
      mainPassActive = false;
      if (isActive() && mc.level != null && camera != null) {
         GpuShadowCache cache = GpuShadowCache.get();
         pendingVanillaSettleFrames = 0;
         sourceSetChangePending = false;
         sourceSetQuietFrames = 0;
         sourceSetChangeAgeFrames = 0;
         Arrays.fill(SOURCE_DATA, 0.0F);
         Arrays.fill(SOURCE_GEOMETRY, 0.0F);
         Arrays.fill(SOURCE_COLORS, 0.0F);
         Arrays.fill(ATLAS_RECTS, 0.0F);
         Arrays.fill(VISIBLE_KEYS, 0L);
         Arrays.fill(VISIBLE_EMISSIONS, 0);
         int count = cache.collectNeoGpuVanillaEntries(
            stagedSources,
            stagedEmissions,
            stagedSources.length,
            (double)VOLUME.buildAnchorX(),
            (double)VOLUME.buildAnchorY(),
            (double)VOLUME.buildAnchorZ(),
            SOURCE_DATA,
            ATLAS_RECTS,
            VISIBLE_KEYS,
            VISIBLE_EMISSIONS,
            SOURCE_GEOMETRY
         );
         boolean capturePending = false;

         for (int i = 0; i < count; i++) {
            SOURCE_DATA[i * 4 + 3] = (float)VISIBLE_EMISSIONS[i];
            GpuLocalLightResolver.resolve(mc.level, VISIBLE_KEYS[i], VISIBLE_EMISSIONS[i], SOURCE_COLORS, i * 4);
            int flags = Math.round(SOURCE_GEOMETRY[i * 4 + 3]);
            if ((flags & 128) != 0) {
               capturePending = true;
            }
         }

         long contentGeneration = cache.getNeoGpuVanillaContentGeneration(stagedSources, stagedSources.length);
         boolean wantsBake = stagedSignature != bakedSignature
            || contentGeneration != observedContentGeneration
            || requestedVanillaGeneration != bakedVanillaGeneration
            || !VOLUME.isReady();
         if (wantsBake) {
            int atlasTexture = count > 0 ? cache.getAtlasTextureId() : 0;
            if (VOLUME.bake(mc.level, atlasTexture, count, SOURCE_DATA, SOURCE_GEOMETRY, SOURCE_COLORS, ATLAS_RECTS)) {
               bakedSignature = stagedSignature;
               observedContentGeneration = contentGeneration;
               bakedVanillaGeneration = requestedVanillaGeneration;
            }
         }

         NeoGpuVanillaGpuDebug.tick(
            stagedSources.length,
            cache.countPendingNeoGpuVanillaFaces(stagedSources, stagedSources.length),
            (Integer)Config.CLIENT.neoGpuVanillaCaptureResolution.get()
         );
         publishCurrentFrame(camera);
      } else {
         publishCurrentFrame(camera);
      }
   }

   public static void beginMainWorldRender() {
      mainPassActive = frameState.enabled();
   }

   public static void finishMainWorldRender() {
      mainPassActive = false;
   }

   public static void onGeometryChanged(BlockPos pos) {
      onGeometryChanged(pos, true);
   }

   public static void onGeometryChanged(BlockPos pos, boolean vanillaLightMayChange) {
      if (isConfiguredEnabled() && Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaGeometryUpdates) && pos != null) {
         GpuShadowCache cache = GpuShadowCache.get();
         if (cache.usesDirectEmbeddiumCapture()) {
            cache.expectRenderedSectionChange(pos);
            SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
            if (worldRenderer != null) {
               worldRenderer.scheduleRebuildForChunk(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, true);
            }
         } else {
            cache.onGeometryChanged(pos);
         }

         if (isInsideBuildVolume(pos)) {
            requestedVanillaGeneration++;
            pendingVanillaSettleFrames = 0;
         }
      }
   }

   public static void onRenderedSectionChanged(int originX, int originY, int originZ) {
      if (isConfiguredEnabled()) {
         GpuShadowCache.get().onRenderedSectionChanged(originX, originY, originZ);
      }
   }

   public static void onChunkSourceRegistryChanged() {
      if (isConfiguredEnabled()) {
         ;
      }
   }

   public static void onSourceRegistryChanged() {
      if (isConfiguredEnabled()) {
         requestedVanillaGeneration++;
         pendingVanillaSettleFrames = 0;
      }
   }

   public static void requestRebake() {
      if (isConfiguredEnabled()) {
         stagedSignature = Long.MIN_VALUE;
      }
   }

   public static void requestRebuild() {
      if (!isConfiguredEnabled()) {
         releaseResources();
      } else {
         bakedSignature = Long.MIN_VALUE;
         pendingVanillaSettleFrames = 0;
      }
   }

   public static void onConfigChanged() {
      if (isConfiguredEnabled()) {
         requestRebuild();
      } else {
         releaseResources();
      }
   }

   public static void releaseResources() {
      trackedLevel = null;
      stagedSources = new long[0];
      stagedEmissions = new int[0];
      mainPassActive = false;
      VOLUME.release();
      GpuShadowCache.get().release();
      bakedSignature = Long.MIN_VALUE;
      observedContentGeneration = Long.MIN_VALUE;
      requestedVanillaGeneration = 0L;
      bakedVanillaGeneration = Long.MIN_VALUE;
      stagedSourceSignature = 0L;
      pendingVanillaSettleFrames = 0;
      sourceSetChangePending = false;
      sourceSetQuietFrames = 0;
      sourceSetChangeAgeFrames = 0;
      NeoGpuVanillaGpuDebug.reset();
      frameState = NeoGpuVanillaFrameState.disabled(++frameVersion);
   }

   public static int getVolumeTextureId() {
      return VOLUME.textureId();
   }

   public static int getReceiverTextureId() {
      return VOLUME.receiverTextureId();
   }

   public static boolean isVolumeReady() {
      return VOLUME.isReady();
   }

   public static Vector3f getVolumeMin(Vector3f out) {
      return out.set((float)VOLUME.anchorX(), (float)VOLUME.anchorY(), (float)VOLUME.anchorZ());
   }

   public static Vector3f getVolumeSize(Vector3f out) {
      return out.set(48.0F, 32.0F, 48.0F);
   }

   public static float sampleLightFactor(double worldX, double worldY, double worldZ) {
      return VOLUME.sampleLightFactor(worldX, worldY, worldZ);
   }

   public static boolean sampleRandomLitPosition(RandomSource random, Vector3f output) {
      return VOLUME.sampleRandomLitPosition(random, output);
   }

   private static boolean isInsideBuildVolume(BlockPos pos) {
      int minX = VOLUME.buildAnchorX();
      int minY = VOLUME.buildAnchorY();
      int minZ = VOLUME.buildAnchorZ();
      return minX != Integer.MIN_VALUE && minY != Integer.MIN_VALUE && minZ != Integer.MIN_VALUE
         ? pos.getX() >= minX && pos.getX() < minX + 48 && pos.getY() >= minY && pos.getY() < minY + 32 && pos.getZ() >= minZ && pos.getZ() < minZ + 48
         : false;
   }

   private static void publishCurrentFrame(Camera camera) {
      if (camera != null && VOLUME.isReady()) {
         Vec3 cameraPos = camera.getPosition();
         boolean fast = Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaFastShadowsEnabled) && VOLUME.fastShadowsReady();
         frameState = new NeoGpuVanillaFrameState(
            true,
            VOLUME.receiverTextureId(),
            VOLUME.receiverScale(),
            fast,
            VOLUME.fastLightTextureId(0),
            VOLUME.fastLightTextureId(1),
            VOLUME.fastLightTextureId(2),
            VOLUME.fastLightTextureId(3),
            VOLUME.fastLightTextureId(4),
            VOLUME.fastLightTextureId(5),
            (float)((double)VOLUME.anchorX() - cameraPos.x),
            (float)((double)VOLUME.anchorY() - cameraPos.y),
            (float)((double)VOLUME.anchorZ() - cameraPos.z),
            48.0F,
            32.0F,
            48.0F,
            ++frameVersion
         );
      } else {
         frameState = NeoGpuVanillaFrameState.disabled(++frameVersion);
      }
   }

   private static void clearFrame() {
      mainPassActive = false;
      frameState = NeoGpuVanillaFrameState.disabled(++frameVersion);
   }

   private static long signature(long[] keys, int[] emissions, int anchorX, int anchorY, int anchorZ, int captureResolution) {
      long h = -7046029254386353131L;
      h = mix(h, (long)anchorX);
      h = mix(h, (long)anchorY);
      h = mix(h, (long)anchorZ);
      h = mix(h, (long)captureResolution);
      h = mix(h, Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaFastShadowsEnabled) ? 1L : 0L);
      h = mix(h, Double.doubleToLongBits((Double)Config.CLIENT.neoGpuVanillaFastShadowStrength.get()));
      h = mix(h, Double.doubleToLongBits((Double)Config.CLIENT.neoGpuVanillaFastDiffuseWrap.get()));
      int count = Math.min(keys.length, emissions.length);
      long xor = 0L;
      long sum = 0L;

      for (int i = 0; i < count; i++) {
         long element = keys[i] ^ -7046029254386353131L * ((long)emissions[i] + 1L);
         element ^= element >>> 30;
         element *= -4658895280553007687L;
         element ^= element >>> 27;
         element *= -7723592293110705685L;
         element ^= element >>> 31;
         xor ^= element;
         sum += Long.rotateLeft(element, emissions[i] & 63);
      }

      h = mix(h, (long)count);
      h = mix(h, xor);
      return mix(h, sum);
   }

   private static long mix(long hash, long value) {
      long v = value * -4417276706812531889L;
      v ^= v >>> 29;
      hash ^= v;
      hash *= 1609587929392839161L;
      return hash ^ hash >>> 32;
   }
}
