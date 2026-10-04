package com.vinlanx.luxium.mixin.sky;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.BlockLightTest.BlockLightTest;
import com.vinlanx.luxium.client.shaders.LuxiumGpuShaderFeatures;
import com.vinlanx.luxium.client.water.EmbeddiumWaterShaderCompileContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {ShaderLoader.class},
   remap = false
)
public abstract class EmbeddiumShaderLoaderMixin {
   @Inject(
      method = {"getShaderSource"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void luxium$replaceTerrainShaders(ResourceLocation name, CallbackInfoReturnable<String> cir) {
      if ("sodium".equals(name.getNamespace())) {
         boolean waterPassNow = Config.isFeatureEnabled(Config.CLIENT.waterEnabled) && EmbeddiumWaterShaderCompileContext.isWaterPass();
         if (!BlockLightTest.enabled()
            || LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled()
            || waterPassNow
            || !"blocks/block_layer_opaque.vsh".equals(name.getPath()) && !"blocks/block_layer_opaque.fsh".equals(name.getPath())) {
            boolean receiverShaders = LuxiumGpuShaderFeatures.receiverShadersEnabled();
            boolean waterPass = waterPassNow;
            boolean neoGpuVanilla = receiverShaders && LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled();
            boolean localReceiver = receiverShaders && LuxiumGpuShaderFeatures.localReceiverEnabled();
            boolean neoSky = LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled() && !neoGpuVanilla && !localReceiver && !waterPassNow;
            boolean neoSkyForward = neoSky && EmbeddiumWaterShaderCompileContext.isTranslucentPass();
            boolean neoSkyInline = neoSky && !neoSkyForward;
            if (receiverShaders || waterPassNow || neoSky || "blocks/block_layer_opaque.vsh".equals(name.getPath())) {
               ResourceLocation replacement;
               if ("blocks/block_layer_opaque.vsh".equals(name.getPath())) {
                  replacement = ResourceLocation.fromNamespaceAndPath(
                     "luxium",
                     waterPassNow
                        ? "shaders/embeddium/block_layer_water.vsh"
                        : (
                           neoSkyForward
                              ? "shaders/embeddium/block_layer_neoskycelestia_forward.vsh"
                              : (
                                 neoSkyInline
                                    ? "shaders/embeddium/block_layer_neoskycelestia.vsh"
                                    : (receiverShaders ? "shaders/embeddium/block_layer_opaque.vsh" : "shaders/embeddium/block_layer_plantswave.vsh")
                              )
                        )
                  );
               } else {
                  if (!"blocks/block_layer_opaque.fsh".equals(name.getPath())) {
                     return;
                  }

                  replacement = ResourceLocation.fromNamespaceAndPath(
                     "luxium",
                     waterPassNow
                        ? "shaders/embeddium/block_layer_water.fsh"
                        : (
                           neoGpuVanilla
                              ? "shaders/embeddium/block_layer_neogpuvanilla.fsh"
                              : (
                                 neoSkyForward
                                    ? "shaders/embeddium/block_layer_neoskycelestia_forward.fsh"
                                    : (neoSkyInline ? "shaders/embeddium/block_layer_neoskycelestia.fsh" : "shaders/embeddium/block_layer_opaque.fsh")
                              )
                        )
                  );
               }

               try {
                  Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(replacement);

                  try (InputStream stream = resource.open()) {
                     String source = IOUtils.toString(stream, StandardCharsets.UTF_8);
                     if ((receiverShaders || neoSkyForward) && !waterPass) {
                        source = source.replace(
                           "#define LUXIUM_ENTITY_SHADOWS_ENABLED 0",
                           "#define LUXIUM_ENTITY_SHADOWS_ENABLED " + (Config.isFeatureEnabled(Config.CLIENT.skyEntityShadowsEnabled) ? "1" : "0")
                        );
                     }

                     if (receiverShaders && !waterPass && (neoGpuVanilla || localReceiver)) {
                        source = source.replace(
                           "#define LUXIUM_NGV_CUTOUT_ENABLED 0",
                           "#define LUXIUM_NGV_CUTOUT_ENABLED "
                              + (neoGpuVanilla && !Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaCutoutEnabled) ? "0" : "1")
                        );
                     }

                     if (BlockLightTest.enabled() && neoSky && name.getPath().endsWith(".vsh")) {
                        source = source.replaceFirst("#version 330 core", "#version 330 core\n#define LUXIUM_BLOCKLIGHT_TEST 1");
                     }

                     cir.setReturnValue(source);
                  }
               } catch (IOException var20) {
                  throw new IllegalStateException("Failed to load Luxium Embeddium shader " + replacement, var20);
               }
            }
         } else {
            ResourceLocation sourcex = ResourceLocation.fromNamespaceAndPath(
               "luxium", "shaders/embeddium/block_layer_blocklighttest." + (name.getPath().endsWith(".vsh") ? "vsh" : "fsh")
            );

            try {
               try (InputStream stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(sourcex).open()) {
                  cir.setReturnValue(IOUtils.toString(stream, StandardCharsets.UTF_8));
               }
            } catch (IOException var18) {
               throw new IllegalStateException("Failed to load BlockLightTest shader " + sourcex, var18);
            }
         }
      }
   }
}
