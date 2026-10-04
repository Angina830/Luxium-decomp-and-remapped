package com.vinlanx.luxium.client.shadows.neoskycelestia;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class NeoSkyCascade implements AutoCloseable {
   private static final float MIN_NEAR_PLANE = 0.25F;
   private static final float MIN_FRONT_PADDING = 2.0F;
   private final String name;
   private final NeoSkyDepthFramebuffer target = new NeoSkyDepthFramebuffer(true);
   private final Matrix4f lightViewRotation = new Matrix4f();
   private final Matrix4f lightProjection = new Matrix4f();
   private final Matrix4f lightFromMainView = new Matrix4f();
   private int resolution;
   private float radius;
   private float minimumCasterReach;
   private float projectionRadius;
   private float receiverGuardBand;
   private float casterEyeOffset;
   private float depthRange = 1.0F;
   private Vec3 center = Vec3.ZERO;
   private Vec3 eye = Vec3.ZERO;
   private Vec3 preparedMainCamera = Vec3.ZERO;
   private Vec3 toLight = new Vec3(0.0, 1.0, 0.0);
   private Vec3 lastBuiltMainCamera = new Vec3(Double.NaN, Double.NaN, Double.NaN);
   private Vec3 lastBuiltToLight = new Vec3(Double.NaN, Double.NaN, Double.NaN);
   private boolean dirty = true;
   private boolean ready;
   private long lastBuildNanos;
   private long generation;

   public NeoSkyCascade(String name) {
      this.name = name;
   }

   public void configure(int resolution, float radius, float minimumCasterReach) {
      int configuredResolution = Math.max(256, resolution);
      float configuredRadius = Math.max(8.0F, radius);
      float configuredMinimumCasterReach = Math.max(configuredRadius, minimumCasterReach);
      if (this.resolution != configuredResolution || this.radius != configuredRadius || this.minimumCasterReach != configuredMinimumCasterReach) {
         this.resolution = configuredResolution;
         this.radius = configuredRadius;
         this.minimumCasterReach = configuredMinimumCasterReach;
         this.target.resize(this.resolution);
         this.invalidate();
      }
   }

   public void markDirty() {
      this.dirty = true;
   }

   public void invalidate() {
      this.dirty = true;
      this.ready = false;
      this.lastBuiltMainCamera = new Vec3(Double.NaN, Double.NaN, Double.NaN);
      this.lastBuiltToLight = new Vec3(Double.NaN, Double.NaN, Double.NaN);
   }

   public boolean shouldBuild(
      Vec3 requestedCenter, Vec3 toLight, double movementThreshold, double angleThresholdDegrees, long minimumIntervalNanos, long nowNanos
   ) {
      if (!this.ready) {
         return true;
      } else if (nowNanos - this.lastBuildNanos < minimumIntervalNanos) {
         return false;
      } else if (this.dirty) {
         return true;
      } else if (this.lastBuiltMainCamera.distanceToSqr(requestedCenter) >= movementThreshold * movementThreshold) {
         return true;
      } else {
         double dot = Mth.clamp(this.lastBuiltToLight.dot(toLight), -1.0, 1.0);
         double angle = Math.toDegrees(Math.acos(dot));
         return angleThresholdDegrees <= 0.0 ? angle > 1.0E-5 : angle >= angleThresholdDegrees;
      }
   }

   public void prepareReceiver(Vec3 mainCamera, Vec3 normalizedToLight, Matrix4f sharedLightViewRotation) {
      this.preparedMainCamera = mainCamera;
      this.toLight = NeoSkyCelestiaMath.safeNormalize(normalizedToLight);
      this.lightViewRotation.set(sharedLightViewRotation);
      float baseWorldTexel = this.radius * 2.0F / (float)Math.max(1, this.resolution);
      this.receiverGuardBand = Math.max(1.0F, baseWorldTexel * 2.0F);
      this.projectionRadius = this.radius + this.receiverGuardBand;
      this.center = NeoSkyCelestiaMath.snapCenterToLightTexels(mainCamera, this.lightViewRotation, this.projectionRadius, this.resolution);
   }

   public void finalizeCasterVolume(float terrainCasterFront, float auxiliaryCasterFront) {
      float requiredFront = 0.0F;
      if (Float.isFinite(terrainCasterFront)) {
         requiredFront = Math.max(requiredFront, terrainCasterFront);
      }

      if (Float.isFinite(auxiliaryCasterFront)) {
         requiredFront = Math.max(requiredFront, auxiliaryCasterFront);
      }

      float frontPadding = Math.max(2.0F, this.receiverGuardBand + 0.5F);
      this.casterEyeOffset = Math.max(this.minimumCasterReach, Math.max(this.radius + frontPadding, requiredFront + frontPadding));
      this.eye = this.center;
      float near = -this.casterEyeOffset;
      float far = this.radius + this.receiverGuardBand + 1.0F;
      far = Math.max(0.25F, far);
      this.depthRange = far - near;
      this.lightProjection
         .set(new Matrix4f().ortho(-this.projectionRadius, this.projectionRadius, -this.projectionRadius, this.projectionRadius, near, far, false));
   }

   public void commitBuild(Vec3 toLight, long nowNanos) {
      this.lastBuiltMainCamera = this.preparedMainCamera;
      this.lastBuiltToLight = NeoSkyCelestiaMath.safeNormalize(toLight);
      this.lastBuildNanos = nowNanos;
      this.dirty = false;
      this.ready = true;
      this.generation++;
   }

   public void updateReceiverMatrix(Vec3 mainCamera, Matrix4f inverseMainViewRotation) {
      if (this.ready) {
         this.lightFromMainView
            .set(NeoSkyCelestiaMath.buildLightFromMainView(mainCamera, this.eye, this.lightViewRotation, this.lightProjection, inverseMainViewRotation));
      }
   }

   public boolean intersectsBlock(int x, int y, int z) {
      return !this.ready || this.potentialCasterIntersectsWorldAabb((double)x, (double)y, (double)z, (double)x + 1.0, (double)y + 1.0, (double)z + 1.0);
   }

   public boolean intersectsChunk(int chunkX, int chunkZ, int minY, int maxY) {
      if (!this.ready) {
         return true;
      } else {
         double minX = (double)(chunkX << 4);
         double minZ = (double)(chunkZ << 4);
         return this.potentialCasterIntersectsWorldAabb(minX, (double)minY, minZ, minX + 16.0, (double)maxY, minZ + 16.0);
      }
   }

   private boolean potentialCasterIntersectsWorldAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
      float lightMinX = Float.POSITIVE_INFINITY;
      float lightMinY = Float.POSITIVE_INFINITY;
      float lightMaxX = Float.NEGATIVE_INFINITY;
      float lightMaxY = Float.NEGATIVE_INFINITY;
      double maxAxial = Double.NEGATIVE_INFINITY;
      Vector3f corner = new Vector3f();

      for (int mask = 0; mask < 8; mask++) {
         double worldX = (mask & 1) == 0 ? minX : maxX;
         double worldY = (mask & 2) == 0 ? minY : maxY;
         double worldZ = (mask & 4) == 0 ? minZ : maxZ;
         double dx = worldX - this.center.x;
         double dy = worldY - this.center.y;
         double dz = worldZ - this.center.z;
         corner.set((float)dx, (float)dy, (float)dz);
         this.lightViewRotation.transformDirection(corner);
         lightMinX = Math.min(lightMinX, corner.x);
         lightMinY = Math.min(lightMinY, corner.y);
         lightMaxX = Math.max(lightMaxX, corner.x);
         lightMaxY = Math.max(lightMaxY, corner.y);
         maxAxial = Math.max(maxAxial, dx * this.toLight.x + dy * this.toLight.y + dz * this.toLight.z);
      }

      return !(lightMaxX < -this.projectionRadius)
            && !(lightMinX > this.projectionRadius)
            && !(lightMaxY < -this.projectionRadius)
            && !(lightMinY > this.projectionRadius)
         ? maxAxial >= (double)(-this.radius - this.receiverGuardBand)
         : false;
   }

   public String name() {
      return this.name;
   }

   public NeoSkyDepthFramebuffer target() {
      return this.target;
   }

   public int resolution() {
      return this.resolution;
   }

   public float radius() {
      return this.radius;
   }

   public float projectionRadius() {
      return this.projectionRadius;
   }

   public float receiverGuardBand() {
      return this.receiverGuardBand;
   }

   public float minimumCasterReach() {
      return this.minimumCasterReach;
   }

   public float casterEyeOffset() {
      return this.casterEyeOffset;
   }

   public float depthRange() {
      return this.depthRange;
   }

   public Vec3 center() {
      return this.center;
   }

   public Vec3 eye() {
      return this.eye;
   }

   public Vec3 toLight() {
      return this.toLight;
   }

   public Matrix4f lightViewRotation() {
      return this.lightViewRotation;
   }

   public Matrix4f lightProjection() {
      return this.lightProjection;
   }

   public Matrix4f lightFromMainView() {
      return this.lightFromMainView;
   }

   public boolean ready() {
      return this.ready;
   }

   public long generation() {
      return this.generation;
   }

   @Override
   public void close() {
      this.target.close();
   }
}
