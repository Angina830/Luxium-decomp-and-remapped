package com.vinlanx.luxium.rtx;

public final class LightRtMath {
   private static final int MAX_LIGHT_LEVEL = 15;
   private static final double MAX_DISTANCE_L15 = 36.0;
   private static final double MIN_DISTANCE_L1 = 2.4;
   private static final double ENTITY_SHADOW_RADIUS_BIAS = 6.0;
   private static final double STEP = 0.5;
   private static final double INV_STEP = 2.0;
   private static final int[][] FALLOFF_LUTS = new int[16][];
   private static final double[] MAX_DISTANCES = new double[16];

   private LightRtMath() {
   }

   private static void initializeStaticParameters() {
      for (int level = 1; level <= 15; level++) {
         double maxDistance = 2.4 + 33.6 * ((double)(level - 1) / 14.0);
         if (level == 1) {
            maxDistance = 2.4;
         }

         if (level == 15) {
            maxDistance = 36.0;
         }

         MAX_DISTANCES[level] = maxDistance;
         FALLOFF_LUTS[level] = buildFalloffLut(maxDistance, level);
      }
   }

   private static int[] buildFalloffLut(double maxDistance, int baseLevel) {
      int lutSize = (int)(maxDistance / 0.5) + 4;
      int[] lut = new int[lutSize];

      for (int i = 0; i < lutSize; i++) {
         double distance = (double)i * 0.5;
         if (distance >= maxDistance) {
            lut[i] = 0;
         } else {
            double attenuation = Math.exp(-distance / (maxDistance / 3.0));
            int light = (int)Math.round((double)baseLevel * attenuation);
            lut[i] = Math.max(0, Math.min(baseLevel, light));
         }
      }

      return lut;
   }

   public static int getFalloff(int level, double distance) {
      level = clamp(level, 1, 15);
      int[] lut = FALLOFF_LUTS[level];
      int index = (int)(Math.max(0.0, distance) * 2.0);
      return index < lut.length ? lut[index] : 0;
   }

   public static double getMaxDistance(int level) {
      return MAX_DISTANCES[clamp(level, 1, 15)];
   }

   public static double getBlockShadowRadius(int level) {
      return Math.max(1.0, getMaxDistance(level));
   }

   public static double getEntityShadowRadius(int level) {
      return getBlockShadowRadius(level) + 6.0;
   }

   private static int clamp(int value, int min, int max) {
      return value < min ? min : (value > max ? max : value);
   }

   static {
      initializeStaticParameters();
   }
}
