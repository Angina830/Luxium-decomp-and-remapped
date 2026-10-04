package com.vinlanx.luxium.client.sunmoonapi;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class CelestialPath {
   public static final float ORBIT_TILT_DEGREES = -40.0F;
   private static final float ORBIT_TILT_RADIANS = (float) (-Math.PI * 2.0 / 9.0);
   private static final double TILT_COS = Math.cos((float) (-Math.PI * 2.0 / 9.0));
   private static final double TILT_SIN = Math.sin((float) (-Math.PI * 2.0 / 9.0));

   private CelestialPath() {
   }

   public static CelestialPath.State sample(ClientLevel level, float partialTick) {
      float sunAngle = level.getSunAngle(partialTick);
      Vec3 sunDirection = sunDirection(sunAngle);
      Vec3 moonDirection = sunDirection.scale(-1.0);
      boolean usingMoon = sunDirection.y < 0.0;
      return new CelestialPath.State(sunAngle, sunDirection, moonDirection, usingMoon ? moonDirection : sunDirection, usingMoon);
   }

   public static Vec3 sunDirection(float sunAngle) {
      double cosine = (double)Mth.cos(sunAngle);
      return new Vec3((double)(-Mth.sin(sunAngle)), cosine * TILT_COS, -cosine * TILT_SIN);
   }

   public static void applyOrbitTilt(PoseStack poseStack) {
      poseStack.mulPose(Axis.XP.rotationDegrees(40.0F));
   }

   public static void applyOrbit(PoseStack poseStack, float sunAngle) {
      applyOrbitTilt(poseStack);
      poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
      poseStack.mulPose(Axis.XP.rotation(sunAngle));
   }

   public static record State(float sunAngle, Vec3 sunDirection, Vec3 moonDirection, Vec3 activeDirection, boolean usingMoon) {
      public Vec3 direction(boolean moon) {
         return moon ? this.moonDirection : this.sunDirection;
      }

      public double elevation(boolean moon) {
         return this.direction(moon).y;
      }
   }
}
