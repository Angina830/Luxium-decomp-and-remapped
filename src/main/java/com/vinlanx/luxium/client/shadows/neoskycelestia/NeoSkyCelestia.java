package com.vinlanx.luxium.client.shadows.neoskycelestia;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.clouds.LuxiumCloudRenderer;
import com.vinlanx.luxium.client.shaders.LuxiumGpuShaderFeatures;
import com.vinlanx.luxium.client.shadows.neoskycelestia.embeddium.NeoSkyEmbeddiumShadowBridge;
import com.vinlanx.luxium.client.shadows.neoskycelestia.lightmap.NeoSkyCelestiaLightLut;
import com.vinlanx.luxium.client.shadows.neoskycelestia.lightmap.NeoSkyLightTextureExtension;
import com.vinlanx.luxium.client.sunmoonapi.CelestialPath;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

public final class NeoSkyCelestia {
   private static final NeoSkyCelestia INSTANCE = new NeoSkyCelestia();
   private static final boolean OCULUS_LOADED = ModList.get().isLoaded("oculus");
   private final NeoSkyCascade near = new NeoSkyCascade("near");
   private final NeoSkyCascade far = new NeoSkyCascade("far");
   private final NeoSkyEntityShadowMap entityShadows = new NeoSkyEntityShadowMap();
   private final NeoSkyEmbeddiumShadowBridge embeddium = new NeoSkyEmbeddiumShadowBridge();
   private final NeoSkyCelestiaLightLut celestialLightLut = new NeoSkyCelestiaLightLut();
   private NeoSkyCelestiaFrameState frameState;
   private ClientLevel boundLevel;
   private int fallbackDepthTexture;
   private boolean mainPassActive;
   private boolean rebuilding;
   private boolean terrainShadowPass;
   private boolean cloudShadowPass;
   private boolean entityShadowPass;
   private float cloudPartialTick;
   private boolean inlineTerrainPass;
   private final Matrix4f nearLightFromWorldRelative = new Matrix4f();
   private final Matrix4f farLightFromWorldRelative = new Matrix4f();
   private final Matrix4f entityLightFromWorldRelative = new Matrix4f();
   private final Matrix4f inverseMainViewRotation = new Matrix4f();

   private NeoSkyCelestia() {
   }

   public static NeoSkyCelestia get() {
      return INSTANCE;
   }

