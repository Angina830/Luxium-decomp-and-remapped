package com.vinlanx.luxium.client.shadows;

public record GpuShadowFrameState(
   boolean enabled,
   boolean softShadows,
   boolean replaceBlockLight,
   int shadowMode,
   float spreadOcclusionStrength,
   int lightCount,
   int atlasTexture,
   int lightGridTexture,
   long version,
   float[] lights,
   float[] atlasRects
) {
   public static final int MODE_FAST_SPREAD = 0;
   public static final int MODE_CUBEMAP_HARD = 1;
   public static final int MODE_CUBEMAP_SOFT = 2;
   private static final float[] EMPTY_LIGHTS = new float[128];
   private static final float[] EMPTY_RECTS = new float[768];

   public static GpuShadowFrameState disabled(long version) {
      return new GpuShadowFrameState(false, false, false, 0, 0.0F, 0, -1, -1, version, EMPTY_LIGHTS, EMPTY_RECTS);
   }

   public static GpuShadowFrameState bakedHardOnly(long version) {
      return new GpuShadowFrameState(true, false, true, 1, 0.0F, 0, -1, -1, version, EMPTY_LIGHTS, EMPTY_RECTS);
   }
}
