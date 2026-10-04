package com.vinlanx.luxium.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.neocpu.NeoCpuShadowsEngine;
import com.vinlanx.luxium.client.postprocess.PostProcessStateGuard;
import com.vinlanx.luxium.client.shadows.GpuNeoShadows;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard.CullStateShard;
import net.minecraft.client.renderer.RenderStateShard.DepthTestStateShard;
import net.minecraft.client.renderer.RenderStateShard.ShaderStateShard;
import net.minecraft.client.renderer.RenderStateShard.TransparencyStateShard;
import net.minecraft.client.renderer.RenderStateShard.WriteMaskStateShard;
import net.minecraft.client.renderer.RenderType.CompositeState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT}
)
public final class NeoShadowsEngine {
   private static final NeoShadowsEngine INSTANCE = new NeoShadowsEngine();
   static final double RENDER_RADIUS = 36.0;
   static final double MAX_RECEIVER_LIGHT_DISTANCE = 30.0;
   static final int MAX_VISIBLE_SOURCES = 256;
   static final int MAX_RECEIVER_BLOCKS = 16384;
   static final int MAX_RECEIVER_FACES = 12288;
   static final int MAX_DYNAMIC_CASTERS = 160;
   static final int FRONTIER_RECEIVER_EXPANSION_RADIUS = 3;
   static final double COMPATIBLE_BUILD_DISTANCE_SQ = 256.0;
   static final float FACE_EPSILON = 0.0015F;
   static final float FACE_PROBE_INSET = 0.18F;
   static final float HORIZONTAL_FACE_PROBE_INSET = 0.18F;
   static final int LIGHTING_FACE_PROBE_GRID = 1;
   static final int OCCLUSION_FACE_PROBE_GRID = 1;
   static final int HORIZONTAL_LIGHTING_FACE_PROBE_GRID = 3;
   static final int HORIZONTAL_OCCLUSION_FACE_PROBE_GRID = 3;
   static final double PROJECT_EPSILON = 1.0E-6;
   static final double PROJECTION_DENOMINATOR_EPSILON = 1.0E-9;
   static final double POLYGON_AREA_EPSILON = 1.0E-5;
   static final double EXTENDED_PROJECTION_DELTA = 0.001;
   static final double EXTENDED_PROJECTION_FAR = 8.0;
   static final double SHADOW_POLYGON_DILATION = 0.016;
   static final double HORIZONTAL_SHADOW_POLYGON_DILATION = 0.024;
   static final double SHADOW_EDGE_SNAP_EPSILON = 0.022;
   static final double HORIZONTAL_SHADOW_EDGE_SNAP_EPSILON = 0.03;
   static final double FULL_RECT_SHADOW_AREA_THRESHOLD = 0.988;
   static final int SPRITE_ALPHA_THRESHOLD = 16;
   static final long SKY_CAPTURE_MIN_UPDATE_INTERVAL_MS = 100L;
   static final float FACE_CAMERA_FACING_EPSILON = 0.02F;
   static final double MIN_CONTRIBUTION_WEIGHT = 0.03;
   static final long REBUILD_DEBOUNCE_MS = 2L;
   static final long REBUILD_MAX_DEFERRAL_MS = 1500L;
   static final int SHADOW_WORKER_COUNT = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
   static volatile ExecutorService shadowWorkerPool;
   static final Direction[] FACES = Direction.values();
   static final int[][] QUAD_EDGE_VERTEX_INDICES = new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 0}};
   static final NeoShadowsTypes.UvPoint[] LIGHTING_FACE_PROBES = NeoShadowsOcclusionPipeline.createFaceProbes(1, true, 0.18F);
   static final NeoShadowsTypes.UvPoint[] OCCLUSION_FACE_PROBES = NeoShadowsOcclusionPipeline.createFaceProbes(1, true, 0.18F);
   static final NeoShadowsTypes.UvPoint[] HORIZONTAL_LIGHTING_FACE_PROBES = NeoShadowsOcclusionPipeline.createFaceProbes(3, true, 0.18F);
   static final NeoShadowsTypes.UvPoint[] HORIZONTAL_OCCLUSION_FACE_PROBES = NeoShadowsOcclusionPipeline.createFaceProbes(3, true, 0.18F);
   static final NeoShadowsTypes.PlaneRect[] LIGHTING_FACE_PROBE_RECTS = NeoShadowsOcclusionPipeline.createProbeSupportRects(LIGHTING_FACE_PROBES);
   static final NeoShadowsTypes.PlaneRect[] HORIZONTAL_LIGHTING_FACE_PROBE_RECTS = NeoShadowsOcclusionPipeline.createProbeSupportRects(
      HORIZONTAL_LIGHTING_FACE_PROBES
   );
   static final int[][] RECEIVER_OFFSETS = NeoShadowsProjection.buildReceiverOffsets();
   static final RenderType MASK_PASS = buildMaskPass();
   static final ThreadLocal<long[]> CANDIDATE_KEYS_TL = ThreadLocal.withInitial(() -> new long[2048]);
   static final ThreadLocal<double[]> CANDIDATE_DISTANCES_TL = ThreadLocal.withInitial(() -> new double[2048]);
   static final ThreadLocal<byte[]> CANDIDATE_MASKS_TL = ThreadLocal.withInitial(() -> new byte[2048]);
   final ConcurrentHashMap<TextureAtlasSprite, NeoShadowsTypes.SpriteOpaqueMask> spriteOpaqueMaskCache = new ConcurrentHashMap<>();
   final AtomicBoolean staticTaskQueued = new AtomicBoolean(false);
   final AtomicBoolean staticHotPatchTaskQueued = new AtomicBoolean(false);
   final AtomicBoolean dynamicTaskQueued = new AtomicBoolean(false);
   final AtomicBoolean dynamicCaptureQueued = new AtomicBoolean(false);
   final ExecutorService staticExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "NeoShadows-Static");
      thread.setDaemon(true);
      thread.setPriority(7);
      return thread;
   });
   final ExecutorService hotPatchExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "NeoShadows-HotPatch");
      thread.setDaemon(true);
      thread.setPriority(7);
      return thread;
   });
   final ExecutorService dynamicExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "NeoShadows-Dynamic");
      thread.setDaemon(true);
      thread.setPriority(5);
      return thread;
   });
   volatile boolean enabled;
   volatile boolean framePrepared;
   volatile boolean worldDirty = true;
   volatile boolean geometryDirty = true;
   volatile boolean skyCaptureDirty = true;
   volatile boolean skyLightCapturePass;
   volatile boolean shadowCapturePass;
   volatile boolean gpuShadowAtlasCapturePass;
   volatile boolean gpuShadowDynamicCastersAllowed;
   volatile int gpuShadowCaptureFace = -1;
   volatile double gpuShadowCaptureSourceX;
   volatile double gpuShadowCaptureSourceY;
   volatile double gpuShadowCaptureSourceZ;
   volatile float gpuShadowCaptureSourceRadius;
   volatile boolean maskReady;
   volatile boolean skySceneReady;
   volatile boolean dynamicDirty = true;
   volatile boolean pendingRebuild;
   volatile long pendingRebuildSinceMs;
   volatile long pendingRebuildFirstRequestMs;
   volatile boolean highPriorityTracePending;
   volatile long buildEpoch = 1L;
   volatile long nextStaticRequestId = 1L;
   volatile long nextStaticHotPatchRequestId = 1L;
   volatile long nextDynamicCaptureRequestId = 1L;
   volatile long nextDynamicRequestId = 1L;
   volatile long lastDynamicScheduleMs;
   volatile long lastDynamicStateScanMs;
   volatile long lastDynamicMeshCaptureMs;
   volatile long dynamicCasterFingerprint;
   volatile long dynamicSnapshotFingerprint;
   @Nullable
   volatile NeoShadowsTypes.StaticRequest latestStaticRequest;
   @Nullable
   volatile NeoShadowsTypes.StaticResult completedStaticResult;
   @Nullable
   volatile NeoShadowsTypes.StaticHotPatchRequest latestStaticHotPatchRequest;
   @Nullable
   volatile NeoShadowsTypes.StaticHotPatchResult completedStaticHotPatchResult;
   @Nullable
   volatile NeoShadowsTypes.DynamicCaptureRequest latestDynamicCaptureRequest;
   @Nullable
   volatile NeoShadowsTypes.DynamicRequest latestDynamicRequest;
   @Nullable
   volatile NeoShadowsTypes.DynamicResult completedDynamicResult;
   @Nullable
   volatile NeoShadowsTypes.RealtimeStaticShadowOutput activeStaticOutput;
   @Nullable
   volatile NeoShadowsTypes.VisibleCandidateBlockSnapshot visibleCandidateSnapshot;
   volatile NeoShadowsTypes.StaticHotPatchOverlay activeStaticHotPatch = NeoShadowsTypes.StaticHotPatchOverlay.EMPTY;
   volatile List<NeoShadowsTypes.ShadowPolygon> activeDynamicPolygons = Collections.emptyList();
   volatile List<NeoShadowsTypes.ShadowPolygon> inFlightStaticPolygons = Collections.emptyList();
   volatile long inFlightStaticEpoch;
   volatile long activeStaticResultId;
   volatile long appliedStaticResultId;
   volatile long appliedStaticHotPatchResultId;
   volatile long activeDynamicResultId;
   volatile long activeDynamicStaticResultId;
   volatile DynamicShadowMeshCapture.Snapshot dynamicCasterSnapshot = DynamicShadowMeshCapture.Snapshot.empty();
   TextureTarget shadowMaskTarget;
   @Nullable
   RenderTarget sceneCopyTarget;
   TextureTarget skyCaptureTarget;
   long lastSkyCaptureRenderMs;
   int lastSkyCaptureWidth = -1;
   int lastSkyCaptureHeight = -1;
   double lastSkyCaptureCamX = Double.NaN;
   double lastSkyCaptureCamY = Double.NaN;
   double lastSkyCaptureCamZ = Double.NaN;
   float lastSkyCaptureYaw = Float.NaN;
   float lastSkyCapturePitch = Float.NaN;
   final Matrix4f lastSkyCaptureProjection = new Matrix4f();
   boolean lastSkyCaptureProjectionValid;
   final NeoShadowsRuntime runtime;
   final NeoShadowsRenderPipeline renderPipeline;
   final NeoShadowsDynamicPipeline dynamicPipeline = new NeoShadowsDynamicPipeline(this);
   final NeoShadowsVisibility visibility;
   final NeoShadowsOcclusionPipeline occlusionPipeline = new NeoShadowsOcclusionPipeline(this);
   final NeoShadowsStaticPipeline staticPipeline;

   private NeoShadowsEngine() {
      this.visibility = new NeoShadowsVisibility(this);
      this.staticPipeline = new NeoShadowsStaticPipeline(this);
      this.runtime = new NeoShadowsRuntime(this);
      this.renderPipeline = new NeoShadowsRenderPipeline(this);
   }

   public static NeoShadowsEngine get() {
      return INSTANCE;
   }

   public static boolean isAnyShadowCapturePass() {
      return INSTANCE.skyLightCapturePass || INSTANCE.shadowCapturePass;
   }

   public static boolean isShadowCapturePass() {
      return INSTANCE.shadowCapturePass;
   }

   public static boolean isGpuShadowAtlasCapturePass() {
      return INSTANCE.gpuShadowAtlasCapturePass;
   }

   public static boolean shouldRenderDynamicCastersInShadowPass() {
      return INSTANCE.gpuShadowAtlasCapturePass && INSTANCE.gpuShadowDynamicCastersAllowed;
   }

   public static void beginGpuShadowAtlasCapture(double sourceX, double sourceY, double sourceZ, float radius, int face, boolean allowDynamicCasters) {
      INSTANCE.shadowCapturePass = true;
      INSTANCE.gpuShadowAtlasCapturePass = true;
      INSTANCE.gpuShadowDynamicCastersAllowed = allowDynamicCasters;
      INSTANCE.gpuShadowCaptureFace = face;
      INSTANCE.gpuShadowCaptureSourceX = sourceX;
      INSTANCE.gpuShadowCaptureSourceY = sourceY;
      INSTANCE.gpuShadowCaptureSourceZ = sourceZ;
      INSTANCE.gpuShadowCaptureSourceRadius = radius;
   }

   public static void endGpuShadowAtlasCapture() {
      INSTANCE.gpuShadowDynamicCastersAllowed = false;
      INSTANCE.gpuShadowAtlasCapturePass = false;
      INSTANCE.gpuShadowCaptureFace = -1;
      INSTANCE.gpuShadowCaptureSourceX = 0.0;
      INSTANCE.gpuShadowCaptureSourceY = 0.0;
      INSTANCE.gpuShadowCaptureSourceZ = 0.0;
      INSTANCE.gpuShadowCaptureSourceRadius = 0.0F;
      INSTANCE.shadowCapturePass = false;
   }

   public static void setShadowCapturePass(boolean val) {
      INSTANCE.shadowCapturePass = val;
   }

   public static boolean isShadowlessSceneReady() {
      return INSTANCE.skySceneReady && INSTANCE.skyCaptureTarget != null;
   }

   public static int getShadowlessSceneColorTextureId() {
      return INSTANCE.skyCaptureTarget != null ? INSTANCE.skyCaptureTarget.getColorTextureId() : -1;
   }

   public static int getShadowlessSceneDepthTextureId() {
      return INSTANCE.skyCaptureTarget != null ? INSTANCE.skyCaptureTarget.getDepthTextureId() : -1;
   }

   public static void markShadowlessCaptureDirty() {
      INSTANCE.skyCaptureDirty = true;
   }

   public static boolean isRenderingSkyLightPass() {
      return INSTANCE.skyLightCapturePass;
   }

   public static void markWorldDirty() {
      INSTANCE.runtime.onWorldDirty(null);
   }

   public static void markWorldDirty(@Nullable LongOpenHashSet changedBlocks) {
      INSTANCE.runtime.onWorldDirty(changedBlocks);
   }

   public static void markLightingDirty() {
      INSTANCE.runtime.onLightingDirty(null, false);
   }

   public static void markLightingDirty(@Nullable LongOpenHashSet changedBlocks, boolean geometryChanged) {
      INSTANCE.runtime.onLightingDirty(changedBlocks, geometryChanged);
   }

   public static void markGeometryDirty() {
      INSTANCE.geometryDirty = true;
   }

   public void notifyHighPriorityTraceDone() {
      this.highPriorityTracePending = true;
   }

   public void onLightingChanged() {
      this.runtime.onLightingDirty(null, true);
   }

   public void onLightingChanged(@Nullable LongOpenHashSet changedBlocks) {
      this.runtime.onLightingDirty(changedBlocks, true);
   }

   public void onLightingChanged(@Nullable LongOpenHashSet changedBlocks, boolean geometryChanged) {
      this.runtime.onLightingDirty(changedBlocks, geometryChanged);
   }

   public static void prepareFrame(Minecraft mc, Camera camera) {
      INSTANCE.runtime.prepareFrameInternal(mc, camera, mc.getPartialTick());
   }

   public static void prepareFrame(Minecraft mc, Camera camera, float partialTick) {
      INSTANCE.runtime.prepareFrameInternal(mc, camera, partialTick);
   }

   public static void captureSkyLightScene(
      LevelRenderer levelRenderer,
      PoseStack poseStack,
      float partialTick,
      long finishNano,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f projectionMatrix
   ) {
      INSTANCE.renderPipeline
         .captureSkyLightSceneInternal(levelRenderer, poseStack, partialTick, finishNano, camera, gameRenderer, lightTexture, projectionMatrix);
   }

   public static void forceDisable() {
      INSTANCE.forceDisableInternal();
   }

   @SubscribeEvent(
      priority = EventPriority.HIGH
   )
   public static void onRenderLevel(RenderLevelStageEvent event) {
      INSTANCE.handleRenderLevelStage(event);
   }

   private void handleRenderLevelStage(RenderLevelStageEvent event) {
      if (!ReflectionSystem.isRenderingWorldPass() && !isRenderingSkyLightPass()) {
         if (this.isSelected()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
               if (event.getStage() == Stage.AFTER_PARTICLES) {
                  this.renderPipeline.renderAfterParticles(mc, event);
                  this.runtime.drainDynamicCaptureQueue();
               }
            }
         } else if (!NeoCpuShadowsEngine.isActive() && !GpuNeoShadows.isActive()) {
            this.forceDisableInternal();
            PostProcessStateGuard.restoreCanonicalMainTargetBinding(Minecraft.getInstance());
         }
      }
   }

   boolean isSelected() {
      return !NeoCpuShadowsEngine.isActive() && !GpuNeoShadows.isActive()
         ? !NeoGpuVanilla.isConfiguredEnabled() && Config.isFeatureEnabled(Config.CLIENT.realisticShadowsEnabled)
         : false;
   }

   void setEnabled(boolean enabled) {
      if (this.enabled != enabled) {
         this.enabled = enabled;
         this.buildEpoch++;
         this.clearRuntimeState();
         if (enabled) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer != null) {
               mc.levelRenderer.allChanged();
            }
         }
      }
   }

   void forceDisableInternal() {
      if (this.enabled) {
         this.setEnabled(false);
      } else if (this.framePrepared || this.skyLightCapturePass || this.maskReady || this.skySceneReady) {
         this.clearRuntimeState();
      }
   }

   void clearRuntimeState() {
      this.framePrepared = false;
      this.worldDirty = true;
      this.skyCaptureDirty = true;
      this.skyLightCapturePass = false;
      this.shadowCapturePass = false;
      this.gpuShadowAtlasCapturePass = false;
      this.gpuShadowDynamicCastersAllowed = false;
      this.gpuShadowCaptureFace = -1;
      this.gpuShadowCaptureSourceX = 0.0;
      this.gpuShadowCaptureSourceY = 0.0;
      this.gpuShadowCaptureSourceZ = 0.0;
      this.gpuShadowCaptureSourceRadius = 0.0F;
      this.maskReady = false;
      this.skySceneReady = false;
      this.dynamicDirty = true;
      this.pendingRebuild = false;
      this.pendingRebuildSinceMs = 0L;
      this.pendingRebuildFirstRequestMs = 0L;
      this.activeStaticOutput = null;
      this.visibleCandidateSnapshot = null;
      this.activeStaticHotPatch = NeoShadowsTypes.StaticHotPatchOverlay.EMPTY;
      this.activeDynamicPolygons = Collections.emptyList();
      this.inFlightStaticPolygons = Collections.emptyList();
      this.inFlightStaticEpoch = 0L;
      this.latestStaticRequest = null;
      this.completedStaticResult = null;
      this.latestStaticHotPatchRequest = null;
      this.completedStaticHotPatchResult = null;
      this.latestDynamicCaptureRequest = null;
      this.latestDynamicRequest = null;
      this.completedDynamicResult = null;
      this.dynamicCaptureQueued.set(false);
      this.activeStaticResultId = 0L;
      this.appliedStaticResultId = 0L;
      this.appliedStaticHotPatchResultId = 0L;
      this.activeDynamicResultId = 0L;
      this.activeDynamicStaticResultId = 0L;
      this.lastDynamicScheduleMs = 0L;
      this.lastDynamicStateScanMs = 0L;
      this.lastDynamicMeshCaptureMs = 0L;
      this.dynamicCasterFingerprint = 0L;
      this.dynamicSnapshotFingerprint = 0L;
      this.dynamicCasterSnapshot = DynamicShadowMeshCapture.Snapshot.empty();
      this.lastSkyCaptureRenderMs = 0L;
      this.lastSkyCaptureWidth = -1;
      this.lastSkyCaptureHeight = -1;
      this.lastSkyCaptureCamX = Double.NaN;
      this.lastSkyCaptureCamY = Double.NaN;
      this.lastSkyCaptureCamZ = Double.NaN;
      this.lastSkyCaptureYaw = Float.NaN;
      this.lastSkyCapturePitch = Float.NaN;
      this.lastSkyCaptureProjectionValid = false;
      this.occlusionPipeline.clearOcclusionCache();
   }

   boolean hasAnyShadowPolygons() {
      return this.activeStaticOutput != null && !this.activeStaticOutput.shadowPolygons.isEmpty()
         || !this.activeStaticHotPatch.polygons.isEmpty()
         || !this.inFlightStaticPolygons.isEmpty()
         || !this.activeDynamicPolygons.isEmpty();
   }

   void ensureTargets(int width, int height) {
      if (this.shadowMaskTarget == null) {
         this.shadowMaskTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
         this.shadowMaskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
      } else if (this.shadowMaskTarget.width != width || this.shadowMaskTarget.height != height) {
         this.shadowMaskTarget.resize(width, height, Minecraft.ON_OSX);
         this.shadowMaskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
      }

      if (this.sceneCopyTarget == null) {
         this.sceneCopyTarget = new RenderTarget(false) {
         };
         this.sceneCopyTarget.createBuffers(width, height, Minecraft.ON_OSX);
         this.sceneCopyTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
      } else if (this.sceneCopyTarget.viewWidth != width || this.sceneCopyTarget.viewHeight != height) {
         this.sceneCopyTarget.resize(width, height, Minecraft.ON_OSX);
      }

      double skyCaptureScaleValue = (Double)Config.CLIENT.realisticShadowsRenderScale.get();
      float skyCaptureScale = (float)Math.max(0.01, Math.min(1.0, skyCaptureScaleValue));
      int skyWidth = Math.max(1, Math.round((float)width * skyCaptureScale));
      int skyHeight = Math.max(1, Math.round((float)height * skyCaptureScale));
      if (this.skyCaptureTarget == null) {
         this.skyCaptureTarget = new TextureTarget(skyWidth, skyHeight, true, Minecraft.ON_OSX);
         this.skyCaptureTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         this.skyCaptureTarget.setFilterMode(9729);
      } else if (this.skyCaptureTarget.width != skyWidth || this.skyCaptureTarget.height != skyHeight) {
         this.skyCaptureTarget.resize(skyWidth, skyHeight, Minecraft.ON_OSX);
         this.skyCaptureTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         this.skyCaptureTarget.setFilterMode(9729);
      }
   }

   static void flushSharedRenderBuffers(Minecraft mc) {
      mc.renderBuffers().bufferSource().endBatch();
      mc.renderBuffers().crumblingBufferSource().endBatch();
      mc.renderBuffers().outlineBufferSource().endOutlineBatch();
   }

   static ExecutorService shadowWorkerPool() {
      ExecutorService pool = shadowWorkerPool;
      if (pool != null) {
         return pool;
      } else {
         synchronized (NeoShadowsEngine.class) {
            if (shadowWorkerPool == null) {
               shadowWorkerPool = Executors.newFixedThreadPool(SHADOW_WORKER_COUNT, runnable -> {
                  Thread thread = new Thread(runnable, "NeoShadows-Worker");
                  thread.setDaemon(true);
                  thread.setPriority(6);
                  return thread;
               });
            }

            return shadowWorkerPool;
         }
      }
   }

   static void mergeScratch(NeoShadowsTypes.GeometryBuildState state, NeoShadowsTypes.BuildScratch scratch) {
      if (!scratch.shadowPolygons.isEmpty()) {
         state.shadowPolygons.addAll(scratch.shadowPolygons);
      }

      state.occluderHitCount = state.occluderHitCount + scratch.occluderHitCount;
      state.shadowPolygonCount = state.shadowPolygonCount + scratch.shadowPolygonCount;
   }

   boolean shouldCullShadowPolygon(NeoShadowsTypes.ShadowPolygon polygon, double cameraX, double cameraY, double cameraZ, @Nullable Frustum frustum) {
      return !isFaceFacingCamera(polygon, cameraX, cameraY, cameraZ) ? true : frustum != null && !frustum.isVisible(polygon.bounds);
   }

   static boolean isFaceFacingCamera(NeoShadowsTypes.ShadowPolygon polygon, double cameraX, double cameraY, double cameraZ) {
      double toCameraX = cameraX - polygon.faceCenterX;
      double toCameraY = cameraY - polygon.faceCenterY;
      double toCameraZ = cameraZ - polygon.faceCenterZ;
      return toCameraX * (double)polygon.faceNormalX + toCameraY * (double)polygon.faceNormalY + toCameraZ * (double)polygon.faceNormalZ > 0.02F;
   }

   void emitPolygon(Matrix4f pose, VertexConsumer consumer, Vec3 cameraPos, NeoShadowsTypes.ShadowPolygon polygon) {
      double[] vertices = polygon.triangleVertices;
      if (vertices.length >= 9 && !(polygon.strength <= 0.0F)) {
         float value = polygon.strength;

         for (int offset = 0; offset < vertices.length; offset += 3) {
            consumer.vertex(
                  pose, (float)(vertices[offset] - cameraPos.x), (float)(vertices[offset + 1] - cameraPos.y), (float)(vertices[offset + 2] - cameraPos.z)
               )
               .color(value, value, value, 1.0F)
               .endVertex();
         }
      }
   }

   private static RenderType buildMaskPass() {
      CompositeState state = CompositeState.builder()
         .setShaderState(new ShaderStateShard(GameRenderer::getPositionColorShader))
         .setTransparencyState(NeoShadowsEngine.RenderHelper.ADDITIVE_TRANSPARENCY_STATE)
         .setDepthTestState(NeoShadowsEngine.RenderHelper.LEQUAL)
         .setCullState(NeoShadowsEngine.RenderHelper.NO_CULL)
         .setWriteMaskState(NeoShadowsEngine.RenderHelper.COLOR_ONLY)
         .createCompositeState(false);
      return RenderType.create("luxium_neo_shadow_mask", DefaultVertexFormat.POSITION_COLOR, Mode.TRIANGLES, 262144, false, true, state);
   }

   static final class RenderHelper extends RenderStateShard {
      static final TransparencyStateShard NO_TRANSPARENCY_STATE = NO_TRANSPARENCY;
      static final TransparencyStateShard ADDITIVE_TRANSPARENCY_STATE = ADDITIVE_TRANSPARENCY;
      static final DepthTestStateShard LEQUAL = LEQUAL_DEPTH_TEST;
      static final CullStateShard NO_CULL = RenderStateShard.NO_CULL;
      static final WriteMaskStateShard COLOR_ONLY = RenderStateShard.COLOR_WRITE;

      private RenderHelper() {
         super("luxium_neo_shadow_helper", () -> {
         }, () -> {
         });
      }
   }
}
