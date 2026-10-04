package com.vinlanx.luxium.client.shadows.neoskycelestia;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.mixin.EntityRenderDispatcherAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class NeoSkyEntityShadowMap implements AutoCloseable {
   private final NeoSkyDepthFramebuffer target = new NeoSkyDepthFramebuffer();
   private final Matrix4f lightViewRotation = new Matrix4f();
   private final Matrix4f lightProjection = new Matrix4f();
   private final Matrix4f lightFromMainView = new Matrix4f();
   private Vec3 eye = Vec3.ZERO;
   private int resolution;
   private float radius;
   private long lastUpdateNanos;
   private boolean ready;
   private long generation;

   public void configure(int requestedResolution, float requestedRadius) {
      int newResolution = Mth.clamp(requestedResolution, 512, 2048);
      float newRadius = Mth.clamp(requestedRadius, 16.0F, 128.0F);
      if (this.resolution != newResolution || this.radius != newRadius) {
         this.resolution = newResolution;
         this.radius = newRadius;
         this.target.resize(newResolution);
         this.ready = false;
      }
   }

   public boolean shouldUpdate(long nowNanos) {
      int updateFps = (Integer)Config.CLIENT.skyEntityShadowUpdateFps.get();
      if (updateFps >= 61) {
         return true;
      } else {
         long interval = 1000000000L / (long)Mth.clamp(updateFps, 10, 60);
         return !this.ready || nowNanos - this.lastUpdateNanos >= interval;
      }
   }

   public void render(Minecraft mc, Vec3 center, Vec3 toLight, float partialTick, long nowNanos) {
      Matrix4f rotation = NeoSkyCelestiaMath.buildLightViewRotation(toLight);
      Vec3 snappedCenter = NeoSkyCelestiaMath.computeSnappedCenter(center, toLight, this.radius, this.resolution);
      float rayLength = (float)((Integer)Config.CLIENT.skyShadowRayLength.get()).intValue();
      this.eye = snappedCenter.add(toLight.scale((double)rayLength));
      this.lightViewRotation.set(rotation);
      this.lightProjection.set(new Matrix4f().ortho(-this.radius, this.radius, -this.radius, this.radius, 0.25F, rayLength + this.radius * 2.5F, false));
      Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
      VertexSorting previousSorting = RenderSystem.getVertexSorting();
      PoseStack modelViewStack = RenderSystem.getModelViewStack();
      EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
      boolean previousRenderShadow = ((EntityRenderDispatcherAccessor)dispatcher).luxium$shouldRenderShadow();
      boolean previousHitBoxes = dispatcher.shouldRenderHitBoxes();
      PoseStack poseStack = new PoseStack();
      poseStack.mulPoseMatrix(this.lightViewRotation);
      BufferSource buffers = MultiBufferSource.immediate(new BufferBuilder(262144));
      this.target.bindAndClear();
      NeoSkyCelestia.get().setEntityShadowPass(true);
      dispatcher.setRenderShadow(false);
      dispatcher.setRenderHitBoxes(false);
      dispatcher.prepare(mc.level, mc.gameRenderer.getMainCamera(), null);
      dispatcher.overrideCameraOrientation(new Quaternionf().setFromNormalized(new Matrix3f(this.lightViewRotation).invert()));
      modelViewStack.pushPose();
      modelViewStack.setIdentity();
      RenderSystem.applyModelViewMatrix();

      try {
         RenderSystem.setProjectionMatrix(this.lightProjection, VertexSorting.DISTANCE_TO_ORIGIN);
         GlStateManager._colorMask(false, false, false, false);
         GlStateManager._enableDepthTest();
         GlStateManager._depthMask(true);
         RenderSystem.depthFunc(513);
         RenderSystem.disableBlend();
         RenderSystem.polygonOffset(1.5F, 3.0F);
         RenderSystem.enablePolygonOffset();

         for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.isRemoved() && this.intersects(entity.getBoundingBoxForCulling().inflate(0.5))) {
               double x = Mth.lerp((double)partialTick, entity.xOld, entity.getX()) - this.eye.x;
               double y = Mth.lerp((double)partialTick, entity.yOld, entity.getY()) - this.eye.y;
               double z = Mth.lerp((double)partialTick, entity.zOld, entity.getZ()) - this.eye.z;
               float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
               dispatcher.render(entity, x, y, z, yaw, partialTick, poseStack, buffers, 15728880);
            }
         }

         buffers.endBatch();
         this.lastUpdateNanos = nowNanos;
         this.ready = true;
         this.generation++;
      } finally {
         NeoSkyCelestia.get().setEntityShadowPass(false);
         dispatcher.setRenderShadow(previousRenderShadow);
         dispatcher.setRenderHitBoxes(previousHitBoxes);
         dispatcher.prepare(mc.level, mc.gameRenderer.getMainCamera(), mc.crosshairPickEntity);
         modelViewStack.popPose();
         RenderSystem.applyModelViewMatrix();
         RenderSystem.polygonOffset(0.0F, 0.0F);
         RenderSystem.disablePolygonOffset();
         RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
         GlStateManager._colorMask(true, true, true, true);
         GlStateManager._depthMask(true);
         RenderSystem.depthFunc(515);
         RenderSystem.disableBlend();
         mc.getMainRenderTarget().bindWrite(true);
      }
   }

   public void updateReceiverMatrix(Vec3 mainCamera, Matrix4f inverseMainViewRotation) {
      if (this.ready) {
         this.lightFromMainView
            .set(NeoSkyCelestiaMath.buildLightFromMainView(mainCamera, this.eye, this.lightViewRotation, this.lightProjection, inverseMainViewRotation));
      }
   }

   private boolean intersects(AABB bounds) {
      float minX = Float.POSITIVE_INFINITY;
      float minY = Float.POSITIVE_INFINITY;
      float minZ = Float.POSITIVE_INFINITY;
      float maxX = Float.NEGATIVE_INFINITY;
      float maxY = Float.NEGATIVE_INFINITY;
      float maxZ = Float.NEGATIVE_INFINITY;
      Vector3f corner = new Vector3f();

      for (int mask = 0; mask < 8; mask++) {
         corner.set(
            (float)(((mask & 1) == 0 ? bounds.minX : bounds.maxX) - this.eye.x),
            (float)(((mask & 2) == 0 ? bounds.minY : bounds.maxY) - this.eye.y),
            (float)(((mask & 4) == 0 ? bounds.minZ : bounds.maxZ) - this.eye.z)
         );
         this.lightViewRotation.transformDirection(corner);
         minX = Math.min(minX, corner.x);
         minY = Math.min(minY, corner.y);
         minZ = Math.min(minZ, corner.z);
         maxX = Math.max(maxX, corner.x);
         maxY = Math.max(maxY, corner.y);
         maxZ = Math.max(maxZ, corner.z);
      }

      float far = (float)((Integer)Config.CLIENT.skyShadowRayLength.get()).intValue() + this.radius * 2.5F;
      return maxX >= -this.radius && minX <= this.radius && maxY >= -this.radius && minY <= this.radius && maxZ >= -far && minZ <= -0.25F;
   }

   public void invalidate() {
      this.ready = false;
      this.lastUpdateNanos = 0L;
   }

   public void bindTarget() {
      this.target.bind();
   }

   public boolean ready() {
      return this.ready;
   }

   public int depthTextureId() {
      return this.target.depthTextureId();
   }

   public Matrix4f lightFromMainView() {
      return this.lightFromMainView;
   }

   public float radius() {
      return this.radius;
   }

   public float texelSize() {
      return 1.0F / (float)Math.max(1, this.resolution);
   }

   public float baseBias() {
      return Math.max(3.5E-4F, this.radius * 2.0F / (float)this.resolution * 8.0E-4F);
   }

   public long generation() {
      return this.generation;
   }

   @Override
   public void close() {
      this.target.close();
      this.invalidate();
   }
}
