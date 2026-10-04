package com.vinlanx.luxium.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.reflections.RainPuddleManager;
import com.vinlanx.luxium.client.reflections.RainPuddleSurface;
import com.vinlanx.luxium.client.reflections.ReflectionMaterial;
import com.vinlanx.luxium.client.reflections.ReflectionMaterialRegistry;
import com.vinlanx.luxium.mixin.CameraAccessor;
import com.vinlanx.luxium.mixin.LevelRendererAccessor;
import com.vinlanx.luxium.mixin.MinecraftAccessor;
import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.soasnottointerfere.ExposedFaceService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.RenderStateShard.CullStateShard;
import net.minecraft.client.renderer.RenderStateShard.DepthTestStateShard;
import net.minecraft.client.renderer.RenderStateShard.LightmapStateShard;
import net.minecraft.client.renderer.RenderStateShard.ShaderStateShard;
import net.minecraft.client.renderer.RenderStateShard.TextureStateShard;
import net.minecraft.client.renderer.RenderStateShard.TransparencyStateShard;
import net.minecraft.client.renderer.RenderStateShard.WriteMaskStateShard;
import net.minecraft.client.renderer.RenderType.CompositeState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL30;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT}
)
public final class ReflectionSystem {
   private static final ReflectionSystem INSTANCE = new ReflectionSystem();
   private static final int MAX_RENDERED_PLANES = 4;
   private static final float FACE_EPSILON = 0.0015F;
   private static final int BOOTSTRAP_SCAN_RADIUS = 24;
   private static final int BOOTSTRAP_VERTICAL_RADIUS = 24;
   private static final int PROBE_FACE = 64;
   private static final long PROBE_UPDATE_MS = 1000L;
   private volatile boolean enabled = false;
   private boolean initialized = false;
   private boolean bilinearFilteringEnabled = true;
   private int recursionDepth = 0;
   @Nullable
   private Vec3 activeRenderCameraPos;
   private final List<ReflectionSystem.PlaneRenderCache> planeRenderCaches = new ArrayList<>(4);
   private DynamicTexture probeTexture;
   private ResourceLocation probeLoc;
   private RenderType probeRenderType;
   private long lastProbeMs;
   private BlockPos probeCenter;
   private final Camera reflectionCamera = new Camera();
   private final Matrix4f frameViewMatrix = new Matrix4f();
   private final Matrix4f frameProjectionMatrix = new Matrix4f();
   private float framePartialTick;
   private long frameFinishNano;
   private GameRenderer frameGameRenderer;
   private LightTexture frameLightTexture;
   private boolean frameContextReady = false;
   private long lastPlaneScanMs = 0L;
   private int lastPlaneScanBlockX = Integer.MIN_VALUE;
   private int lastPlaneScanBlockY = Integer.MIN_VALUE;
   private int lastPlaneScanBlockZ = Integer.MIN_VALUE;
   private int lastPlaneScanDistance = Integer.MIN_VALUE;
   private final List<ReflectionSystem.MirrorPlane> activePlanes = new ArrayList<>();
   private int activePassPlaneIndex = -1;
   private int activePassViewportWidth;
   private int activePassViewportHeight;
   private boolean[][] feedDemand = new boolean[4][4];
   private double lastCamX;
   private double lastCamY;
   private double lastCamZ;
   private float lastCamYaw;
   private float lastCamPitch;
   private boolean cameraMovedThisFrame = true;
   private volatile boolean worldDirty = false;
   private long lastWorldDirtyMs = 0L;
   private static final long WORLD_DIRTY_DEBOUNCE_MS = 50L;
   private final Matrix4f cachedOriginalProjection = new Matrix4f();
   private final Matrix3f cachedOriginalInverseView = new Matrix3f();
   private static final int SLOT_EXACT = 0;
   private static final int SLOT_FEED = 1;
   private static final float FEED_FAR_PLANE = 1536.0F;
   private static final float FEED_WORLD_HALF_EXTENT = 24.0F;
   private final Matrix4f feedScratchMirrorLinear = new Matrix4f();
   private final Matrix4f feedScratchProj = new Matrix4f();
   private final Vec3[] feedScratchVirtCam = new Vec3[1];

   private ReflectionSystem() {
   }

   public static ReflectionSystem get() {
      return INSTANCE;
   }

