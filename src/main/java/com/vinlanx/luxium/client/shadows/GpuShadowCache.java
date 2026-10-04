package com.vinlanx.luxium.client.shadows;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.math.Axis;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.shadows.embeddium.EmbeddiumLocalShadowBridge;
import com.vinlanx.luxium.mixin.CameraAccessor;
import com.vinlanx.luxium.mixin.MinecraftAccessor;
import com.vinlanx.luxium.rtx.LightRtMath;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanillaGpuDebug;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.nio.ByteBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class GpuShadowCache {
   private static final int DEFAULT_TILE_SIZE = 256;
   private static final int FACE_COUNT = 6;
   private static final int ALL_FACES_MASK = 63;
   private static final int ATLAS_SIZE = 4096;
   private static final int MAX_DYNAMIC_FACE_REBUILDS_PER_FRAME = 24;
   private static final double BUILD_RADIUS_PADDING = 1.25;
   private static final double DYNAMIC_CASTER_SCAN_RADIUS_PADDING = 6.0;
   private static final float SHADOW_NEAR_PLANE = 0.55F;
   private static final float SHADOW_FACE_FOV = (float)Math.toRadians(92.0);
   private static final GpuShadowCache INSTANCE = new GpuShadowCache();
   private final EmbeddiumLocalShadowBridge embeddiumLocalShadowBridge = new EmbeddiumLocalShadowBridge();
   private final Long2ObjectOpenHashMap<GpuShadowCache.Entry> entries = new Long2ObjectOpenHashMap();
   private final LongOpenHashSet removedSources = new LongOpenHashSet();
   private final LongOpenHashSet pendingRenderedSections = new LongOpenHashSet();
   private final LongOpenHashSet expectedRenderedSections = new LongOpenHashSet();
   private int expectedRenderedSectionWaitFrames;
   private int renderedSectionQuietFrames;
   private int renderedSectionDeferralFrames;
   private final Long2ObjectOpenHashMap<AABB> trackedDynamicCasters = new Long2ObjectOpenHashMap();
   private int tileSize = 256;
   private int tileGrid = 16;
   private int maxTiles = this.tileGrid * this.tileGrid;
   private int[] freeTiles = new int[this.maxTiles];
   private int freeTileCount;
   private long visibilityFrame;
   private long revision;
   private int atlasTextureId = -1;
   private int fboId = -1;
   private RenderTarget shadowTileTarget;
   private final Camera shadowCamera = new Camera();
   @Nullable
   private ClientLevel trackedDynamicLevel;
   @Nullable
   private BlockPos trackedDynamicCenter;
   private long lastDynamicScanMs;

   private GpuShadowCache() {
      this.resetFreeTiles();
   }

   public static GpuShadowCache get() {
      return INSTANCE;
   }

   public int getAtlasTextureId() {
      return this.atlasTextureId;
   }

   public int getSourceCapacity() {
      this.syncLayout();
      return Math.max(1, this.maxTiles / 6);
   }

   public long getRevision() {
      return this.revision;
   }

   public void release() {
      if (this.fboId != -1) {
         GL30.glDeleteFramebuffers(this.fboId);
         this.fboId = -1;
      }

      if (this.atlasTextureId != -1) {
         TextureUtil.releaseTextureId(this.atlasTextureId);
         this.atlasTextureId = -1;
      }

      if (this.shadowTileTarget != null) {
         this.shadowTileTarget.destroyBuffers();
         this.shadowTileTarget = null;
      }

      this.entries.clear();
      this.removedSources.clear();
      this.pendingRenderedSections.clear();
      this.expectedRenderedSections.clear();
      this.expectedRenderedSectionWaitFrames = 0;
      this.renderedSectionQuietFrames = 0;
      this.renderedSectionDeferralFrames = 0;
      this.trackedDynamicCasters.clear();
      this.trackedDynamicLevel = null;
      this.trackedDynamicCenter = null;
      this.lastDynamicScanMs = 0L;
      this.visibilityFrame = 0L;
      this.revision++;
      this.resetFreeTiles();
   }

   public void onSourceRemoved(long sourceKey) {
      if (captureConsumerActive()) {
         this.removedSources.add(sourceKey);
      }
   }

   public void markAllDirty() {
      ObjectIterator var1 = this.entries.values().iterator();

      while (var1.hasNext()) {
         GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var1.next();
         markAllFacesDirty(entry, true);
      }

      this.revision++;
   }

   public void onSourcesRemoved(LongOpenHashSet sourceKeys) {
      if (captureConsumerActive() && sourceKeys != null && !sourceKeys.isEmpty()) {
         this.removedSources.addAll(sourceKeys);
      }
   }

   public void onGeometryChanged(BlockPos pos) {
      if (captureConsumerActive() && pos != null && !this.entries.isEmpty()) {
         double px = (double)pos.getX() + 0.5;
         double py = (double)pos.getY() + 0.5;
         double pz = (double)pos.getZ() + 0.5;
         boolean changed = false;
         ObjectIterator var9 = this.entries.values().iterator();

         while (var9.hasNext()) {
            GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var9.next();
            double dx = entry.worldX - px;
            double dy = entry.worldY - py;
            double dz = entry.worldZ - pz;
            double maxDistance = (double)entry.radius + 1.25;
            if (dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance) {
               markAllFacesDirty(entry, true);
               changed = true;
            }
         }

         if (changed) {
            this.revision++;
         }
      }
   }

   public void expectRenderedSectionChange(BlockPos pos) {
      if (NeoGpuVanilla.isConfiguredEnabled() && pos != null) {
         int originX = pos.getX() >> 4 << 4;
         int originY = pos.getY() >> 4 << 4;
         int originZ = pos.getZ() >> 4 << 4;
         boolean wasEmpty = this.expectedRenderedSections.isEmpty();
         if (this.expectedRenderedSections.add(BlockPos.asLong(originX, originY, originZ)) && wasEmpty) {
            this.expectedRenderedSectionWaitFrames = 0;
         }

         AABB editedBlock = new AABB(pos);
         this.markUrgentStaticEntriesAffectedByBounds(editedBlock);
      }
   }

   public boolean onRenderedSectionChanged(int originX, int originY, int originZ) {
      if (!captureConsumerActive()) {
         return false;
      } else {
         long sectionKey = BlockPos.asLong(originX, originY, originZ);
         if (!this.expectedRenderedSections.remove(sectionKey)) {
            return false;
         } else {
            if (this.expectedRenderedSections.isEmpty()) {
               this.expectedRenderedSectionWaitFrames = 0;
            }

            if (this.entries.isEmpty()) {
               return false;
            } else {
               boolean wasEmpty = this.pendingRenderedSections.isEmpty();
               boolean added = this.pendingRenderedSections.add(sectionKey);
               if (wasEmpty && added) {
                  this.renderedSectionDeferralFrames = 0;
               }

               this.renderedSectionQuietFrames = 0;
               return added;
            }
         }
      }
   }

   public boolean usesDirectEmbeddiumCapture() {
      return NeoGpuVanilla.isConfiguredEnabled() && this.embeddiumLocalShadowBridge.isAvailable();
   }

   public void refreshDynamicCasters(ClientLevel level, BlockPos center, Vec3 cameraPos) {
      if (level != null && center != null && cameraPos != null) {
         if (Config.isFeatureEnabled(Config.CLIENT.gpuShadowsEnabled)
            && GpuNeoShadows.entityShadowCastersEnabled()
            && !Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled)
            && GpuNeoShadows.usesCubemapShadows()) {
            long nowMs = System.currentTimeMillis();
            long intervalMs = this.getDynamicUpdateIntervalMs();
            boolean levelChanged = this.trackedDynamicLevel != level;
            boolean centerChanged = this.trackedDynamicCenter == null || !this.trackedDynamicCenter.equals(center);
            if (levelChanged || centerChanged || nowMs - this.lastDynamicScanMs >= intervalMs) {
               double scanRadius = GpuNeoShadows.getLightSearchRadius() + LightRtMath.getEntityShadowRadius(15) + 6.0;
               double scanRadiusSq = scanRadius * scanRadius;
               Long2ObjectOpenHashMap<AABB> nextCasters = new Long2ObjectOpenHashMap();
               this.scanDynamicCasters(level, center, cameraPos, scanRadiusSq, nextCasters);
               this.lastDynamicScanMs = nowMs;
               if (levelChanged) {
                  ObjectIterator var15 = this.entries.values().iterator();

                  while (var15.hasNext()) {
                     GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var15.next();
                     markAllFacesDirty(entry, false);
                  }
               } else {
                  this.markDynamicCasterDiff(nextCasters);
               }

               ObjectIterator var17 = nextCasters.values().iterator();

               while (var17.hasNext()) {
                  AABB bounds = (AABB)var17.next();
                  this.markEntriesAffectedByCasterBounds(bounds);
               }

               this.trackedDynamicCasters.clear();
               this.trackedDynamicCasters.putAll(nextCasters);
               this.trackedDynamicLevel = level;
               this.trackedDynamicCenter = center.immutable();
            }
         } else {
            this.clearDynamicTracking(level != this.trackedDynamicLevel || this.trackedDynamicCenter != null);
         }
      }
   }

   public void stageVisibleEntries(ClientLevel level, long[] sourceKeys, int[] emissions, int limit) {
      if (sourceKeys != null && emissions != null && limit > 0) {
         int max = Math.min(limit, Math.min(sourceKeys.length, emissions.length));
         if (max > 0) {
            this.ensureAtlasAndTargets();
            this.flushRemovedSources();
            long frame = ++this.visibilityFrame;
            if (NeoGpuVanilla.isConfiguredEnabled()) {
               ObjectIterator visibleKeys = this.entries.values().iterator();

               while (visibleKeys.hasNext()) {
                  GpuShadowCache.Entry existing = (GpuShadowCache.Entry)visibleKeys.next();
                  existing.stagedPriority = Integer.MAX_VALUE;
               }
            }

            LongOpenHashSet visibleKeys = new LongOpenHashSet(max);

            for (int i = 0; i < max; i++) {
               if (emissions[i] > 0) {
                  visibleKeys.add(sourceKeys[i]);
               }
            }

            for (int ix = 0; ix < max; ix++) {
               long sourceKey = sourceKeys[ix];
               int emission = emissions[ix];
               if (emission > 0) {
                  GpuLocalLightResolver.SourceGeometry geometry = GpuLocalLightResolver.resolveGeometry(level, sourceKey);
                  GpuShadowCache.Entry entry = (GpuShadowCache.Entry)this.entries.get(sourceKey);
                  if (entry == null) {
                     if (this.freeTileCount < 6) {
                        this.evictLeastRecentlyVisible(visibleKeys);
                     }

                     entry = this.createEntry(geometry, emission);
                     if (entry == null) {
                        continue;
                     }

                     this.entries.put(sourceKey, entry);
                  } else {
                     boolean geometryChanged = applyGeometry(entry, geometry);
                     if (entry.emission != emission) {
                        entry.emission = emission;
                        entry.radius = (float)LightRtMath.getBlockShadowRadius(emission);
                        geometryChanged = true;
                     }

                     if (geometryChanged) {
                        markAllFacesDirty(entry, true);
                     }
                  }

                  entry.lastVisibleFrame = frame;
                  entry.stagedPriority = ix;
                  entry.framesAlive++;
                  if (!NeoGpuVanilla.isConfiguredEnabled() && !GpuNeoShadows.isHardShadowBakeEnabled()) {
                     if (entry.refreshCount < 8) {
                        if (entry.framesAlive == 10
                           || entry.framesAlive == 30
                           || entry.framesAlive == 60
                           || entry.framesAlive == 120
                           || entry.framesAlive == 240
                           || entry.framesAlive == 480
                           || entry.framesAlive == 900) {
                           markAllFacesDirty(entry, true);
                           entry.refreshCount++;
                        }
                     } else if (entry.framesAlive % 600 == 0) {
                        markAllFacesDirty(entry, true);
                     }
                  }
               }
            }
         }
      }
   }

   public int collectVisibleEntries(
      long[] sourceKeys,
      int[] emissions,
      int limit,
      double cameraX,
      double cameraY,
      double cameraZ,
      float[] lightPosRadiusOut,
      float[] atlasRectOut,
      long[] visibleSourceKeysOut,
      int[] visibleEmissionsOut
   ) {
      return this.collectVisibleEntries(
         sourceKeys, emissions, limit, cameraX, cameraY, cameraZ, lightPosRadiusOut, atlasRectOut, visibleSourceKeysOut, visibleEmissionsOut, null
      );
   }

   public int collectVisibleEntries(
      long[] sourceKeys,
      int[] emissions,
      int limit,
      double cameraX,
      double cameraY,
      double cameraZ,
      float[] lightPosRadiusOut,
      float[] atlasRectOut,
      long[] visibleSourceKeysOut,
      int[] visibleEmissionsOut,
      float[] geometryOut
   ) {
      if (sourceKeys != null && emissions != null && limit > 0) {
         int count = 0;
         int max = Math.min(limit, Math.min(sourceKeys.length, emissions.length));

         for (int i = 0; i < max; i++) {
            if (emissions[i] > 0) {
               GpuShadowCache.Entry entry = (GpuShadowCache.Entry)this.entries.get(sourceKeys[i]);
               if (entry != null && entry.ready && !hasDirtyFaces(entry)) {
                  int posBase = count * 4;
                  lightPosRadiusOut[posBase] = (float)(entry.worldX - cameraX);
                  lightPosRadiusOut[posBase + 1] = (float)(entry.worldY - cameraY);
                  lightPosRadiusOut[posBase + 2] = (float)(entry.worldZ - cameraZ);
                  lightPosRadiusOut[posBase + 3] = entry.radius;
                  if (geometryOut != null && posBase + 3 < geometryOut.length) {
                     geometryOut[posBase] = (float)(entry.blockCenterX - cameraX);
                     geometryOut[posBase + 1] = (float)(entry.blockCenterY - cameraY);
                     geometryOut[posBase + 2] = (float)(entry.blockCenterZ - cameraZ);
                     geometryOut[posBase + 3] = (float)entry.blockedFaceMask + (entry.boxEmitter ? 64.0F : 0.0F);
                  }

                  int rectBase = count * 24;

                  for (int face = 0; face < 6; face++) {
                     atlasRectOut[rectBase + face * 4] = entry.u0[face];
                     atlasRectOut[rectBase + face * 4 + 1] = entry.v0[face];
                     atlasRectOut[rectBase + face * 4 + 2] = entry.us;
                     atlasRectOut[rectBase + face * 4 + 3] = entry.vs;
                  }

                  if (visibleSourceKeysOut != null && count < visibleSourceKeysOut.length) {
                     visibleSourceKeysOut[count] = sourceKeys[i];
                  }

                  if (visibleEmissionsOut != null && count < visibleEmissionsOut.length) {
                     visibleEmissionsOut[count] = emissions[i];
                  }

                  count++;
               }
            }
         }

         return count;
      } else {
         return 0;
      }
   }

   public int collectNeoGpuVanillaEntries(
      long[] sourceKeys,
      int[] emissions,
      int limit,
      double referenceX,
      double referenceY,
      double referenceZ,
      float[] lightPosRadiusOut,
      float[] atlasRectOut,
      long[] visibleSourceKeysOut,
      int[] visibleEmissionsOut,
      float[] geometryOut
   ) {
      if (sourceKeys != null && emissions != null && limit > 0) {
         int count = 0;
         int max = Math.min(limit, Math.min(sourceKeys.length, emissions.length));

         for (int i = 0; i < max; i++) {
            int emission = emissions[i];
            if (emission > 0) {
               GpuShadowCache.Entry entry = (GpuShadowCache.Entry)this.entries.get(sourceKeys[i]);
               if (entry != null) {
                  boolean capturePending = !entry.ready || hasDirtyFaces(entry);
                  int posBase = count * 4;
                  lightPosRadiusOut[posBase] = (float)(entry.worldX - referenceX);
                  lightPosRadiusOut[posBase + 1] = (float)(entry.worldY - referenceY);
                  lightPosRadiusOut[posBase + 2] = (float)(entry.worldZ - referenceZ);
                  lightPosRadiusOut[posBase + 3] = entry.radius;
                  if (geometryOut != null && posBase + 3 < geometryOut.length) {
                     geometryOut[posBase] = (float)(entry.blockCenterX - referenceX);
                     geometryOut[posBase + 1] = (float)(entry.blockCenterY - referenceY);
                     geometryOut[posBase + 2] = (float)(entry.blockCenterZ - referenceZ);
                     int flags = entry.blockedFaceMask & 63;
                     if (entry.boxEmitter) {
                        flags |= 64;
                     }

                     if (capturePending) {
                        flags |= 128;
                     }

                     geometryOut[posBase + 3] = (float)flags;
                  }

                  int rectBase = count * 24;

                  for (int face = 0; face < 6; face++) {
                     atlasRectOut[rectBase + face * 4] = entry.u0[face];
                     atlasRectOut[rectBase + face * 4 + 1] = entry.v0[face];
                     atlasRectOut[rectBase + face * 4 + 2] = entry.us;
                     atlasRectOut[rectBase + face * 4 + 3] = entry.vs;
                  }

                  if (visibleSourceKeysOut != null && count < visibleSourceKeysOut.length) {
                     visibleSourceKeysOut[count] = sourceKeys[i];
                  }

                  if (visibleEmissionsOut != null && count < visibleEmissionsOut.length) {
                     visibleEmissionsOut[count] = emission;
                  }

                  count++;
               }
            }
         }

         return count;
      } else {
         return 0;
      }
   }

   public long getNeoGpuVanillaContentGeneration(long[] sourceKeys, int limit) {
      if (sourceKeys != null && limit > 0) {
         int max = Math.min(limit, sourceKeys.length);
         long xor = 0L;
         long sum = 0L;

         for (int i = 0; i < max; i++) {
            long key = sourceKeys[i];
            GpuShadowCache.Entry entry = (GpuShadowCache.Entry)this.entries.get(key);
            long generation = entry == null ? -1L : entry.neoGpuContentGeneration;
            long value = key ^ generation * -7046029254386353131L;
            value ^= value >>> 30;
            value *= -4658895280553007687L;
            value ^= value >>> 27;
            value *= -7723592293110705685L;
            value ^= value >>> 31;
            xor ^= value;
            sum += Long.rotateLeft(value, (int)(key & 63L));
         }

         return xor ^ Long.rotateLeft(sum, max & 63) ^ (long)max * -4417276706812531889L;
      } else {
         return 0L;
      }
   }

   public boolean hasPendingNeoGpuVanillaCaptures(long[] sourceKeys, int limit) {
      if (!this.expectedRenderedSections.isEmpty() || !this.pendingRenderedSections.isEmpty()) {
         return true;
      } else if (sourceKeys != null && limit > 0) {
         int max = Math.min(limit, sourceKeys.length);

         for (int i = 0; i < max; i++) {
            GpuShadowCache.Entry entry = (GpuShadowCache.Entry)this.entries.get(sourceKeys[i]);
            if (entry == null || !entry.ready || hasDirtyFaces(entry)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public int countDirtyFacesForSources(long[] sourceKeys, int limit) {
      if (sourceKeys != null && limit > 0) {
         int count = 0;
         int max = Math.min(limit, sourceKeys.length);

         for (int i = 0; i < max; i++) {
            GpuShadowCache.Entry entry = (GpuShadowCache.Entry)this.entries.get(sourceKeys[i]);
            if (entry != null && entry.ready) {
               count += Integer.bitCount(entry.dirtyFaceMask & 63);
            } else {
               count += 6;
            }
         }

         return count;
      } else {
         return 0;
      }
   }

   public int countPendingNeoGpuVanillaFaces(long[] sourceKeys, int limit) {
      if (sourceKeys != null && limit > 0) {
         int count = 0;
         int max = Math.min(limit, sourceKeys.length);

         for (int i = 0; i < max; i++) {
            GpuShadowCache.Entry entry = (GpuShadowCache.Entry)this.entries.get(sourceKeys[i]);
            if (entry == null) {
               count += 6;
            } else {
               count += Integer.bitCount(entry.dirtyFaceMask & 63);
            }
         }

         return count;
      } else {
         return 0;
      }
   }

   public void buildPendingShadows(LevelRenderer levelRenderer, float partialTick, long finishNano, GameRenderer gameRenderer, LightTexture lightTexture) {
      if (captureConsumerActive() && !this.entries.isEmpty()) {
         this.flushExpectedRenderedSectionsIfTimedOut();
         this.flushRenderedSectionInvalidationsIfReady();
         boolean urgentMeshUploadPending = !this.expectedRenderedSections.isEmpty();
         boolean hasDirty = false;
         ObjectIterator mc = this.entries.values().iterator();

         while (mc.hasNext()) {
            GpuShadowCache.Entry e = (GpuShadowCache.Entry)mc.next();
            if (hasDirtyFaces(e)) {
               hasDirty = true;
               break;
            }
         }

         if (hasDirty) {
            Minecraft mcx = Minecraft.getInstance();
            ClientLevel level = mcx.level;
            if (level != null) {
               RenderTarget oldMainTarget = mcx.getMainRenderTarget();
               Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
               VertexSorting oldSorting = RenderSystem.getVertexSorting();
               this.ensureAtlasAndTargets();
               this.shadowCamera.setup(level, (Entity)(mcx.getCameraEntity() != null ? mcx.getCameraEntity() : mcx.player), true, false, partialTick);
               ((MinecraftAccessor)mcx).luxium$setMainRenderTargetField(this.shadowTileTarget);
               NeoGpuVanillaGpuDebug.beginCapture();

               try {
                  int staticBuildsThisFrame = 0;
                  int dynamicFacesThisFrame = 0;
                  if (NeoGpuVanilla.isConfiguredEnabled()) {
                     for (int faceBudget = this.neoGpuFaceRebuildBudget(); staticBuildsThisFrame < faceBudget; staticBuildsThisFrame++) {
                        GpuShadowCache.Entry nearestDirty = null;
                        ObjectIterator var33 = this.entries.values().iterator();

                        while (var33.hasNext()) {
                           GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var33.next();
                           if (entry.lastVisibleFrame == this.visibilityFrame
                              && hasDirtyFaces(entry)
                              && entry.staticDirty
                              && (!urgentMeshUploadPending || !entry.urgentStaticDirty)
                              && (
                                 nearestDirty == null
                                    || entry.urgentStaticDirty && !nearestDirty.urgentStaticDirty
                                    || entry.urgentStaticDirty == nearestDirty.urgentStaticDirty && entry.stagedPriority < nearestDirty.stagedPriority
                              )) {
                              nearestDirty = entry;
                           }
                        }

                        if (nearestDirty == null) {
                           break;
                        }

                        int face = Integer.numberOfTrailingZeros(nearestDirty.dirtyFaceMask & 63);
                        if (face < 0 || face >= 6) {
                           break;
                        }

                        this.rebuildFaces(levelRenderer, partialTick, finishNano, gameRenderer, lightTexture, nearestDirty, 1 << face);
                     }
                  } else if (!GpuNeoShadows.isHardShadowBakeEnabled()) {
                     ObjectIterator var26 = this.entries.values().iterator();

                     while (var26.hasNext()) {
                        GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var26.next();
                        if (staticBuildsThisFrame >= 1) {
                           break;
                        }

                        if (hasDirtyFaces(entry) && entry.staticDirty) {
                           this.rebuildFaces(levelRenderer, partialTick, finishNano, gameRenderer, lightTexture, entry, entry.dirtyFaceMask);
                           staticBuildsThisFrame++;
                        }
                     }
                  } else {
                     for (int faceBudget = Math.max(1, (Integer)Config.CLIENT.gpuHardShadowCaptureBudget.get());
                        staticBuildsThisFrame < faceBudget;
                        staticBuildsThisFrame++
                     ) {
                        GpuShadowCache.Entry nearestDirty = null;
                        ObjectIterator mask = this.entries.values().iterator();

                        while (mask.hasNext()) {
                           GpuShadowCache.Entry entryx = (GpuShadowCache.Entry)mask.next();
                           if (entryx.lastVisibleFrame == this.visibilityFrame
                              && hasDirtyFaces(entryx)
                              && entryx.staticDirty
                              && (
                                 nearestDirty == null
                                    || entryx.urgentStaticDirty && !nearestDirty.urgentStaticDirty
                                    || entryx.urgentStaticDirty == nearestDirty.urgentStaticDirty && entryx.stagedPriority < nearestDirty.stagedPriority
                              )) {
                              nearestDirty = entryx;
                           }
                        }

                        if (nearestDirty == null) {
                           break;
                        }

                        int face = Integer.numberOfTrailingZeros(nearestDirty.dirtyFaceMask & 63);
                        if (face < 0 || face >= 6) {
                           break;
                        }

                        this.rebuildFaces(levelRenderer, partialTick, finishNano, gameRenderer, lightTexture, nearestDirty, 1 << face);
                     }
                  }

                  if (dynamicFacesThisFrame < 24) {
                     ObjectIterator var28 = this.entries.values().iterator();

                     while (var28.hasNext()) {
                        GpuShadowCache.Entry entryx = (GpuShadowCache.Entry)var28.next();
                        if (dynamicFacesThisFrame >= 24) {
                           break;
                        }

                        if (hasDirtyFaces(entryx) && !entryx.staticDirty) {
                           for (int mask = entryx.dirtyFaceMask; mask != 0 && dynamicFacesThisFrame < 24; dynamicFacesThisFrame++) {
                              int face = Integer.numberOfTrailingZeros(mask);
                              int singleFaceMask = 1 << face;
                              this.rebuildFaces(levelRenderer, partialTick, finishNano, gameRenderer, lightTexture, entryx, singleFaceMask);
                              mask &= ~singleFaceMask;
                           }
                        }
                     }
                  }
               } finally {
                  NeoGpuVanillaGpuDebug.endCapture();
                  NeoShadowsEngine.endGpuShadowAtlasCapture();
                  ((MinecraftAccessor)mcx).luxium$setMainRenderTargetField(oldMainTarget);
                  RenderSystem.setProjectionMatrix(oldProj, oldSorting);
                  oldMainTarget.bindWrite(true);
                  GL11.glDisable(3089);
                  GlStateManager._activeTexture(33984);
                  GlStateManager._colorMask(true, true, true, true);
                  GlStateManager._depthMask(true);
                  GlStateManager._enableDepthTest();
                  RenderSystem.depthFunc(515);
                  RenderSystem.disableBlend();
                  RenderSystem.enableCull();
                  GL11.glCullFace(1029);
               }
            }
         }
      }
   }

   private void rebuildFaces(
      LevelRenderer levelRenderer,
      float partialTick,
      long finishNano,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      GpuShadowCache.Entry entry,
      int faceMask
   ) {
      int mask = faceMask & 63;
      if (mask != 0) {
         boolean directEmbeddium = NeoGpuVanilla.isConfiguredEnabled() && this.embeddiumLocalShadowBridge.isAvailable();
         EmbeddiumLocalShadowBridge.PreparedCapture directCapture = directEmbeddium
            ? this.embeddiumLocalShadowBridge.prepare(entry.worldX, entry.worldY, entry.worldZ, entry.radius)
            : null;
         if (!directEmbeddium || directCapture != null && directCapture.lists().regionCount() != 0) {
            while (mask != 0) {
               int face = Integer.numberOfTrailingZeros(mask);
               int singleFaceMask = 1 << face;
               this.shadowTileTarget.clear(Minecraft.ON_OSX);
               this.shadowTileTarget.bindWrite(true);
               ((CameraAccessor)this.shadowCamera).luxium$setPosition(entry.worldX, entry.worldY, entry.worldZ);
               ((CameraAccessor)this.shadowCamera).luxium$setRotation(getYawForFace(face), getPitchForFace(face));
               Matrix4f proj = new Matrix4f().setPerspective(SHADOW_FACE_FOV, 1.0F, 0.55F, entry.radius);
               RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
               NeoShadowsEngine.beginGpuShadowAtlasCapture(
                  entry.worldX, entry.worldY, entry.worldZ, entry.radius, face, GpuNeoShadows.entityShadowCastersEnabled() && GpuNeoShadows.isActive()
               );

               try {
                  PoseStack shadowPose = new PoseStack();
                  shadowPose.mulPose(Axis.XP.rotationDegrees(this.shadowCamera.getXRot()));
                  shadowPose.mulPose(Axis.YP.rotationDegrees(this.shadowCamera.getYRot() + 180.0F));
                  Matrix4f lightViewRotation = new Matrix4f(shadowPose.last().pose());
                  boolean renderedDirect = directEmbeddium && this.embeddiumLocalShadowBridge.renderFace(directCapture, proj, lightViewRotation);
                  if (directEmbeddium && !renderedDirect) {
                     return;
                  }

                  if (!directEmbeddium) {
                     levelRenderer.renderLevel(shadowPose, partialTick, finishNano, false, this.shadowCamera, gameRenderer, lightTexture, proj);
                  }

                  if (!this.copyDepthToAtlas(entry, face, proj)) {
                     mask &= ~singleFaceMask;
                     continue;
                  }
               } finally {
                  NeoShadowsEngine.endGpuShadowAtlasCapture();
               }

               entry.validFaceMask |= singleFaceMask;
               entry.dirtyFaceMask &= ~singleFaceMask;
               entry.neoGpuContentGeneration++;
               NeoGpuVanillaGpuDebug.recordCapturedFace();
               mask &= ~singleFaceMask;
            }

            entry.ready = entry.validFaceMask == 63;
            this.revision++;
            if (entry.dirtyFaceMask == 0) {
               entry.staticDirty = false;
               entry.dynamicDirty = false;
               entry.urgentStaticDirty = false;
            }
         }
      }
   }

   private boolean copyDepthToAtlas(GpuShadowCache.Entry entry, int face, Matrix4f proj) {
      ShaderInstance shader = ShaderManager.getDepthToDistanceShader();
      if (shader == null) {
         return false;
      } else {
         GL30.glBindFramebuffer(36160, this.fboId);
         int tileX = entry.tileIndices[face] % this.tileGrid;
         int tileY = entry.tileIndices[face] / this.tileGrid;
         int vx = tileX * this.tileSize;
         int vy = tileY * this.tileSize;
         GL11.glViewport(vx, vy, this.tileSize, this.tileSize);
         GL11.glEnable(3089);
         GL11.glScissor(vx, vy, this.tileSize, this.tileSize);
         GL11.glDisable(2929);
         GlStateManager._colorMask(true, true, true, true);

         boolean var12;
         try {
            shader.setSampler("DepthSampler", this.shadowTileTarget.getDepthTextureId());
            if (shader.getUniform("InverseProj") != null) {
               shader.getUniform("InverseProj").set(new Matrix4f(proj).invert());
            }

            if (shader.getUniform("LightRadius") != null) {
               shader.getUniform("LightRadius").set(entry.radius);
            }

            Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float)this.tileSize, (float)this.tileSize, 0.0F, -1.0F, 1.0F);
            RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
            shader.MODEL_VIEW_MATRIX.set(new Matrix4f());
            shader.PROJECTION_MATRIX.set(ortho);
            shader.apply();
            Tesselator tess = RenderSystem.renderThreadTesselator();
            BufferBuilder buf = tess.getBuilder();
            buf.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buf.vertex(0.0, (double)this.tileSize, 0.0).uv(0.0F, 0.0F).endVertex();
            buf.vertex((double)this.tileSize, (double)this.tileSize, 0.0).uv(1.0F, 0.0F).endVertex();
            buf.vertex((double)this.tileSize, 0.0, 0.0).uv(1.0F, 1.0F).endVertex();
            buf.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).endVertex();
            BufferUploader.draw(buf.end());
            var12 = true;
         } finally {
            shader.clear();
            GL11.glDisable(3089);
            GL11.glEnable(2929);
            GL30.glBindFramebuffer(36160, 0);
         }

         return var12;
      }
   }

   private static float getYawForFace(int face) {
      return switch (face) {
         case 0 -> -90.0F;
         case 1 -> 90.0F;
         default -> 0.0F;
         case 4 -> 0.0F;
         case 5 -> 180.0F;
      };
   }

   private static float getPitchForFace(int face) {
      return switch (face) {
         case 2 -> -90.0F;
         case 3 -> 90.0F;
         default -> 0.0F;
      };
   }

   private void ensureAtlasAndTargets() {
      this.syncLayout();
      if (this.atlasTextureId == -1) {
         this.atlasTextureId = TextureUtil.generateTextureId();
         RenderSystem.activeTexture(33990);
         GL11.glBindTexture(3553, this.atlasTextureId);
         GL11.glTexParameteri(3553, 10241, 9728);
         GL11.glTexParameteri(3553, 10240, 9728);
         GL11.glTexParameteri(3553, 10242, 33071);
         GL11.glTexParameteri(3553, 10243, 33071);
         GL11.glTexImage2D(3553, 0, 33326, 4096, 4096, 0, 6403, 5126, (ByteBuffer)null);
         this.fboId = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(36160, this.fboId);
         GL30.glFramebufferTexture2D(36160, 36064, 3553, this.atlasTextureId, 0);
         GL30.glBindFramebuffer(36160, 0);
         RenderSystem.activeTexture(33984);
      }

      if (this.shadowTileTarget == null) {
         this.shadowTileTarget = new TextureTarget(this.tileSize, this.tileSize, true, Minecraft.ON_OSX);
         this.shadowTileTarget.setClearColor(1.0F, 1.0F, 1.0F, 1.0F);
      }
   }

   private void flushRemovedSources() {
      if (!this.removedSources.isEmpty()) {
         LongIterator it = this.removedSources.iterator();

         while (it.hasNext()) {
            long sourceKey = it.nextLong();
            GpuShadowCache.Entry removed = (GpuShadowCache.Entry)this.entries.remove(sourceKey);
            this.releaseEntryTiles(removed);
         }

         this.removedSources.clear();
         this.revision++;
      }
   }

   private void evictLeastRecentlyVisible(LongOpenHashSet protectedKeys) {
      long victimKey = 0L;
      GpuShadowCache.Entry victim = null;
      LongIterator var5 = this.entries.keySet().iterator();

      while (var5.hasNext()) {
         long key = (Long)var5.next();
         if (!protectedKeys.contains(key)) {
            GpuShadowCache.Entry candidate = (GpuShadowCache.Entry)this.entries.get(key);
            if (candidate != null && (victim == null || candidate.lastVisibleFrame < victim.lastVisibleFrame)) {
               victimKey = key;
               victim = candidate;
            }
         }
      }

      if (victim != null) {
         this.entries.remove(victimKey);
         this.releaseEntryTiles(victim);
      }
   }

   private void releaseEntryTiles(@Nullable GpuShadowCache.Entry entry) {
      if (entry != null) {
         for (int face = 0; face < 6; face++) {
            if (this.freeTileCount < this.freeTiles.length) {
               this.freeTiles[this.freeTileCount++] = entry.tileIndices[face];
            }
         }
      }
   }

   @Nullable
   private GpuShadowCache.Entry createEntry(GpuLocalLightResolver.SourceGeometry geometry, int emission) {
      if (this.freeTileCount < 6) {
         return null;
      } else {
         GpuShadowCache.Entry entry = new GpuShadowCache.Entry();
         entry.emission = emission;
         entry.radius = (float)LightRtMath.getBlockShadowRadius(emission);

         for (int f = 0; f < 6; f++) {
            int tileIndex = this.freeTiles[--this.freeTileCount];
            entry.tileIndices[f] = tileIndex;
            int tileX = tileIndex % this.tileGrid;
            int tileY = tileIndex / this.tileGrid;
            entry.u0[f] = ((float)(tileX * this.tileSize) + 0.5F) / 4096.0F;
            entry.v0[f] = ((float)(tileY * this.tileSize) + 0.5F) / 4096.0F;
         }

         entry.us = ((float)this.tileSize - 1.0F) / 4096.0F;
         entry.vs = ((float)this.tileSize - 1.0F) / 4096.0F;
         applyGeometry(entry, geometry);
         entry.dirtyFaceMask = 63;
         entry.staticDirty = true;
         return entry;
      }
   }

   private static boolean applyGeometry(GpuShadowCache.Entry entry, GpuLocalLightResolver.SourceGeometry geometry) {
      boolean changed = Double.compare(entry.worldX, geometry.sourceX) != 0
         || Double.compare(entry.worldY, geometry.sourceY) != 0
         || Double.compare(entry.worldZ, geometry.sourceZ) != 0
         || Double.compare(entry.blockCenterX, geometry.centerX) != 0
         || Double.compare(entry.blockCenterY, geometry.centerY) != 0
         || Double.compare(entry.blockCenterZ, geometry.centerZ) != 0
         || entry.boxEmitter != geometry.boxEmitter
         || entry.blockedFaceMask != geometry.blockedFaceMask;
      entry.worldX = geometry.sourceX;
      entry.worldY = geometry.sourceY;
      entry.worldZ = geometry.sourceZ;
      entry.blockCenterX = geometry.centerX;
      entry.blockCenterY = geometry.centerY;
      entry.blockCenterZ = geometry.centerZ;
      entry.boxEmitter = geometry.boxEmitter;
      entry.blockedFaceMask = geometry.blockedFaceMask;
      return changed;
   }

   private static boolean hasDirtyFaces(GpuShadowCache.Entry entry) {
      return entry != null && (entry.dirtyFaceMask & 63) != 0;
   }

   private static void markAllFacesDirty(GpuShadowCache.Entry entry, boolean staticDirty) {
      if (entry != null) {
         entry.dirtyFaceMask |= 63;
         if (staticDirty) {
            entry.staticDirty = true;
         } else {
            entry.dynamicDirty = true;
         }
      }
   }

   private void markDynamicCasterDiff(Long2ObjectOpenHashMap<AABB> nextCasters) {
      LongIterator var2 = this.trackedDynamicCasters.keySet().iterator();

      while (var2.hasNext()) {
         long key = (Long)var2.next();
         AABB oldBox = (AABB)this.trackedDynamicCasters.get(key);
         AABB newBox = (AABB)nextCasters.get(key);
         if (newBox == null) {
            this.markEntriesAffectedByCasterBounds(oldBox);
         } else if (!sameBounds(oldBox, newBox)) {
            this.markEntriesAffectedByCasterBounds(union(oldBox, newBox));
         }
      }

      var2 = nextCasters.keySet().iterator();

      while (var2.hasNext()) {
         long key = (Long)var2.next();
         if (!this.trackedDynamicCasters.containsKey(key)) {
            this.markEntriesAffectedByCasterBounds((AABB)nextCasters.get(key));
         }
      }
   }

   private void markEntriesAffectedByCasterBounds(@Nullable AABB bounds) {
      if (bounds != null && !this.entries.isEmpty()) {
         ObjectIterator var2 = this.entries.values().iterator();

         while (var2.hasNext()) {
            GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var2.next();
            double influence = Math.max((double)entry.radius, LightRtMath.getEntityShadowRadius(entry.emission)) + 1.25;
            if (isBoundsNearLight(bounds, entry.worldX, entry.worldY, entry.worldZ, influence)) {
               int faceMask = computeAffectedFaceMask(entry, bounds);
               if (faceMask == 0) {
                  faceMask = 63;
               }

               entry.dirtyFaceMask |= faceMask;
               entry.dynamicDirty = true;
            }
         }
      }
   }

   private void markUrgentStaticEntriesAffectedByBounds(@Nullable AABB bounds) {
      if (bounds != null && !this.entries.isEmpty()) {
         boolean changed = false;
         ObjectIterator var3 = this.entries.values().iterator();

         while (var3.hasNext()) {
            GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var3.next();
            double influence = (double)entry.radius + 1.25;
            if (isBoundsNearLight(bounds, entry.worldX, entry.worldY, entry.worldZ, influence)) {
               int faceMask = computeAffectedFaceMask(entry, bounds);
               if (faceMask == 0) {
                  faceMask = 63;
               }

               int newlyDirty = faceMask & ~entry.dirtyFaceMask;
               entry.dirtyFaceMask |= faceMask;
               entry.staticDirty = true;
               entry.urgentStaticDirty = true;
               if (newlyDirty != 0) {
                  changed = true;
               }
            }
         }

         if (changed) {
            this.revision++;
         }
      }
   }

   private static boolean isBoundsNearLight(AABB bounds, double lightX, double lightY, double lightZ, double influence) {
      double cx = clamp(lightX, bounds.minX, bounds.maxX);
      double cy = clamp(lightY, bounds.minY, bounds.maxY);
      double cz = clamp(lightZ, bounds.minZ, bounds.maxZ);
      double dx = lightX - cx;
      double dy = lightY - cy;
      double dz = lightZ - cz;
      return dx * dx + dy * dy + dz * dz <= influence * influence;
   }

   private static int computeAffectedFaceMask(GpuShadowCache.Entry entry, AABB bounds) {
      int mask = 0;

      for (double x : new double[]{bounds.minX, bounds.maxX}) {
         for (double y : new double[]{bounds.minY, bounds.maxY}) {
            for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
               mask |= faceMaskForDirection(x - entry.worldX, y - entry.worldY, z - entry.worldZ);
            }
         }
      }

      return mask;
   }

   private static int faceMaskForDirection(double dx, double dy, double dz) {
      double absX = Math.abs(dx);
      double absY = Math.abs(dy);
      double absZ = Math.abs(dz);
      double maxAxis = Math.max(absX, Math.max(absY, absZ));
      if (maxAxis <= 1.0E-6) {
         return 63;
      } else {
         int mask = 0;
         double epsilon = maxAxis * 0.12;
         if (absX + epsilon >= maxAxis) {
            mask |= dx >= 0.0 ? 1 : 2;
         }

         if (absY + epsilon >= maxAxis) {
            mask |= dy >= 0.0 ? 4 : 8;
         }

         if (absZ + epsilon >= maxAxis) {
            mask |= dz >= 0.0 ? 16 : 32;
         }

         return mask == 0 ? 63 : mask;
      }
   }

   private long getDynamicUpdateIntervalMs() {
      int fps = Math.max(1, (Integer)Config.CLIENT.entityShadowUpdateFpsLimit.get());
      return Math.max(1L, 1000L / (long)fps);
   }

   private void clearDynamicTracking(boolean markDirty) {
      if (markDirty) {
         ObjectIterator var2 = this.entries.values().iterator();

         while (var2.hasNext()) {
            GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var2.next();
            if (entry.dynamicDirty) {
               entry.dynamicDirty = false;
            }
         }
      }

      this.trackedDynamicCasters.clear();
      this.trackedDynamicLevel = null;
      this.trackedDynamicCenter = null;
      this.lastDynamicScanMs = 0L;
   }

   private void scanDynamicCasters(ClientLevel level, BlockPos center, Vec3 cameraPos, double radiusSq, Long2ObjectOpenHashMap<AABB> out) {
      Vec3 centerPos = Vec3.atCenterOf(center);

      for (Entity entity : level.entitiesForRendering()) {
         if (entity != null && !entity.isRemoved() && !entity.isInvisible()) {
            AABB box = entity.getBoundingBox();
            if (box != null && !(distanceToAabbSq(centerPos, box) > radiusSq)) {
               out.put((long)entity.getId(), box);
            }
         }
      }

      BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
      int chunkRadius = Math.max(1, (int)Math.ceil(Math.sqrt(radiusSq) / 16.0));
      int centerChunkX = center.getX() >> 4;
      int centerChunkZ = center.getZ() >> 4;

      for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
         for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
            if (chunk != null) {
               for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                  if (blockEntity != null && !blockEntity.isRemoved()) {
                     BlockEntityRenderer renderer = dispatcher.getRenderer(blockEntity);
                     if (renderer != null && renderer.shouldRender(blockEntity, cameraPos)) {
                        AABB box = blockEntity.getRenderBoundingBox();
                        if (box == null) {
                           box = new AABB(blockEntity.getBlockPos());
                        }

                        if (!(distanceToAabbSq(centerPos, box) > radiusSq)) {
                           out.put(blockEntityKey(blockEntity.getBlockPos().asLong()), box);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean sameBounds(@Nullable AABB a, @Nullable AABB b) {
      if (a == b) {
         return true;
      } else {
         return a != null && b != null
            ? Double.compare(a.minX, b.minX) == 0
               && Double.compare(a.minY, b.minY) == 0
               && Double.compare(a.minZ, b.minZ) == 0
               && Double.compare(a.maxX, b.maxX) == 0
               && Double.compare(a.maxY, b.maxY) == 0
               && Double.compare(a.maxZ, b.maxZ) == 0
            : false;
      }
   }

   private static AABB union(AABB a, AABB b) {
      return new AABB(
         Math.min(a.minX, b.minX),
         Math.min(a.minY, b.minY),
         Math.min(a.minZ, b.minZ),
         Math.max(a.maxX, b.maxX),
         Math.max(a.maxY, b.maxY),
         Math.max(a.maxZ, b.maxZ)
      );
   }

   private static double distanceToAabbSq(Vec3 point, AABB box) {
      double dx = point.x < box.minX ? box.minX - point.x : Math.max(0.0, point.x - box.maxX);
      double dy = point.y < box.minY ? box.minY - point.y : Math.max(0.0, point.y - box.maxY);
      double dz = point.z < box.minZ ? box.minZ - point.z : Math.max(0.0, point.z - box.maxZ);
      return dx * dx + dy * dy + dz * dz;
   }

   private static long blockEntityKey(long posKey) {
      return posKey ^ Long.MIN_VALUE;
   }

   private static double clamp(double value, double min, double max) {
      return Math.max(min, Math.min(max, value));
   }

   private void flushExpectedRenderedSectionsIfTimedOut() {
      if (!this.expectedRenderedSections.isEmpty()) {
         this.expectedRenderedSectionWaitFrames++;
         if (this.expectedRenderedSectionWaitFrames >= 20) {
            boolean wasEmpty = this.pendingRenderedSections.isEmpty();
            this.pendingRenderedSections.addAll(this.expectedRenderedSections);
            this.expectedRenderedSections.clear();
            this.expectedRenderedSectionWaitFrames = 0;
            if (wasEmpty && !this.pendingRenderedSections.isEmpty()) {
               this.renderedSectionDeferralFrames = 0;
            }

            this.renderedSectionQuietFrames = 0;
         }
      }
   }

   private void flushRenderedSectionInvalidationsIfReady() {
      if (!this.pendingRenderedSections.isEmpty()) {
         this.renderedSectionDeferralFrames++;
         if (this.renderedSectionQuietFrames > 0) {
            this.renderedSectionQuietFrames--;
         }

         if (this.renderedSectionQuietFrames <= 0) {
            boolean changed = false;
            LongIterator var2 = this.pendingRenderedSections.iterator();

            while (var2.hasNext()) {
               long sectionKey = (Long)var2.next();
               int originX = BlockPos.getX(sectionKey);
               int originY = BlockPos.getY(sectionKey);
               int originZ = BlockPos.getZ(sectionKey);
               AABB bounds = new AABB((double)originX, (double)originY, (double)originZ, (double)originX + 16.0, (double)originY + 16.0, (double)originZ + 16.0);
               ObjectIterator var9 = this.entries.values().iterator();

               while (var9.hasNext()) {
                  GpuShadowCache.Entry entry = (GpuShadowCache.Entry)var9.next();
                  double influence = (double)entry.radius + 1.25;
                  if (isBoundsNearLight(bounds, entry.worldX, entry.worldY, entry.worldZ, influence)) {
                     int faceMask = computeAffectedFaceMask(entry, bounds);
                     if (faceMask == 0) {
                        faceMask = 63;
                     }

                     int newlyDirty = faceMask & ~entry.dirtyFaceMask;
                     entry.dirtyFaceMask |= faceMask;
                     entry.staticDirty = true;
                     entry.urgentStaticDirty = true;
                     if (newlyDirty != 0) {
                        changed = true;
                     }
                  }
               }
            }

            this.pendingRenderedSections.clear();
            this.renderedSectionQuietFrames = 0;
            this.renderedSectionDeferralFrames = 0;
            if (changed) {
               this.revision++;
            }
         }
      }
   }

   private static boolean captureConsumerActive() {
      return GpuNeoShadows.isActive() && GpuNeoShadows.usesCubemapShadows() || NeoGpuVanilla.isConfiguredEnabled();
   }

   private int neoGpuFaceRebuildBudget() {
      return Math.max(1, (Integer)Config.CLIENT.neoGpuVanillaCaptureBudget.get());
   }

   private void syncLayout() {
      int requested = NeoGpuVanilla.isConfiguredEnabled()
         ? neoGpuVanillaCapturePixels(
            (Integer)Config.CLIENT.neoGpuVanillaCaptureResolution.get(), Math.max(1, Math.min(40, (Integer)Config.CLIENT.neoGpuVanillaMaxSources.get()))
         )
         : 256;
      requested = nearestPowerOfTwo(Math.max(16, Math.min(512, requested)));
      if (requested != this.tileSize) {
         if (this.fboId != -1) {
            GL30.glDeleteFramebuffers(this.fboId);
            this.fboId = -1;
         }

         if (this.atlasTextureId != -1) {
            TextureUtil.releaseTextureId(this.atlasTextureId);
            this.atlasTextureId = -1;
         }

         if (this.shadowTileTarget != null) {
            this.shadowTileTarget.destroyBuffers();
            this.shadowTileTarget = null;
         }

         this.entries.clear();
         this.removedSources.clear();
         this.pendingRenderedSections.clear();
         this.expectedRenderedSections.clear();
         this.expectedRenderedSectionWaitFrames = 0;
         this.renderedSectionQuietFrames = 0;
         this.renderedSectionDeferralFrames = 0;
         this.tileSize = requested;
         this.tileGrid = Math.max(1, 4096 / this.tileSize);
         this.maxTiles = this.tileGrid * this.tileGrid;
         this.freeTiles = new int[this.maxTiles];
         this.resetFreeTiles();
         this.revision++;
      }
   }

   private static int neoGpuVanillaCapturePixels(int pixelsPerBlock, int requestedSources) {
      int p = Math.max(1, pixelsPerBlock);
      int capturePixels = p >= 32 ? 512 : 16 * p;
      capturePixels = nearestPowerOfTwo(Math.max(16, Math.min(512, capturePixels)));

      for (int wanted = Math.max(1, Math.min(40, requestedSources)); capturePixels > 16; capturePixels >>= 1) {
         int grid = Math.max(1, 4096 / capturePixels);
         int capacity = grid * grid / 6;
         if (capacity >= wanted) {
            break;
         }
      }

      return capturePixels;
   }

   private static int nearestPowerOfTwo(int value) {
      int[] values = new int[]{16, 32, 64, 128, 256, 512};
      int best = values[0];
      int bestDistance = Math.abs(value - best);

      for (int candidate : values) {
         int distance = Math.abs(value - candidate);
         if (distance < bestDistance) {
            best = candidate;
            bestDistance = distance;
         }
      }

      return best;
   }

   private void resetFreeTiles() {
      this.freeTileCount = this.maxTiles;
      if (this.freeTiles.length != this.maxTiles) {
         this.freeTiles = new int[this.maxTiles];
      }

      for (int i = 0; i < this.maxTiles; i++) {
         this.freeTiles[i] = this.maxTiles - 1 - i;
      }
   }

   private static final class Entry {
      final int[] tileIndices = new int[6];
      final float[] u0 = new float[6];
      final float[] v0 = new float[6];
      float us;
      float vs;
      float radius;
      int emission;
      int dirtyFaceMask;
      int validFaceMask;
      boolean ready;
      boolean staticDirty;
      boolean dynamicDirty;
      boolean urgentStaticDirty;
      boolean boxEmitter;
      int blockedFaceMask;
      double worldX;
      double worldY;
      double worldZ;
      double blockCenterX;
      double blockCenterY;
      double blockCenterZ;
      int framesAlive = 0;
      int refreshCount = 0;
      int stagedPriority = Integer.MAX_VALUE;
      long lastVisibleFrame;
      long neoGpuContentGeneration;
   }
}
