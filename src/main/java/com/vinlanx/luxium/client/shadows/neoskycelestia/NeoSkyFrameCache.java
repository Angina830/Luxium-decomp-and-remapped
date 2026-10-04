package com.vinlanx.luxium.client.shadows.neoskycelestia;

import com.vinlanx.luxium.client.sunmoonapi.CelestialPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

public final class NeoSkyFrameCache {
   private static ClientLevel frameLevel;
   private static int framePartialBits;
   private static CelestialPath.State celestialPath;
   private static boolean celestialValid;
   private static NeoSkyCelestiaLighting.State lighting = NeoSkyCelestiaLighting.State.disabled();
   private static boolean lightingValid;
   private static final Matrix4f PROJECTION = new Matrix4f();
   private static final Matrix4f VIEW = new Matrix4f();
   private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();
   private static final Matrix4f INVERSE_VIEW = new Matrix4f();
   private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
   private static final Matrix4f INVERSE_VIEW_PROJECTION = new Matrix4f();
   private static boolean matricesValid;
   private static boolean inverseProjectionValid;
   private static boolean inverseViewValid;
   private static boolean viewProjectionValid;
   private static boolean inverseViewProjectionValid;
   private static long frameSerial;

   private NeoSkyFrameCache() {
   }

   public static void beginFrame(Minecraft mc, float partialTick, Matrix4f projection, Matrix4f view) {
      frameSerial++;
      frameLevel = mc.level;
      framePartialBits = Float.floatToIntBits(partialTick);
      celestialPath = null;
      celestialValid = false;
      lightingValid = false;
      PROJECTION.set(projection);
      VIEW.set(view);
      matricesValid = true;
      inverseProjectionValid = false;
      inverseViewValid = false;
      viewProjectionValid = false;
      inverseViewProjectionValid = false;
   }

   public static long frameSerial() {
      return frameSerial;
   }

   public static CelestialPath.State celestial(ClientLevel level, float partialTick) {
      if (matchesFrame(level, partialTick)) {
         if (!celestialValid) {
            celestialPath = CelestialPath.sample(level, partialTick);
            celestialValid = true;
         }

         return celestialPath;
      } else {
         return CelestialPath.sample(level, partialTick);
      }
   }

   public static NeoSkyCelestiaLighting.State lighting(ClientLevel level, float partialTick) {
      if (matchesFrame(level, partialTick)) {
         if (!lightingValid) {
            CelestialPath.State path = celestial(level, partialTick);
            lighting = NeoSkyCelestiaLighting.calculate(level, partialTick, path);
            lightingValid = true;
         }

         return lighting;
      } else {
         CelestialPath.State path = CelestialPath.sample(level, partialTick);
         return NeoSkyCelestiaLighting.calculate(level, partialTick, path);
      }
   }

   public static Matrix4f copyInverseProjection(Matrix4f source, Matrix4f destination) {
      if (matricesValid && same(source, PROJECTION)) {
         if (!inverseProjectionValid) {
            INVERSE_PROJECTION.set(PROJECTION).invert();
            inverseProjectionValid = true;
         }

         return destination.set(INVERSE_PROJECTION);
      } else {
         return destination.set(source).invert();
      }
   }

   public static Matrix4f copyInverseView(Matrix4f source, Matrix4f destination) {
      if (matricesValid && same(source, VIEW)) {
         if (!inverseViewValid) {
            INVERSE_VIEW.set(VIEW).invert();
            inverseViewValid = true;
         }

         return destination.set(INVERSE_VIEW);
      } else {
         return destination.set(source).invert();
      }
   }

   public static Matrix4f copyViewProjection(Matrix4f projection, Matrix4f view, Matrix4f destination) {
      if (matricesValid && same(projection, PROJECTION) && same(view, VIEW)) {
         ensureViewProjection();
         return destination.set(VIEW_PROJECTION);
      } else {
         return destination.set(projection).mul(view);
      }
   }

   public static Matrix4f copyInverseViewProjection(Matrix4f projection, Matrix4f view, Matrix4f destination) {
      if (matricesValid && same(projection, PROJECTION) && same(view, VIEW)) {
         if (!inverseViewProjectionValid) {
            ensureViewProjection();
            INVERSE_VIEW_PROJECTION.set(VIEW_PROJECTION).invert();
            inverseViewProjectionValid = true;
         }

         return destination.set(INVERSE_VIEW_PROJECTION);
      } else {
         return destination.set(projection).mul(view).invert();
      }
   }

   private static void ensureViewProjection() {
      if (!viewProjectionValid) {
         VIEW_PROJECTION.set(PROJECTION).mul(VIEW);
         viewProjectionValid = true;
      }
   }

   private static boolean matchesFrame(ClientLevel level, float partialTick) {
      return level != null && level == frameLevel && framePartialBits == Float.floatToIntBits(partialTick);
   }

   private static boolean same(Matrix4f a, Matrix4f b) {
      return Float.floatToIntBits(a.m00()) == Float.floatToIntBits(b.m00())
         && Float.floatToIntBits(a.m01()) == Float.floatToIntBits(b.m01())
         && Float.floatToIntBits(a.m02()) == Float.floatToIntBits(b.m02())
         && Float.floatToIntBits(a.m03()) == Float.floatToIntBits(b.m03())
         && Float.floatToIntBits(a.m10()) == Float.floatToIntBits(b.m10())
         && Float.floatToIntBits(a.m11()) == Float.floatToIntBits(b.m11())
         && Float.floatToIntBits(a.m12()) == Float.floatToIntBits(b.m12())
         && Float.floatToIntBits(a.m13()) == Float.floatToIntBits(b.m13())
         && Float.floatToIntBits(a.m20()) == Float.floatToIntBits(b.m20())
         && Float.floatToIntBits(a.m21()) == Float.floatToIntBits(b.m21())
         && Float.floatToIntBits(a.m22()) == Float.floatToIntBits(b.m22())
         && Float.floatToIntBits(a.m23()) == Float.floatToIntBits(b.m23())
         && Float.floatToIntBits(a.m30()) == Float.floatToIntBits(b.m30())
         && Float.floatToIntBits(a.m31()) == Float.floatToIntBits(b.m31())
         && Float.floatToIntBits(a.m32()) == Float.floatToIntBits(b.m32())
         && Float.floatToIntBits(a.m33()) == Float.floatToIntBits(b.m33());
   }
}
