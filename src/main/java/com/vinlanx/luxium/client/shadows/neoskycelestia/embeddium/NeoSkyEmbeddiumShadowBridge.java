package com.vinlanx.luxium.client.shadows.neoskycelestia.embeddium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCascade;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyShadowRenderLists;
import com.vinlanx.luxium.mixin.sky.RenderSectionManagerAccessor;
import com.vinlanx.luxium.mixin.sky.SodiumWorldRendererAccessor;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;

public final class NeoSkyEmbeddiumShadowBridge {
   private final NeoSkyShadowRenderLists.Cache nearCache = new NeoSkyShadowRenderLists.Cache();
   private final NeoSkyShadowRenderLists.Cache farCache = new NeoSkyShadowRenderLists.Cache();
   private int frameId;

   public boolean isAvailable() {
      return SodiumWorldRenderer.instanceNullable() != null;
   }

   public NeoSkyEmbeddiumShadowBridge.PreparedRenderLists prepareRenderLists(NeoSkyCascade near, boolean buildNear, NeoSkyCascade far, boolean buildFar) {
      SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
      if (worldRenderer == null) {
         return null;
      } else {
         RenderSectionManager manager = ((SodiumWorldRendererAccessor)worldRenderer).luxium$getRenderSectionManager();
         if (manager == null) {
            return null;
         } else {
            RenderSectionManagerAccessor accessor = (RenderSectionManagerAccessor)manager;
            NeoSkyShadowRenderLists.BuildResult lists = NeoSkyShadowRenderLists.build(
               accessor.luxium$getSectionByPosition(), near, buildNear, far, buildFar, ++this.frameId, this.nearCache, this.farCache
            );
            return new NeoSkyEmbeddiumShadowBridge.PreparedRenderLists(
               accessor.luxium$getChunkRenderer(),
               lists.near(),
               lists.nearCasterFront(),
               lists.nearIncomplete(),
               lists.far(),
               lists.farCasterFront(),
               lists.farIncomplete()
            );
         }
      }
   }

   public boolean renderCascade(NeoSkyCascade cascade, ChunkRenderer renderer, NeoSkyShadowRenderLists lists) {
      if (renderer == null || lists == null) {
         return false;
      } else if (lists.regionCount() == 0) {
         return true;
      } else {
         ChunkRenderMatrices matrices = new ChunkRenderMatrices(cascade.lightProjection(), cascade.lightViewRotation());
         CameraTransform camera = new CameraTransform(cascade.eye().x, cascade.eye().y, cascade.eye().z);
         RenderDevice.enterManagedCode();
         CommandList commandList = null;
         boolean previousBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
         SodiumClientMod.options().performance.useBlockFaceCulling = false;

         boolean var8;
         try {
            commandList = RenderDevice.INSTANCE.createCommandList();
            GlStateManager._colorMask(false, false, false, false);
            GlStateManager._enableDepthTest();
            GlStateManager._depthMask(true);
            RenderSystem.depthFunc(513);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            renderer.render(matrices, commandList, lists, DefaultTerrainRenderPasses.SOLID, camera);
            renderer.render(matrices, commandList, lists, DefaultTerrainRenderPasses.CUTOUT, camera);
            var8 = true;
         } finally {
            SodiumClientMod.options().performance.useBlockFaceCulling = previousBlockFaceCulling;
            GlStateManager._colorMask(true, true, true, true);
            if (commandList != null) {
               commandList.close();
            }

            RenderDevice.exitManagedCode();
         }

         return var8;
      }
   }

   public void clearCaches() {
      this.nearCache.clear();
      this.farCache.clear();
   }

   public static record PreparedRenderLists(
      ChunkRenderer renderer,
      NeoSkyShadowRenderLists near,
      float nearCasterFront,
      boolean nearIncomplete,
      NeoSkyShadowRenderLists far,
      float farCasterFront,
      boolean farIncomplete
   ) {
   }
}
