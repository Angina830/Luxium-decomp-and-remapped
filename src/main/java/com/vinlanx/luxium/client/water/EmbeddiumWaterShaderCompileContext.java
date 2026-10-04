package com.vinlanx.luxium.client.water;

public final class EmbeddiumWaterShaderCompileContext {
   private static final ThreadLocal<Boolean> WATER_PASS = ThreadLocal.withInitial(() -> false);
   private static final ThreadLocal<Boolean> TRANSLUCENT_PASS = ThreadLocal.withInitial(() -> false);

   private EmbeddiumWaterShaderCompileContext() {
   }

   public static void begin(boolean waterPass, boolean translucentPass) {
      WATER_PASS.set(waterPass);
      TRANSLUCENT_PASS.set(translucentPass);
   }

   public static boolean isWaterPass() {
      return WATER_PASS.get();
   }

   public static boolean isTranslucentPass() {
      return TRANSLUCENT_PASS.get();
   }

   public static void end() {
      WATER_PASS.remove();
      TRANSLUCENT_PASS.remove();
   }
}
