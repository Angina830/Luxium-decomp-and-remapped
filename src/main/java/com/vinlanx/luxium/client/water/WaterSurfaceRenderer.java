package com.vinlanx.luxium.client.water;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.vinlanx.luxium.client.SharedPostResources;
import com.vinlanx.luxium.client.posteffects.PostEffectPipeline;
import com.vinlanx.luxium.client.ssr.ScreenSpaceReflectionSystem;
import com.vinlanx.luxium.client.ssr.SsrConsumer;
import com.vinlanx.luxium.client.ssr.SsrSettings;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;

public final class WaterSurfaceRenderer {
   private static RenderSectionManager lateManager;
   private static ChunkRenderMatrices lateMatrices;
   private static double lateCameraX;
   private static double lateCameraY;
   private static double lateCameraZ;
   private static boolean lateFrameArmed;

   private WaterSurfaceRenderer() {
   }

   public static boolean renderWater(RenderSectionManager manager, ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ) {
      if (manager != null && WaterSurfaceState.canRenderMainPass()) {
         Minecraft mc = Minecraft.getInstance();
         RenderTarget main = mc.getMainRenderTarget();
         if (main.width > 0 && main.height > 0) {
            WaterSurfaceState.beginFrame();
            ScreenSpaceReflectionSystem.invalidateHit(SsrConsumer.WATER);
            SharedPostResources.prepareWaterRenderTarget(main);
            if (SharedPostResources.getWaterRenderColorTextureId() > 0
               && SharedPostResources.getWaterRenderFramebufferId() > 0
               && SharedPostResources.getWaterRenderWidth() > 0
               && SharedPostResources.getWaterRenderHeight() > 0) {
               SsrSettings ssr = ScreenSpaceReflectionSystem.settings(SsrConsumer.WATER);
               boolean hitTraceAttached = false;
               if (ssr.enabled()) {
                  hitTraceAttached = ScreenSpaceReflectionSystem.beginHitTrace(
                     SsrConsumer.WATER,
                     SharedPostResources.getWaterRenderFramebufferId(),
                     SharedPostResources.getWaterRenderWidth(),
                     SharedPostResources.getWaterRenderHeight()
                  );
               }

               int previousSceneFilter = main.filterMode;
               if (previousSceneFilter != 9729) {
                  main.setFilterMode(9729);
               }

               boolean materialRendered = false;
               boolean resolved = false;
               RenderDevice.enterManagedCode();

               boolean var15;
               try {
                  WaterSurfaceState.beginMaterialPass();

                  try {
                     manager.renderLayer(matrices, WaterTerrainPass.get(), cameraX, cameraY, cameraZ);
                     materialRendered = true;
                  } finally {
                     WaterSurfaceState.finishMaterialPass(materialRendered);
                     if (hitTraceAttached) {
                        ScreenSpaceReflectionSystem.endHitTrace(SsrConsumer.WATER, materialRendered);
                     }
                  }

                  if (!materialRendered || !WaterSurfaceState.resolvedReady()) {
                     return false;
                  }

                  main.bindWrite(true);
                  WaterSurfaceState.beginResolvePass();

                  try {
                     manager.renderLayer(matrices, WaterTerrainPass.get(), cameraX, cameraY, cameraZ);
                     resolved = true;
                  } finally {
                     WaterSurfaceState.finishResolvePass();
                  }

                  var15 = true;
               } finally {
                  RenderDevice.exitManagedCode();
                  if (hitTraceAttached && ScreenSpaceReflectionSystem.isHitTraceActive(SsrConsumer.WATER)) {
                     ScreenSpaceReflectionSystem.endHitTrace(SsrConsumer.WATER, false);
                  }

                  if (main.filterMode != previousSceneFilter) {
                     main.setFilterMode(previousSceneFilter);
                  }

                  main.bindWrite(true);
                  if (resolved && ssr.enabled() && ScreenSpaceReflectionSystem.isHitReady(SsrConsumer.WATER)) {
                     lateManager = manager;
                     lateMatrices = matrices;
                     lateCameraX = cameraX;
                     lateCameraY = cameraY;
                     lateCameraZ = cameraZ;
                     lateFrameArmed = true;
                  } else {
                     disarmLateFrame();
                  }
               }

               return var15;
            } else {
               main.bindWrite(true);
               disarmLateFrame();
               return false;
            }
         } else {
            disarmLateFrame();
            return false;
         }
      } else {
         disarmLateFrame();
         return false;
      }
   }

   public static boolean renderLateReflections() {
      if (lateFrameArmed && lateManager != null && lateMatrices != null) {
         SsrSettings ssr = ScreenSpaceReflectionSystem.settings(SsrConsumer.WATER);
         if (ssr.enabled() && WaterSurfaceState.canRenderMainPass() && ScreenSpaceReflectionSystem.isHitReady(SsrConsumer.WATER)) {
            Minecraft mc = Minecraft.getInstance();
            PostEffectPipeline.flushDeferredTonemapForSceneConsumer();
            RenderTarget main = mc.getMainRenderTarget();
            if (main.width > 0 && main.height > 0 && main.getColorTextureId() > 0) {
               SharedPostResources.prepareWaterRenderTarget(main);
               int previousSceneFilter = main.filterMode;
               if (previousSceneFilter != 9729) {
                  main.setFilterMode(9729);
               }

               boolean reflectionRendered = false;
               RenderDevice.enterManagedCode();

               boolean var5;
               try {
                  WaterSurfaceState.beginLateReflectionPass();

                  try {
                     lateManager.renderLayer(lateMatrices, WaterTerrainPass.get(), lateCameraX, lateCameraY, lateCameraZ);
                     reflectionRendered = true;
                  } finally {
                     WaterSurfaceState.finishLateReflectionPass(reflectionRendered);
                  }

                  if (!reflectionRendered || !WaterSurfaceState.lateReflectionResolvedReady()) {
                     return false;
                  }

                  main.bindWrite(true);
                  WaterSurfaceState.beginLateResolvePass();

                  try {
                     lateManager.renderLayer(lateMatrices, WaterTerrainPass.get(), lateCameraX, lateCameraY, lateCameraZ);
                  } finally {
                     WaterSurfaceState.finishLateResolvePass();
                  }

                  var5 = true;
               } finally {
                  RenderDevice.exitManagedCode();
                  if (main.filterMode != previousSceneFilter) {
                     main.setFilterMode(previousSceneFilter);
                  }

                  main.bindWrite(true);
                  disarmLateFrame();
               }

               return var5;
            } else {
               disarmLateFrame();
               return false;
            }
         } else {
            disarmLateFrame();
            return false;
         }
      } else {
         return false;
      }
   }

   private static void disarmLateFrame() {
      lateFrameArmed = false;
      lateManager = null;
      lateMatrices = null;
      ScreenSpaceReflectionSystem.invalidateHit(SsrConsumer.WATER);
   }
}