   @SubscribeEvent
   public static void onRenderLevel(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_PARTICLES) {
         if (!NeoShadowsEngine.isShadowCapturePass()) {
            Minecraft mc = Minecraft.getInstance();
            ReflectionSystem reflect = get();
            reflect.setEnabled(Config.isFeatureEnabled(Config.CLIENT.reflectionEnabled));
            if (reflect.recursionDepth == 0) {
               reflect.updateProbe(mc);
            }

            if (reflect.isEnabled()) {
               reflect.renderPlanar(mc, event.getPoseStack(), mc.renderBuffers().bufferSource());
            }
         }
      }
   }

   public static boolean isRenderingWorldPass() {
      return INSTANCE.recursionDepth > 0;
   }

   @Nullable
   public static Vec3 getActiveRenderCameraPos() {
      return INSTANCE.activeRenderCameraPos;
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public void setEnabled(boolean enabled) {
      if (this.enabled != enabled) {
         this.enabled = enabled;
         if (enabled) {
            this.forceImmediateUpdate();
            this.bootstrapNearbyFaces(Minecraft.getInstance());
         } else {
            this.activePlanes.clear();

            for (ReflectionSystem.PlaneRenderCache cache : this.planeRenderCaches) {
               cache.invalidate();
               cache.closeMesh();
               cache.lastPlaneIdentity = Long.MIN_VALUE;
            }

            this.frameContextReady = false;
         }

         Minecraft mc = Minecraft.getInstance();
         if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
         }
      }
   }

   public void markWorldDirty() {
      long now = System.currentTimeMillis();
      if (now - this.lastWorldDirtyMs >= 50L) {
         this.lastWorldDirtyMs = now;
         this.worldDirty = true;
      }
   }

   public void forceImmediateUpdate() {
      this.lastPlaneScanMs = 0L;
      this.lastPlaneScanBlockX = Integer.MIN_VALUE;
      this.lastPlaneScanBlockY = Integer.MIN_VALUE;
      this.lastPlaneScanBlockZ = Integer.MIN_VALUE;
      this.lastPlaneScanDistance = Integer.MIN_VALUE;
      this.worldDirty = true;
   }

   private void bootstrapNearbyFaces(Minecraft mc) {
      if (mc.level != null && mc.player != null) {
         int horizontalRadius = Math.min(24, (int)Math.ceil(getMirrorScanRadius()));
         int verticalRadius = Math.min(24, horizontalRadius);
         ExposedFaceService.get().bootstrapAround(mc.level, mc.player.blockPosition(), horizontalRadius, verticalRadius);
         TorchRtxState.get().requestReflectiveSnapshotRefresh();
         this.worldDirty = true;
      }
   }

   public void onMainRenderLevel(
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
      Minecraft mc = Minecraft.getInstance();
      boolean configuredEnabled = Config.isFeatureEnabled(Config.CLIENT.reflectionEnabled);
      if (this.enabled != configuredEnabled) {
         this.setEnabled(configuredEnabled);
      }

      if (this.enabled && mc.level != null && mc.player != null && this.recursionDepth <= 0) {
         this.ensureInit(mc);
         this.frameViewMatrix.set(poseStack.last().pose());
         this.frameProjectionMatrix.set(projectionMatrix);
         this.framePartialTick = partialTick;
         this.frameFinishNano = finishNano;
         this.frameGameRenderer = gameRenderer;
         this.frameLightTexture = lightTexture;
         this.frameContextReady = true;
         Vec3 mainCameraPos = camera.getPosition();
         double cx = mainCameraPos.x;
         double cy = mainCameraPos.y;
         double cz = mainCameraPos.z;
         float yaw = camera.getYRot();
         float pitch = camera.getXRot();
         this.cameraMovedThisFrame = cx != this.lastCamX || cy != this.lastCamY || cz != this.lastCamZ || yaw != this.lastCamYaw || pitch != this.lastCamPitch;
         this.lastCamX = cx;
         this.lastCamY = cy;
         this.lastCamZ = cz;
         this.lastCamYaw = yaw;
         this.lastCamPitch = pitch;
         this.updateMirrorPlanes(mc, mainCameraPos);
         this.updatePlaneRenderCaches(mc, mainCameraPos);
      }
   }

   public void updateProbe(Minecraft mc) {
      if (this.enabled && mc.level != null && mc.player != null) {
         this.ensureInit(mc);
         long now = System.currentTimeMillis();
         if (now - this.lastProbeMs >= 1000L) {
            this.lastProbeMs = now;
            this.probeCenter = mc.player.blockPosition();
            this.fillProbeCross(mc);
         }
      }
   }

   public void renderPlanar(Minecraft mc, PoseStack mainPoseStack, MultiBufferSource buffers) {
      if (this.enabled && this.initialized && this.frameContextReady && !this.activePlanes.isEmpty()) {
         Vec3 cameraPos = this.recursionDepth > 0 && this.activeRenderCameraPos != null
            ? this.activeRenderCameraPos
            : mc.gameRenderer.getMainCamera().getPosition();
         Frustum currentFrustum = new Frustum(mainPoseStack.last().pose(), RenderSystem.getProjectionMatrix());
         currentFrustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);
         this.cachedOriginalProjection.set(RenderSystem.getProjectionMatrix());
         this.cachedOriginalInverseView.set(mainPoseStack.last().normal()).invert();
         RenderTarget originalTarget = mc.getMainRenderTarget();
         int planeCount = Math.min(this.activePlanes.size(), this.planeRenderCaches.size());

         for (int planeIndex = 0; planeIndex < planeCount; planeIndex++) {
            ReflectionSystem.MirrorPlane plane = this.activePlanes.get(planeIndex);
            if (plane.isFacingCamera(cameraPos) && currentFrustum.isVisible(plane.getCullingBounds())) {
               ReflectionSystem.PlaneRenderCache cache = this.planeRenderCaches.get(planeIndex);
               boolean nested = this.recursionDepth > 0;
               Matrix4f slotMirrorLinear;
               Matrix4f slotVirtualProj;
               Vec3 slotVirtualCamPos;
               int slotIndex;
               if (nested) {
                  if (!cache.feedValid || cache.feedVirtualCamPos == null) {
                     continue;
                  }

                  slotMirrorLinear = cache.feedMirrorLinear;
                  slotVirtualProj = cache.feedVirtualProj;
                  slotVirtualCamPos = cache.feedVirtualCamPos;
                  slotIndex = 1;
                  cache.activeUvScaleX = cache.feedUvScaleX;
                  cache.activeUvScaleY = cache.feedUvScaleY;
               } else {
                  if (!cache.exactValid || cache.exactVirtualCamPos == null) {
                     continue;
                  }

                  slotMirrorLinear = cache.exactMirrorLinear;
                  slotVirtualProj = cache.exactVirtualProj;
                  slotVirtualCamPos = cache.exactVirtualCamPos;
                  slotIndex = 0;
                  cache.activeUvScaleX = cache.exactUvScaleX;
                  cache.activeUvScaleY = cache.exactUvScaleY;
               }

               cache.textureRef.setGlId(cache.targets[slotIndex].getColorTextureId());
               RenderSystem.setProjectionMatrix(this.cachedOriginalProjection, VertexSorting.DISTANCE_TO_ORIGIN);
               RenderSystem.setInverseViewRotationMatrix(this.cachedOriginalInverseView);
               originalTarget.bindWrite(false);
               RenderSystem.viewport(
                  0,
                  0,
                  this.recursionDepth > 0 ? this.activePassViewportWidth : originalTarget.viewWidth,
                  this.recursionDepth > 0 ? this.activePassViewportHeight : originalTarget.viewHeight
               );
               this.drawPlane(mainPoseStack, plane, cache, slotMirrorLinear, slotVirtualProj, slotVirtualCamPos, cameraPos);
            }
         }

         RenderSystem.setProjectionMatrix(this.cachedOriginalProjection, VertexSorting.DISTANCE_TO_ORIGIN);
         RenderSystem.setInverseViewRotationMatrix(this.cachedOriginalInverseView);
         originalTarget.bindWrite(false);
         RenderSystem.viewport(
            0,
            0,
            this.recursionDepth > 0 ? this.activePassViewportWidth : originalTarget.viewWidth,
            this.recursionDepth > 0 ? this.activePassViewportHeight : originalTarget.viewHeight
         );
      }
   }

   private void updatePlaneRenderCaches(Minecraft mc, Vec3 mainCameraPos) {
      if (!this.activePlanes.isEmpty()) {
         int planeCount = Math.min(this.activePlanes.size(), this.planeRenderCaches.size());

         for (int i = 0; i < planeCount; i++) {
            ReflectionSystem.MirrorPlane plane = this.activePlanes.get(i);
            ReflectionSystem.PlaneRenderCache cache = this.planeRenderCaches.get(i);
            long planeIdentity = computePlaneIdentity(plane);
            if (cache.lastPlaneIdentity != planeIdentity) {
               cache.exactValid = false;
               cache.feedValid = false;
               cache.feedConsumerIdentity = Long.MIN_VALUE;
               cache.lastPlaneIdentity = planeIdentity;
            }

            cache.ensureMesh(plane, computePlaneSignature(plane));
         }

         Frustum playerFrustum = new Frustum(this.frameViewMatrix, this.frameProjectionMatrix);
         playerFrustum.prepare(mainCameraPos.x, mainCameraPos.y, mainCameraPos.z);
         RenderTarget originalTarget = mc.getMainRenderTarget();
         Vec3[] exactCamOf = new Vec3[planeCount];
         boolean[] playerSeesArr = new boolean[planeCount];

         for (int i = 0; i < planeCount; i++) {
            ReflectionSystem.MirrorPlane plane = this.activePlanes.get(i);
            double signedDist = mainCameraPos.subtract(plane.planePoint).dot(plane.normal);
            if (signedDist > 0.05) {
               exactCamOf[i] = reflectPoint(mainCameraPos, plane.planePoint, plane.normal);
               playerSeesArr[i] = playerFrustum.isVisible(plane.getCullingBounds());
            }
         }

         for (int ix = 0; ix < 4; ix++) {
            for (int j = 0; j < 4; j++) {
               this.feedDemand[ix][j] = false;
            }
         }

         for (int consumer = 0; consumer < planeCount; consumer++) {
            if (playerSeesArr[consumer] && exactCamOf[consumer] != null) {
               Matrix4f consumerView = new Matrix4f(this.frameViewMatrix).mul(axisReflectionMatrix(this.activePlanes.get(consumer).face.getAxis()));
               Frustum consumerFrustum = new Frustum(consumerView, this.frameProjectionMatrix);
               Vec3 consumerCamera = exactCamOf[consumer];
               consumerFrustum.prepare(consumerCamera.x, consumerCamera.y, consumerCamera.z);

               for (int producer = 0; producer < planeCount; producer++) {
                  if (producer != consumer) {
                     ReflectionSystem.MirrorPlane producerPlane = this.activePlanes.get(producer);
                     this.feedDemand[consumer][producer] = producerPlane.isFacingCamera(consumerCamera)
                        && consumerFrustum.isVisible(producerPlane.getCullingBounds());
                  }
               }
            }
         }

         for (int consumerx = 0; consumerx < planeCount; consumerx++) {
            for (int producerx = 0; producerx < planeCount; producerx++) {
               if (this.feedDemand[consumerx][producerx]) {
                  this.feedDemand[producerx][consumerx] = true;
               }
            }
         }

         if (planeCount > 1) {
            for (int ix = 0; ix < planeCount; ix++) {
               ReflectionSystem.MirrorPlane plane = this.activePlanes.get(ix);
               ReflectionSystem.PlaneRenderCache cache = this.planeRenderCaches.get(ix);
               Vec3 feedViewer = null;
               int feedConsumer = -1;

               for (int consumerx = 0; consumerx < planeCount; consumerx++) {
                  if (computePlaneIdentity(this.activePlanes.get(consumerx)) == cache.feedConsumerIdentity
                     && this.feedDemand[consumerx][ix]
                     && exactCamOf[consumerx] != null) {
                     feedViewer = exactCamOf[consumerx];
                     feedConsumer = consumerx;
                     break;
                  }
               }

               for (int consumerxx = 0; consumerxx < planeCount && feedViewer == null; consumerxx++) {
                  if (this.feedDemand[consumerxx][ix] && exactCamOf[consumerxx] != null) {
                     feedViewer = exactCamOf[consumerxx];
                     feedConsumer = consumerxx;
                  }
               }

               if (feedViewer != null
                  && this.computeFeedMatrices(plane, feedViewer, mainCameraPos, this.feedScratchMirrorLinear, this.feedScratchProj, this.feedScratchVirtCam)) {
                  cache.textureRef.setGlId(cache.targets[1].getColorTextureId());
                  this.activePassPlaneIndex = feedConsumer;
                  this.renderPlanePassEx(
                     mc,
                     cache.targets[1],
                     this.feedScratchVirtCam[0],
                     this.feedScratchMirrorLinear,
                     this.feedScratchProj,
                     this.feedScratchProj,
                     cache.targets[1].viewWidth,
                     cache.targets[1].viewHeight
                  );
                  cache.feedMirrorLinear.set(this.feedScratchMirrorLinear);
                  cache.feedVirtualProj.set(this.feedScratchProj);
                  cache.feedVirtualCamPos = this.feedScratchVirtCam[0];
                  cache.feedUvScaleX = 1.0F;
                  cache.feedUvScaleY = 1.0F;
                  cache.feedValid = true;
                  cache.feedConsumerIdentity = computePlaneIdentity(this.activePlanes.get(feedConsumer));
               } else {
                  cache.feedValid = false;
               }
            }
         } else {
            for (int ix = 0; ix < planeCount; ix++) {
               this.planeRenderCaches.get(ix).feedValid = false;
            }
         }

         for (int ix = 0; ix < planeCount; ix++) {
            ReflectionSystem.MirrorPlane plane = this.activePlanes.get(ix);
            ReflectionSystem.PlaneRenderCache cache = this.planeRenderCaches.get(ix);
            if (!playerSeesArr[ix]) {
               cache.exactValid = false;
            } else {
               Vec3 virtualCamPos = exactCamOf[ix];
               Matrix4f mirrorLinear = new Matrix4f(this.frameViewMatrix).mul(axisReflectionMatrix(plane.face.getAxis()));
               Vector4f clipPlaneVS = worldPlaneToViewSpace(mirrorLinear, virtualCamPos, plane.normal, plane.planePoint);
               Matrix4f virtualProj = applyObliqueNearClip(new Matrix4f(this.frameProjectionMatrix), clipPlaneVS);
               ReflectionSystem.CropRect crop = computeCropRect(
                  plane, mainCameraPos, this.frameViewMatrix, this.frameProjectionMatrix, cache.targets[0].viewWidth, cache.targets[0].viewHeight
               );
               if (crop == null) {
                  cache.exactValid = false;
               } else {
                  Matrix4f croppedProj = applyProjectionCrop(virtualProj, crop);
                  cache.textureRef.setGlId(cache.targets[0].getColorTextureId());
                  this.activePassPlaneIndex = ix;
                  this.renderPlanePassEx(mc, cache.targets[0], virtualCamPos, mirrorLinear, croppedProj, croppedProj, crop.width, crop.height);
                  cache.exactMirrorLinear.set(mirrorLinear);
                  cache.exactVirtualProj.set(croppedProj);
                  cache.exactVirtualCamPos = virtualCamPos;
                  cache.exactUvScaleX = (float)crop.width / (float)cache.targets[0].viewWidth;
                  cache.exactUvScaleY = (float)crop.height / (float)cache.targets[0].viewHeight;
                  cache.exactValid = true;
               }
            }
         }

         this.activePassPlaneIndex = -1;
         originalTarget.bindWrite(true);
         this.worldDirty = false;
      }
   }

   @Nullable
   private static ReflectionSystem.CropRect computeCropRect(
      ReflectionSystem.MirrorPlane plane, Vec3 cameraPos, Matrix4f view, Matrix4f projection, int targetWidth, int targetHeight
   ) {
      if (plane.face.getAxis() == Axis.Y) {
         return new ReflectionSystem.CropRect(0, 0, targetWidth, targetHeight, targetWidth, targetHeight);
      } else {
         double planeDistance = Math.abs(cameraPos.subtract(plane.planePoint).dot(plane.normal));
         if (planeDistance < 2.5) {
            return new ReflectionSystem.CropRect(0, 0, targetWidth, targetHeight, targetWidth, targetHeight);
         } else {
            Matrix4f viewProjection = new Matrix4f(projection).mul(view);
            Vector3f corner = new Vector3f();
            Vector4f clip = new Vector4f();
            float minX = 1.0F;
            float maxX = -1.0F;
            float minY = 1.0F;
            float maxY = -1.0F;
            boolean projected = false;
            boolean crossesNearPlane = false;

            for (int i = 0; i < 4; i++) {
               plane.getRectCorner(i, corner);
               clip.set(corner.x - (float)cameraPos.x, corner.y - (float)cameraPos.y, corner.z - (float)cameraPos.z, 1.0F);
               viewProjection.transform(clip);
               if (clip.w <= 1.0E-4F) {
                  crossesNearPlane = true;
               } else {
                  float ndcX = Mth.clamp(clip.x / clip.w, -1.0F, 1.0F);
                  float ndcY = Mth.clamp(clip.y / clip.w, -1.0F, 1.0F);
                  minX = Math.min(minX, ndcX);
                  maxX = Math.max(maxX, ndcX);
                  minY = Math.min(minY, ndcY);
                  maxY = Math.max(maxY, ndcY);
                  projected = true;
               }
            }

            AABB bounds = plane.getBounds();

            for (int xi = 0; xi < 2; xi++) {
               for (int yi = 0; yi < 2; yi++) {
                  for (int zi = 0; zi < 2; zi++) {
                     clip.set(
                        (float)(xi == 0 ? bounds.minX : bounds.maxX) - (float)cameraPos.x,
                        (float)(yi == 0 ? bounds.minY : bounds.maxY) - (float)cameraPos.y,
                        (float)(zi == 0 ? bounds.minZ : bounds.maxZ) - (float)cameraPos.z,
                        1.0F
                     );
                     viewProjection.transform(clip);
                     if (clip.w <= 1.0E-4F) {
                        crossesNearPlane = true;
                     } else {
                        float ndcX = Mth.clamp(clip.x / clip.w, -1.0F, 1.0F);
                        float ndcY = Mth.clamp(clip.y / clip.w, -1.0F, 1.0F);
                        minX = Math.min(minX, ndcX);
                        maxX = Math.max(maxX, ndcX);
                        minY = Math.min(minY, ndcY);
                        maxY = Math.max(maxY, ndcY);
                        projected = true;
                     }
                  }
               }
            }

            if (!projected || maxX <= minX || maxY <= minY) {
               return null;
            } else if (crossesNearPlane) {
               return new ReflectionSystem.CropRect(0, 0, targetWidth, targetHeight, targetWidth, targetHeight);
            } else {
               float marginX = Math.max(0.04F, (maxX - minX) * 0.12F);
               float marginY = Math.max(0.04F, (maxY - minY) * 0.12F);
               minX = Math.max(-1.0F, minX - marginX);
               maxX = Math.min(1.0F, maxX + marginX);
               minY = Math.max(-1.0F, minY - marginY);
               maxY = Math.min(1.0F, maxY + marginY);
               int x = Math.max(0, Mth.floor((minX * 0.5F + 0.5F) * (float)targetWidth) - 8);
               int y = Math.max(0, Mth.floor((minY * 0.5F + 0.5F) * (float)targetHeight) - 8);
               int right = Math.min(targetWidth, Mth.ceil((maxX * 0.5F + 0.5F) * (float)targetWidth) + 8);
               int top = Math.min(targetHeight, Mth.ceil((maxY * 0.5F + 0.5F) * (float)targetHeight) + 8);
               return right > x && top > y ? new ReflectionSystem.CropRect(x, y, right - x, top - y, targetWidth, targetHeight) : null;
            }
         }
      }
   }

   private static Matrix4f applyProjectionCrop(Matrix4f projection, ReflectionSystem.CropRect crop) {
      float left = (float)crop.x / (float)crop.targetWidth * 2.0F - 1.0F;
      float right = (float)(crop.x + crop.width) / (float)crop.targetWidth * 2.0F - 1.0F;
      float bottom = (float)crop.y / (float)crop.targetHeight * 2.0F - 1.0F;
      float top = (float)(crop.y + crop.height) / (float)crop.targetHeight * 2.0F - 1.0F;
      float scaleX = 2.0F / (right - left);
      float scaleY = 2.0F / (top - bottom);
      float offsetX = -(right + left) / (right - left);
      float offsetY = -(top + bottom) / (top - bottom);
      Matrix4f cropMatrix = new Matrix4f().identity();
      cropMatrix.m00(scaleX);
      cropMatrix.m11(scaleY);
      cropMatrix.m30(offsetX);
      cropMatrix.m31(offsetY);
      return cropMatrix.mul(new Matrix4f(projection));
   }

   private boolean computeFeedMatrices(
      ReflectionSystem.MirrorPlane plane, Vec3 viewerPos, Vec3 mainCameraPos, Matrix4f outMirrorLinear, Matrix4f outProj, Vec3[] outVirtCam
   ) {
      double signedDist = viewerPos.subtract(plane.planePoint).dot(plane.normal);
      if (signedDist < 0.02) {
         return false;
      } else {
         Vec3 virtualCamPos = reflectPoint(viewerPos, plane.planePoint, plane.normal);
         Vector3f dir = new Vector3f((float)(-plane.normal.x), (float)(-plane.normal.y), (float)(-plane.normal.z));
         Vector3f up = plane.face.getAxis() == Axis.Y ? new Vector3f(0.0F, 0.0F, 1.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
         Matrix4f lookRot = new Matrix4f().setLookAlong(dir, up);
         outMirrorLinear.set(lookRot).mul(axisReflectionMatrix(plane.face.getAxis()));
         float minX = Float.POSITIVE_INFINITY;
         float maxX = Float.NEGATIVE_INFINITY;
         float minY = Float.POSITIVE_INFINITY;
         float maxY = Float.NEGATIVE_INFINITY;
         Vector3f corner = new Vector3f();

         for (int k = 0; k < 4; k++) {
            plane.getFeedRectCorner(k, mainCameraPos, 24.0F, corner);
            corner.sub((float)virtualCamPos.x, (float)virtualCamPos.y, (float)virtualCamPos.z);
            outMirrorLinear.transformPosition(corner);
            minX = Math.min(minX, corner.x);
            maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y);
            maxY = Math.max(maxY, corner.y);
         }

         float near = (float)signedDist;
         float margin = 0.03F * Math.max(maxX - minX, maxY - minY) + 0.05F;
         minX -= margin;
         maxX += margin;
         minY -= margin;
         maxY += margin;
         if (!(maxX - minX < 1.0E-4F) && !(maxY - minY < 1.0E-4F)) {
            outProj.setFrustum(minX, maxX, minY, maxY, near, 1536.0F);
            outVirtCam[0] = virtualCamPos;
            return true;
         } else {
            return false;
         }
      }
   }

   public float sampleProbeGloss(BlockPos pos, Vec3 normal) {
      if (this.enabled && this.probeCenter != null) {
         if (pos.distSqr(this.probeCenter) > 1024.0) {
            return 0.0F;
         } else {
            Vec3 view = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            Vec3 dir = new Vec3((double)pos.getX() + 0.5 - view.x, (double)pos.getY() + 0.5 - view.y, (double)pos.getZ() + 0.5 - view.z).normalize();
            double ndv = Math.max(0.0, normal.normalize().dot(dir));
            return (float)(Math.pow(1.0 - ndv, 4.0) * 0.35);
         }
      } else {
         return 0.0F;
      }
   }

   public void renderGlossOverlay(Minecraft mc, PoseStack ps, MultiBufferSource buffers, Iterable<BlockPos> glossyBlocks) {
      if (this.enabled && this.initialized && this.probeRenderType != null) {
         VertexConsumer vc = buffers.getBuffer(this.probeRenderType);
         Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();

         for (BlockPos bp : glossyBlocks) {
            float a = this.sampleProbeGloss(bp, new Vec3(0.0, 1.0, 0.0));
            if (!(a <= 0.01F)) {
               float bx = (float)((double)bp.getX() - cam.x);
               float by = (float)((double)bp.getY() + 1.001 - cam.y);
               float bz = (float)((double)bp.getZ() - cam.z);
               reflectVertex(ps, vc, bx, by, bz, 0.0F, 0.0F, a);
               reflectVertex(ps, vc, bx + 1.0F, by, bz, 1.0F, 0.0F, a);
               reflectVertex(ps, vc, bx + 1.0F, by, bz + 1.0F, 1.0F, 1.0F, a);
               reflectVertex(ps, vc, bx, by, bz + 1.0F, 0.0F, 1.0F, a);
            }
         }
      }
   }

   private void ensureInit(Minecraft mc) {
      int targetWidth = getReflectionTargetSize(mc.getWindow().getWidth());
      int targetHeight = getReflectionTargetSize(mc.getWindow().getHeight());
      boolean bilinearFiltering = (Boolean)Config.CLIENT.reflectionBilinearFiltering.get();
      if (this.initialized) {
         for (ReflectionSystem.PlaneRenderCache cache : this.planeRenderCaches) {
            boolean sizeChanged = cache.targets[0].width != targetWidth || cache.targets[0].height != targetHeight;
            if (sizeChanged) {
               cache.targets[0].resize(targetWidth, targetHeight, Minecraft.ON_OSX);
               cache.targets[1].resize(targetWidth, targetHeight, Minecraft.ON_OSX);
               cache.invalidate();
               cache.textureRef.setGlId(cache.targets[0].getColorTextureId());
            }

            if (sizeChanged || this.bilinearFilteringEnabled != bilinearFiltering) {
               configureReflectionTexture(cache.targets[0], bilinearFiltering);
               configureReflectionTexture(cache.targets[1], bilinearFiltering);
            }
         }

         this.bilinearFilteringEnabled = bilinearFiltering;
      } else {
         this.initialized = true;
         this.bilinearFilteringEnabled = bilinearFiltering;

         for (int i = 0; i < 4; i++) {
            RenderTarget[] targets = new RenderTarget[2];

            for (int d = 0; d < targets.length; d++) {
               targets[d] = new RenderTarget(true) {
               };
               targets[d].createBuffers(targetWidth, targetHeight, Minecraft.ON_OSX);
               targets[d].setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
               targets[d].clear(Minecraft.ON_OSX);
               configureReflectionTexture(targets[d], bilinearFiltering);
            }

            ReflectionSystem.GlTextureRef textureRef = new ReflectionSystem.GlTextureRef(targets[0].getColorTextureId());
            ResourceLocation textureLocation = ResourceLocation.fromNamespaceAndPath("luxium", "dynamic/planar_reflect_" + i);
            mc.getTextureManager().register(textureLocation, textureRef);
            this.planeRenderCaches
               .add(
                  new ReflectionSystem.PlaneRenderCache(
                     targets,
                     textureRef,
                     buildReflectType(textureLocation),
                     buildPolishedAndesiteReflectType(textureLocation),
                     buildMetalReflectType(textureLocation)
                  )
               );
         }

         this.probeTexture = new DynamicTexture(192, 128, true);
         this.probeLoc = ResourceLocation.fromNamespaceAndPath("luxium", "dynamic/probe");
         mc.getTextureManager().register(this.probeLoc, this.probeTexture);
         this.probeRenderType = buildReflectType(this.probeLoc);
      }
   }

   private static int getReflectionTargetSize(int fullSize) {
      double scale = Mth.clamp((Double)Config.CLIENT.reflectionRenderScale.get(), 0.1, 1.0);
      return Math.max(1, Mth.ceil((float)((double)fullSize * scale)));
   }

   private static void configureReflectionTexture(RenderTarget target, boolean bilinearFiltering) {
      RenderSystem.bindTexture(target.getColorTextureId());
      int filter = bilinearFiltering ? 9729 : 9728;
      GlStateManager._texParameter(3553, 10241, filter);
      GlStateManager._texParameter(3553, 10240, filter);
      GlStateManager._texParameter(3553, 10242, 33071);
      GlStateManager._texParameter(3553, 10243, 33071);
      RenderSystem.bindTexture(0);
   }

   private void updateMirrorPlanes(Minecraft mc, Vec3 cameraPos) {
      long now = System.currentTimeMillis();
      long scanIntervalMs = getPlaneScanUpdateMs();
      BlockPos base = BlockPos.containing(cameraPos);
      int configuredDistance = (Integer)Config.CLIENT.reflectionDistance.get();
      boolean activeAreaMoved = base.getX() != this.lastPlaneScanBlockX
         || base.getY() != this.lastPlaneScanBlockY
         || base.getZ() != this.lastPlaneScanBlockZ
         || configuredDistance != this.lastPlaneScanDistance;
      if (activeAreaMoved || now - this.lastPlaneScanMs >= scanIntervalMs) {
         this.lastPlaneScanMs = now;
         this.lastPlaneScanBlockX = base.getX();
         this.lastPlaneScanBlockY = base.getY();
         this.lastPlaneScanBlockZ = base.getZ();
         this.lastPlaneScanDistance = configuredDistance;
         Map<ReflectionSystem.PlaneKey, ReflectionSystem.MirrorPlane> planes = new HashMap<>();
         Map<Long, long[]> snapshot = TorchRtxState.get().getReflectiveSnapshotView();
         ExposedFaceService exposedFaceService = ExposedFaceService.get();
         RainPuddleManager rainPuddleManager = RainPuddleManager.get();
         if (snapshot.isEmpty()) {
            this.activePlanes.clear();
         } else {
            int centerChunkX = base.getX() >> 4;
            int centerChunkZ = base.getZ() >> 4;
            double mirrorScanRadius = getMirrorScanRadius();
            double faceInclusionRadius = mirrorScanRadius + 0.875;
            double faceInclusionRadiusSq = faceInclusionRadius * faceInclusionRadius;
            int chunkRadius = Math.max(1, (int)Math.ceil(mirrorScanRadius / 16.0)) + 1;

            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
               for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                  long[] bucket = snapshot.get(ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz));
                  if (bucket != null) {
                     for (long blockKey : bucket) {
                        BlockPos pos = BlockPos.of(blockKey);
                        BlockState state = mc.level.getBlockState(pos);
                        ReflectionMaterial material = ReflectionMaterialRegistry.find(state);
                        if (material != null) {
                           byte exposedMask = exposedFaceService.getExposedFaceMask(mc.level, blockKey);
                           if (exposedMask != 0) {
                              for (Direction face : Direction.values()) {
                                 if ((exposedMask & 1 << face.ordinal()) != 0
                                    && !(faceCenter(pos, face).distanceToSqr(cameraPos) > faceInclusionRadiusSq)
                                    && material.shouldReflectFace(mc, pos, state, face)) {
                                    ReflectionSystem.PlaneKey key = ReflectionSystem.PlaneKey.of(pos, face);
                                    ReflectionSystem.MirrorPlane plane = planes.computeIfAbsent(
                                       key, k -> new ReflectionSystem.MirrorPlane(face, planePointForFace(pos, face))
                                    );
                                    boolean usePolishedShader = state.is(Blocks.POLISHED_ANDESITE);
                                    boolean useMetalShader = state.is(Blocks.IRON_BLOCK);
                                    RainPuddleSurface rainPuddleSurface = face == Direction.UP ? rainPuddleManager.getActiveSurface(mc.level, pos) : null;
                                    plane.addFace(pos, face, material, usePolishedShader, useMetalShader, cameraPos, rainPuddleSurface);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            this.activePlanes.clear();
            if (!planes.isEmpty()) {
               List<ReflectionSystem.MirrorPlane> sorted = new ArrayList<>(planes.values());
               sorted.sort((a, b) -> {
                  int byFaceCount = Integer.compare(b.faces.size(), a.faces.size());
                  if (byFaceCount != 0) {
                     return byFaceCount;
                  } else {
                     int byDistance = Double.compare(a.minDistanceSq, b.minDistanceSq);
                     if (byDistance != 0) {
                        return byDistance;
                     } else {
                        int byFace = Integer.compare(a.face.get3DDataValue(), b.face.get3DDataValue());
                        return byFace != 0 ? byFace : Double.compare(planeCoordinateValue(a), planeCoordinateValue(b));
                     }
                  }
               });
               if (sorted.size() > 4) {
                  sorted = new ArrayList<>(sorted.subList(0, 4));
               }

               sorted.sort((a, b) -> Long.compare(computePlaneIdentity(a), computePlaneIdentity(b)));
               this.activePlanes.addAll(sorted);
            }
         }
      }
   }

   private static double getMirrorScanRadius() {
      return (double)Mth.clamp((Integer)Config.CLIENT.reflectionDistance.get(), 5, 200);
   }

   private static double computeScaledDistanceMul() {
      double distanceScale = 32.0 / getMirrorScanRadius();
      return distanceScale * distanceScale;
   }

   private static long getPlaneScanUpdateMs() {
      int value = (Integer)Config.CLIENT.reflectionScanIntervalMs.get();
      if (value < 5) {
         value = 5;
      }

      if (value > 5000) {
         value = 5000;
      }

      return (long)value;
   }

   private void renderPlanePassEx(
      Minecraft mc,
      RenderTarget targetToBind,
      Vec3 virtualCamPos,
      Matrix4f mirrorLinear,
      Matrix4f virtualProj,
      Matrix4f cullProj,
      int viewportWidth,
      int viewportHeight
   ) {
      PoseStack mirrorPose = new PoseStack();
      mirrorPose.last().pose().set(mirrorLinear);
      mirrorPose.last().normal().set(new Matrix3f(mirrorLinear));
      this.configureReflectionCamera(mc, virtualCamPos);
      flushSharedRenderBuffers(mc);
      targetToBind.bindWrite(false);
      RenderSystem.viewport(0, 0, viewportWidth, viewportHeight);
      RenderSystem.enableScissor(0, 0, viewportWidth, viewportHeight);
      GL30.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
      GL30.glClear(16640);
      MinecraftAccessor mcAccessor = (MinecraftAccessor)mc;
      RenderTarget oldMainTargetField = mcAccessor.luxium$getMainRenderTargetField();
      mcAccessor.luxium$setMainRenderTargetField(targetToBind);
      float savedFogStart = RenderSystem.getShaderFogStart();
      float savedFogEnd = RenderSystem.getShaderFogEnd();
      float[] savedFogColorArr = RenderSystem.getShaderFogColor();
      Vector4f savedFogColor = new Vector4f(savedFogColorArr[0], savedFogColorArr[1], savedFogColorArr[2], savedFogColorArr[3]);
      FogShape savedFogShape = RenderSystem.getShaderFogShape();
      float[] savedShaderColorArr = RenderSystem.getShaderColor();
      Vector4f savedShaderColor = new Vector4f(savedShaderColorArr[0], savedShaderColorArr[1], savedShaderColorArr[2], savedShaderColorArr[3]);
      int savedShaderTex0 = RenderSystem.getShaderTexture(0);
      int savedShaderTex1 = RenderSystem.getShaderTexture(1);
      int savedShaderTex2 = RenderSystem.getShaderTexture(2);
      Vec3 oldActiveCamera = this.activeRenderCameraPos;
      int oldRecursionDepth = this.recursionDepth;
      int oldViewportWidth = this.activePassViewportWidth;
      int oldViewportHeight = this.activePassViewportHeight;
      this.activeRenderCameraPos = virtualCamPos;
      this.recursionDepth = 1;
      this.activePassViewportWidth = viewportWidth;
      this.activePassViewportHeight = viewportHeight;

      try {
         RenderSystem.setProjectionMatrix(virtualProj, VertexSorting.DISTANCE_TO_ORIGIN);
         RenderSystem.setInverseViewRotationMatrix(new Matrix3f(mirrorPose.last().normal()).invert());
         RenderSystem.enableCull();
         GL30.glCullFace(1028);
         mc.levelRenderer.prepareCullFrustum(mirrorPose, virtualCamPos, new Matrix4f(cullProj));
         this.renderReflectionWorld(mc, mirrorPose, virtualProj);
         flushSharedRenderBuffers(mc);
         flushSharedRenderBuffers(mc);
         mc.getProfiler().pop();
      } finally {
         RenderSystem.disableScissor();
         mcAccessor.luxium$setMainRenderTargetField(oldMainTargetField);
         GL30.glCullFace(1029);
         GlStateManager._depthMask(true);
         GlStateManager._colorMask(true, true, true, true);
         RenderSystem.disableBlend();
         RenderSystem.defaultBlendFunc();
         RenderSystem.depthFunc(515);
         RenderSystem.setShaderFogStart(savedFogStart);
         RenderSystem.setShaderFogEnd(savedFogEnd);
         RenderSystem.setShaderFogColor(savedFogColor.x, savedFogColor.y, savedFogColor.z, savedFogColor.w);
         RenderSystem.setShaderFogShape(savedFogShape);
         RenderSystem.setShaderColor(savedShaderColor.x, savedShaderColor.y, savedShaderColor.z, savedShaderColor.w);
         RenderSystem.setShaderTexture(0, savedShaderTex0);
         RenderSystem.setShaderTexture(1, savedShaderTex1);
         RenderSystem.setShaderTexture(2, savedShaderTex2);
         RenderSystem.setProjectionMatrix(new Matrix4f(this.frameProjectionMatrix), VertexSorting.DISTANCE_TO_ORIGIN);
         RenderSystem.setInverseViewRotationMatrix(new Matrix3f(this.frameViewMatrix).invert());
         PoseStack restored = new PoseStack();
         restored.last().pose().set(this.frameViewMatrix);
         mc.levelRenderer.prepareCullFrustum(restored, mc.gameRenderer.getMainCamera().getPosition(), this.frameProjectionMatrix);
         this.recursionDepth = oldRecursionDepth;
         this.activeRenderCameraPos = oldActiveCamera;
         this.activePassViewportWidth = oldViewportWidth;
         this.activePassViewportHeight = oldViewportHeight;
      }
   }

   private void renderReflectionWorld(Minecraft mc, PoseStack poseStack, Matrix4f projectionMatrix) {
      LevelRendererAccessor levelRenderer = (LevelRendererAccessor)mc.levelRenderer;
      Frustum frustum = levelRenderer.luxium$getCullingFrustum();
      levelRenderer.luxium$setupRender(this.reflectionCamera, frustum, false, true);
      levelRenderer.luxium$compileChunks(this.reflectionCamera);
      Vec3 cameraPos = this.reflectionCamera.getPosition();
      double cameraX = cameraPos.x;
      double cameraY = cameraPos.y;
      double cameraZ = cameraPos.z;
      float renderDistance = this.frameGameRenderer.getRenderDistance();
      boolean foggy = mc.level.effects().isFoggyAt(Mth.floor(cameraX), Mth.floor(cameraZ)) || mc.gui.getBossOverlay().shouldCreateWorldFog();
      FogRenderer.setupColor(
         this.reflectionCamera,
         this.framePartialTick,
         mc.level,
         mc.options.getEffectiveRenderDistance(),
         this.frameGameRenderer.getDarkenWorldAmount(this.framePartialTick)
      );
      FogRenderer.levelFogColor();
      FogRenderer.setupFog(this.reflectionCamera, FogMode.FOG_SKY, renderDistance, foggy, this.framePartialTick);
      RenderSystem.setShader(GameRenderer::getPositionShader);
      mc.levelRenderer
         .renderSky(
            poseStack,
            projectionMatrix,
            this.framePartialTick,
            this.reflectionCamera,
            foggy,
            () -> FogRenderer.setupFog(this.reflectionCamera, FogMode.FOG_SKY, renderDistance, foggy, this.framePartialTick)
         );
      FogRenderer.setupFog(this.reflectionCamera, FogMode.FOG_TERRAIN, Math.max(renderDistance, 32.0F), foggy, this.framePartialTick);
      levelRenderer.luxium$renderChunkLayer(RenderType.solid(), poseStack, cameraX, cameraY, cameraZ, projectionMatrix);
      levelRenderer.luxium$renderChunkLayer(RenderType.cutoutMipped(), poseStack, cameraX, cameraY, cameraZ, projectionMatrix);
      levelRenderer.luxium$renderChunkLayer(RenderType.cutout(), poseStack, cameraX, cameraY, cameraZ, projectionMatrix);
      mc.getEntityRenderDispatcher().prepare(mc.level, this.reflectionCamera, mc.crosshairPickEntity);
      BufferSource buffers = mc.renderBuffers().bufferSource();

      for (Entity entity : mc.level.entitiesForRendering()) {
         if (mc.getEntityRenderDispatcher().shouldRender(entity, frustum, cameraX, cameraY, cameraZ)
            && (entity != this.reflectionCamera.getEntity() || this.reflectionCamera.isDetached())) {
            double x = Mth.lerp((double)this.framePartialTick, entity.xOld, entity.getX()) - cameraX;
            double y = Mth.lerp((double)this.framePartialTick, entity.yOld, entity.getY()) - cameraY;
            double z = Mth.lerp((double)this.framePartialTick, entity.zOld, entity.getZ()) - cameraZ;
            float yaw = Mth.lerp(this.framePartialTick, entity.yRotO, entity.getYRot());
            mc.getEntityRenderDispatcher()
               .render(
                  entity,
                  x,
                  y,
                  z,
                  yaw,
                  this.framePartialTick,
                  poseStack,
                  buffers,
                  mc.getEntityRenderDispatcher().getPackedLightCoords(entity, this.framePartialTick)
               );
         }
      }

      buffers.endBatch();
      levelRenderer.luxium$renderChunkLayer(RenderType.translucent(), poseStack, cameraX, cameraY, cameraZ, projectionMatrix);
      levelRenderer.luxium$renderChunkLayer(RenderType.tripwire(), poseStack, cameraX, cameraY, cameraZ, projectionMatrix);
      mc.particleEngine.render(poseStack, buffers, this.frameLightTexture, this.reflectionCamera, this.framePartialTick, frustum);
      buffers.endBatch();
      this.renderPlanar(mc, poseStack, buffers);
      if (mc.options.getCloudsType() != CloudStatus.OFF) {
         mc.levelRenderer.renderClouds(poseStack, projectionMatrix, this.framePartialTick, cameraX, cameraY, cameraZ);
      }
   }

   private static void flushSharedRenderBuffers(Minecraft mc) {
      mc.renderBuffers().bufferSource().endBatch();
      mc.renderBuffers().crumblingBufferSource().endBatch();
      mc.renderBuffers().outlineBufferSource().endOutlineBatch();
   }

   private void configureReflectionCamera(Minecraft mc, Vec3 reflectedCamPos) {
      Camera mainCamera = mc.gameRenderer.getMainCamera();
      this.reflectionCamera
         .setup(
            mc.level,
            (Entity)(mc.cameraEntity == null ? mc.player : mc.cameraEntity),
            !mc.options.getCameraType().isFirstPerson(),
            mc.options.getCameraType().isMirrored(),
            this.framePartialTick
         );
      CameraAccessor accessor = (CameraAccessor)this.reflectionCamera;
      accessor.luxium$setPosition(reflectedCamPos.x, reflectedCamPos.y, reflectedCamPos.z);
      accessor.luxium$setRotation(mainCamera.getYRot(), mainCamera.getXRot());
   }

   private void drawPlane(
      PoseStack mainPoseStack,
      ReflectionSystem.MirrorPlane plane,
      ReflectionSystem.PlaneRenderCache cache,
      Matrix4f mirrorLinear,
      Matrix4f virtualProj,
      Vec3 virtualCamPos,
      Vec3 cameraPos
   ) {
      ReflectionSystem.CachedPlaneMesh mesh = cache.mesh;
      if (mesh != null) {
         Vec3 origin = plane.meshOrigin;
         Matrix4f modelView = new Matrix4f(mainPoseStack.last().pose())
            .translate((float)(origin.x - cameraPos.x), (float)(origin.y - cameraPos.y), (float)(origin.z - cameraPos.z));
         Matrix4f virtualVP = new Matrix4f(virtualProj)
            .mul(mirrorLinear)
            .translate((float)(origin.x - virtualCamPos.x), (float)(origin.y - virtualCamPos.y), (float)(origin.z - virtualCamPos.z));
         Vec3 playerCameraPos = new Vec3(this.lastCamX, this.lastCamY, this.lastCamZ);
         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
         RenderSystem.enableDepthTest();
         RenderSystem.depthFunc(515);
         GlStateManager._depthMask(false);
         RenderSystem.disableCull();
         RenderSystem.setShaderTexture(0, cache.textureRef.getId());
         RenderSystem.polygonOffset(-1.0F, -1.0F);
         RenderSystem.enablePolygonOffset();

         try {
            this.drawCachedMesh(
               mesh.defaultBuffer,
               ShaderManager.getMetalReflectionShader(),
               modelView,
               this.cachedOriginalProjection,
               virtualVP,
               plane,
               cache,
               cameraPos,
               playerCameraPos
            );
            this.drawCachedMesh(
               mesh.metalBuffer,
               ShaderManager.getMetalReflectionShader(),
               modelView,
               this.cachedOriginalProjection,
               virtualVP,
               plane,
               cache,
               cameraPos,
               playerCameraPos
            );
            this.drawCachedMesh(
               mesh.polishedBuffer,
               ShaderManager.getPolishedAndesiteShader(),
               modelView,
               this.cachedOriginalProjection,
               virtualVP,
               plane,
               cache,
               cameraPos,
               playerCameraPos
            );
         } finally {
            VertexBuffer.unbind();
            RenderSystem.polygonOffset(0.0F, 0.0F);
            RenderSystem.disablePolygonOffset();
            GlStateManager._depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
         }
      }
   }

   private void drawCachedMesh(
      @Nullable VertexBuffer buffer,
      @Nullable ShaderInstance shader,
      Matrix4f modelView,
      Matrix4f projection,
      Matrix4f virtualVP,
      ReflectionSystem.MirrorPlane plane,
      ReflectionSystem.PlaneRenderCache cache,
      Vec3 cameraPos,
      Vec3 playerCameraPos
   ) {
      if (buffer != null && shader != null && !buffer.isInvalid()) {
         setMatrixUniform(shader, "VirtualVPMat", virtualVP);
         setVector3Uniform(
            shader,
            "CameraOffset",
            (float)(plane.meshOrigin.x - cameraPos.x),
            (float)(plane.meshOrigin.y - cameraPos.y),
            (float)(plane.meshOrigin.z - cameraPos.z)
         );
         setVector3Uniform(
            shader,
            "PlayerOffset",
            (float)(plane.meshOrigin.x - playerCameraPos.x),
            (float)(plane.meshOrigin.y - playerCameraPos.y),
            (float)(plane.meshOrigin.z - playerCameraPos.z)
         );
         setVector3Uniform(shader, "FaceNormal", (float)plane.normal.x, (float)plane.normal.y, (float)plane.normal.z);
         setFloatUniform(shader, "DistanceScale", (float)Math.sqrt(computeScaledDistanceMul()));
         setFloatUniform(shader, "ReflectionDistance", (float)getMirrorScanRadius());
         setFloatUniform(shader, "NestedPass", this.recursionDepth > 0 ? 1.0F : 0.0F);
         setVector2Uniform(shader, "TextureUvScale", cache.activeUvScaleX, cache.activeUvScaleY);
         buffer.bind();
         buffer.drawWithShader(modelView, projection, shader);
      }
   }

   private static void setMatrixUniform(ShaderInstance shader, String name, Matrix4f value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void setVector3Uniform(ShaderInstance shader, String name, float x, float y, float z) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y, z);
      }
   }

   private static void setVector2Uniform(ShaderInstance shader, String name, float x, float y) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y);
      }
   }

   private static void setFloatUniform(ShaderInstance shader, String name, float value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static long computePlaneSignature(ReflectionSystem.MirrorPlane plane) {
      if (plane.cachedSignature != Long.MIN_VALUE) {
         return plane.cachedSignature;
      } else {
         long h = 31L * (long)plane.face.get3DDataValue() + Double.doubleToLongBits(planeCoordinateValue(plane));

         for (ReflectionSystem.MirrorFace face : plane.faces) {
            h = h * 31L + face.blockPos.asLong();
            h = h * 31L + (long)face.material.getClass().getName().hashCode();

            for (int i = 0; i < 4; i++) {
               h = h * 31L + (long)Float.floatToIntBits(face.warpedU[i]);
               h = h * 31L + (long)Float.floatToIntBits(face.warpedV[i]);
            }

            if (face.rainPuddleSurface != null) {
               h = h * 31L + (long)Float.floatToIntBits(face.rainPuddleSurface.sample(face.blockPos, 0.0F, 0.0F));
               h = h * 31L + (long)Float.floatToIntBits(face.rainPuddleSurface.sample(face.blockPos, 1.0F, 0.0F));
               h = h * 31L + (long)Float.floatToIntBits(face.rainPuddleSurface.sample(face.blockPos, 1.0F, 1.0F));
               h = h * 31L + (long)Float.floatToIntBits(face.rainPuddleSurface.sample(face.blockPos, 0.0F, 1.0F));
            }
         }

         plane.cachedSignature = h;
         return plane.cachedSignature;
      }
   }

   private static long computePlaneIdentity(ReflectionSystem.MirrorPlane plane) {
      long coordinate = Double.doubleToLongBits(planeCoordinateValue(plane));
      return coordinate ^ (long)plane.face.get3DDataValue() << 56;
   }

   private static double planeCoordinateValue(ReflectionSystem.MirrorPlane plane) {
      return switch (plane.face.getAxis()) {
         case X -> plane.planePoint.x;
         case Y -> plane.planePoint.y;
         case Z -> plane.planePoint.z;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static float sampleMaskedAlpha(float baseAlpha, @Nullable RainPuddleSurface rainPuddleSurface, BlockPos pos, float faceU, float faceV) {
      return rainPuddleSurface == null ? baseAlpha : baseAlpha * rainPuddleSurface.sample(pos, faceU, faceV);
   }

   private static double facePointX(BlockPos pos, Direction face, float s) {
      double minX = (double)pos.getX();
      double maxX = minX + 1.0;
      double e = 0.0015F;

      return switch (face) {
         case UP, DOWN, SOUTH -> minX + (double)s;
         case NORTH -> maxX - (double)s;
         case WEST -> minX - e;
         case EAST -> maxX + e;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static double facePointY(BlockPos pos, Direction face, float t) {
      double minY = (double)pos.getY();
      double maxY = minY + 1.0;
      double e = 0.0015F;

      return switch (face) {
         case UP -> maxY + e;
         case DOWN -> minY - e;
         default -> minY + (double)t;
      };
   }

   private static double facePointZ(BlockPos pos, Direction face, float s, float t) {
      double minZ = (double)pos.getZ();
      double maxZ = minZ + 1.0;
      double e = 0.0015F;

      return switch (face) {
         case UP -> minZ + (double)t;
         case DOWN -> maxZ - (double)t;
         case SOUTH -> maxZ + e;
         case NORTH -> minZ - e;
         case WEST -> maxZ - (double)s;
         case EAST -> minZ + (double)s;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static Vector4f worldPlaneToViewSpace(Matrix4f mirrorLinear, Vec3 virtualCamPos, Vec3 planeNormal, Vec3 planePoint) {
      Vector3f nv = new Vector3f((float)planeNormal.x, (float)planeNormal.y, (float)planeNormal.z);
      mirrorLinear.transformDirection(nv);
      float dx = (float)(planePoint.x - virtualCamPos.x);
      float dy = (float)(planePoint.y - virtualCamPos.y);
      float dz = (float)(planePoint.z - virtualCamPos.z);
      Vector3f vp = new Vector3f(dx, dy, dz);
      mirrorLinear.transformPosition(vp);
      float d = -(nv.x * vp.x + nv.y * vp.y + nv.z * vp.z);
      return new Vector4f(nv.x, nv.y, nv.z, d);
   }

   private static Matrix4f applyObliqueNearClip(Matrix4f proj, Vector4f c) {
      float qx = (Math.signum(c.x) + proj.m20()) / proj.m00();
      float qy = (Math.signum(c.y) + proj.m21()) / proj.m11();
      float qz = -1.0F;
      float qw = (1.0F + proj.m22()) / proj.m32();
      float dot = c.x * qx + c.y * qy + c.z * qz + c.w * qw;
      if (Math.abs(dot) < 1.0E-4F) {
         return proj;
      } else {
         float d = 2.0F / dot;
         Matrix4f result = new Matrix4f(proj);
         result.m02(c.x * d);
         result.m12(c.y * d);
         result.m22(c.z * d + 1.0F);
         result.m32(c.w * d);
         return Float.isFinite(result.m02())
               && Float.isFinite(result.m12())
               && Float.isFinite(result.m22())
               && Float.isFinite(result.m32())
               && !(Math.abs(result.m22()) > 50.0F)
            ? result
            : proj;
      }
   }

   private static Matrix4f axisReflectionMatrix(Axis axis) {
      return switch (axis) {
         case X -> new Matrix4f().scaling(-1.0F, 1.0F, 1.0F);
         case Y -> new Matrix4f().scaling(1.0F, -1.0F, 1.0F);
         case Z -> new Matrix4f().scaling(1.0F, 1.0F, -1.0F);
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static Vec3 reflectPoint(Vec3 point, Vec3 planePoint, Vec3 planeNormal) {
      double dist = point.subtract(planePoint).dot(planeNormal);
      return point.subtract(planeNormal.scale(dist * 2.0));
   }

   private static Vec3 planePointForFace(BlockPos pos, Direction face) {
      return switch (face) {
         case UP -> new Vec3((double)pos.getX(), (double)pos.getY() + 1.0, (double)pos.getZ());
         case DOWN -> new Vec3((double)pos.getX(), (double)pos.getY(), (double)pos.getZ());
         case SOUTH -> new Vec3((double)pos.getX(), (double)pos.getY(), (double)pos.getZ() + 1.0);
         case NORTH -> new Vec3((double)pos.getX(), (double)pos.getY(), (double)pos.getZ());
         case WEST -> new Vec3((double)pos.getX(), (double)pos.getY(), (double)pos.getZ());
         case EAST -> new Vec3((double)pos.getX() + 1.0, (double)pos.getY(), (double)pos.getZ());
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static Vec3 faceCenter(BlockPos pos, Direction face) {
      return Vec3.atLowerCornerOf(pos).add(0.5 + (double)face.getStepX() * 0.5, 0.5 + (double)face.getStepY() * 0.5, 0.5 + (double)face.getStepZ() * 0.5);
   }

   private void fillProbeCross(Minecraft mc) {
      NativeImage img = this.probeTexture.getPixels();
      if (img != null) {
         Vec3 skyVec = mc.level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(), 1.0F);
         float skyR = (float)skyVec.x;
         float skyG = (float)skyVec.y;
         float skyB = (float)skyVec.z;
         float fogR = skyR * 0.78F;
         float fogG = skyG * 0.8F;
         float fogB = Math.min(1.0F, skyB * 0.88F);
         float gndR = 0.17F;
         float gndG = 0.13F;
         float gndB = 0.07F;
         float[][] faces = new float[][]{
            {0.0F, 0.0F, fogR, fogG, fogB},
            {1.0F, 0.0F, skyR, skyG, skyB},
            {2.0F, 0.0F, fogR, fogG, fogB},
            {0.0F, 1.0F, fogR, fogG, fogB},
            {1.0F, 1.0F, gndR, gndG, gndB},
            {2.0F, 1.0F, fogR, fogG, fogB}
         };
         int f = 64;

         for (float[] face : faces) {
            int col = (int)face[0];
            int row = (int)face[1];
            float fr = face[2];
            float fg = face[3];
            float fb = face[4];

            for (int py = 0; py < f; py++) {
               for (int px = 0; px < f; px++) {
                  float dx = ((float)px - (float)f * 0.5F) / (float)f;
                  float dy = ((float)py - (float)f * 0.5F) / (float)f;
                  float vig = Math.max(0.75F, 1.0F - 0.9F * (dx * dx + dy * dy) * 4.0F);
                  img.setPixelRGBA(col * f + px, row * f + py, packARGB(clamp(fr * vig), clamp(fg * vig), clamp(fb * vig), 220));
               }
            }
         }

         boxBlur(img, 3);
         this.probeTexture.upload();
      }
   }

   private static RenderType buildReflectType(ResourceLocation texture) {
      CompositeState state = CompositeState.builder()
         .setShaderState(new ShaderStateShard(ShaderManager::getMetalReflectionShader))
         .setTextureState(new TextureStateShard(texture, false, false))
         .setTransparencyState(ReflectionSystem.RenderHelper.TRANSLUCENT)
         .setDepthTestState(ReflectionSystem.RenderHelper.LEQUAL)
         .setCullState(ReflectionSystem.RenderHelper.NO_CULL)
         .setLightmapState(ReflectionSystem.RenderHelper.LIGHTMAP_ON)
         .setWriteMaskState(ReflectionSystem.RenderHelper.COLOR_WRITE)
         .createCompositeState(false);
      return RenderType.create("luxium_reflect", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, Mode.QUADS, 256, false, true, state);
   }

   private static RenderType buildPolishedAndesiteReflectType(ResourceLocation texture) {
      CompositeState state = CompositeState.builder()
         .setShaderState(new ShaderStateShard(ShaderManager::getPolishedAndesiteShader))
         .setTextureState(new TextureStateShard(texture, false, false))
         .setTransparencyState(ReflectionSystem.RenderHelper.TRANSLUCENT)
         .setDepthTestState(ReflectionSystem.RenderHelper.LEQUAL)
         .setCullState(ReflectionSystem.RenderHelper.NO_CULL)
         .setLightmapState(ReflectionSystem.RenderHelper.LIGHTMAP_ON)
         .setWriteMaskState(ReflectionSystem.RenderHelper.COLOR_WRITE)
         .createCompositeState(false);
      return RenderType.create("luxium_reflect_polished_andesite", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, Mode.QUADS, 256, false, true, state);
   }

   private static RenderType buildMetalReflectType(ResourceLocation texture) {
      CompositeState state = CompositeState.builder()
         .setShaderState(new ShaderStateShard(ShaderManager::getMetalReflectionShader))
         .setTextureState(new TextureStateShard(texture, false, false))
         .setTransparencyState(ReflectionSystem.RenderHelper.TRANSLUCENT)
         .setDepthTestState(ReflectionSystem.RenderHelper.LEQUAL)
         .setCullState(ReflectionSystem.RenderHelper.NO_CULL)
         .setLightmapState(ReflectionSystem.RenderHelper.LIGHTMAP_ON)
         .setWriteMaskState(ReflectionSystem.RenderHelper.COLOR_WRITE)
         .createCompositeState(false);
      return RenderType.create("luxium_reflect_metal", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, Mode.QUADS, 256, false, true, state);
   }

   private static void reflectVertex(PoseStack ps, VertexConsumer vc, float x, float y, float z, float u, float v, float alpha) {
      vc.vertex(ps.last().pose(), x, y, z).color(1.0F, 1.0F, 1.0F, alpha).uv(u, v).uv2(15728880).endVertex();
   }

   private static int packARGB(int r, int g, int b, int a) {
      return a << 24 | r << 16 | g << 8 | b;
   }

   private static int clamp(float v) {
      return Math.max(0, Math.min(255, (int)(v * 255.0F)));
   }

   private static void boxBlur(NativeImage img, int radius) {
      int w = img.getWidth();
      int h = img.getHeight();
      int[] src = new int[w * h];

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            src[y * w + x] = img.getPixelRGBA(x, y);
         }
      }

      int[] horizontal = new int[w * h];

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            long ra = 0L;
            long ga = 0L;
            long ba = 0L;
            long aa = 0L;
            long n = 0L;

            for (int dx = -radius; dx <= radius; dx++) {
               int xx = x + dx;
               if (xx >= 0 && xx < w) {
                  int px = src[y * w + xx];
                  aa += (long)(px >>> 24 & 0xFF);
                  ra += (long)(px >>> 16 & 0xFF);
                  ga += (long)(px >>> 8 & 0xFF);
                  ba += (long)(px & 0xFF);
                  n++;
               }
            }

            horizontal[y * w + x] = (int)(aa / n) << 24 | (int)(ra / n) << 16 | (int)(ga / n) << 8 | (int)(ba / n);
         }
      }

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            long ra = 0L;
            long ga = 0L;
            long ba = 0L;
            long aa = 0L;
            long n = 0L;

            for (int dy = -radius; dy <= radius; dy++) {
               int yy = y + dy;
               if (yy >= 0 && yy < h) {
                  int px = horizontal[yy * w + x];
                  aa += (long)(px >>> 24 & 0xFF);
                  ra += (long)(px >>> 16 & 0xFF);
                  ga += (long)(px >>> 8 & 0xFF);
                  ba += (long)(px & 0xFF);
                  n++;
               }
            }

            img.setPixelRGBA(x, y, (int)(aa / n) << 24 | (int)(ra / n) << 16 | (int)(ga / n) << 8 | (int)(ba / n));
         }
      }
   }

   private static final class CachedPlaneMesh implements AutoCloseable {
      @Nullable
      final VertexBuffer defaultBuffer;
      @Nullable
      final VertexBuffer polishedBuffer;
      @Nullable
      final VertexBuffer metalBuffer;

      CachedPlaneMesh(ReflectionSystem.MirrorPlane plane) {
         this.defaultBuffer = buildBuffer(plane, 0);
         this.polishedBuffer = buildBuffer(plane, 1);
         this.metalBuffer = buildBuffer(plane, 2);
      }

      @Nullable
      private static VertexBuffer buildBuffer(ReflectionSystem.MirrorPlane plane, int category) {
         int count = 0;

         for (ReflectionSystem.MirrorFace face : plane.faces) {
            if (categoryOf(face) == category) {
               count++;
            }
         }

         if (count == 0) {
            return null;
         } else {
            BufferBuilder builder = new BufferBuilder(Math.max(256, count * 128));
            builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

            for (ReflectionSystem.MirrorFace facex : plane.faces) {
               if (categoryOf(facex) == category) {
                  emitFace(builder, plane, facex);
               }
            }

            VertexBuffer buffer = new VertexBuffer(Usage.STATIC);
            buffer.bind();
            buffer.upload(builder.end());
            VertexBuffer.unbind();
            return buffer;
         }
      }

      private static void emitFace(BufferBuilder builder, ReflectionSystem.MirrorPlane plane, ReflectionSystem.MirrorFace face) {
         for (int vertex = 0; vertex < 4; vertex++) {
            float u = vertex != 0 && vertex != 3 ? 1.0F : 0.0F;
            float v = vertex <= 1 ? 0.0F : 1.0F;
            float mask = ReflectionSystem.sampleMaskedAlpha(1.0F, face.rainPuddleSurface, face.blockPos, u, v);
            emitMeshVertex(
               builder,
               plane,
               face,
               ReflectionSystem.facePointX(face.blockPos, face.face, u),
               ReflectionSystem.facePointY(face.blockPos, face.face, v),
               ReflectionSystem.facePointZ(face.blockPos, face.face, u, v),
               face.warpedU[vertex],
               face.warpedV[vertex],
               mask
            );
         }
      }

      private static void emitMeshVertex(
         BufferBuilder builder,
         ReflectionSystem.MirrorPlane plane,
         ReflectionSystem.MirrorFace face,
         double x,
         double y,
         double z,
         float u,
         float v,
         float mask
      ) {
         Vec3 origin = plane.meshOrigin;
         builder.vertex(x - origin.x, y - origin.y, z - origin.z)
            .color(face.material.getBaseAlpha(), face.material.getMaxDistance() / 64.0F, mask, categoryOf(face) == 0 ? 0.5F : 1.0F)
            .uv(u, v)
            .uv2(15728880)
            .endVertex();
      }

      private static int categoryOf(ReflectionSystem.MirrorFace face) {
         if (face.usePolishedShader) {
            return 1;
         } else {
            return face.useMetalShader ? 2 : 0;
         }
      }

      @Override
      public void close() {
         closeBuffer(this.defaultBuffer);
         closeBuffer(this.polishedBuffer);
         closeBuffer(this.metalBuffer);
      }

      private static void closeBuffer(@Nullable VertexBuffer buffer) {
         if (buffer != null) {
            buffer.close();
         }
      }
   }

   private static record CropRect(int x, int y, int width, int height, int targetWidth, int targetHeight) {
   }

   static final class GlTextureRef extends AbstractTexture {
      GlTextureRef(int glId) {
         this.id = glId;
      }

      void setGlId(int glId) {
         this.id = glId;
      }

      public void load(ResourceManager manager) throws IOException {
      }
   }

   private static final class MirrorFace {
      final BlockPos blockPos;
      final Direction face;
      final ReflectionMaterial material;
      final boolean usePolishedShader;
      final boolean useMetalShader;
      @Nullable
      final RainPuddleSurface rainPuddleSurface;
      final float[] warpedU = new float[4];
      final float[] warpedV = new float[4];

      MirrorFace(
         BlockPos p,
         Direction f,
         ReflectionMaterial material,
         boolean usePolishedShader,
         boolean useMetalShader,
         @Nullable RainPuddleSurface rainPuddleSurface,
         Vec3 cameraPos
      ) {
         this.blockPos = p.immutable();
         this.face = f;
         this.material = material;
         this.usePolishedShader = usePolishedShader;
         this.useMetalShader = useMetalShader;
         this.rainPuddleSurface = rainPuddleSurface;

         for (int vertex = 0; vertex < 4; vertex++) {
            float u = vertex != 0 && vertex != 3 ? 1.0F : 0.0F;
            float v = vertex <= 1 ? 0.0F : 1.0F;
            float worldX = (float)ReflectionSystem.facePointX(p, f, u);
            float worldY = (float)ReflectionSystem.facePointY(p, f, v);
            float worldZ = (float)ReflectionSystem.facePointZ(p, f, u, v);
            this.warpedU[vertex] = material.warpU(u, v, worldX, worldY, worldZ, p, f, vertex, cameraPos);
            this.warpedV[vertex] = material.warpV(u, v, worldX, worldY, worldZ, p, f, vertex, cameraPos);
         }
      }
   }

   private static final class MirrorPlane {
      final Direction face;
      final Vec3 normal;
      final Vec3 planePoint;
      final List<ReflectionSystem.MirrorFace> faces = new ArrayList<>();
      final Vec3 meshOrigin;
      double minDistanceSq = Double.MAX_VALUE;
      int minAlongU = Integer.MAX_VALUE;
      int minAlongV = Integer.MAX_VALUE;
      int maxAlongU = Integer.MIN_VALUE;
      int maxAlongV = Integer.MIN_VALUE;
      long cachedSignature = Long.MIN_VALUE;

      MirrorPlane(Direction face, Vec3 planePoint) {
         this.face = face;
         this.normal = Vec3.atLowerCornerOf(face.getNormal());
         this.planePoint = planePoint;
         this.meshOrigin = planePoint;
      }

      void addFace(
         BlockPos pos,
         Direction face,
         ReflectionMaterial material,
         boolean usePolishedShader,
         boolean useMetalShader,
         Vec3 cameraPos,
         @Nullable RainPuddleSurface rainPuddleSurface
      ) {
         this.faces.add(new ReflectionSystem.MirrorFace(pos, face, material, usePolishedShader, useMetalShader, rainPuddleSurface, cameraPos));
         this.minDistanceSq = Math.min(this.minDistanceSq, ReflectionSystem.faceCenter(pos, face).distanceToSqr(cameraPos));
         int alongU = this.coordinateAlongU(pos);
         int alongV = this.coordinateAlongV(pos);
         this.minAlongU = Math.min(this.minAlongU, alongU);
         this.minAlongV = Math.min(this.minAlongV, alongV);
         this.maxAlongU = Math.max(this.maxAlongU, alongU + 1);
         this.maxAlongV = Math.max(this.maxAlongV, alongV + 1);
      }

      boolean isFacingCamera(Vec3 cameraPos) {
         return cameraPos.subtract(this.planePoint).dot(this.normal) > 0.05;
      }

      AABB getBounds() {
         double minU = (double)this.minAlongU;
         double maxU = (double)this.maxAlongU;
         double minV = (double)this.minAlongV;
         double maxV = (double)this.maxAlongV;

         return switch (this.face) {
            case UP, DOWN -> new AABB(minU, this.planePoint.y - 0.1, minV, maxU, this.planePoint.y + 0.1, maxV);
            case SOUTH, NORTH -> new AABB(minU, minV, this.planePoint.z - 0.1, maxU, maxV, this.planePoint.z + 0.1);
            case WEST, EAST -> new AABB(this.planePoint.x - 0.1, minV, minU, this.planePoint.x + 0.1, maxV, maxU);
            default -> throw new IncompatibleClassChangeError();
         };
      }

      AABB getCullingBounds() {
         return this.getBounds().inflate(0.5);
      }

      void getRectCorner(int k, Vector3f out) {
         float u = k != 0 && k != 3 ? (float)this.maxAlongU : (float)this.minAlongU;
         float v = k <= 1 ? (float)this.minAlongV : (float)this.maxAlongV;
         switch (this.face) {
            case UP:
            case DOWN:
               out.set(u, (float)this.planePoint.y, v);
               break;
            case SOUTH:
            case NORTH:
               out.set(u, v, (float)this.planePoint.z);
               break;
            case WEST:
            case EAST:
               out.set((float)this.planePoint.x, v, u);
         }
      }

      void getFeedRectCorner(int k, Vec3 cameraPos, float halfExtent, Vector3f out) {
         float signU = k != 0 && k != 3 ? 1.0F : -1.0F;
         float signV = k <= 1 ? -1.0F : 1.0F;
         switch (this.face) {
            case UP:
            case DOWN: {
               float centerU = (float)Mth.clamp(cameraPos.x, (double)this.minAlongU, (double)this.maxAlongU);
               float centerV = (float)Mth.clamp(cameraPos.z, (double)this.minAlongV, (double)this.maxAlongV);
               out.set(centerU + signU * halfExtent, (float)this.planePoint.y, centerV + signV * halfExtent);
               break;
            }
            case SOUTH:
            case NORTH: {
               float centerU = (float)Mth.clamp(cameraPos.x, (double)this.minAlongU, (double)this.maxAlongU);
               float centerV = (float)Mth.clamp(cameraPos.y, (double)this.minAlongV, (double)this.maxAlongV);
               out.set(centerU + signU * halfExtent, centerV + signV * halfExtent, (float)this.planePoint.z);
               break;
            }
            case WEST:
            case EAST: {
               float centerU = (float)Mth.clamp(cameraPos.z, (double)this.minAlongU, (double)this.maxAlongU);
               float centerV = (float)Mth.clamp(cameraPos.y, (double)this.minAlongV, (double)this.maxAlongV);
               out.set((float)this.planePoint.x, centerV + signV * halfExtent, centerU + signU * halfExtent);
            }
         }
      }

      int coordinateAlongU(BlockPos pos) {
         return switch (this.face) {
            case UP, DOWN, SOUTH -> pos.getX();
            case NORTH -> pos.getX();
            case WEST, EAST -> pos.getZ();
            default -> throw new IncompatibleClassChangeError();
         };
      }

      int coordinateAlongV(BlockPos pos) {
         return switch (this.face) {
            case UP, DOWN -> pos.getZ();
            case SOUTH, NORTH, WEST, EAST -> pos.getY();
            default -> throw new IncompatibleClassChangeError();
         };
      }
   }

   private static final class PlaneKey {
      final Direction face;
      final int coordinate;

      private PlaneKey(Direction face, int coordinate) {
         this.face = face;
         this.coordinate = coordinate;
      }

      static ReflectionSystem.PlaneKey of(BlockPos pos, Direction face) {
         int coord = switch (face) {
            case UP -> pos.getY() + 1;
            case DOWN -> pos.getY();
            case SOUTH -> pos.getZ() + 1;
            case NORTH -> pos.getZ();
            case WEST -> pos.getX();
            case EAST -> pos.getX() + 1;
            default -> throw new IncompatibleClassChangeError();
         };
         return new ReflectionSystem.PlaneKey(face, coord);
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         } else {
            return !(o instanceof ReflectionSystem.PlaneKey k) ? false : this.face == k.face && this.coordinate == k.coordinate;
         }
      }

      @Override
      public int hashCode() {
         return 31 * this.face.hashCode() + this.coordinate;
      }
   }

   private static final class PlaneRenderCache {
      final RenderTarget[] targets;
      final ReflectionSystem.GlTextureRef textureRef;
      final RenderType reflectRenderType;
      final RenderType polishedAndesiteReflectRenderType;
      final RenderType metalReflectRenderType;
      final Matrix4f exactMirrorLinear = new Matrix4f();
      final Matrix4f exactVirtualProj = new Matrix4f();
      @Nullable
      Vec3 exactVirtualCamPos;
      boolean exactValid = false;
      final Matrix4f feedMirrorLinear = new Matrix4f();
      final Matrix4f feedVirtualProj = new Matrix4f();
      @Nullable
      Vec3 feedVirtualCamPos;
      boolean feedValid = false;
      long feedConsumerIdentity = Long.MIN_VALUE;
      float exactUvScaleX = 1.0F;
      float exactUvScaleY = 1.0F;
      float feedUvScaleX = 1.0F;
      float feedUvScaleY = 1.0F;
      float activeUvScaleX = 1.0F;
      float activeUvScaleY = 1.0F;
      @Nullable
      ReflectionSystem.CachedPlaneMesh mesh;
      long meshSignature = Long.MIN_VALUE;
      long lastPlaneIdentity = Long.MIN_VALUE;

      PlaneRenderCache(
         RenderTarget[] targets,
         ReflectionSystem.GlTextureRef textureRef,
         RenderType reflectRenderType,
         RenderType polishedAndesiteReflectRenderType,
         RenderType metalReflectRenderType
      ) {
         this.targets = targets;
         this.textureRef = textureRef;
         this.reflectRenderType = reflectRenderType;
         this.polishedAndesiteReflectRenderType = polishedAndesiteReflectRenderType;
         this.metalReflectRenderType = metalReflectRenderType;
      }

      void invalidate() {
         this.exactValid = false;
         this.exactVirtualCamPos = null;
         this.feedValid = false;
         this.feedVirtualCamPos = null;
      }

      void ensureMesh(ReflectionSystem.MirrorPlane plane, long signature) {
         if (this.mesh == null || this.meshSignature != signature) {
            if (this.mesh != null) {
               this.mesh.close();
            }

            this.mesh = new ReflectionSystem.CachedPlaneMesh(plane);
            this.meshSignature = signature;
         }
      }

      void closeMesh() {
         if (this.mesh != null) {
            this.mesh.close();
            this.mesh = null;
         }

         this.meshSignature = Long.MIN_VALUE;
      }
   }

   private static final class RenderHelper extends RenderStateShard {
      static final TransparencyStateShard TRANSLUCENT = TRANSLUCENT_TRANSPARENCY;
      static final DepthTestStateShard LEQUAL = LEQUAL_DEPTH_TEST;
      static final CullStateShard NO_CULL = RenderStateShard.NO_CULL;
      static final LightmapStateShard LIGHTMAP_ON = LIGHTMAP;
      static final WriteMaskStateShard COLOR_WRITE = RenderStateShard.COLOR_WRITE;

      private RenderHelper() {
         super("loh_helper", () -> {
         }, () -> {
         });
      }
   }
}
