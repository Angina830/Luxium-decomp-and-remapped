package com.vinlanx.luxium.mixin.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.SharedPostResources;
import com.vinlanx.luxium.client.BlockLightTest.BlockLightTest;
import com.vinlanx.luxium.client.plantswave.PlantWaveUniformState;
import com.vinlanx.luxium.client.shaders.LuxiumGpuShaderFeatures;
import com.vinlanx.luxium.client.shadows.GpuNeoShadows;
import com.vinlanx.luxium.client.shadows.GpuShadowFrameState;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestiaFrameState;
import com.vinlanx.luxium.client.ssr.ScreenSpaceReflectionSystem;
import com.vinlanx.luxium.client.ssr.SsrConsumer;
import com.vinlanx.luxium.client.ssr.SsrSettings;
import com.vinlanx.luxium.client.water.WaterSurfaceState;
import com.vinlanx.luxium.client.water.WaterTerrainPass;
import com.vinlanx.luxium.client.water.WaterTextureResources;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanillaCutoutPrepass;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanillaFrameState;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {ChunkShaderInterface.class},
   remap = false
)
public abstract class EmbeddiumChunkShaderInterfaceMixin {
   @Unique
   private GlUniformInt luxium$blockOnlySampler;
   @Unique
   private GlUniformInt luxium$celestialLutSampler;
   @Unique
   private GlUniformInt luxium$nearShadowSampler;
   @Unique
   private GlUniformInt luxium$farShadowSampler;
   @Unique
   private GlUniformInt luxium$entityShadowSampler;
   @Unique
   private GlUniformInt luxium$enabled;
   @Unique
   private GlUniformInt luxium$entityShadowEnabled;
   @Unique
   private GlUniformInt luxium$skyLightEnabled;
   @Unique
   private GlUniformInt luxium$filterSamples;
   @Unique
   private GlUniformInt luxium$shadowPass;
   @Unique
   private GlUniformInt luxium$neoSkyInlineActive;
   @Unique
   private GlUniformInt luxium$neoSkyInlineLutSampler;
   @Unique
   private GlUniformInt luxium$neoSkyInlineNearSampler;
   @Unique
   private GlUniformInt luxium$neoSkyInlineFarSampler;
   @Unique
   private GlUniformInt luxium$neoSkyInlineEntitySampler;
   @Unique
   private GlUniformInt luxium$neoSkyInlineEnabled;
   @Unique
   private GlUniformInt luxium$neoSkyInlineLightEnabled;
   @Unique
   private GlUniformInt luxium$neoSkyInlineEntityEnabled;
   @Unique
   private GlUniformInt luxium$neoSkyInlineFilterSamples;
   @Unique
   private GlUniformFloat4v luxium$neoSkyInlineCascadeData;
   @Unique
   private GlUniformFloat4v luxium$neoSkyInlineBiasData;
   @Unique
   private GlUniformFloat4v luxium$neoSkyInlineEntityData;
   @Unique
   private GlUniformMatrix4f luxium$neoSkyInlineNearWorldMatrix;
   @Unique
   private GlUniformMatrix4f luxium$neoSkyInlineFarWorldMatrix;
   @Unique
   private GlUniformMatrix4f luxium$neoSkyInlineEntityWorldMatrix;
   @Unique
   private GlUniformFloat luxium$directStrength;
   @Unique
   private GlUniformFloat luxium$ambientStrength;
   @Unique
   private GlUniformFloat luxium$vanillaBlockLightInSunShadows;
   @Unique
   private GlUniformFloat3v luxium$lightDirection;
   @Unique
   private GlUniformFloat3v luxium$directColor;
   @Unique
   private GlUniformFloat3v luxium$skyAmbientColor;
   @Unique
   private GlUniformFloat3v luxium$groundAmbientColor;
   @Unique
   private GlUniformFloat4v luxium$cascadeData;
   @Unique
   private GlUniformFloat4v luxium$biasData;
   @Unique
   private GlUniformFloat4v luxium$entityShadowData;
   @Unique
   private GlUniformMatrix4f luxium$nearMatrix;
   @Unique
   private GlUniformMatrix4f luxium$farMatrix;
   @Unique
   private GlUniformMatrix4f luxium$entityMatrix;
   @Unique
   private final float[] luxium$cascadeValues = new float[4];
   @Unique
   private final float[] luxium$biasValues = new float[4];
   @Unique
   private final float[] luxium$neoSkyInlineEntityValues = new float[4];
   @Unique
   private boolean luxium$skyOnlyPermutation;
   @Unique
   private boolean luxium$neoSkyForwardPermutation;
   @Unique
   private GlUniformInt luxium$localAtlasSampler;
   @Unique
   private GlUniformInt luxium$localGridSampler;
   @Unique
   private GlUniformInt luxium$localEnabled;
   @Unique
   private GlUniformInt luxium$localReplaceBlockLight;
   @Unique
   private GlUniformInt luxium$localSoft;
   @Unique
   private GlUniformInt luxium$localMode;
   @Unique
   private GlUniformInt luxium$localCount;
   @Unique
   private GlUniformFloat luxium$localSpreadOcclusion;
   @Unique
   private GlUniformInt luxium$localBakedHardEnabled;
   @Unique
   private final GlUniformInt[] luxium$localBakedHardSamplers = new GlUniformInt[6];
   @Unique
   private GlUniformFloat3v luxium$localBakedHardMin;
   @Unique
   private GlUniformFloat3v luxium$localBakedHardSize;
   @Unique
   private int luxium$localProgram = -1;
   @Unique
   private int luxium$localLightsLocation = -1;
   @Unique
   private int luxium$localRectsLocation = -1;
   @Unique
   private long luxium$localUploadedVersion = Long.MIN_VALUE;
   @Unique
   private GlUniformInt luxium$neoGpuSampler;
   @Unique
   private GlUniformInt luxium$neoGpuEnabled;
   @Unique
   private GlUniformInt luxium$neoGpuFineScale;
   @Unique
   private GlUniformInt luxium$neoGpuDepthPrepass;
   @Unique
   private GlUniformInt luxium$neoGpuFastEnabled;
   @Unique
   private final GlUniformInt[] luxium$neoGpuFastSamplers = new GlUniformInt[6];
   @Unique
   private GlUniformFloat3v luxium$neoGpuMin;
   @Unique
   private GlUniformFloat3v luxium$neoGpuSize;
   @Unique
   private GlUniformInt luxium$plantWaveEnabled;
   @Unique
   private GlUniformFloat luxium$plantWaveTime;
   @Unique
   private GlUniformFloat3v luxium$plantWaveCamera;
   @Unique
   private GlUniformFloat4v luxium$plantWaveSettings0;
   @Unique
   private GlUniformFloat4v luxium$plantWaveSettings1;
   @Unique
   private final float[] luxium$plantWaveSettings0Values = new float[4];
   @Unique
   private final float[] luxium$plantWaveSettings1Values = new float[4];
   @Unique
   private GlUniformInt luxium$waterSceneColor;
   @Unique
   private GlUniformInt luxium$waterSceneDepth;
   @Unique
   private GlUniformInt luxium$waterResolvedColor;
   @Unique
   private GlUniformInt luxium$waterDepthAware;
   @Unique
   private GlUniformInt luxium$waterRenderMode;
   @Unique
   private GlUniformInt luxium$waterMicroNormal;
   @Unique
   private GlUniformInt luxium$waterMicroReady;
   @Unique
   private GlUniformInt luxium$ssrHitTexture;
   @Unique
   private GlUniformInt luxium$waterNearShadowSampler;
   @Unique
   private GlUniformInt luxium$waterFarShadowSampler;
   @Unique
   private GlUniformInt luxium$waterSkyShadowEnabled;
   @Unique
   private GlUniformInt luxium$waterFilterSamples;
   @Unique
   private GlUniformFloat4v luxium$waterFrame;
   @Unique
   private GlUniformFloat4v luxium$waterWave;
   @Unique
   private GlUniformFloat4v luxium$waterOptics;
   @Unique
   private GlUniformFloat4v luxium$waterSurface;
   @Unique
   private GlUniformFloat4v luxium$waterCascadeData;
   @Unique
   private GlUniformFloat4v luxium$waterBiasData;
   @Unique
   private GlUniformMatrix4f luxium$waterNearMatrix;
   @Unique
   private GlUniformMatrix4f luxium$waterFarMatrix;
   @Unique
   private GlUniformFloat3v luxium$waterCameraPos;
   @Unique
   private GlUniformFloat3v luxium$waterLightDir;
   @Unique
   private GlUniformFloat3v luxium$waterLightColor;
   @Unique
   private GlUniformInt luxium$ssrEnabled;
   @Unique
   private GlUniformFloat4v luxium$ssrTrace;
   @Unique
   private GlUniformFloat4v luxium$ssrResolve;
   @Unique
   private final float[] luxium$waterFrameValues = new float[4];
   @Unique
   private final float[] luxium$waterWaveValues = new float[4];
   @Unique
   private final float[] luxium$waterOpticsValues = new float[4];
   @Unique
   private final float[] luxium$waterSurfaceValues = new float[4];
   @Unique
   private final float[] luxium$waterCascadeValues = new float[4];
   @Unique
   private final float[] luxium$waterBiasValues = new float[4];
   @Unique
   private final float[] luxium$ssrTraceValues = new float[4];
   @Unique
   private final float[] luxium$ssrResolveValues = new float[4];
   @Unique
   private static int luxium$maxFragmentTextureUnits = -1;

