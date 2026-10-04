package com.vinlanx.luxium.rtx;

import net.minecraft.world.level.lighting.LightEngine;

public interface LuxiumLayerLightStorageExtension {
   int luxium$getStoredLevel(long var1);

   void luxium$setStoredLevel(long var1, int var3);

   void luxium$markNewInconsistencies(LightEngine<?, ?> var1);

   void luxium$swapSectionMap();
}