   public void prepareForMainWorldRender(Minecraft mc, Camera camera, float partialTick, Matrix4f mainProjection, Matrix4f mainViewRotation) {
      RenderSystem.assertOnRenderThread();
      this.mainPassActive = false;
      this.inlineTerrainPass = false;
      this.cloudPartialTick = partialTick;
      this.ensureFallbackDepthTexture();
      if (!this.canRun(mc)) {
         this.publishDisabledState(mc);
      } else {
         if (this.boundLevel != mc.level) {
            this.onWorldChanged(mc.level);
         }

         this.configureCascades();
         CelestialPath.State celestialPath = NeoSkyFrameCache.celestial(mc.level, partialTick);
         Vec3 toLight = celestialPath.activeDirection();
         NeoSkyCelestiaLighting.State lighting = NeoSkyFrameCache.lighting(mc.level, partialTick);
         float horizonVisibility = NeoSkyCelestiaMath.horizonFade(toLight);
         boolean wantsLighting = this.isGpuModeSelected() && lighting.enabled();
         boolean wantsShadows = wantsLighting && this.isGpuModeSelected() && horizonVisibility > 0.005F;
         if (!wantsShadows && !wantsLighting) {
            this.publishDisabledState(mc);
         } else {
            Vec3 cameraPosition = camera.getPosition();
            long now = System.nanoTime();
            boolean wantsEntityShadows = wantsShadows && Config.isFeatureEnabled(Config.CLIENT.skyEntityShadowsEnabled);
            double nearMovementThreshold = Math.max(1.5, (double)this.near.radius() * 16.0 / (double)this.near.resolution());
            long nearUpdateIntervalNanos = Math.max(10000000L, (long)((Double)Config.CLIENT.skyShadowNearUpdateIntervalSeconds.get() * 1.0E9));
            boolean buildNear = wantsShadows && this.near.shouldBuild(cameraPosition, toLight, nearMovementThreshold, 0.0, nearUpdateIntervalNanos, now);
            boolean buildFar = wantsShadows
               && this.far
                  .shouldBuild(
                     cameraPosition,
                     toLight,
                     4.0,
                     Config.isFeatureEnabled(Config.CLIENT.skyCloudShadowsEnabled) ? 0.0 : 0.75,
                     (long)((Integer)Config.CLIENT.skyShadowFarUpdateMs.get()).intValue() * 1000000L,
                     now
                  );
            long nearGenerationBefore = this.near.generation();
            long farGenerationBefore = this.far.generation();
            if (buildNear || buildFar) {
               this.rebuilding = true;

               try {
                  Matrix4f sharedLightRotation = NeoSkyCelestiaMath.buildLightViewRotation(toLight);
                  if (buildNear) {
                     this.near.prepareReceiver(cameraPosition, toLight, sharedLightRotation);
                  }

                  if (buildFar) {
                     this.far.prepareReceiver(cameraPosition, toLight, sharedLightRotation);
                  }

                  NeoSkyEmbeddiumShadowBridge.PreparedRenderLists prepared = this.embeddium.prepareRenderLists(this.near, buildNear, this.far, buildFar);
                  if (prepared == null) {
                     if (buildNear) {
                        this.near.markDirty();
                     }

                     if (buildFar) {
                        this.far.markDirty();
                     }
                  } else {
                     if (buildNear) {
                        this.near.finalizeCasterVolume(prepared.nearCasterFront(), computeAuxiliaryCasterFront(mc, this.near));
                        this.rebuildPreparedCascade(this.near, toLight, now, prepared.renderer(), prepared.near(), prepared.nearIncomplete());
                     }

                     if (buildFar) {
                        this.far.finalizeCasterVolume(prepared.farCasterFront(), computeAuxiliaryCasterFront(mc, this.far));
                        this.rebuildPreparedCascade(this.far, toLight, now, prepared.renderer(), prepared.far(), prepared.farIncomplete());
                     }
                  }
               } finally {
                  this.rebuilding = false;
                  restoreKnownMainRenderState(mc);
               }
            }

            this.entityShadows
               .configure((Integer)Config.CLIENT.skyEntityShadowResolution.get(), (float)((Integer)Config.CLIENT.skyEntityShadowRadius.get()).intValue());
            if (wantsEntityShadows && this.entityShadows.shouldUpdate(now)) {
               this.rebuilding = true;

               try {
                  this.entityShadows.render(mc, cameraPosition, toLight, partialTick, now);
               } finally {
                  this.rebuilding = false;
                  restoreKnownMainRenderState(mc);
               }
            }

            NeoSkyFrameCache.copyInverseView(mainViewRotation, this.inverseMainViewRotation);
            this.near.updateReceiverMatrix(cameraPosition, this.inverseMainViewRotation);
            this.far.updateReceiverMatrix(cameraPosition, this.inverseMainViewRotation);
            this.entityShadows.updateReceiverMatrix(cameraPosition, this.inverseMainViewRotation);
            this.nearLightFromWorldRelative.set(this.near.lightFromMainView()).mul(mainViewRotation);
            this.farLightFromWorldRelative.set(this.far.lightFromMainView()).mul(mainViewRotation);
            this.entityLightFromWorldRelative.set(this.entityShadows.lightFromMainView()).mul(mainViewRotation);
            int blockOnlyTexture = getBlockOnlyTexture(mc.gameRenderer.lightTexture());
            boolean cascadesReady = wantsShadows && this.near.ready() && this.far.ready() && blockOnlyTexture > 0;
            boolean shadowMapUpdated = this.near.generation() != nearGenerationBefore || this.far.generation() != farGenerationBefore;
            float sunShadowBlockVisibility = ((Double)Config.CLIENT.vanillaBlockLightInSunShadows.get()).floatValue() * 0.01F * lighting.sunInfluence();
            int celestialLightLutTexture = this.celestialLightLut.textureId();
            if (wantsLighting && (shadowMapUpdated || celestialLightLutTexture <= 0)) {
               celestialLightLutTexture = this.celestialLightLut.update(mc.gameRenderer.lightTexture(), lighting, cascadesReady, sunShadowBlockVisibility);
            }

            boolean shadowsReady = cascadesReady && celestialLightLutTexture > 0;
            boolean lightReady = wantsLighting && celestialLightLutTexture > 0;
            this.frameState = new NeoSkyCelestiaFrameState(
               shadowsReady,
               lightReady,
               lighting.moon(),
               blockOnlyTexture,
               celestialLightLutTexture,
               this.near.ready() ? this.near.target().depthTextureId() : this.fallbackDepthTexture,
               this.far.ready() ? this.far.target().depthTextureId() : this.fallbackDepthTexture,
               wantsEntityShadows && this.entityShadows.ready(),
               this.entityShadows.ready() ? this.entityShadows.depthTextureId() : this.fallbackDepthTexture,
               this.near.lightFromMainView(),
               this.far.lightFromMainView(),
               this.entityShadows.lightFromMainView(),
               this.near.radius(),
               this.far.radius(),
               this.entityShadows.radius(),
               1.0F / (float)this.near.resolution(),
               1.0F / (float)this.far.resolution(),
               this.entityShadows.texelSize(),
               Config.CLIENT.skyShadowFilterSamples.get() >= 4 ? 4 : 1,
               computeBaseBias(this.near),
               computeBaseBias(this.far),
               this.entityShadows.baseBias(),
               1.0F,
               0.94F,
               new Vector3f(lighting.direction()),
               new Vector3f(lighting.directColor()),
               new Vector3f(lighting.shadowSkyAmbientColor()),
               new Vector3f(lighting.shadowGroundAmbientColor()),
               lighting.directStrength(),
               lighting.ambientStrength(),
               this.near.generation(),
               this.far.generation(),
               this.entityShadows.generation()
            );
            this.mainPassActive = shadowsReady || lightReady;
         }
      }
   }

