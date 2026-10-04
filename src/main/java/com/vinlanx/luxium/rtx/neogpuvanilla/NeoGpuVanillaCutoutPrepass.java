package com.vinlanx.luxium.rtx.neogpuvanilla;

import com.vinlanx.luxium.Config;

public final class NeoGpuVanillaCutoutPrepass {
   private static final int OFF = 0;
   private static final int DEPTH_PREPASS = 1;
   private static final int COLOR_PASS = 2;
   private static int mode = 0;

   private NeoGpuVanillaCutoutPrepass() {
   }

   public static boolean shouldRun() {
      NeoGpuVanillaFrameState state = NeoGpuVanilla.frameState();
      return NeoGpuVanilla.isConfiguredEnabled()
         && Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaCutoutEnabled)
         && NeoGpuVanilla.isMainPassActive()
         && state.enabled();
   }

   public static void beginDepthPrepass() {
      mode = 1;
   }

   public static void armColorPass() {
      mode = 2;
   }

   public static void finish() {
      mode = 0;
   }

   public static boolean isDepthPrepass() {
      return mode == 1;
   }

   public static boolean isColorPass() {
      return mode == 2;
   }
}
