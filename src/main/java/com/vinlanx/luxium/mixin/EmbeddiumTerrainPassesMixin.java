package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.client.water.WaterTerrainPass;
import java.util.Arrays;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {DefaultTerrainRenderPasses.class},
   remap = false
)
public abstract class EmbeddiumTerrainPassesMixin {
   @Shadow
   @Final
   @Mutable
   public static TerrainRenderPass[] ALL;

   @Inject(
      method = {"<clinit>"},
      at = {@At("TAIL")}
   )
   private static void luxium$appendWaterTerrainPass(CallbackInfo ci) {
      WaterTerrainPass.bootstrap();
      TerrainRenderPass water = WaterTerrainPass.get();

      for (TerrainRenderPass pass : ALL) {
         if (pass == water) {
            return;
         }
      }

      TerrainRenderPass[] extended = Arrays.copyOf(ALL, ALL.length + 1);
      extended[ALL.length] = water;
      ALL = extended;
   }
}