   private void rebuildPreparedCascade(
      NeoSkyCascade cascade, Vec3 toLight, long now, ChunkRenderer renderer, NeoSkyShadowRenderLists lists, boolean incompleteCoverage
   ) {
      cascade.target().bindAndClear();
      RenderSystem.polygonOffset(1.5F, 1.0F);
      RenderSystem.enablePolygonOffset();
      this.terrainShadowPass = true;

      boolean rendered;
      try {
         RenderSystem.disableCull();
         rendered = this.embeddium.renderCascade(cascade, renderer, lists);
      } finally {
         this.terrainShadowPass = false;
         RenderSystem.polygonOffset(0.0F, 0.0F);
         RenderSystem.disablePolygonOffset();
         RenderSystem.enableCull();
         GL11.glCullFace(1029);
      }

      if (rendered) {
         this.renderCloudCasters(cascade);
      }

      if (rendered) {
         cascade.commitBuild(toLight, now);
         if (incompleteCoverage) {
            cascade.markDirty();
         }
      } else {
         cascade.markDirty();
      }
   }

   private void renderCloudCasters(NeoSkyCascade cascade) {
      Minecraft mc = Minecraft.getInstance();
      if (Config.isFeatureEnabled(Config.CLIENT.skyCloudShadowsEnabled) && mc.levelRenderer != null && mc.options.getCloudsType() != CloudStatus.OFF) {
         Vec3 center = cascade.center();
         Vec3 eye = cascade.eye();
         PoseStack poseStack = new PoseStack();
         poseStack.mulPoseMatrix(
            new Matrix4f(cascade.lightViewRotation()).translate((float)(center.x - eye.x), (float)(center.y - eye.y), (float)(center.z - eye.z))
         );
         this.cloudShadowPass = true;

         try {
            GlStateManager._colorMask(false, false, false, false);
            GlStateManager._enableDepthTest();
            GlStateManager._depthMask(true);
            RenderSystem.depthFunc(513);
            RenderSystem.disableBlend();
            boolean handledCustomClouds = LuxiumCloudRenderer.get()
               .renderCustomShadowCasters(
                  poseStack,
                  cascade.lightProjection(),
                  cascade.lightViewRotation(),
                  cascade.projectionRadius(),
                  cascade.toLight(),
                  this.cloudPartialTick,
                  center.x,
                  center.y,
                  center.z,
                  mc.levelRenderer.getTicks()
               );
            if (!handledCustomClouds) {
               mc.levelRenderer.renderClouds(poseStack, cascade.lightProjection(), this.cloudPartialTick, center.x, center.y, center.z);
            }
         } finally {
            this.cloudShadowPass = false;
            GlStateManager._colorMask(false, false, false, false);
            GlStateManager._enableDepthTest();
            GlStateManager._depthMask(true);
            RenderSystem.depthFunc(513);
            RenderSystem.disableBlend();
         }
      }
   }

   private static void restoreKnownMainRenderState(Minecraft mc) {
      resetSkyGpuShaderBindings();
      if (mc.getMainRenderTarget() != null) {
         mc.getMainRenderTarget().bindWrite(true);
      }

      GlStateManager._activeTexture(33984);
      GlStateManager._colorMask(true, true, true, true);
      GlStateManager._depthMask(true);
      RenderSystem.polygonOffset(0.0F, 0.0F);
      RenderSystem.disablePolygonOffset();
      GlStateManager._enableDepthTest();
      RenderSystem.depthFunc(515);
      RenderSystem.disableBlend();
      RenderSystem.enableCull();
      GL11.glCullFace(1029);
   }

