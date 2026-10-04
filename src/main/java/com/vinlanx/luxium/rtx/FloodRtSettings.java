package com.vinlanx.luxium.rtx;

public final class FloodRtSettings {
   public static final int MIN_RADIUS_CAP = 16;
   public static final int MAX_RADIUS_CAP = 36;
   private static volatile int radiusCap = 36;
   private static volatile float penumbraSoftness = 0.9F;
   private static volatile float cornerSeal = 2.0F;
   private static volatile float directionalBias = 1.0F;
   private static volatile int updateBudgetPerTick = 8;
   private static volatile int revision = 1;

   private FloodRtSettings() {
   }

   public static synchronized boolean apply(
      int configuredRadiusCap, double configuredSoftness, double configuredCornerSeal, double configuredDirectionalBias, int configuredUpdateBudget
   ) {
      int nextRadius = clampInt(configuredRadiusCap, 16, 36);
      float nextSoftness = (float)clampDouble(configuredSoftness, 0.0, 1.0);
      float nextSeal = (float)clampDouble(configuredCornerSeal, 0.0, 4.0);
      float nextBias = (float)clampDouble(configuredDirectionalBias, 0.25, 4.0);
      int nextBudget = clampInt(configuredUpdateBudget, 1, 64);
      boolean changed = radiusCap != nextRadius
         || penumbraSoftness != nextSoftness
         || cornerSeal != nextSeal
         || directionalBias != nextBias
         || updateBudgetPerTick != nextBudget;
      if (!changed) {
         return false;
      } else {
         radiusCap = nextRadius;
         penumbraSoftness = nextSoftness;
         cornerSeal = nextSeal;
         directionalBias = nextBias;
         updateBudgetPerTick = nextBudget;
         revision++;
         return true;
      }
   }

   public static int radiusCap() {
      return radiusCap;
   }

   public static float penumbraSoftness() {
      return penumbraSoftness;
   }

   public static float cornerSeal() {
      return cornerSeal;
   }

   public static float directionalBias() {
      return directionalBias;
   }

   public static int updateBudgetPerTick() {
      return updateBudgetPerTick;
   }

   public static int revision() {
      return revision;
   }

   private static int clampInt(int value, int min, int max) {
      return value < min ? min : (value > max ? max : value);
   }

   private static double clampDouble(double value, double min, double max) {
      if (Double.isNaN(value)) {
         return min;
      } else {
         return value < min ? min : (value > max ? max : value);
      }
   }
}
