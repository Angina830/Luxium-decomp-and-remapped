package com.vinlanx.luxium.client.shaders;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;

public final class LuxiumGpuShaderFeatures {
   private static final int MASK_NEO_GPU_VANILLA = 1;
   private static final int MASK_LOCAL_RECEIVER = 2;
   private static final int MASK_NEO_SKY = 4;
   private static final int MASK_RECEIVER = 8;
   private static final int MASK_RECEIVER_BINDING = 16;
   private static volatile int featureMask;
   private static volatile boolean initialized;

   private LuxiumGpuShaderFeatures() {
   }

   public static void beginFrame() {
      refreshNow();
   }

   public static void refreshNow() {
      boolean neoGpuVanilla = NeoGpuVanilla.isConfiguredEnabled();
      boolean localReceiver = !neoGpuVanilla
         && Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled)
         && !Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled)
         && Config.CLIENT.gpuLocalLightingMode.get() != Config.GpuLocalLightingMode.NEOFLOOD_ONLY;
      boolean neoSky = Config.isFeatureEnabled(Config.CLIENT.skyLightEnabled);
      int mask = 0;
      if (neoGpuVanilla) {
         mask |= 1;
      }

      if (localReceiver) {
         mask |= 2;
      }

      if (neoSky) {
         mask |= 4;
      }

      if (neoGpuVanilla || localReceiver) {
         mask |= 8;
      }

      if (neoGpuVanilla || localReceiver || neoSky) {
         mask |= 16;
      }

      featureMask = mask;
      initialized = true;
   }

   public static boolean neoGpuVanillaReceiverEnabled() {
      return (mask() & 1) != 0;
   }

   public static boolean localReceiverEnabled() {
      return (mask() & 2) != 0;
   }

   public static boolean neoSkyCelestiaEnabled() {
      return (mask() & 4) != 0;
   }

   public static boolean skyReceiverEnabled() {
      return false;
   }

   public static boolean receiverShadersEnabled() {
      return (mask() & 8) != 0;
   }

   public static boolean receiverBindingEnabled() {
      return (mask() & 16) != 0;
   }

   private static int mask() {
      if (!initialized) {
         refreshNow();
      }

      return featureMask;
   }
}
