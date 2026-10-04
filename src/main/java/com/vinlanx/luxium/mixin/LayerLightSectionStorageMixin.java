package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.rtx.LuxiumLayerLightStorageExtension;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin({LayerLightSectionStorage.class})
public abstract class LayerLightSectionStorageMixin implements LuxiumLayerLightStorageExtension {
   @Shadow
   protected abstract int getStoredLevel(long var1);

   @Shadow
   protected abstract void setStoredLevel(long var1, int var3);

   @Shadow
   protected abstract void markNewInconsistencies(LightEngine<?, ?> var1);

   @Shadow
   protected abstract void swapSectionMap();

   @Override
   public int luxium$getStoredLevel(long posKey) {
      return this.getStoredLevel(posKey);
   }

   @Override
   public void luxium$setStoredLevel(long posKey, int value) {
      this.setStoredLevel(posKey, value);
   }

   @Override
   public void luxium$markNewInconsistencies(LightEngine<?, ?> engine) {
      this.markNewInconsistencies(engine);
   }

   @Override
   public void luxium$swapSectionMap() {
      this.swapSectionMap();
   }
}
