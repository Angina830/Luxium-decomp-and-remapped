package com.vinlanx.luxium.client.water;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;

public final class WaterTextureResources {
   private static final ResourceLocation MICRO_NORMAL = new ResourceLocation("luxium", "textures/effects/water_micro_normal.png");
   private static SimpleTexture microNormalTexture;

   private WaterTextureResources() {
   }

   public static int microNormalTextureId() {
      if (microNormalTexture == null) {
         microNormalTexture = new SimpleTexture(MICRO_NORMAL);
         Minecraft.getInstance().getTextureManager().register(MICRO_NORMAL, microNormalTexture);
      }

      return microNormalTexture.getId();
   }
}
