package com.vinlanx.luxium.client.shadows.neoskycelestia;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class NeoSkyCelestiaMath {
   private static final Matrix4f BIAS_MATRIX = new Matrix4f(0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.5F, 0.0F, 0.5F, 0.5F, 0.5F, 1.0F);

   private NeoSkyCelestiaMath() {
   }

   public static Matrix4f buildLightViewRotation(Vec3 normalizedToLight) {
      Vec3 lightForward = normalizedToLight.scale(-1.0);
      Vector3f forward = new Vector3f((float)lightForward.x, (float)lightForward.y, (float)lightForward.z);
      return new Matrix4f().lookAlong(forward, chooseStableUp(forward));
   }

   public static Vec3 computeSnappedCenter(Vec3 center, Vec3 toLight, float radius, int resolution) {
      Vec3 normalizedToLight = safeNormalize(toLight);
      Matrix4f rotation = buildLightViewRotation(normalizedToLight);
      return snapCenterToLightTexels(center, rotation, radius, resolution);
   }

   public static Matrix4f buildLightFromMainView(
      Vec3 mainCamera, Vec3 shadowEye, Matrix4f lightViewRotation, Matrix4f lightProjection, Matrix4f inverseMainViewRotation
   ) {
      Vector3f mainCameraToEye = new Vector3f((float)(mainCamera.x - shadowEye.x), (float)(mainCamera.y - shadowEye.y), (float)(mainCamera.z - shadowEye.z));
      return new Matrix4f(BIAS_MATRIX).mul(lightProjection).mul(lightViewRotation).translate(mainCameraToEye).mul(inverseMainViewRotation);
   }

   static Vec3 snapCenterToLightTexels(Vec3 center, Matrix4f lightRotation, float radius, int resolution) {
      float texelWorldSize = radius * 2.0F / (float)Math.max(1, resolution);
      double anchorX = Math.floor(center.x / 1024.0) * 1024.0;
      double anchorY = Math.floor(center.y / 1024.0) * 1024.0;
      double anchorZ = Math.floor(center.z / 1024.0) * 1024.0;
      Vector3f relative = new Vector3f((float)(center.x - anchorX), (float)(center.y - anchorY), (float)(center.z - anchorZ));
      lightRotation.transformDirection(relative);
      relative.x = (float)Math.round(relative.x / texelWorldSize) * texelWorldSize;
      relative.y = (float)Math.round(relative.y / texelWorldSize) * texelWorldSize;
      new Matrix4f(lightRotation).invert().transformDirection(relative);
      return new Vec3(anchorX + (double)relative.x, anchorY + (double)relative.y, anchorZ + (double)relative.z);
   }

   private static Vector3f chooseStableUp(Vector3f forward) {
      Vector3f worldUp = new Vector3f(0.0F, 1.0F, 0.0F);
      return Math.abs(forward.dot(worldUp)) > 0.94F ? new Vector3f(0.0F, 0.0F, 1.0F) : worldUp;
   }

   public static Vec3 safeNormalize(Vec3 vector) {
      double lengthSqr = vector.lengthSqr();
      return !(lengthSqr < 1.0E-12) && Double.isFinite(lengthSqr) ? vector.scale(1.0 / Math.sqrt(lengthSqr)) : new Vec3(0.0, 1.0, 0.0);
   }

   public static float horizonFade(Vec3 toLight) {
      double height = Mth.clamp(Math.abs(toLight.y) / 0.12, 0.0, 1.0);
      return (float)Mth.smoothstep(height);
   }
}