   @Inject(
      method = {"setupState"},
      at = {@At("TAIL")},
      require = 0
   )
   private void luxium$uploadBlockLightTest(CallbackInfo ci) {
      if (BlockLightTest.enabled()) {
         BlockLightTest.bind(GL11.glGetInteger(35725), false);
         GlStateManager._activeTexture(33984);
      }
   }

   @Inject(
      method = {"<init>"},
      at = {@At("TAIL")}
   )
   private void luxium$bindShadowUniforms(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
      boolean waterEnabled = Config.isFeatureEnabled(Config.CLIENT.waterEnabled);
      boolean waterPass = waterEnabled && WaterTerrainPass.is(options.pass());
      if (!waterPass) {
         this.luxium$plantWaveEnabled = (GlUniformInt)context.bindUniform("u_LuxiumPlantWaveEnabled", GlUniformInt::new);
         this.luxium$plantWaveTime = (GlUniformFloat)context.bindUniform("u_LuxiumPlantWaveTime", GlUniformFloat::new);
         this.luxium$plantWaveCamera = (GlUniformFloat3v)context.bindUniform("u_LuxiumPlantWaveCamera", GlUniformFloat3v::new);
         this.luxium$plantWaveSettings0 = (GlUniformFloat4v)context.bindUniform("u_LuxiumPlantWaveSettings0", GlUniformFloat4v::new);
         this.luxium$plantWaveSettings1 = (GlUniformFloat4v)context.bindUniform("u_LuxiumPlantWaveSettings1", GlUniformFloat4v::new);
      }

      if (!BlockLightTest.enabled() || LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled() || waterPass) {
         boolean receiverShaders = LuxiumGpuShaderFeatures.receiverShadersEnabled();
         boolean neoSky = LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled()
            && !LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled()
            && !LuxiumGpuShaderFeatures.localReceiverEnabled()
            && !waterPass;
         boolean neoSkyForward = neoSky && options.pass().isReverseOrder();
         boolean neoSkyInline = neoSky && !neoSkyForward;
         this.luxium$neoSkyForwardPermutation = neoSkyForward;
         if (receiverShaders || waterPass || neoSky) {
            if (!receiverShaders
               || !LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled()
               || !options.pass().supportsFragmentDiscard()
               || Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaCutoutEnabled)) {
               if (waterPass) {
                  this.luxium$waterSceneColor = (GlUniformInt)context.bindUniform("u_LuxiumWaterSceneColor", GlUniformInt::new);
                  this.luxium$waterSceneDepth = (GlUniformInt)context.bindUniform("u_LuxiumWaterSceneDepth", GlUniformInt::new);
                  this.luxium$waterResolvedColor = (GlUniformInt)context.bindUniform("u_LuxiumWaterResolvedColor", GlUniformInt::new);
                  this.luxium$waterMicroNormal = (GlUniformInt)context.bindUniform("u_LuxiumWaterMicroNormal", GlUniformInt::new);
                  this.luxium$waterMicroReady = (GlUniformInt)context.bindUniform("u_LuxiumWaterMicroReady", GlUniformInt::new);
                  this.luxium$ssrHitTexture = (GlUniformInt)context.bindUniform("u_LuxiumSsrHitTexture", GlUniformInt::new);
                  this.luxium$waterDepthAware = (GlUniformInt)context.bindUniform("u_LuxiumWaterDepthAware", GlUniformInt::new);
                  this.luxium$waterRenderMode = (GlUniformInt)context.bindUniform("u_LuxiumWaterRenderMode", GlUniformInt::new);
                  this.luxium$waterFrame = (GlUniformFloat4v)context.bindUniform("u_LuxiumWaterFrame", GlUniformFloat4v::new);
                  this.luxium$waterWave = (GlUniformFloat4v)context.bindUniform("u_LuxiumWaterWave", GlUniformFloat4v::new);
                  this.luxium$waterOptics = (GlUniformFloat4v)context.bindUniform("u_LuxiumWaterOptics", GlUniformFloat4v::new);
                  this.luxium$waterSurface = (GlUniformFloat4v)context.bindUniform("u_LuxiumWaterSurface", GlUniformFloat4v::new);
                  this.luxium$waterCameraPos = (GlUniformFloat3v)context.bindUniform("u_LuxiumWaterCameraPos", GlUniformFloat3v::new);
                  this.luxium$waterLightDir = (GlUniformFloat3v)context.bindUniform("u_LuxiumWaterLightDir", GlUniformFloat3v::new);
                  this.luxium$waterLightColor = (GlUniformFloat3v)context.bindUniform("u_LuxiumWaterLightColor", GlUniformFloat3v::new);
                  this.luxium$waterNearShadowSampler = (GlUniformInt)context.bindUniform("u_LuxiumWaterShadowMap0", GlUniformInt::new);
                  this.luxium$waterFarShadowSampler = (GlUniformInt)context.bindUniform("u_LuxiumWaterShadowMap1", GlUniformInt::new);
                  this.luxium$waterSkyShadowEnabled = (GlUniformInt)context.bindUniform("u_LuxiumWaterSkyShadowEnabled", GlUniformInt::new);
                  this.luxium$waterFilterSamples = (GlUniformInt)context.bindUniform("u_LuxiumWaterFilterSamples", GlUniformInt::new);
                  this.luxium$waterCascadeData = (GlUniformFloat4v)context.bindUniform("u_LuxiumWaterCascadeData", GlUniformFloat4v::new);
                  this.luxium$waterBiasData = (GlUniformFloat4v)context.bindUniform("u_LuxiumWaterBiasData", GlUniformFloat4v::new);
                  this.luxium$waterNearMatrix = (GlUniformMatrix4f)context.bindUniform("u_LuxiumWaterLightFromView0", GlUniformMatrix4f::new);
                  this.luxium$waterFarMatrix = (GlUniformMatrix4f)context.bindUniform("u_LuxiumWaterLightFromView1", GlUniformMatrix4f::new);
                  this.luxium$ssrEnabled = (GlUniformInt)context.bindUniform("u_LuxiumSsrEnabled", GlUniformInt::new);
                  this.luxium$ssrTrace = (GlUniformFloat4v)context.bindUniform("u_LuxiumSsrTrace", GlUniformFloat4v::new);
                  this.luxium$ssrResolve = (GlUniformFloat4v)context.bindUniform("u_LuxiumSsrResolve", GlUniformFloat4v::new);
               } else if (neoSkyInline) {
                  this.luxium$neoSkyInlineActive = (GlUniformInt)context.bindUniform("u_LuxiumNeoSkyInlineActive", GlUniformInt::new);
                  this.luxium$neoSkyInlineLutSampler = (GlUniformInt)context.bindUniform("u_LuxiumCelestialLut", GlUniformInt::new);
                  this.luxium$neoSkyInlineNearSampler = (GlUniformInt)context.bindUniform("u_LuxiumShadowMap0", GlUniformInt::new);
                  this.luxium$neoSkyInlineFarSampler = (GlUniformInt)context.bindUniform("u_LuxiumShadowMap1", GlUniformInt::new);
                  this.luxium$neoSkyInlineEntitySampler = (GlUniformInt)context.bindUniform("u_LuxiumEntityShadowMap", GlUniformInt::new);
                  this.luxium$neoSkyInlineEnabled = (GlUniformInt)context.bindUniform("u_LuxiumSkyShadowEnabled", GlUniformInt::new);
                  this.luxium$neoSkyInlineLightEnabled = (GlUniformInt)context.bindUniform("u_LuxiumSkyLightEnabled", GlUniformInt::new);
                  this.luxium$neoSkyInlineEntityEnabled = (GlUniformInt)context.bindUniform("u_LuxiumEntityShadowEnabled", GlUniformInt::new);
                  this.luxium$neoSkyInlineFilterSamples = (GlUniformInt)context.bindUniform("u_LuxiumFilterSamples", GlUniformInt::new);
                  this.luxium$neoSkyInlineCascadeData = (GlUniformFloat4v)context.bindUniform("u_LuxiumCascadeData", GlUniformFloat4v::new);
                  this.luxium$neoSkyInlineBiasData = (GlUniformFloat4v)context.bindUniform("u_LuxiumBiasData", GlUniformFloat4v::new);
                  this.luxium$neoSkyInlineEntityData = (GlUniformFloat4v)context.bindUniform("u_LuxiumEntityShadowData", GlUniformFloat4v::new);
                  this.luxium$neoSkyInlineNearWorldMatrix = (GlUniformMatrix4f)context.bindUniform("u_LuxiumLightFromWorldRelative0", GlUniformMatrix4f::new);
                  this.luxium$neoSkyInlineFarWorldMatrix = (GlUniformMatrix4f)context.bindUniform("u_LuxiumLightFromWorldRelative1", GlUniformMatrix4f::new);
                  this.luxium$neoSkyInlineEntityWorldMatrix = (GlUniformMatrix4f)context.bindUniform(
                     "u_LuxiumEntityLightFromWorldRelative", GlUniformMatrix4f::new
                  );
               } else if (receiverShaders || neoSkyForward) {
                  this.luxium$skyOnlyPermutation = neoSkyForward
                     || LuxiumGpuShaderFeatures.skyReceiverEnabled()
                        && !LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled()
                        && !LuxiumGpuShaderFeatures.localReceiverEnabled();
                  if (this.luxium$skyOnlyPermutation) {
                     this.luxium$celestialLutSampler = (GlUniformInt)context.bindUniform("u_LuxiumCelestialLut", GlUniformInt::new);
                  } else {
                     this.luxium$blockOnlySampler = (GlUniformInt)context.bindUniform("u_LuxiumBlockOnlyLightTex", GlUniformInt::new);
                  }

                  this.luxium$nearShadowSampler = (GlUniformInt)context.bindUniform("u_LuxiumShadowMap0", GlUniformInt::new);
                  this.luxium$farShadowSampler = (GlUniformInt)context.bindUniform("u_LuxiumShadowMap1", GlUniformInt::new);
                  boolean entityShadowPermutation = Config.isFeatureEnabled(Config.CLIENT.skyEntityShadowsEnabled);
                  if (entityShadowPermutation) {
                     this.luxium$entityShadowSampler = (GlUniformInt)context.bindUniform("u_LuxiumEntityShadowMap", GlUniformInt::new);
                     this.luxium$entityShadowEnabled = (GlUniformInt)context.bindUniform("u_LuxiumEntityShadowEnabled", GlUniformInt::new);
                  }

                  this.luxium$enabled = (GlUniformInt)context.bindUniform("u_LuxiumSkyShadowEnabled", GlUniformInt::new);
                  this.luxium$skyLightEnabled = (GlUniformInt)context.bindUniform("u_LuxiumSkyLightEnabled", GlUniformInt::new);
                  this.luxium$filterSamples = (GlUniformInt)context.bindUniform("u_LuxiumFilterSamples", GlUniformInt::new);
                  this.luxium$shadowPass = (GlUniformInt)context.bindUniform("u_LuxiumShadowPass", GlUniformInt::new);
                  if (!this.luxium$skyOnlyPermutation) {
                     this.luxium$lightDirection = (GlUniformFloat3v)context.bindUniform("u_LuxiumLightDirection", GlUniformFloat3v::new);
                     this.luxium$directStrength = (GlUniformFloat)context.bindUniform("u_LuxiumDirectStrength", GlUniformFloat::new);
                     this.luxium$ambientStrength = (GlUniformFloat)context.bindUniform("u_LuxiumAmbientStrength", GlUniformFloat::new);
                     this.luxium$vanillaBlockLightInSunShadows = (GlUniformFloat)context.bindUniform(
                        "u_LuxiumVanillaBlockLightInSunShadows", GlUniformFloat::new
                     );
                     this.luxium$directColor = (GlUniformFloat3v)context.bindUniform("u_LuxiumDirectColor", GlUniformFloat3v::new);
                     this.luxium$skyAmbientColor = (GlUniformFloat3v)context.bindUniform("u_LuxiumSkyAmbientColor", GlUniformFloat3v::new);
                     this.luxium$groundAmbientColor = (GlUniformFloat3v)context.bindUniform("u_LuxiumGroundAmbientColor", GlUniformFloat3v::new);
                  }

                  this.luxium$cascadeData = (GlUniformFloat4v)context.bindUniform("u_LuxiumCascadeData", GlUniformFloat4v::new);
                  this.luxium$biasData = (GlUniformFloat4v)context.bindUniform("u_LuxiumBiasData", GlUniformFloat4v::new);
                  this.luxium$nearMatrix = (GlUniformMatrix4f)context.bindUniform("u_LuxiumLightFromView0", GlUniformMatrix4f::new);
                  this.luxium$farMatrix = (GlUniformMatrix4f)context.bindUniform("u_LuxiumLightFromView1", GlUniformMatrix4f::new);
                  if (entityShadowPermutation) {
                     this.luxium$entityShadowData = (GlUniformFloat4v)context.bindUniform("u_LuxiumEntityShadowData", GlUniformFloat4v::new);
                     this.luxium$entityMatrix = (GlUniformMatrix4f)context.bindUniform("u_LuxiumEntityLightFromView", GlUniformMatrix4f::new);
                  }

                  if (LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled()) {
                     this.luxium$neoGpuSampler = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuVanillaVolume", GlUniformInt::new);
                     this.luxium$neoGpuFastEnabled = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuFastShadowsEnabled", GlUniformInt::new);
                     this.luxium$neoGpuFastSamplers[0] = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuFastLightPX", GlUniformInt::new);
                     this.luxium$neoGpuFastSamplers[1] = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuFastLightNX", GlUniformInt::new);
                     this.luxium$neoGpuFastSamplers[2] = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuFastLightPY", GlUniformInt::new);
                     this.luxium$neoGpuFastSamplers[3] = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuFastLightNY", GlUniformInt::new);
                     this.luxium$neoGpuFastSamplers[4] = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuFastLightPZ", GlUniformInt::new);
                     this.luxium$neoGpuFastSamplers[5] = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuFastLightNZ", GlUniformInt::new);
                     this.luxium$neoGpuEnabled = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuVanillaEnabled", GlUniformInt::new);
                     this.luxium$neoGpuFineScale = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuVanillaFineScale", GlUniformInt::new);
                     this.luxium$neoGpuMin = (GlUniformFloat3v)context.bindUniform("u_LuxiumNeoGpuVanillaMin", GlUniformFloat3v::new);
                     this.luxium$neoGpuSize = (GlUniformFloat3v)context.bindUniform("u_LuxiumNeoGpuVanillaSize", GlUniformFloat3v::new);
                     this.luxium$neoGpuDepthPrepass = (GlUniformInt)context.bindUniform("u_LuxiumNeoGpuDepthPrepass", GlUniformInt::new);
                  }

                  if (LuxiumGpuShaderFeatures.localReceiverEnabled()) {
                     this.luxium$localAtlasSampler = (GlUniformInt)context.bindUniform("u_LuxiumLocalShadowAtlas", GlUniformInt::new);
                     this.luxium$localSoft = (GlUniformInt)context.bindUniform("u_LuxiumLocalSoftShadows", GlUniformInt::new);
                     this.luxium$localMode = (GlUniformInt)context.bindUniform("u_LuxiumLocalShadowMode", GlUniformInt::new);
                     this.luxium$localGridSampler = (GlUniformInt)context.bindUniform("u_LuxiumLocalLightGrid", GlUniformInt::new);
                     this.luxium$localEnabled = (GlUniformInt)context.bindUniform("u_LuxiumLocalShadowEnabled", GlUniformInt::new);
                     this.luxium$localReplaceBlockLight = (GlUniformInt)context.bindUniform("u_LuxiumLocalReplaceBlockLight", GlUniformInt::new);
                     this.luxium$localSpreadOcclusion = (GlUniformFloat)context.bindUniform("u_LuxiumLocalSpreadOcclusion", GlUniformFloat::new);
                     this.luxium$localCount = (GlUniformInt)context.bindUniform("u_LuxiumLocalLightCount", GlUniformInt::new);
                     this.luxium$localBakedHardEnabled = (GlUniformInt)context.bindUniform("u_LuxiumLocalBakedHardEnabled", GlUniformInt::new);
                     this.luxium$localBakedHardSamplers[0] = (GlUniformInt)context.bindUniform("u_LuxiumLocalBakedHardPX", GlUniformInt::new);
                     this.luxium$localBakedHardSamplers[1] = (GlUniformInt)context.bindUniform("u_LuxiumLocalBakedHardNX", GlUniformInt::new);
                     this.luxium$localBakedHardSamplers[2] = (GlUniformInt)context.bindUniform("u_LuxiumLocalBakedHardPY", GlUniformInt::new);
                     this.luxium$localBakedHardSamplers[3] = (GlUniformInt)context.bindUniform("u_LuxiumLocalBakedHardNY", GlUniformInt::new);
                     this.luxium$localBakedHardSamplers[4] = (GlUniformInt)context.bindUniform("u_LuxiumLocalBakedHardPZ", GlUniformInt::new);
                     this.luxium$localBakedHardSamplers[5] = (GlUniformInt)context.bindUniform("u_LuxiumLocalBakedHardNZ", GlUniformInt::new);
                     this.luxium$localBakedHardMin = (GlUniformFloat3v)context.bindUniform("u_LuxiumLocalBakedHardMin", GlUniformFloat3v::new);
                     this.luxium$localBakedHardSize = (GlUniformFloat3v)context.bindUniform("u_LuxiumLocalBakedHardSize", GlUniformFloat3v::new);
                  }
               }
            }
         }
      }
   }

   @Inject(
      method = {"setupState"},
      at = {@At("TAIL")}
   )
   private void luxium$setupShadowState(CallbackInfo ci) {
      if (this.luxium$plantWaveEnabled != null) {
         this.luxium$setupPlantWaveState();
      }

      if (this.luxium$waterFrame != null) {
         this.luxium$setupWaterState();
      }

      if (this.luxium$neoSkyInlineActive != null) {
         this.luxium$setupNeoSkyInlineState();
         GlStateManager._activeTexture(33984);
      } else if (this.luxium$enabled == null) {
         GlStateManager._activeTexture(33984);
      } else {
         boolean neoDepthPrepass = NeoGpuVanillaCutoutPrepass.isDepthPrepass();
         if (this.luxium$neoGpuDepthPrepass != null) {
            this.luxium$neoGpuDepthPrepass.setInt(neoDepthPrepass ? 1 : 0);
         }

         if (neoDepthPrepass) {
            this.luxium$shadowPass.setInt(0);
         } else {
            NeoSkyCelestia skySystem = NeoSkyCelestia.get();
            boolean casterPass = skySystem.isRebuildingShadowMap() || NeoShadowsEngine.isGpuShadowAtlasCapturePass();
            this.luxium$shadowPass.setInt(casterPass ? 1 : 0);
            if (casterPass) {
               this.luxium$enabled.setInt(0);
               this.luxium$skyLightEnabled.setInt(0);
               if (this.luxium$entityShadowEnabled != null) {
                  this.luxium$entityShadowEnabled.setInt(0);
               }

               if (this.luxium$localEnabled != null) {
                  this.luxium$localEnabled.setInt(0);
               }

               if (this.luxium$localReplaceBlockLight != null) {
                  this.luxium$localReplaceBlockLight.setInt(0);
               }

               if (this.luxium$localCount != null) {
                  this.luxium$localCount.setInt(0);
               }

               if (this.luxium$localMode != null) {
                  this.luxium$localMode.setInt(0);
               }

               if (this.luxium$localSoft != null) {
                  this.luxium$localSoft.setInt(0);
               }

               if (this.luxium$localSpreadOcclusion != null) {
                  this.luxium$localSpreadOcclusion.setFloat(0.0F);
               }

               if (this.luxium$localBakedHardEnabled != null) {
                  this.luxium$localBakedHardEnabled.setInt(0);
               }

               if (this.luxium$neoGpuEnabled != null) {
                  this.luxium$neoGpuEnabled.setInt(0);
               }

               if (this.luxium$neoGpuFastEnabled != null) {
                  this.luxium$neoGpuFastEnabled.setInt(0);
               }

               if (this.luxium$neoGpuFineScale != null) {
                  this.luxium$neoGpuFineScale.setInt(1);
               }
            } else {
               NeoSkyCelestiaFrameState sky = skySystem.frameState();
               boolean skyActive = skySystem.isMainPassActive();
               boolean skyShadows = skyActive && sky.enabled();
               boolean skyLight = skyActive && sky.skyLightEnabled();
               this.luxium$enabled.setInt(skyShadows ? 1 : 0);
               this.luxium$skyLightEnabled.setInt(skyLight ? 1 : 0);
               if (this.luxium$entityShadowEnabled != null) {
                  this.luxium$entityShadowEnabled.setInt(skyActive && sky.entityShadowEnabled() ? 1 : 0);
               }

               if (skyShadows || skyLight) {
                  if (this.luxium$skyOnlyPermutation) {
                     if (this.luxium$neoSkyForwardPermutation) {
                        bindTexture(2, sky.celestialLightLutTexture());
                     } else {
                        bindTexture(2, sky.celestialLightLutTexture());
                     }

                     this.luxium$celestialLutSampler.setInt(2);
                  } else {
                     bindTexture(2, sky.blockOnlyLightTexture());
                     this.luxium$blockOnlySampler.setInt(2);
                  }

                  bindTexture(3, sky.nearDepthTexture());
                  bindTexture(4, sky.farDepthTexture());
                  this.luxium$nearShadowSampler.setInt(3);
                  this.luxium$farShadowSampler.setInt(4);
                  if (this.luxium$entityShadowSampler != null) {
                     bindTexture(5, sky.entityDepthTexture());
                     this.luxium$entityShadowSampler.setInt(5);
                  }

                  this.luxium$filterSamples.setInt(sky.filterSamples());
                  this.luxium$cascadeValues[0] = sky.nearRadius();
                  this.luxium$cascadeValues[1] = sky.farRadius();
                  this.luxium$cascadeValues[2] = sky.nearTexelSize();
                  this.luxium$cascadeValues[3] = sky.farTexelSize();
                  this.luxium$cascadeData.set(this.luxium$cascadeValues);
                  this.luxium$biasValues[0] = sky.baseBiasNear();
                  this.luxium$biasValues[1] = sky.baseBiasFar();
                  this.luxium$biasValues[2] = sky.slopeBias();
                  this.luxium$biasValues[3] = sky.cascadeBlendStart();
                  this.luxium$biasData.set(this.luxium$biasValues);
                  this.luxium$nearMatrix.set(sky.nearLightFromView());
                  this.luxium$farMatrix.set(sky.farLightFromView());
                  if (this.luxium$entityMatrix != null) {
                     this.luxium$entityMatrix.set(sky.entityLightFromView());
                  }

                  if (this.luxium$entityShadowData != null) {
                     this.luxium$entityShadowData.set(new float[]{sky.entityTexelSize(), sky.entityBaseBias(), sky.slopeBias(), sky.entityRadius()});
                  }

                  if (this.luxium$lightDirection != null) {
                     this.luxium$lightDirection.set(sky.lightDirection().x, sky.lightDirection().y, sky.lightDirection().z);
                  }

                  if (this.luxium$directColor != null) {
                     this.luxium$directColor.set(sky.directColor().x, sky.directColor().y, sky.directColor().z);
                     this.luxium$skyAmbientColor.set(sky.skyAmbientColor().x, sky.skyAmbientColor().y, sky.skyAmbientColor().z);
                     this.luxium$groundAmbientColor.set(sky.groundAmbientColor().x, sky.groundAmbientColor().y, sky.groundAmbientColor().z);
                     this.luxium$directStrength.setFloat(sky.directStrength());
                     this.luxium$ambientStrength.setFloat(sky.ambientStrength());
                     this.luxium$vanillaBlockLightInSunShadows
                        .setFloat(sky.moonLight() ? 0.0F : ((Double)Config.CLIENT.vanillaBlockLightInSunShadows.get()).floatValue() * 0.01F);
                  }
               }

               NeoGpuVanillaFrameState neoGpu = NeoGpuVanilla.frameState();
               boolean neoGpuActive = NeoGpuVanilla.isMainPassActive() && neoGpu.enabled();
               boolean neoGpuFastActive = neoGpuActive && neoGpu.fastShadowsEnabled();
               if (this.luxium$neoGpuEnabled != null) {
                  this.luxium$neoGpuEnabled.setInt(neoGpuActive ? 1 : 0);
               }

               if (this.luxium$neoGpuFastEnabled != null) {
                  this.luxium$neoGpuFastEnabled.setInt(neoGpuFastActive ? 1 : 0);
               }

               if (this.luxium$neoGpuFineScale != null) {
                  this.luxium$neoGpuFineScale.setInt(neoGpuActive ? neoGpu.fineScale() : 1);
               }

               if (neoGpuActive) {
                  bindTexture(9, neoGpu.volumeTexture());
                  if (this.luxium$neoGpuSampler != null) {
                     this.luxium$neoGpuSampler.setInt(9);
                  }

                  if (this.luxium$neoGpuMin != null) {
                     this.luxium$neoGpuMin.set(neoGpu.minX(), neoGpu.minY(), neoGpu.minZ());
                  }

                  if (this.luxium$neoGpuSize != null) {
                     this.luxium$neoGpuSize.set(neoGpu.sizeX(), neoGpu.sizeY(), neoGpu.sizeZ());
                  }

                  if (neoGpuFastActive) {
                     int[] textures = new int[]{
                        neoGpu.fastLightPX(), neoGpu.fastLightNX(), neoGpu.fastLightPY(), neoGpu.fastLightNY(), neoGpu.fastLightPZ(), neoGpu.fastLightNZ()
                     };

                     for (int i = 0; i < 6; i++) {
                        bindTexture3D(10 + i, textures[i]);
                        if (this.luxium$neoGpuFastSamplers[i] != null) {
                           this.luxium$neoGpuFastSamplers[i].setInt(10 + i);
                        }
                     }
                  }
               }

               if (this.luxium$localEnabled == null) {
                  GlStateManager._activeTexture(33984);
               } else {
                  GpuShadowFrameState local = GpuNeoShadows.frameState();
                  boolean localActive = GpuNeoShadows.isMainPassActive() && local.enabled();
                  boolean bakedHardActive = localActive && GpuNeoShadows.isBakedHardReceiverReady();
                  this.luxium$localEnabled.setInt(localActive ? 1 : 0);
                  this.luxium$localReplaceBlockLight.setInt(localActive && local.replaceBlockLight() ? 1 : 0);
                  this.luxium$localCount.setInt(localActive && !bakedHardActive ? local.lightCount() : 0);
                  if (this.luxium$localSoft != null) {
                     this.luxium$localSoft.setInt(!bakedHardActive && local.softShadows() ? 1 : 0);
                  }

                  if (this.luxium$localMode != null) {
                     this.luxium$localMode.setInt(local.shadowMode());
                  }

                  this.luxium$localSpreadOcclusion.setFloat(local.spreadOcclusionStrength());
                  if (this.luxium$localBakedHardEnabled != null) {
                     this.luxium$localBakedHardEnabled.setInt(bakedHardActive ? 1 : 0);
                  }

                  if (bakedHardActive) {
                     for (int ix = 0; ix < 6; ix++) {
                        bindTexture3D(8 + ix, GpuNeoShadows.bakedHardTexture(ix));
                        if (this.luxium$localBakedHardSamplers[ix] != null) {
                           this.luxium$localBakedHardSamplers[ix].setInt(8 + ix);
                        }
                     }

                     if (this.luxium$localBakedHardMin != null) {
                        this.luxium$localBakedHardMin.set(GpuNeoShadows.bakedHardMinX(), GpuNeoShadows.bakedHardMinY(), GpuNeoShadows.bakedHardMinZ());
                     }

                     if (this.luxium$localBakedHardSize != null) {
                        this.luxium$localBakedHardSize.set(GpuNeoShadows.bakedHardSizeX(), GpuNeoShadows.bakedHardSizeY(), GpuNeoShadows.bakedHardSizeZ());
                     }
                  }

                  if (localActive && !bakedHardActive) {
                     if (this.luxium$localAtlasSampler != null) {
                        bindTexture(6, local.atlasTexture());
                        this.luxium$localAtlasSampler.setInt(6);
                     }

                     bindTexture(7, local.lightGridTexture());
                     this.luxium$localGridSampler.setInt(7);
                     int program = GL11.glGetInteger(35725);
                     if (program != this.luxium$localProgram) {
                        this.luxium$localProgram = program;
                        this.luxium$localLightsLocation = uniformLocation(program, "u_LuxiumLocalLights");
                        this.luxium$localRectsLocation = uniformLocation(program, "u_LuxiumLocalAtlasRect");
                        this.luxium$localUploadedVersion = Long.MIN_VALUE;
                     }

                     if (this.luxium$localUploadedVersion != local.version()) {
                        if (this.luxium$localLightsLocation >= 0) {
                           GL20.glUniform4fv(this.luxium$localLightsLocation, local.lights());
                        }

                        if (this.luxium$localRectsLocation >= 0) {
                           GL20.glUniform4fv(this.luxium$localRectsLocation, local.atlasRects());
                        }

                        this.luxium$localUploadedVersion = local.version();
                     }
                  }

                  GlStateManager._activeTexture(33984);
               }
            }
         }
      }
   }

   @Unique
   private void luxium$setupPlantWaveState() {
      PlantWaveUniformState state = PlantWaveUniformState.capture();
      this.luxium$plantWaveEnabled.setInt(state.enabled());
      this.luxium$plantWaveTime.setFloat(state.time());
      this.luxium$plantWaveCamera.set(state.cameraX(), state.cameraY(), state.cameraZ());
      this.luxium$plantWaveSettings0Values[0] = state.strength();
      this.luxium$plantWaveSettings0Values[1] = state.speed();
      this.luxium$plantWaveSettings0Values[2] = state.gustStrength();
      this.luxium$plantWaveSettings0Values[3] = state.distance();
      this.luxium$plantWaveSettings1Values[0] = state.grassStrength();
      this.luxium$plantWaveSettings1Values[1] = state.leavesStrength();
      this.luxium$plantWaveSettings1Values[2] = state.aquaticStrength();
      this.luxium$plantWaveSettings1Values[3] = state.bendStrength();
      this.luxium$plantWaveSettings0.set(this.luxium$plantWaveSettings0Values);
      this.luxium$plantWaveSettings1.set(this.luxium$plantWaveSettings1Values);
   }

   @Unique
   private void luxium$setupNeoSkyInlineState() {
      NeoSkyCelestia system = NeoSkyCelestia.get();
      boolean active = system.isInlineTerrainPassActive();
      this.luxium$neoSkyInlineActive.setInt(active ? 1 : 0);
      if (!active) {
         this.luxium$neoSkyInlineEnabled.setInt(0);
         this.luxium$neoSkyInlineLightEnabled.setInt(0);
         this.luxium$neoSkyInlineEntityEnabled.setInt(0);
      } else {
         NeoSkyCelestiaFrameState sky = system.frameState();
         boolean shadows = sky.enabled();
         boolean lighting = sky.skyLightEnabled();
         boolean entity = shadows && sky.entityShadowEnabled();
         this.luxium$neoSkyInlineEnabled.setInt(shadows ? 1 : 0);
         this.luxium$neoSkyInlineLightEnabled.setInt(lighting ? 1 : 0);
         this.luxium$neoSkyInlineEntityEnabled.setInt(entity ? 1 : 0);
         this.luxium$neoSkyInlineFilterSamples.setInt(sky.filterSamples());
         bindTexture(2, sky.celestialLightLutTexture());
         this.luxium$neoSkyInlineLutSampler.setInt(2);
         if (shadows) {
            bindTexture(3, sky.nearDepthTexture());
            bindTexture(4, sky.farDepthTexture());
            this.luxium$neoSkyInlineNearSampler.setInt(3);
            this.luxium$neoSkyInlineFarSampler.setInt(4);
            this.luxium$cascadeValues[0] = sky.nearRadius();
            this.luxium$cascadeValues[1] = sky.farRadius();
            this.luxium$cascadeValues[2] = sky.nearTexelSize();
            this.luxium$cascadeValues[3] = sky.farTexelSize();
            this.luxium$neoSkyInlineCascadeData.set(this.luxium$cascadeValues);
            this.luxium$biasValues[0] = sky.baseBiasNear();
            this.luxium$biasValues[1] = sky.baseBiasFar();
            this.luxium$biasValues[2] = sky.slopeBias();
            this.luxium$biasValues[3] = sky.cascadeBlendStart();
            this.luxium$neoSkyInlineBiasData.set(this.luxium$biasValues);
            this.luxium$neoSkyInlineNearWorldMatrix.set(system.nearLightFromWorldRelative());
            this.luxium$neoSkyInlineFarWorldMatrix.set(system.farLightFromWorldRelative());
         }

         if (entity) {
            bindTexture(5, sky.entityDepthTexture());
            this.luxium$neoSkyInlineEntitySampler.setInt(5);
            this.luxium$neoSkyInlineEntityValues[0] = sky.entityTexelSize();
            this.luxium$neoSkyInlineEntityValues[1] = sky.entityBaseBias();
            this.luxium$neoSkyInlineEntityValues[2] = sky.slopeBias();
            this.luxium$neoSkyInlineEntityValues[3] = sky.entityRadius();
            this.luxium$neoSkyInlineEntityData.set(this.luxium$neoSkyInlineEntityValues);
            this.luxium$neoSkyInlineEntityWorldMatrix.set(system.entityLightFromWorldRelative());
         }
      }
   }

   @Unique
   private void luxium$setupWaterState() {
      Minecraft mc = Minecraft.getInstance();
      int requestedMode = WaterSurfaceState.renderMode();
      int colorUnit = 2;
      int depthOrHitUnit = 3;
      int microUnit = 4;
      int nearShadowUnit = 5;
      int farShadowUnit = 6;
      int maxTextureUnits = luxium$getMaxFragmentTextureUnits();
      boolean baseSceneReady = requestedMode == 1 && WaterSurfaceState.sceneReady() && maxTextureUnits > depthOrHitUnit;
      boolean lateSceneReady = requestedMode == 3
         && mc.getMainRenderTarget().getColorTextureId() > 0
         && ScreenSpaceReflectionSystem.isHitReady(SsrConsumer.WATER)
         && maxTextureUnits > depthOrHitUnit;
      boolean baseResolvedReady = requestedMode == 2 && WaterSurfaceState.resolvedReady() && maxTextureUnits > colorUnit;
      boolean lateResolvedReady = requestedMode == 4 && WaterSurfaceState.lateReflectionResolvedReady() && maxTextureUnits > colorUnit;
      int renderMode = requestedMode;
      if (requestedMode == 2 && !baseResolvedReady) {
         renderMode = 0;
      }

      if (requestedMode == 3 && !lateSceneReady) {
         renderMode = -1;
      }

      if (requestedMode == 4 && !lateResolvedReady) {
         renderMode = -1;
      }

      this.luxium$waterRenderMode.setInt(renderMode);
      boolean scaledPass = renderMode == 1 || renderMode == 3;
      int width = scaledPass ? Math.max(SharedPostResources.getWaterRenderWidth(), 1) : Math.max(mc.getMainRenderTarget().width, 1);
      int height = scaledPass ? Math.max(SharedPostResources.getWaterRenderHeight(), 1) : Math.max(mc.getMainRenderTarget().height, 1);
      if (renderMode == 2 || renderMode == 4) {
         bindTexture(colorUnit, SharedPostResources.getWaterRenderColorTextureId());
         this.luxium$waterResolvedColor.setInt(colorUnit);
      } else if (renderMode == 1 && baseSceneReady) {
         bindTexture(colorUnit, mc.getMainRenderTarget().getColorTextureId());
         bindTexture(depthOrHitUnit, mc.getMainRenderTarget().getDepthTextureId());
         this.luxium$waterSceneColor.setInt(colorUnit);
         this.luxium$waterSceneDepth.setInt(depthOrHitUnit);
      } else if (renderMode == 3 && lateSceneReady) {
         bindTexture(colorUnit, mc.getMainRenderTarget().getColorTextureId());
         bindTexture(depthOrHitUnit, ScreenSpaceReflectionSystem.hitTexture(SsrConsumer.WATER));
         this.luxium$waterSceneColor.setInt(colorUnit);
         this.luxium$ssrHitTexture.setInt(depthOrHitUnit);
      }

      int microTexture = 0;
      boolean microReady = renderMode == 1 && maxTextureUnits > microUnit;
      if (microReady) {
         microTexture = WaterTextureResources.microNormalTextureId();
         microReady = microTexture > 0;
      }

      if (microReady) {
         bindTexture(microUnit, microTexture);
         this.luxium$waterMicroNormal.setInt(microUnit);
      }

      this.luxium$waterMicroReady.setInt(microReady ? 1 : 0);
      this.luxium$waterFrameValues[0] = (float)width;
      this.luxium$waterFrameValues[1] = (float)height;
      this.luxium$waterFrameValues[2] = WaterSurfaceState.timeSeconds();
      this.luxium$waterFrameValues[3] = !baseSceneReady && !lateSceneReady ? 0.0F : 1.0F;
      this.luxium$waterFrame.set(this.luxium$waterFrameValues);
      this.luxium$waterWaveValues[0] = ((Double)Config.CLIENT.waterWaveStrength.get()).floatValue();
      this.luxium$waterWaveValues[1] = ((Double)Config.CLIENT.waterWaveScale.get()).floatValue();
      this.luxium$waterWaveValues[2] = ((Double)Config.CLIENT.waterWaveSpeed.get()).floatValue();
      this.luxium$waterWaveValues[3] = Config.isFeatureEnabled(Config.CLIENT.waterDetailWaves)
         ? ((Double)Config.CLIENT.waterDetailStrength.get()).floatValue()
         : 0.0F;
      this.luxium$waterWave.set(this.luxium$waterWaveValues);
      this.luxium$waterOpticsValues[0] = ((Double)Config.CLIENT.waterRefractionStrength.get()).floatValue();
      this.luxium$waterOpticsValues[1] = ((Double)Config.CLIENT.waterAbsorptionStrength.get()).floatValue();
      this.luxium$waterOpticsValues[2] = ((Double)Config.CLIENT.waterVisibilityDepth.get()).floatValue();
      this.luxium$waterOpticsValues[3] = ((Double)Config.CLIENT.waterSurfaceOpacity.get()).floatValue();
      this.luxium$waterOptics.set(this.luxium$waterOpticsValues);
      this.luxium$waterSurfaceValues[0] = ((Double)Config.CLIENT.waterFresnelStrength.get()).floatValue();
      this.luxium$waterSurfaceValues[1] = ((Double)Config.CLIENT.waterFresnelPower.get()).floatValue();
      this.luxium$waterSurfaceValues[2] = ((Double)Config.CLIENT.waterSpecularStrength.get()).floatValue();
      this.luxium$waterSurfaceValues[3] = ((Double)Config.CLIENT.waterSpecularSharpness.get()).floatValue();
      this.luxium$waterSurface.set(this.luxium$waterSurfaceValues);
      NeoSkyCelestia waterSkySystem = NeoSkyCelestia.get();
      NeoSkyCelestiaFrameState waterSky = waterSkySystem.frameState();
      boolean waterSkyShadows = renderMode == 1 && waterSkySystem.isMainPassActive() && waterSky.enabled() && maxTextureUnits > farShadowUnit;
      this.luxium$waterSkyShadowEnabled.setInt(waterSkyShadows ? 1 : 0);
      if (waterSkyShadows) {
         bindTexture(nearShadowUnit, waterSky.nearDepthTexture());
         bindTexture(farShadowUnit, waterSky.farDepthTexture());
         this.luxium$waterNearShadowSampler.setInt(nearShadowUnit);
         this.luxium$waterFarShadowSampler.setInt(farShadowUnit);
         this.luxium$waterFilterSamples.setInt(waterSky.filterSamples());
         this.luxium$waterCascadeValues[0] = waterSky.nearRadius();
         this.luxium$waterCascadeValues[1] = waterSky.farRadius();
         this.luxium$waterCascadeValues[2] = waterSky.nearTexelSize();
         this.luxium$waterCascadeValues[3] = waterSky.farTexelSize();
         this.luxium$waterCascadeData.set(this.luxium$waterCascadeValues);
         this.luxium$waterBiasValues[0] = waterSky.baseBiasNear();
         this.luxium$waterBiasValues[1] = waterSky.baseBiasFar();
         this.luxium$waterBiasValues[2] = waterSky.slopeBias();
         this.luxium$waterBiasValues[3] = waterSky.cascadeBlendStart();
         this.luxium$waterBiasData.set(this.luxium$waterBiasValues);
         this.luxium$waterNearMatrix.set(waterSky.nearLightFromView());
         this.luxium$waterFarMatrix.set(waterSky.farLightFromView());
      }

      Vec3 camera = WaterSurfaceState.cameraPosition();
      this.luxium$waterCameraPos.set((float)camera.x, (float)camera.y, (float)camera.z);
      WaterSurfaceState.Light light = WaterSurfaceState.light(mc.getFrameTime());
      this.luxium$waterLightDir.set(light.direction().x, light.direction().y, light.direction().z);
      float lightStrength = light.strength();
      this.luxium$waterLightColor.set(light.color().x * lightStrength, light.color().y * lightStrength, light.color().z * lightStrength);
      this.luxium$waterDepthAware.setInt(Config.isFeatureEnabled(Config.CLIENT.waterDepthAwareRefraction) ? 1 : 0);
      SsrSettings ssr = ScreenSpaceReflectionSystem.settings(SsrConsumer.WATER);
      boolean traceActive = renderMode == 1 && ScreenSpaceReflectionSystem.isHitTraceActive(SsrConsumer.WATER);
      boolean colorResolveActive = renderMode == 3 && ScreenSpaceReflectionSystem.isHitReady(SsrConsumer.WATER);
      this.luxium$ssrEnabled.setInt(!ssr.enabled() || !traceActive && !colorResolveActive ? 0 : 1);
      this.luxium$ssrTraceValues[0] = (float)ssr.coarseSteps();
      this.luxium$ssrTraceValues[1] = (float)ssr.refinementSteps();
      this.luxium$ssrTraceValues[2] = ssr.maxDistance();
      this.luxium$ssrTraceValues[3] = ssr.thickness();
      this.luxium$ssrTrace.set(this.luxium$ssrTraceValues);
      this.luxium$ssrResolveValues[0] = ssr.strength();
      this.luxium$ssrResolveValues[1] = ssr.edgeFade();
      this.luxium$ssrResolveValues[2] = 0.0F;
      this.luxium$ssrResolveValues[3] = 0.0F;
      this.luxium$ssrResolve.set(this.luxium$ssrResolveValues);
      GlStateManager._activeTexture(33984);
   }

   @Unique
   private static int luxium$getMaxFragmentTextureUnits() {
      if (luxium$maxFragmentTextureUnits < 0) {
         luxium$maxFragmentTextureUnits = Math.max(0, GL11.glGetInteger(34930));
      }

      return luxium$maxFragmentTextureUnits;
   }

   @Unique
   private static int uniformLocation(int program, String name) {
      int location = GL20.glGetUniformLocation(program, name + "[0]");
      return location >= 0 ? location : GL20.glGetUniformLocation(program, name);
   }

   @Unique
   private static void bindTexture(int unit, int textureId) {
      GlStateManager._activeTexture(33984 + unit);
      GlStateManager._bindTexture(Math.max(textureId, 0));
   }

   @Unique
   private static void bindTexture3D(int unit, int textureId) {
      GlStateManager._activeTexture(33984 + unit);
      GL11.glBindTexture(32879, Math.max(textureId, 0));
      GlStateManager._activeTexture(33984);
   }
}
