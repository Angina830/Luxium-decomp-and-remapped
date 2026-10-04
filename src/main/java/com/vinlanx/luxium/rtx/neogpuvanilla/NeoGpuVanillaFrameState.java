package com.vinlanx.luxium.rtx.neogpuvanilla;

public record NeoGpuVanillaFrameState(
   boolean enabled,
   int volumeTexture,
   int fineScale,
   boolean fastShadowsEnabled,
   int fastLightPX,
   int fastLightNX,
   int fastLightPY,
   int fastLightNY,
   int fastLightPZ,
   int fastLightNZ,
   float minX,
   float minY,
   float minZ,
   float sizeX,
   float sizeY,
   float sizeZ,
   long version
) {
   public static NeoGpuVanillaFrameState disabled(long version) {
      return new NeoGpuVanillaFrameState(false, -1, 1, false, -1, -1, -1, -1, -1, -1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, version);
   }
}
