package com.vinlanx.luxium.client.shadows.embeddium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.mixin.sky.RenderSectionManagerAccessor;
import com.vinlanx.luxium.mixin.sky.SodiumWorldRendererAccessor;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

public final class EmbeddiumLocalShadowBridge {
   private static final int GEOMETRY_MASK = 1;
   private static final double SECTION_MARGIN = 18.0;
   private int frameId;

   public boolean isAvailable() {
      return SodiumWorldRenderer.instanceNullable() != null;
   }

   public EmbeddiumLocalShadowBridge.PreparedCapture prepare(double lightX, double lightY, double lightZ, float radius) {
      SodiumWorldRenderer worldRenderer = SodiumWorldRenderer.instanceNullable();
      if (worldRenderer == null) {
         return null;
      } else {
         RenderSectionManager manager = ((SodiumWorldRendererAccessor)worldRenderer).luxium$getRenderSectionManager();
         if (manager == null) {
            return null;
         } else {
            RenderSectionManagerAccessor accessor = (RenderSectionManagerAccessor)manager;
            ChunkRenderer renderer = accessor.luxium$getChunkRenderer();
            Long2ReferenceMap<RenderSection> sections = accessor.luxium$getSectionByPosition();
            if (renderer != null && sections != null && !sections.isEmpty()) {
               double reach = Math.max(1.0, (double)radius) + 18.0;
               int minSectionX = SectionPos.blockToSectionCoord(Mth.floor(lightX - reach));
               int minSectionY = SectionPos.blockToSectionCoord(Mth.floor(lightY - reach));
               int minSectionZ = SectionPos.blockToSectionCoord(Mth.floor(lightZ - reach));
               int maxSectionX = SectionPos.blockToSectionCoord(Mth.floor(lightX + reach));
               int maxSectionY = SectionPos.blockToSectionCoord(Mth.floor(lightY + reach));
               int maxSectionZ = SectionPos.blockToSectionCoord(Mth.floor(lightZ + reach));
               int currentFrame = ++this.frameId;
               Reference2ObjectLinkedOpenHashMap<RenderRegion, ChunkRenderList> byRegion = new Reference2ObjectLinkedOpenHashMap();
               double maxDistance = reach + 14.0;
               double maxDistanceSq = maxDistance * maxDistance;

               for (int sx = minSectionX; sx <= maxSectionX; sx++) {
                  for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                     for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                        RenderSection section = (RenderSection)sections.get(SectionPos.asLong(sx, sy, sz));
                        if (section != null && section.isBuilt() && !section.isDisposed() && (section.getFlags() & 1) != 0) {
                           double dx = (double)section.getCenterX() - lightX;
                           double dy = (double)section.getCenterY() - lightY;
                           double dz = (double)section.getCenterZ() - lightZ;
                           if (!(dx * dx + dy * dy + dz * dz > maxDistanceSq)) {
                              RenderRegion region = section.getRegion();
                              if (region != null) {
                                 ChunkRenderList list = (ChunkRenderList)byRegion.get(region);
                                 if (list == null) {
                                    list = new ChunkRenderList(region);
                                    list.reset(currentFrame);
                                    byRegion.put(region, list);
                                 }

                                 list.add(section);
                              }
                           }
                        }
                     }
                  }
               }

               EmbeddiumLocalShadowBridge.LocalRenderLists lists = new EmbeddiumLocalShadowBridge.LocalRenderLists(new ArrayList<>(byRegion.values()));
               return new EmbeddiumLocalShadowBridge.PreparedCapture(renderer, lists, lightX, lightY, lightZ);
            } else {
               return null;
            }
         }
      }
   }

   public boolean renderFace(EmbeddiumLocalShadowBridge.PreparedCapture capture, Matrix4f projection, Matrix4f viewRotation) {
      if (capture == null || capture.renderer == null || capture.lists == null) {
         return false;
      } else if (capture.lists.regionCount() == 0) {
         return false;
      } else {
         ChunkRenderMatrices matrices = new ChunkRenderMatrices(projection, viewRotation);
         CameraTransform camera = new CameraTransform(capture.lightX, capture.lightY, capture.lightZ);
         RenderDevice.enterManagedCode();
         CommandList commandList = null;

         boolean var7;
         try {
            commandList = RenderDevice.INSTANCE.createCommandList();
            GlStateManager._colorMask(false, false, false, false);
            GlStateManager._enableDepthTest();
            GlStateManager._depthMask(true);
            RenderSystem.depthFunc(513);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            capture.renderer.render(matrices, commandList, capture.lists, DefaultTerrainRenderPasses.SOLID, camera);
            if (!NeoGpuVanilla.isConfiguredEnabled() || Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaCutoutEnabled)) {
               capture.renderer.render(matrices, commandList, capture.lists, DefaultTerrainRenderPasses.CUTOUT, camera);
            }

            var7 = true;
         } finally {
            GlStateManager._colorMask(true, true, true, true);
            if (commandList != null) {
               commandList.close();
            }

            RenderDevice.exitManagedCode();
         }

         return var7;
      }
   }

   public static final class LocalRenderLists implements ChunkRenderListIterable {
      private final List<ChunkRenderList> lists;

      private LocalRenderLists(List<ChunkRenderList> lists) {
         this.lists = lists;
      }

      public Iterator<ChunkRenderList> iterator(boolean reverse) {
         if (!reverse) {
            return this.lists.iterator();
         } else {
            final ListIterator<ChunkRenderList> iterator = this.lists.listIterator(this.lists.size());
            return new Iterator<ChunkRenderList>() {
               @Override
               public boolean hasNext() {
                  return iterator.hasPrevious();
               }

               public ChunkRenderList next() {
                  return iterator.previous();
               }
            };
         }
      }

      public int regionCount() {
         return this.lists.size();
      }
   }

   public static record PreparedCapture(ChunkRenderer renderer, EmbeddiumLocalShadowBridge.LocalRenderLists lists, double lightX, double lightY, double lightZ) {
   }
}
