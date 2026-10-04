package com.vinlanx.luxium.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import java.util.function.Consumer;
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
public final class ShaderManager {
   private static final Logger LOGGER = LogManager.getLogger();
   @Nullable
   private static ShaderInstance polishedAndesiteShader;
   @Nullable
   private static ShaderInstance metalReflectionShader;
   @Nullable
   private static ShaderInstance godRaysPostShader;
   @Nullable
   private static ShaderInstance skyGodRaysShader;
   @Nullable
   private static ShaderInstance lensFlareShader;
   @Nullable
   private static ShaderInstance postEffectsCombinedShader;
   @Nullable
   private static ShaderInstance skyShader;
   @Nullable
   private static ShaderInstance skyBakeShader;
   @Nullable
   private static ShaderInstance grassShader;
   @Nullable
   private static ShaderInstance floodShadowCompositeShader;
   @Nullable
   private static ShaderInstance fogShader;
   @Nullable
   private static ShaderInstance tonemapShader;
   @Nullable
   private static ShaderInstance postEffectCompositeShader;
   @Nullable
   private static ShaderInstance airDistortionShader;
   @Nullable
   private static ShaderInstance blackHoleShader;
   @Nullable
   private static ShaderInstance cloudShadowCasterShader;
   @Nullable
   private static ShaderInstance depthToDistanceShader;
   @Nullable
   private static ShaderInstance neoGpuVanillaBakeShader;
   @Nullable
   private static ShaderInstance neoGpuVanillaResolveShader;
   @Nullable
   private static ShaderInstance neoGpuVanillaFastResolveShader;
   @Nullable
   private static ShaderInstance gpuHardShadowBakeShader;
   @Nullable
   private static ShaderInstance configGlassShader;
   @Nullable
   private static ShaderInstance youShader;
   @Nullable
   private static ShaderInstance waterSurfaceShader;
   @Nullable
   private static ShaderInstance rainPuddleShader;
   @Nullable
   private static ShaderInstance wetSurfaceShader;
   @Nullable
   private static ShaderInstance lsrUpscaleShader;
   @Nullable
   private static ShaderInstance temporalReprojectionShader;
   @Nullable
   private static ShaderInstance temporalMotionShader;
   @Nullable
   private static ShaderInstance temporalPrepareShader;
   @Nullable
   private static ShaderInstance kawaseSourceShader;
   @Nullable
   private static ShaderInstance kawaseDownShader;
   @Nullable
   private static ShaderInstance kawaseUpShader;
   @Nullable
   private static ShaderInstance kawaseCompositeShader;

   private ShaderManager() {
   }

