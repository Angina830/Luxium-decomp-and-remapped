package com.vinlanx.luxium.client.water;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.RenderType;

public final class WaterTerrainPass {
   private static TerrainRenderPass water;

   private WaterTerrainPass() {
   }

   public static void bootstrap() {
      if (water == null) {
         water = new TerrainRenderPass(RenderType.translucent(), false, false);
      }
   }

   public static TerrainRenderPass get() {
      bootstrap();
      return water;
   }

   public static boolean is(TerrainRenderPass pass) {
      return pass != null && pass == water;
   }
}