   public void beginInlineTerrainPass() {
      if (this.isMainPassActive() && !this.inlineTerrainPass) {
         this.inlineTerrainPass = true;
      }
   }

   public void finishInlineTerrainPass() {
      this.inlineTerrainPass = false;
   }

   public boolean isInlineTerrainPassActive() {
      return this.inlineTerrainPass;
   }

   public Matrix4f nearLightFromWorldRelative() {
      return this.nearLightFromWorldRelative;
   }

   public Matrix4f farLightFromWorldRelative() {
      return this.farLightFromWorldRelative;
   }

   public Matrix4f entityLightFromWorldRelative() {
      return this.entityLightFromWorldRelative;
   }

   public void finishMainWorldRender() {
      this.inlineTerrainPass = false;
      boolean wasActive = this.mainPassActive;
      this.mainPassActive = false;
      if (wasActive) {
         resetSkyGpuShaderBindings();
      }
   }

   private static void resetSkyGpuShaderBindings() {
      ShaderInstance trackedShader = RenderSystem.getShader();
      if (trackedShader != null) {
         trackedShader.clear();
      } else {
         GlStateManager._glUseProgram(0);
      }

      for (int unit = 2; unit <= 6; unit++) {
         GlStateManager._activeTexture(33984 + unit);
         GlStateManager._bindTexture(0);
         GL11.glBindTexture(32879, 0);
         RenderSystem.setShaderTexture(unit, 0);
      }

      GlStateManager._activeTexture(33984);
   }

   public boolean isMainPassActive() {
      return this.mainPassActive && !this.rebuilding;
   }

   public boolean isRebuildingShadowMap() {
      return this.rebuilding;
   }

   public boolean isRenderingTerrainShadowPass() {
      return this.terrainShadowPass;
   }

   public boolean isRenderingCloudShadowPass() {
      return this.cloudShadowPass;
   }

   public boolean isRenderingEntityShadowPass() {
      return this.entityShadowPass;
   }

   public void setEntityShadowPass(boolean active) {
      this.entityShadowPass = active;
   }

   public void bindEntityShadowTarget() {
      if (this.entityShadowPass) {
         this.entityShadows.bindTarget();
      }
   }

   public boolean isConfiguredAndAvailable() {
      Minecraft mc = Minecraft.getInstance();
      return this.isGpuModeSelected() && this.embeddium.isAvailable();
   }

   public boolean isGpuModeSelected() {
      Minecraft mc = Minecraft.getInstance();
      return LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled()
         && !LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled()
         && !LuxiumGpuShaderFeatures.localReceiverEnabled()
         && !OCULUS_LOADED
         && mc.level != null
         && mc.level.dimensionType().hasSkyLight();
   }

   public NeoSkyCelestiaFrameState frameState() {
      if (this.frameState == null) {
         this.ensureFallbackDepthTexture();
         this.frameState = NeoSkyCelestiaFrameState.disabled(this.celestialLightLut.textureId(), this.fallbackDepthTexture);
      }

      return this.frameState;
   }

   public void markBlockDirty(int x, int y, int z) {
      boolean nearDirty = this.near.intersectsBlock(x, y, z);
      boolean farDirty = this.far.intersectsBlock(x, y, z);
      if (nearDirty || farDirty) {
         if (nearDirty) {
            this.near.markDirty();
         }

         if (farDirty) {
            this.far.markDirty();
         }
      }
   }

   public void markChunkDirty(int chunkX, int chunkZ) {
      ClientLevel level = this.boundLevel;
      if (level == null) {
         this.markAllDirty();
      } else {
         int minY = level.getMinBuildHeight();
         int maxY = level.getMaxBuildHeight();
         boolean nearDirty = this.near.intersectsChunk(chunkX, chunkZ, minY, maxY);
         boolean farDirty = this.far.intersectsChunk(chunkX, chunkZ, minY, maxY);
         if (nearDirty || farDirty) {
            if (nearDirty) {
               this.near.markDirty();
            }

            if (farDirty) {
               this.far.markDirty();
            }
         }
      }
   }

   public void markAllDirty() {
      this.near.markDirty();
      this.far.markDirty();
   }

   public void onWorldChanged(ClientLevel level) {
      this.boundLevel = level;
      this.near.invalidate();
      this.far.invalidate();
      this.entityShadows.invalidate();
      this.embeddium.clearCaches();
      this.publishDisabledState(Minecraft.getInstance());
   }

