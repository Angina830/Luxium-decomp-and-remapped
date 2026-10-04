package com.vinlanx.luxium.Testing.ExplosionTesting;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.MOD
)
public final class ExplosionShader {
   private static final Logger LOGGER = LogManager.getLogger();
   @Nullable
   private static ShaderInstance particleShader;

   private ExplosionShader() {
   }

   @SubscribeEvent
   public static void register(RegisterShadersEvent event) {
      try {
         ShaderInstance shader = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "explosion_particle"), DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP
         );
         event.registerShader(shader, value -> particleShader = value);
      } catch (IOException var2) {
         LOGGER.error("Failed to load explosion particle shader", var2);
      }
   }

   @Nullable
   static ShaderInstance get() {
      return particleShader;
   }
}