   @SubscribeEvent
   public static void onRegisterShaders(RegisterShadersEvent event) {
      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(),
            ResourceLocation.fromNamespaceAndPath("luxium", "polished_andesite_reflection"),
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP
         );
         event.registerShader(instance, shader -> polishedAndesiteShader = shader);
      } catch (IOException var31) {
         LOGGER.error("Failed to load polished-andesite reflection shader", var31);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "metal_reflection"), DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP
         );
         event.registerShader(instance, shader -> metalReflectionShader = shader);
      } catch (IOException var30) {
         LOGGER.error("Failed to load metal reflection shader", var30);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "godrays_post"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> godRaysPostShader = shader);
      } catch (IOException var29) {
         LOGGER.error("Failed to load god rays post shader", var29);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "skygodrays"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> skyGodRaysShader = shader);
      } catch (IOException var28) {
         LOGGER.error("Failed to load sky god rays shader", var28);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "lensflare"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> lensFlareShader = shader);
      } catch (IOException var27) {
         LOGGER.error("Failed to load lens flare shader", var27);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "posteffects_combined"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> postEffectsCombinedShader = shader);
      } catch (IOException var26) {
         LOGGER.error("Failed to load fused post-effects shader", var26);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "sky"), DefaultVertexFormat.POSITION
         );
         event.registerShader(instance, shader -> skyShader = shader);
      } catch (IOException var25) {
         LOGGER.error("Failed to load sky shader", var25);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "sky_bake"), DefaultVertexFormat.POSITION
         );
         event.registerShader(instance, shader -> skyBakeShader = shader);
      } catch (IOException var24) {
         LOGGER.error("Failed to load sky bake shader", var24);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "fog"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> fogShader = shader);
      } catch (IOException var23) {
         LOGGER.error("Failed to load fog shader", var23);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "tonemap"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> tonemapShader = shader);
      } catch (IOException var22) {
         LOGGER.error("Failed to load tonemap shader", var22);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "posteffect_composite"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> postEffectCompositeShader = shader);
      } catch (IOException var21) {
         LOGGER.error("Failed to load post-effect composite shader", var21);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "grass"), DefaultVertexFormat.NEW_ENTITY
         );
         event.registerShader(instance, shader -> grassShader = shader);
      } catch (IOException var20) {
         LOGGER.error("Failed to load grass shader", var20);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "flood_shadow_composite"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> floodShadowCompositeShader = shader);
      } catch (IOException var19) {
         LOGGER.error("Failed to load flood shadow composite shader", var19);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "air_distortion"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> airDistortionShader = shader);
      } catch (IOException var18) {
         LOGGER.error("Failed to load air distortion shader", var18);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "blackhole"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> blackHoleShader = shader);
      } catch (IOException var17) {
         LOGGER.error("Failed to load black hole shader", var17);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "cloud_shadow_caster"), DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
         );
         event.registerShader(instance, shader -> cloudShadowCasterShader = shader);
      } catch (IOException var16) {
         LOGGER.error("Failed to load cloud shadow caster shader", var16);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "depth_to_distance"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> depthToDistanceShader = shader);
      } catch (IOException var15) {
         LOGGER.error("Failed to load depth_to_distance shader", var15);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "neogpuvanilla_bake"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> neoGpuVanillaBakeShader = shader);
      } catch (IOException var14) {
         LOGGER.error("Failed to load NeoGpuVanilla bake shader", var14);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "neogpuvanilla_resolve"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> neoGpuVanillaResolveShader = shader);
      } catch (IOException var13) {
         LOGGER.error("Failed to load NeoGpuVanilla resolve shader", var13);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "neogpuvanilla_fast_resolve"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> neoGpuVanillaFastResolveShader = shader);
      } catch (IOException var12) {
         LOGGER.error("Failed to load NeoGpuVanilla Fast GPU Shadows resolve shader", var12);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "gpu_hard_shadow_bake"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> gpuHardShadowBakeShader = shader);
      } catch (IOException var11) {
         LOGGER.error("Failed to load baked Cubemap Hard receiver shader", var11);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "config_glass"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> configGlassShader = shader);
      } catch (IOException var10) {
         LOGGER.error("Failed to load config glass shader", var10);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "water_surface"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> waterSurfaceShader = shader);
      } catch (IOException var9) {
         LOGGER.error("Failed to load water surface shader", var9);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "rain_puddle"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> rainPuddleShader = shader);
      } catch (IOException var8) {
         LOGGER.error("Failed to load new rain puddle shader", var8);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "wet_surface"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> wetSurfaceShader = shader);
      } catch (IOException var7) {
         LOGGER.error("Failed to load wet surface shader", var7);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "lsr_upscale"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> lsrUpscaleShader = shader);
      } catch (IOException var6) {
         LOGGER.error("Failed to load LSR upscale shader", var6);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "temporal_reprojection"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> temporalReprojectionShader = shader);
      } catch (IOException var5) {
         LOGGER.error("Failed to load temporal reprojection shader", var5);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "temporal_motion"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> temporalMotionShader = shader);
      } catch (IOException var4) {
         LOGGER.error("Failed to load temporal motion shader", var4);
      }

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "temporal_prepare"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> temporalPrepareShader = shader);
      } catch (IOException var3) {
         LOGGER.error("Failed to load temporal prepare shader", var3);
      }

      registerFullscreen(event, "kawase_source", shader -> kawaseSourceShader = shader);
      registerFullscreen(event, "kawase_down", shader -> kawaseDownShader = shader);
      registerFullscreen(event, "kawase_up", shader -> kawaseUpShader = shader);
      registerFullscreen(event, "kawase_composite", shader -> kawaseCompositeShader = shader);

      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", "youshader"), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, shader -> youShader = shader);
      } catch (IOException var2) {
         LOGGER.error("Failed to load youshader shader", var2);
      }
   }

   @Nullable
   public static ShaderInstance getPolishedAndesiteShader() {
      return polishedAndesiteShader;
   }

   @Nullable
   public static ShaderInstance getMetalReflectionShader() {
      return metalReflectionShader;
   }

   @Nullable
   public static ShaderInstance getGodRaysPostShader() {
      return godRaysPostShader;
   }

   @Nullable
   public static ShaderInstance getSkyGodRaysShader() {
      return skyGodRaysShader;
   }

   @Nullable
   public static ShaderInstance getLensFlareShader() {
      return lensFlareShader;
   }

   @Nullable
   public static ShaderInstance getPostEffectsCombinedShader() {
      return postEffectsCombinedShader;
   }

   @Nullable
   public static ShaderInstance getSkyShader() {
      return skyShader;
   }

   @Nullable
   public static ShaderInstance getSkyBakeShader() {
      return skyBakeShader;
   }

   @Nullable
   public static ShaderInstance getFogShader() {
      return fogShader;
   }

   @Nullable
   public static ShaderInstance getTonemapShader() {
      return tonemapShader;
   }

   @Nullable
   public static ShaderInstance getPostEffectCompositeShader() {
      return postEffectCompositeShader;
   }

   @Nullable
   public static ShaderInstance getGrassShader() {
      return grassShader;
   }

   @Nullable
   public static ShaderInstance getFloodShadowCompositeShader() {
      return floodShadowCompositeShader;
   }

   @Nullable
   public static ShaderInstance getAirDistortionShader() {
      return airDistortionShader;
   }

   @Nullable
   public static ShaderInstance getBlackHoleShader() {
      return blackHoleShader;
   }

   @Nullable
   public static ShaderInstance getCloudShadowCasterShader() {
      return cloudShadowCasterShader;
   }

   @Nullable
   public static ShaderInstance getDepthToDistanceShader() {
      return depthToDistanceShader;
   }

   @Nullable
   public static ShaderInstance getNeoGpuVanillaBakeShader() {
      return neoGpuVanillaBakeShader;
   }

   @Nullable
   public static ShaderInstance getNeoGpuVanillaResolveShader() {
      return neoGpuVanillaResolveShader;
   }

   @Nullable
   public static ShaderInstance getNeoGpuVanillaFastResolveShader() {
      return neoGpuVanillaFastResolveShader;
   }

   @Nullable
   public static ShaderInstance getGpuHardShadowBakeShader() {
      return gpuHardShadowBakeShader;
   }

   @Nullable
   public static ShaderInstance getConfigGlassShader() {
      return configGlassShader;
   }

   @Nullable
   public static ShaderInstance getYouShader() {
      return youShader;
   }

   @Nullable
   public static ShaderInstance getWaterSurfaceShader() {
      return waterSurfaceShader;
   }

   @Nullable
   public static ShaderInstance getRainPuddleShader() {
      return rainPuddleShader;
   }

   @Nullable
   public static ShaderInstance getWetSurfaceShader() {
      return wetSurfaceShader;
   }

   @Nullable
   public static ShaderInstance getLsrUpscaleShader() {
      return lsrUpscaleShader;
   }

   @Nullable
   public static ShaderInstance getTemporalReprojectionShader() {
      return temporalReprojectionShader;
   }

   @Nullable
   public static ShaderInstance getTemporalMotionShader() {
      return temporalMotionShader;
   }

   @Nullable
   public static ShaderInstance getTemporalPrepareShader() {
      return temporalPrepareShader;
   }

   @Nullable
   public static ShaderInstance getKawaseSourceShader() {
      return kawaseSourceShader;
   }

   @Nullable
   public static ShaderInstance getKawaseDownShader() {
      return kawaseDownShader;
   }

   @Nullable
   public static ShaderInstance getKawaseUpShader() {
      return kawaseUpShader;
   }

   @Nullable
   public static ShaderInstance getKawaseCompositeShader() {
      return kawaseCompositeShader;
   }

   private static void registerFullscreen(RegisterShadersEvent event, String name, Consumer<ShaderInstance> setter) {
      try {
         ShaderInstance instance = new ShaderInstance(
            event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("luxium", name), DefaultVertexFormat.POSITION_TEX
         );
         event.registerShader(instance, setter);
      } catch (IOException var4) {
         LOGGER.error("Failed to load {} shader", name, var4);
         setter.accept(null);
      }
   }
}