   public void close() {
      RenderSystem.assertOnRenderThread();
      this.near.close();
      this.far.close();
      this.entityShadows.close();
      this.celestialLightLut.close();
      this.embeddium.clearCaches();
      if (this.fallbackDepthTexture != 0) {
         GlStateManager._deleteTexture(this.fallbackDepthTexture);
         this.fallbackDepthTexture = 0;
      }

      this.frameState = null;
      this.boundLevel = null;
      this.mainPassActive = false;
      this.rebuilding = false;
      this.terrainShadowPass = false;
      this.cloudShadowPass = false;
      this.entityShadowPass = false;
      this.inlineTerrainPass = false;
   }

   private boolean canRun(Minecraft mc) {
      return this.isGpuModeSelected()
         && this.embeddium.isAvailable()
         && mc.player != null
         && !NeoShadowsEngine.isAnyShadowCapturePass()
         && !ReflectionSystem.isRenderingWorldPass();
   }

   private void configureCascades() {
      float nearRadius = (float)((Integer)Config.CLIENT.skyShadowNearRadius.get()).intValue();
      float farRadius = Math.max((float)((Integer)Config.CLIENT.skyShadowFarRadius.get()).intValue(), nearRadius + 16.0F);
      float minimumCasterReach = (float)((Integer)Config.CLIENT.skyShadowRayLength.get()).intValue();
      this.near.configure((Integer)Config.CLIENT.skyShadowNearResolution.get(), nearRadius, minimumCasterReach);
      this.far.configure((Integer)Config.CLIENT.skyShadowFarResolution.get(), farRadius, minimumCasterReach);
      boolean softShadows = (Boolean)Config.CLIENT.skyShadowSoftShadowsEnabled.get();
      this.near.target().setLinearFiltering(softShadows);
      this.far.target().setLinearFiltering(softShadows);
   }

   private static float computeAuxiliaryCasterFront(Minecraft mc, NeoSkyCascade cascade) {
      if (mc != null && mc.level != null && Config.isFeatureEnabled(Config.CLIENT.skyCloudShadowsEnabled) && mc.options.getCloudsType() != CloudStatus.OFF) {
         float cloudHeight = mc.level.effects().getCloudHeight();
         if (!Float.isFinite(cloudHeight)) {
            return Float.NaN;
         } else if (Config.isFeatureEnabled(Config.CLIENT.cloudsEnabled)) {
            return LuxiumCloudRenderer.get()
               .computeCustomShadowCasterFront(
                  cascade.center(), cascade.toLight(), cascade.lightViewRotation(), cascade.projectionRadius(), mc.options.getCloudsType()
               );
         } else {
            Vec3 center = cascade.center();
            Vec3 toLight = cascade.toLight();
            if (toLight.y <= 0.0) {
               return Float.NaN;
            } else {
               double cloudCasterReach = ((double)cloudHeight - center.y) / toLight.y;
               double receiverFront = (double)cascade.projectionRadius() * (Math.abs(toLight.x) + Math.abs(toLight.z));
               return (float)(cloudCasterReach + receiverFront + 16.0);
            }
         }
      } else {
         return Float.NaN;
      }
   }

   private void publishDisabledState(Minecraft mc) {
      this.ensureFallbackDepthTexture();
      this.frameState = NeoSkyCelestiaFrameState.disabled(this.celestialLightLut.textureId(), this.fallbackDepthTexture);
      this.mainPassActive = false;
   }

   private static int getBlockOnlyTexture(LightTexture lightTexture) {
      return ((NeoSkyLightTextureExtension)lightTexture).neosky$getBlockOnlyLightTextureId();
   }

   private static float computeBaseBias(NeoSkyCascade cascade) {
      float worldTexel = cascade.projectionRadius() * 2.0F / (float)Math.max(1, cascade.resolution());
      float worldBias = Math.max(0.008F, Math.min(0.04F, worldTexel * 0.18F));
      float depthRange = Math.max(1.0F, cascade.depthRange());
      return worldBias / depthRange;
   }

   private void ensureFallbackDepthTexture() {
      if (this.fallbackDepthTexture == 0) {
         this.fallbackDepthTexture = GlStateManager._genTexture();
         GlStateManager._bindTexture(this.fallbackDepthTexture);
         MemoryStack stack = MemoryStack.stackPush();

         try {
            GL11.glTexImage2D(3553, 0, 33190, 1, 1, 0, 6402, 5126, stack.floats(1.0F));
         } catch (Throwable var5) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var4) {
                  var5.addSuppressed(var4);
               }
            }

            throw var5;
         }

         if (stack != null) {
            stack.close();
         }

         GL11.glTexParameteri(3553, 10241, 9728);
         GL11.glTexParameteri(3553, 10240, 9728);
      }
   }
}
