package com.vinlanx.luxium;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.common.ForgeConfigSpec.DoubleValue;
import net.minecraftforge.common.ForgeConfigSpec.EnumValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;
import org.apache.commons.lang3.tuple.Pair;

public class Config {
   public static final int MAX_RTX_WORLD_TRACING_WORKERS = Math.max(1, Math.min(24, Runtime.getRuntime().availableProcessors()));
   public static final Config.Client CLIENT;
   public static final ForgeConfigSpec CLIENT_SPEC;

   public static void register() {
      ModLoadingContext.get().registerConfig(Type.CLIENT, CLIENT_SPEC);
   }

   public static boolean isEnabled() {
      return (Boolean)CLIENT.luxiumEnabled.get();
   }

   public static boolean isFeatureEnabled(BooleanValue feature) {
      return isEnabled() && feature.get()
         ? !(Boolean)CLIENT.blockLightTestEnabled.get()
            || feature != CLIENT.rtxEnabled
               && feature != CLIENT.gpuShadowsEnabled
               && feature != CLIENT.neoGpuVanillaEnabled
               && feature != CLIENT.neoCpuShadowsEnabled
         : false;
   }

   static {
      Pair<Config.Client, ForgeConfigSpec> specPair = new Builder().configure(Config.Client::new);
      CLIENT = (Config.Client)specPair.getLeft();
      CLIENT_SPEC = (ForgeConfigSpec)specPair.getRight();
   }

   public static class Client {
      public final BooleanValue luxiumEnabled;
      public final BooleanValue rtxEnabled;
      public final BooleanValue rtxLavaTracingEnabled;
      public final BooleanValue entityShadowsEnabled;
      public final IntValue rtxWorldTracingWorkers;
      public final IntValue floodRadiusCap;
      public final DoubleValue floodPenumbraSoftness;
      public final DoubleValue floodCornerSeal;
      public final DoubleValue floodDirectionalBias;
      public final IntValue floodUpdateBudget;
      public final BooleanValue neoGpuVanillaEnabled;
      public final BooleanValue blockLightTestEnabled;
      public final IntValue neoGpuVanillaCaptureResolution;
      public final IntValue neoGpuVanillaDistance;
      public final IntValue neoGpuVanillaCaptureBudget;
      public final IntValue neoGpuVanillaMaxSources;
      public final BooleanValue neoGpuVanillaCutoutEnabled;
      public final BooleanValue neoGpuVanillaFastShadowsEnabled;
      public final DoubleValue neoGpuVanillaFastShadowStrength;
      public final DoubleValue neoGpuVanillaFastDiffuseWrap;
      public final BooleanValue neoGpuVanillaGeometryUpdates;
      public final BooleanValue neoGpuVanillaDebug;
      public final BooleanValue realisticShadowsEnabled;
      public final BooleanValue neoCpuShadowsEnabled;
      public final BooleanValue gpuShadowsEnabled;
      public final EnumValue<Config.GpuLocalLightingMode> gpuLocalLightingMode;
      public final IntValue gpuLocalLightDistance;
      public final EnumValue<Config.GpuLocalShadowMode> gpuLocalShadowMode;
      public final BooleanValue gpuHardShadowBakeEnabled;
      public final IntValue gpuHardShadowCaptureBudget;
      public final DoubleValue gpuSpreadOcclusionStrength;
      public final IntValue gpuFastSpreadMaxLights;
      public final BooleanValue gpuShadowsBlurEnabled;
      public final IntValue entityShadowUpdateFpsLimit;
      public final DoubleValue realisticShadowsRenderScale;
      public final BooleanValue skyLightEnabled;
      public final BooleanValue skyLightColorsEnabled;
      public final IntValue skyShadowRayLength;
      public final IntValue skyShadowNearResolution;
      public final IntValue skyShadowFarResolution;
      public final IntValue skyShadowNearRadius;
      public final DoubleValue skyShadowNearUpdateIntervalSeconds;
      public final IntValue skyShadowFarRadius;
      public final IntValue skyShadowFarUpdateMs;
      public final IntValue skyShadowFilterSamples;
      public final BooleanValue skyShadowSoftShadowsEnabled;
      public final BooleanValue skyCloudShadowsEnabled;
      public final BooleanValue skyEntityShadowsEnabled;
      public final IntValue skyEntityShadowResolution;
      public final IntValue skyEntityShadowRadius;
      public final IntValue skyEntityShadowUpdateFps;
      public final DoubleValue skyLightSunStrength;
      public final DoubleValue skyLightMoonStrength;
      public final IntValue skyLightSunZenithColor;
      public final DoubleValue skyLightSunZenithStrength;
      public final IntValue skyLightSunsetColor;
      public final DoubleValue skyLightSunsetStrength;
      public final IntValue skyLightMoonColor;
      public final DoubleValue skyLightMoonBaseStrength;
      public final DoubleValue skyLightAmbientStrength;
      public final BooleanValue realisticShadowTemperature;
      public final DoubleValue shadowTemperatureStrength;
      public final IntValue shadowTemperatureBias;
      public final DoubleValue vanillaBlockLightInSunShadows;
      public final BooleanValue skyEnabled;
      public final DoubleValue skyBakeUpdateIntervalSeconds;
      public final BooleanValue skyBakeSmoothEnabled;
      public final IntValue skyBakeWorkPerFrame;
      public final EnumValue<Config.SkyBakeTileSize> skyBakeTileSize;
      public final DoubleValue skySunSize;
      public final DoubleValue skyMoonSize;
      public final BooleanValue cloudsEnabled;
      public final BooleanValue cloudLayer1Enabled;
      public final DoubleValue cloudLayer1Height;
      public final DoubleValue cloudLayer1Speed;
      public final DoubleValue cloudLayer1Direction;
      public final DoubleValue cloudLayer1Scale;
      public final DoubleValue cloudLayer1Opacity;
      public final DoubleValue cloudLayer1Thickness;
      public final DoubleValue cloudLayer1Brightness;
      public final DoubleValue cloudLayer1Coverage;
      public final DoubleValue cloudLayer1EdgeSoftness;
      public final DoubleValue cloudLayer1Variation;
      public final DoubleValue cloudLayer1WeatherInfluence;
      public final DoubleValue cloudLayer1StormDarkening;
      public final IntValue cloudLayer1RenderDistance;
      public final BooleanValue cloudLayer2Enabled;
      public final DoubleValue cloudLayer2Height;
      public final DoubleValue cloudLayer2Speed;
      public final DoubleValue cloudLayer2Direction;
      public final DoubleValue cloudLayer2Scale;
      public final DoubleValue cloudLayer2Opacity;
      public final DoubleValue cloudLayer2Thickness;
      public final DoubleValue cloudLayer2Brightness;
      public final DoubleValue cloudLayer2Coverage;
      public final DoubleValue cloudLayer2EdgeSoftness;
      public final DoubleValue cloudLayer2Variation;
      public final DoubleValue cloudLayer2WeatherInfluence;
      public final DoubleValue cloudLayer2StormDarkening;
      public final IntValue cloudLayer2RenderDistance;
      public final BooleanValue cloudLayer3Enabled;
      public final DoubleValue cloudLayer3Height;
      public final DoubleValue cloudLayer3Speed;
      public final DoubleValue cloudLayer3Direction;
      public final DoubleValue cloudLayer3Scale;
      public final DoubleValue cloudLayer3Opacity;
      public final DoubleValue cloudLayer3Thickness;
      public final DoubleValue cloudLayer3Brightness;
      public final DoubleValue cloudLayer3Coverage;
      public final DoubleValue cloudLayer3EdgeSoftness;
      public final DoubleValue cloudLayer3Variation;
      public final DoubleValue cloudLayer3WeatherInfluence;
      public final DoubleValue cloudLayer3StormDarkening;
      public final IntValue cloudLayer3RenderDistance;
      public final BooleanValue reflectionEnabled;
      public final BooleanValue reflectionBilinearFiltering;
      public final DoubleValue reflectionRenderScale;
      public final IntValue reflectionDistance;
      public final IntValue reflectionScanIntervalMs;
      public final DoubleValue postEffectsRenderScale;
      public final BooleanValue kawaseBloomEnabled;
      public final DoubleValue kawaseBloomIntensity;
      public final DoubleValue kawaseBloomRadius;
      public final IntValue kawaseBloomLevels;
      public final DoubleValue kawaseBloomThreshold;
      public final IntValue kawaseBloomSourceDistance;
      public final BooleanValue kawaseBloomDepthOcclusion;
      public final DoubleValue kawaseBloomDepthTolerance;
      public final BooleanValue skyGodRaysEnabled;
      public final DoubleValue skyGodRaysSunSize;
      public final DoubleValue skyGodRaysMoonSize;
      public final BooleanValue skyGodRaysCelestialDiscEnabled;
      public final BooleanValue skyGodRaysHaloEnabled;
      public final EnumValue<Config.SkyGodRaysVisualPreset> skyGodRaysVisualPreset;
      public final DoubleValue skyGodRaysRayIntensity;
      public final DoubleValue skyGodRaysHaloIntensity;
      public final DoubleValue skyGodRaysDiscIntensity;
      public final DoubleValue skyGodRaysHaloSize;
      public final DoubleValue skyGodRaysDiscSize;
      public final IntValue skyGodRaysSunDayColor;
      public final IntValue skyGodRaysSunsetColor;
      public final IntValue skyGodRaysMoonBaseColor;
      public final IntValue skyGodRaysMoonHaloColor;
      public final DoubleValue skyGodRaysWeatherInfluence;
      public final DoubleValue skyGodRaysCenterSuppression;
      public final BooleanValue skyVolumetricGodRaysEnabled;
      public final DoubleValue skyVolumetricGodRaysIntensity;
      public final DoubleValue skyVolumetricGodRaysDensity;
      public final IntValue skyVolumetricGodRaysSamples;
      public final DoubleValue skyVolumetricGodRaysUniformity;
      public final DoubleValue skyVolumetricGodRaysSideVisibility;
      public final DoubleValue skyVolumetricGodRaysHazeSuppression;
      public final BooleanValue skyVolumetricGodRaysAutoAdaptation;
      public final IntValue skyVolumetricGodRaysFullAdaptationSkylight;
      public final BooleanValue skyVolumetricGodRaysEntityOcclusion;
      public final DoubleValue skyVolumetricGodRaysMaxDistance;
      public final DoubleValue skyVolumetricGodRaysAnisotropy;
      public final DoubleValue skyVolumetricGodRaysCelestialColorInfluence;
      public final BooleanValue lensFlareEnabled;
      public final DoubleValue lensFlareIntensity;
      public final DoubleValue lensFlareStreakIntensity;
      public final DoubleValue lensFlareStreakLength;
      public final DoubleValue lensFlareStreakWidth;
      public final DoubleValue lensFlareChromaticSpread;
      public final DoubleValue lensFlareGhostIntensity;
      public final DoubleValue lensFlareGhostSize;
      public final DoubleValue lensFlareSpread;
      public final BooleanValue tonemapEnabled;
      public final DoubleValue tonemapExposure;
      public final DoubleValue tonemapContrast;
      public final DoubleValue tonemapHighlightCompression;
      public final DoubleValue tonemapShadowDepth;
      public final DoubleValue tonemapSaturation;
      public final DoubleValue tonemapVibrance;
      public final DoubleValue tonemapGamma;
      public final DoubleValue tonemapStrength;
      public final BooleanValue fogEnabled;
      public final BooleanValue fogCelestialScatteringEnabled;
      public final DoubleValue fogDensity;
      public final DoubleValue fogScatteringBrightness;
      public final DoubleValue fogMaxBrightness;
      public final DoubleValue fogNearDensityBoost;
      public final DoubleValue fogNearBoostRange;
      public final BooleanValue fogDynamicCelestialColor;
      public final DoubleValue fogCelestialColorBlend;
      public final DoubleValue fogStartDistance;
      public final DoubleValue fogNearFade;
      public final DoubleValue fogMaxOpacity;
      public final DoubleValue fogSkyTint;
      public final DoubleValue fogScatteringStrength;
      public final DoubleValue fogDistanceCurve;
      public final DoubleValue fogHeight;
      public final BooleanValue waterEnabled;
      public final BooleanValue waterDepthAwareRefraction;
      public final BooleanValue waterDetailWaves;
      public final DoubleValue waterRenderScale;
      public final DoubleValue waterWaveStrength;
      public final DoubleValue waterWaveScale;
      public final DoubleValue waterWaveSpeed;
      public final DoubleValue waterDetailStrength;
      public final DoubleValue waterRefractionStrength;
      public final DoubleValue waterAbsorptionStrength;
      public final DoubleValue waterVisibilityDepth;
      public final DoubleValue waterFresnelStrength;
      public final DoubleValue waterFresnelPower;
      public final DoubleValue waterSurfaceOpacity;
      public final DoubleValue waterSpecularStrength;
      public final DoubleValue waterSpecularSharpness;
      public final BooleanValue waterSsrEnabled;
      public final DoubleValue waterSsrStrength;
      public final DoubleValue waterSsrMaxDistance;
      public final DoubleValue waterSsrThickness;
      public final DoubleValue waterSsrEdgeFade;
      public final DoubleValue ssrQuality;
      public final DoubleValue puddleMaxDepth;
      public final DoubleValue puddleDepthCurve;
      public final DoubleValue puddleWetDarkening;
      public final DoubleValue puddleSurfaceOpacity;
      public final DoubleValue puddleEdgeSoftness;
      public final DoubleValue puddleWaveStrength;
      public final DoubleValue puddleWaveScale;
      public final DoubleValue puddleWaveSpeed;
      public final DoubleValue puddleRippleStrength;
      public final DoubleValue puddleRefractionStrength;
      public final BooleanValue puddleSsrEnabled;
      public final DoubleValue puddleSsrStrength;
      public final DoubleValue puddleSsrMaxDistance;
      public final DoubleValue puddleSsrThickness;
      public final DoubleValue puddleSsrEdgeFade;
      public final BooleanValue wetEnabled;
      public final DoubleValue wetMaxDepth;
      public final DoubleValue wetDepthCurve;
      public final DoubleValue wetDarkening;
      public final DoubleValue wetSurfaceOpacity;
      public final DoubleValue wetEdgeSoftness;
      public final DoubleValue wetWaveStrength;
      public final DoubleValue wetWaveScale;
      public final DoubleValue wetWaveSpeed;
      public final DoubleValue wetRippleStrength;
      public final DoubleValue wetRefractionStrength;
      public final DoubleValue wetEnvironmentReflectionStrength;
      public final DoubleValue wetCelestialSpecularStrength;
      public final DoubleValue wetSheenFloor;
      public final DoubleValue wetRippleHighlightStrength;
      public final BooleanValue wetSsrEnabled;
      public final DoubleValue wetSsrStrength;
      public final DoubleValue wetSsrMaxDistance;
      public final DoubleValue wetSsrThickness;
      public final DoubleValue wetSsrEdgeFade;
      public final BooleanValue plantsWaveEnabled;
      public final DoubleValue plantsWaveStrength;
      public final DoubleValue plantsWaveSpeed;
      public final DoubleValue plantsWaveGustStrength;
      public final IntValue plantsWaveDistance;
      public final DoubleValue plantsWaveGrassStrength;
      public final DoubleValue plantsWaveLeavesStrength;
      public final DoubleValue plantsWaveAquaticStrength;
      public final DoubleValue plantsWaveBendStrength;
      public final BooleanValue opaqueIceEnabled;
      public final BooleanValue alphaWarningShown;
      public final BooleanValue debugHudEnabled;
      public final BooleanValue debugHudFps;
      public final BooleanValue debugHudMemory;
      public final BooleanValue debugHudAllocated;
      public final EnumValue<Config.DebugHudPosition> debugHudPosition;
      public final IntValue debugHudOffsetX;
      public final IntValue debugHudOffsetY;
      public final DoubleValue debugHudTextScale;
      public final DoubleValue debugHudUpdateSeconds;
      public final BooleanValue lsrEnabled;
      public final DoubleValue lsrRenderScale;
      public final DoubleValue lsrSharpness;
      public final BooleanValue tfrEnabled;
      public final DoubleValue tfrReprojectionStrength;
      public final DoubleValue tfrHistoryStability;
      public final DoubleValue tfrCameraCutDistance;
      public final DoubleValue tfrCameraCutAngle;

      Client(Builder builder) {
         this.luxiumEnabled = builder.define("luxiumEnabled", true);
         builder.comment("NeoFlood RTX Settings").push("rtx");
         this.rtxEnabled = builder.comment("Whether NeoFlood RTX block lighting is enabled by default.").define("enabled", false);
         this.rtxLavaTracingEnabled = builder.comment(
               "Enable NeoFlood tracing for lava outside the Nether. Disabled uses an isolated vanilla BlockLightEngine with budgeted lava-only propagation. Lava RTX tracing is always disabled in the Nether."
            )
            .define("lavaTracingEnabled", false);
         this.entityShadowsEnabled = builder.comment(
               "Enable entity and block-entity shadows. Direct GPU local shadows render them into point-light cubemaps and shade their surfaces; NeoCPU/legacy modes use their own dynamic paths; plain NeoFlood lighting uses lightweight entity occlusion."
            )
            .define("entityShadowsEnabled", true);
         this.rtxWorldTracingWorkers = builder.comment(
               "Exact number of background NeoFlood solver threads. A value of 1 runs exactly one background source solve at a time; it is not a CPU multiplier. Maximum matches logical CPU count, capped at 24."
            )
            .defineInRange("worldTracingWorkers", 1, 1, Config.MAX_RTX_WORLD_TRACING_WORKERS);
         this.floodRadiusCap = builder.comment(
               "Maximum light propagation radius in blocks. The effective radius is the smallest of this value and the light-level falloff reach; the packed source volume has a fixed maximum radius of 36 blocks. lowering it is the most direct performance lever because solver cost scales with the cube of the radius."
            )
            .defineInRange("floodRadiusCap", 36, 16, 36);
         this.floodPenumbraSoftness = builder.comment(
               "Blends between the nearest straight-line back-projection sample and exact bilinear sampling on the previous shell. 0 gives crisp block-resolution shadows; 1 gives a smooth sub-voxel penumbra. Both remain aligned to the real source angle."
            )
            .defineInRange("floodPenumbraSoftness", 0.59, 0.0, 1.0);
         this.floodCornerSeal = builder.comment(
               "Edge seal for mixed lit/dark back-projection footprints. 0 keeps full bilinear blending; 4 selects the nearest projected sample at an occluder boundary. Unlike the legacy implementation, this never multiplies visibility and cannot accumulate into black axis-aligned wedges."
            )
            .defineInRange("floodCornerSeal", 1.35, 0.0, 4.0);
         this.floodDirectionalBias = builder.comment(
               "Symmetric sharpness of the sub-voxel back-projection fractions. 1.0 is exact geometric interpolation. Above 1.0 sharpens transitions; below 1.0 broadens them. The curve is axis-neutral and never prefers X, Y or Z."
            )
            .defineInRange("floodDirectionalBias", 0.25, 0.25, 4.0);
         this.floodUpdateBudget = builder.comment(
               "Upper bound on source solves granted to one background batch. A batch starts at most once every 50 ms and is additionally capped by World Trace Workers. Manual player source changes use one dedicated high-priority worker."
            )
            .defineInRange("floodUpdateBudget", 8, 1, 64);
         builder.pop();
         builder.comment("Independent block light experiment").push("blockLightTest");
         this.blockLightTestEnabled = builder.comment(
               "Use vanilla light propagation with a separate lightweight colored block-light renderer. Does not use NeoFlood, NeoGpuVanilla or GPU shadow volumes."
            )
            .define("enabled", false);
         builder.pop();
         builder.comment("Vanilla GPU").push("vanillaGpu");
         this.neoGpuVanillaEnabled = builder.comment(
               "Enable NeoGpuVanilla. Vanilla remains the only block-light propagation engine; one-time point-light depth captures bake a camera-centred RGBA8 receiver volume. Enabling this runtime-disables NeoFlood, NeoCPU local shadows, and legacy GPU local lighting without deleting their code or config values."
            )
            .define("enabled", false);
         this.neoGpuVanillaCaptureResolution = builder.comment(
               "NeoGpuVanilla shadow quality expressed as pixels per block. The config screen snaps to 1, 2, 4, 8, 16, 32, 64, 128, 256, or 512 px/block. Higher steps increase the one-shot fine receiver bake quality; normal world rendering still performs one R8 lookup per pixel and never loops over lights. Values above 256 px/block use substantially more VRAM for the baked receiver textures."
            )
            .defineInRange("pixelsPerBlock", 32, 1, 512);
         this.neoGpuVanillaDistance = builder.comment(
               "Maximum camera distance for sources included in the baked vanilla clipping volume. Higher values capture more village lights but require more source cubemaps when entering a new area."
            )
            .defineInRange("sourceDistance", 41, 16, 64);
         this.neoGpuVanillaCaptureBudget = builder.comment(
               "Maximum NeoGpuVanilla cubemap faces captured per rendered frame while the cache is dirty. Higher values make new shadows appear faster but can cause a larger short GPU spike while capturing."
            )
            .defineInRange("captureBudget", 300, 1, 300);
         this.neoGpuVanillaMaxSources = builder.comment(
               "Maximum nearest non-lava sources baked into the current volume. NeoGpuVanilla automatically adapts cubemap capture tile resolution so every value on this slider (up to 40) fits the shared 4096x4096 depth atlas; fine receiver pixels-per-block quality is unchanged."
            )
            .defineInRange("maxSources", 25, 4, 40);
         this.neoGpuVanillaCutoutEnabled = builder.comment(
               "Allow CUTOUT terrain (leaves, grass and other alpha-tested block geometry) to participate in NeoGpuVanilla. Disabled by default for a strict performance mode: CUTOUT uses the normal Embeddium terrain shader, receives no NeoGpuVanilla receiver lookup, runs no NeoGpuVanilla CUTOUT prepass, and is omitted from NeoGpuVanilla point-light cubemap captures."
            )
            .define("cutout", false);
         this.neoGpuVanillaFastShadowsEnabled = builder.comment(
               "Bake the visual style of legacy Fast GPU Shadows directly into NeoGpuVanilla. Source color, exponential attenuation, wrapped diffuse response and source-local cubemap visibility are accumulated during bake into a tiny directional 3D field. Normal rendering performs a fixed six cached 3D lookups regardless of whether 4 or 40 sources are active."
            )
            .define("fastGpuShadows", false);
         this.neoGpuVanillaFastShadowStrength = builder.comment(
               "Strength multiplier for baked Fast GPU Shadows direct colored light. 1.0 matches the legacy Fast GPU Shadows visual intensity."
            )
            .defineInRange("fastGpuShadowsStrength", 0.97, 0.0, 2.0);
         this.neoGpuVanillaFastDiffuseWrap = builder.comment(
               "Wrapped diffuse floor used by baked Fast GPU Shadows. 0.16 matches the legacy Fast GPU Shadows shader; higher values make local light wrap farther around surfaces."
            )
            .defineInRange("fastGpuShadowsDiffuseWrap", 0.14, 0.0, 0.5);
         this.neoGpuVanillaGeometryUpdates = builder.comment(
               "Refresh affected source cubemaps and the baked volume when nearby block geometry changes. Disable only for debugging static scenes."
            )
            .define("geometryUpdates", false);
         this.neoGpuVanillaDebug = builder.comment(
               "Enable NeoGpuVanilla GPU diagnostics. Uses asynchronous OpenGL timer queries and writes one summary per second to latest.log: capture GPU ms, fine-bake GPU ms, dirty/captured faces, source count, bake count, and pixels-per-block."
            )
            .define("debug", false);
         builder.pop();
         builder.comment("Realistic Shadows").push("realisticShadows");
         this.realisticShadowsEnabled = builder.comment(
               "Enable occlusive face shadows that cut RTX block light by projecting solid occluders onto visible receiver faces."
            )
            .define("enabled", false);
         this.neoCpuShadowsEnabled = builder.comment(
               "Enable the new source-local NeoCPUUUShadows engine. This bypasses legacy NeoShadows/GpuNeoShadows, keeps Torch light-source scanning active for NeoCPU, and routes entity shadows to NeoCPU when entityShadowsEnabled is also on."
            )
            .define("neoCpuShadowsEnabled", false);
         this.gpuShadowsEnabled = builder.comment(
               "Enable GPU local point lighting and shadowed cubemap visibility directly inside normal terrain and entity shaders. The system can work alone, as an RTX near-field LOD, or be bypassed in RTX-only mode."
            )
            .define("gpuShadowsEnabled", false);
         this.gpuLocalLightingMode = builder.comment(
               "GPU_ONLY renders nearby emissive blocks with GPU direct lighting and does not start NeoFlood. HYBRID hands the near field to GPU lighting while NeoFlood remains available for the transition and outer field. NEOFLOOD_ONLY bypasses GPU local lighting and uses the clean NeoFlood block-lighting pipeline."
            )
            .defineEnum("gpuLocalLightingMode", Config.GpuLocalLightingMode.GPU_ONLY);
         this.gpuLocalLightDistance = builder.comment(
               "Maximum camera distance in blocks for GPU local lights. The outer 20 percent is a smooth GPU/RTX transition band. Cubemap modes become expensive at high distances; Fast Spread is designed for low-end GPUs."
            )
            .defineInRange("gpuLocalLightDistance", 42, 4, 64);
         this.gpuLocalShadowMode = builder.comment(
               "Legacy GPU local shadow renderer. CUBEMAP_HARD keeps precise block/entity caster shadows with a comparison filter. CUBEMAP_SOFT keeps the expensive PCSS path. The old FAST_SPREAD enum value remains readable only for config-file compatibility and is treated as CUBEMAP_HARD; its visual path now lives under Vanilla GPU -> Fast GPU Shadows."
            )
            .defineEnum("gpuLocalShadowMode", Config.GpuLocalShadowMode.CUBEMAP_SOFT);
         this.gpuHardShadowBakeEnabled = builder.comment(
               "Bake Cubemap Hard local lighting into a camera-centred six-direction RGB receiver volume. Runtime terrain/entity lighting then uses a fixed 1-3 texture lookups instead of looping over every source. While enabled, PCSS and dynamic entity shadow casters are disabled."
            )
            .define("gpuHardShadowBakeEnabled", false);
         this.gpuHardShadowCaptureBudget = builder.comment(
               "Maximum Cubemap Hard faces captured per frame while baked hard shadows are enabled. The same budget also scales directional receiver bake progress. Higher values finish sooner but can cause a larger one-time GPU spike."
            )
            .defineInRange("gpuHardShadowCaptureBudget", 1, 1, 300);
         this.gpuSpreadOcclusionStrength = builder.comment(
               "Legacy compatibility key from the removed Fast Spread renderer. It is no longer used; Fast GPU Shadows now live under Vanilla GPU and are baked instead of using NeoFlood spread visibility."
            )
            .defineInRange("gpuSpreadOcclusionStrength", 1.0, 0.0, 1.0);
         this.gpuFastSpreadMaxLights = builder.comment(
               "Maximum number of closest lights used by Cubemap Hard, baked Cubemap Hard, and PCSS local-shadow rendering. The historical key name is retained for config-file compatibility."
            )
            .defineInRange("gpuFastSpreadMaxLights", 32, 4, 32);
         this.gpuShadowsBlurEnabled = builder.comment("Legacy compatibility key. New versions use gpuLocalShadowMode instead.")
            .define("gpuShadowsBlurEnabled", false);
         this.entityShadowUpdateFpsLimit = builder.comment(
               "Maximum FPS for scanning and rebuilding dynamic entity and block-entity casters in GPU Local Shadows, NeoShadowsEngine, and NeoCPU shadows."
            )
            .defineInRange("entityUpdateFps", 63, 10, 120);
         this.realisticShadowsRenderScale = builder.comment(
               "Scale of the shadowless scene capture used only by legacy/NeoCPU realistic shadows. It does not affect direct GPU Local Shadows."
            )
            .defineInRange("renderScale", 1.0, 0.01, 1.0);
         builder.pop();
         builder.comment("Sky Shadows").push("skyShadows");
         this.skyLightEnabled = builder.comment("Enable directional Sun/Moon lighting on blocks and entities through NeoSkyCelestia.")
            .define("skyLightEnabled", true);
         this.skyLightColorsEnabled = builder.comment("Tint celestial lighting with dynamic Sun, sunset, Moon, sky, and ground colors.")
            .define("skyLightColorsEnabled", true);
         this.skyShadowRayLength = builder.comment(
               "Minimum caster reach in blocks. Also controls entity-shadow depth; terrain and clouds expand automatically."
            )
            .defineInRange("rayLength", 248, 32, 512);
         this.skyShadowNearResolution = builder.comment("Resolution of the near directional depth map.").defineInRange("nearResolution", 2304, 256, 4096);
         this.skyShadowFarResolution = builder.comment("Resolution of the far directional depth map.").defineInRange("farResolution", 1536, 256, 4096);
         this.skyShadowNearRadius = builder.comment("Near cascade radius in blocks.").defineInRange("nearRadius", 31, 16, 128);
         this.skyShadowNearUpdateIntervalSeconds = builder.comment(
               "How often the near GPU shadow cascade is refreshed in seconds. Lower values make nearby shadows appear and react faster, but rebuild the near shadow map more often."
            )
            .defineInRange("nearUpdateIntervalSeconds", 1.2, 0.01, 5.0);
         this.skyShadowFarRadius = builder.comment("Far cascade radius in blocks.").defineInRange("farRadius", 248, 64, 384);
         this.skyShadowFarUpdateMs = builder.comment("Minimum interval between far cascade updates.").defineInRange("farUpdateMs", 1050, 50, 3000);
         this.skyShadowFilterSamples = builder.comment("Directional shadow filter samples. Supported values are 1 and 4.")
            .defineInRange("filterSamples", 1, 1, 4);
         this.skyShadowSoftShadowsEnabled = builder.comment("Use hardware linear PCF filtering for near and far celestial shadow maps.")
            .define("softShadowsEnabled", true);
         this.skyCloudShadowsEnabled = builder.comment("Render vanilla clouds into directional sky-shadow depth maps.").define("cloudShadowsEnabled", false);
         this.skyEntityShadowsEnabled = builder.comment("Render entities into a separate frequently updated celestial shadow map.")
            .define("entityShadowsEnabled", false);
         this.skyEntityShadowResolution = builder.comment("Resolution of the celestial entity shadow map.")
            .defineInRange("entityShadowResolution", 1536, 512, 2048);
         this.skyEntityShadowRadius = builder.comment("Half-width of the celestial entity shadow area around the camera in blocks.")
            .defineInRange("entityShadowRadius", 16, 16, 128);
         this.skyEntityShadowUpdateFps = builder.comment("Maximum update rate of the celestial entity shadow map. 61 updates every rendered frame.")
            .defineInRange("entityShadowUpdateFps", 61, 10, 61);
         this.skyLightSunStrength = builder.comment("Sun direct-light strength in percent.").defineInRange("sunLightStrengthPercent", 200.0, 0.0, 200.0);
         this.skyLightMoonStrength = builder.comment("Moon direct-light strength in percent.").defineInRange("moonLightStrengthPercent", 200.0, 0.0, 200.0);
         this.skyLightSunZenithColor = builder.defineInRange("sunZenithColor", 16766136, 0, 16777215);
         this.skyLightSunZenithStrength = builder.defineInRange("sunZenithStrengthPercent", 59.5, 0.0, 200.0);
         this.skyLightSunsetColor = builder.defineInRange("sunsetColor", 16742963, 0, 16777215);
         this.skyLightSunsetStrength = builder.defineInRange("sunsetStrengthPercent", 92.0, 0.0, 200.0);
         this.skyLightMoonColor = builder.defineInRange("moonColor", 6717613, 0, 16777215);
         this.skyLightMoonBaseStrength = builder.defineInRange("moonStrengthPercent", 24.0, 0.0, 200.0);
         this.skyLightAmbientStrength = builder.comment("Hemispheric sky ambient-light strength in percent.")
            .defineInRange("ambientStrengthPercent", 30.0, 0.0, 200.0);
         this.realisticShadowTemperature = builder.define("realisticShadowTemperature", true);
         this.shadowTemperatureStrength = builder.defineInRange("shadowTemperatureStrength", 1.0, 0.0, 1.0);
         this.shadowTemperatureBias = builder.defineInRange("shadowTemperatureBias", 0, -100, 100);
         this.vanillaBlockLightInSunShadows = builder.comment(
               "How much vanilla torch/lamp block light remains visible inside GPU-cast Sun shadows in percent. 0 hides the extra block-light contribution in bright Sun shadows, 100 keeps the full vanilla contribution there. Direct sunlight remains daylight-adapted. Does not affect GPU local lighting."
            )
            .defineInRange("vanillaBlockLightInSunShadowsPercent", 10.0, 0.0, 100.0);
         builder.pop();
         builder.comment("Sky").push("sky");
         this.skyEnabled = builder.comment("Enable cinematic physical sky rendering.").define("enabled", false);
         this.skyBakeUpdateIntervalSeconds = builder.comment("Minimum pause after a completed physical sky cubemap bake before the next bake can start.")
            .defineInRange("bakeUpdateIntervalSeconds", 0.8, 0.1, 2.0);
         this.skyBakeSmoothEnabled = builder.comment("Split sky cubemap baking into smaller tiles and spread the work across frames to reduce visible stalls.")
            .define("smoothBake", true);
         this.skyBakeWorkPerFrame = builder.comment(
               "Bake work units per frame. With smooth baking enabled this is tiles per frame; otherwise it is whole cubemap faces per frame."
            )
            .defineInRange("workPerFrame", 1, 1, 6);
         this.skyBakeTileSize = builder.comment(
               "Tile size used by smooth sky baking. Smaller tiles reduce per-frame stalls but take more frames to finish a full cubemap."
            )
            .defineEnum("tileSize", Config.SkyBakeTileSize.SIZE_256);
         this.skySunSize = builder.comment("Size multiplier for the vanilla rendered sun disc.").defineInRange("sunSize", 0.47, 0.1, 3.0);
         this.skyMoonSize = builder.comment("Size multiplier for the vanilla rendered moon disc.").defineInRange("moonSize", 0.54, 0.1, 3.0);
         builder.pop();
         builder.comment("Three Layer Vanilla Clouds").push("clouds");
         this.cloudsEnabled = builder.comment(
               "Replace the normal vanilla/Embeddium cloud draw with Luxium's three independently configurable vanilla-style cloud layers. Minecraft's own Clouds option must still be Fast or Fancy. Disabled by default."
            )
            .define("enabled", false);
         builder.comment("Low cloud layer").push("layer1");
         this.cloudLayer1Enabled = builder.comment("Render the low cloud layer.").define("enabled", true);
         this.cloudLayer1Height = builder.comment("Absolute Y height in blocks.").defineInRange("height", 150.0, 64.0, 384.0);
         this.cloudLayer1Speed = builder.comment("Wind speed multiplier relative to vanilla clouds.").defineInRange("speed", 1.25, 0.0, 3.0);
         this.cloudLayer1Direction = builder.comment("Wind direction in degrees. 0 = +X, 90 = +Z.").defineInRange("direction", 0.0, 0.0, 360.0);
         this.cloudLayer1Scale = builder.comment("Horizontal vanilla cloud pattern scale.").defineInRange("scale", 0.8, 0.25, 4.0);
         this.cloudLayer1Opacity = builder.comment("Layer opacity in percent.").defineInRange("opacity", 65.0, 0.0, 100.0);
         this.cloudLayer1Thickness = builder.comment("Fancy-cloud vertical thickness in blocks. Fast clouds remain flat.")
            .defineInRange("thickness", 4.0, 0.5, 16.0);
         this.cloudLayer1Brightness = builder.comment("Brightness multiplier applied after Minecraft's time/weather cloud color.")
            .defineInRange("brightness", 0.95, 0.25, 1.5);
         this.cloudLayer1Coverage = builder.comment(
               "Cloud amount/fullness. 50 preserves approximately the vanilla mask, lower values erode it and higher values expand it toward overcast."
            )
            .defineInRange("coverage", 42.0, 0.0, 100.0);
         this.cloudLayer1EdgeSoftness = builder.comment("Soft alpha feather around the pixel cloud mask, measured in source texture pixels.")
            .defineInRange("edgeSoftness", 0.0, 0.0, 4.0);
         this.cloudLayer1Variation = builder.comment(
               "Periodic shape perturbation that breaks up uniformly expanded cloud masses while keeping the texture perfectly tileable."
            )
            .defineInRange("variation", 10.0, 0.0, 100.0);
         this.cloudLayer1WeatherInfluence = builder.comment("How strongly rain/thunder pushes this layer toward full overcast coverage.")
            .defineInRange("weatherInfluence", 40.0, 0.0, 100.0);
         this.cloudLayer1StormDarkening = builder.comment("Additional brightness reduction during heavy rain/thunder.")
            .defineInRange("stormDarkening", 25.0, 0.0, 100.0);
         this.cloudLayer1RenderDistance = builder.comment("Approximate horizontal cloud radius in blocks.").defineInRange("renderDistance", 384, 128, 1024);
         builder.pop();
         builder.comment("Main cloud layer").push("layer2");
         this.cloudLayer2Enabled = builder.comment("Render the main cloud layer.").define("enabled", true);
         this.cloudLayer2Height = builder.comment("Absolute Y height in blocks.").defineInRange("height", 200.0, 64.0, 384.0);
         this.cloudLayer2Speed = builder.comment("Wind speed multiplier relative to vanilla clouds.").defineInRange("speed", 1.0, 0.0, 3.0);
         this.cloudLayer2Direction = builder.comment("Wind direction in degrees. 0 = +X, 90 = +Z.").defineInRange("direction", 10.0, 0.0, 360.0);
         this.cloudLayer2Scale = builder.comment("Horizontal vanilla cloud pattern scale.").defineInRange("scale", 1.0, 0.25, 4.0);
         this.cloudLayer2Opacity = builder.comment("Layer opacity in percent.").defineInRange("opacity", 80.0, 0.0, 100.0);
         this.cloudLayer2Thickness = builder.comment("Fancy-cloud vertical thickness in blocks. Fast clouds remain flat.")
            .defineInRange("thickness", 4.0, 0.5, 16.0);
         this.cloudLayer2Brightness = builder.comment("Brightness multiplier applied after Minecraft's time/weather cloud color.")
            .defineInRange("brightness", 1.0, 0.25, 1.5);
         this.cloudLayer2Coverage = builder.comment(
               "Cloud amount/fullness. 50 preserves approximately the vanilla mask, lower values erode it and higher values expand it toward overcast."
            )
            .defineInRange("coverage", 50.0, 0.0, 100.0);
         this.cloudLayer2EdgeSoftness = builder.comment("Soft alpha feather around the pixel cloud mask, measured in source texture pixels.")
            .defineInRange("edgeSoftness", 0.0, 0.0, 4.0);
         this.cloudLayer2Variation = builder.comment(
               "Periodic shape perturbation that breaks up uniformly expanded cloud masses while keeping the texture perfectly tileable."
            )
            .defineInRange("variation", 8.0, 0.0, 100.0);
         this.cloudLayer2WeatherInfluence = builder.comment("How strongly rain/thunder pushes this layer toward full overcast coverage.")
            .defineInRange("weatherInfluence", 70.0, 0.0, 100.0);
         this.cloudLayer2StormDarkening = builder.comment("Additional brightness reduction during heavy rain/thunder.")
            .defineInRange("stormDarkening", 35.0, 0.0, 100.0);
         this.cloudLayer2RenderDistance = builder.comment("Approximate horizontal cloud radius in blocks.").defineInRange("renderDistance", 448, 128, 1024);
         builder.pop();
         builder.comment("High cloud layer").push("layer3");
         this.cloudLayer3Enabled = builder.comment("Render the high cloud layer.").define("enabled", true);
         this.cloudLayer3Height = builder.comment("Absolute Y height in blocks.").defineInRange("height", 270.0, 64.0, 384.0);
         this.cloudLayer3Speed = builder.comment("Wind speed multiplier relative to vanilla clouds.").defineInRange("speed", 0.55, 0.0, 3.0);
         this.cloudLayer3Direction = builder.comment("Wind direction in degrees. 0 = +X, 90 = +Z.").defineInRange("direction", 350.0, 0.0, 360.0);
         this.cloudLayer3Scale = builder.comment("Horizontal vanilla cloud pattern scale.").defineInRange("scale", 1.7, 0.25, 4.0);
         this.cloudLayer3Opacity = builder.comment("Layer opacity in percent.").defineInRange("opacity", 50.0, 0.0, 100.0);
         this.cloudLayer3Thickness = builder.comment("Fancy-cloud vertical thickness in blocks. Fast clouds remain flat.")
            .defineInRange("thickness", 3.0, 0.5, 16.0);
         this.cloudLayer3Brightness = builder.comment("Brightness multiplier applied after Minecraft's time/weather cloud color.")
            .defineInRange("brightness", 0.95, 0.25, 1.5);
         this.cloudLayer3Coverage = builder.comment(
               "Cloud amount/fullness. 50 preserves approximately the vanilla mask, lower values erode it and higher values expand it toward overcast."
            )
            .defineInRange("coverage", 38.0, 0.0, 100.0);
         this.cloudLayer3EdgeSoftness = builder.comment("Soft alpha feather around the pixel cloud mask, measured in source texture pixels.")
            .defineInRange("edgeSoftness", 0.0, 0.0, 4.0);
         this.cloudLayer3Variation = builder.comment(
               "Periodic shape perturbation that breaks up uniformly expanded cloud masses while keeping the texture perfectly tileable."
            )
            .defineInRange("variation", 15.0, 0.0, 100.0);
         this.cloudLayer3WeatherInfluence = builder.comment("How strongly rain/thunder pushes this layer toward full overcast coverage.")
            .defineInRange("weatherInfluence", 100.0, 0.0, 100.0);
         this.cloudLayer3StormDarkening = builder.comment("Additional brightness reduction during heavy rain/thunder.")
            .defineInRange("stormDarkening", 45.0, 0.0, 100.0);
         this.cloudLayer3RenderDistance = builder.comment("Approximate horizontal cloud radius in blocks.").defineInRange("renderDistance", 512, 128, 1024);
         builder.pop();
         builder.pop();
         builder.comment("Planar Reflections").push("reflections");
         this.reflectionEnabled = builder.comment("Whether planar reflections are enabled.").define("enabled", false);
         this.reflectionBilinearFiltering = builder.comment("Enable bilinear filtering for planar reflection textures. Disabling it preserves sharp pixels.")
            .define("bilinearFiltering", true);
         this.reflectionRenderScale = builder.comment("Resolution scale for both exact and feed planar reflection render targets.")
            .defineInRange("renderScale", 0.78, 0.1, 1.0);
         this.reflectionDistance = builder.comment(
               "Player-centered reflection render radius in blocks. Reflections smoothly fade to transparent near the boundary."
            )
            .defineInRange("distance", 24, 5, 200);
         this.reflectionScanIntervalMs = builder.comment("Reflection scan update interval in milliseconds.").defineInRange("scanIntervalMs", 800, 5, 5000);
         builder.pop();
         builder.comment("PostEffects Quality").push("postEffects");
         this.postEffectsRenderScale = builder.comment(
               "Shared render scale for fog, Sky God Rays, Sky Volumetric God Rays and lens flare. Lower values reduce GPU cost but soften post effects. Tonemap always renders at full resolution."
            )
            .defineInRange("renderScale", 0.44, 0.1, 1.0);
         builder.pop();
         builder.comment("Block-light-only Dual Kawase bloom").push("kawaseBloom");
         this.kawaseBloomEnabled = builder.comment("Enable isolated bloom for blocks whose current state emits vanilla block light.").define("enabled", false);
         this.kawaseBloomIntensity = builder.comment("Final additive bloom intensity.").defineInRange("intensity", 0.55, 0.0, 3.0);
         this.kawaseBloomRadius = builder.comment("Dual Kawase sample offset per pyramid pass.").defineInRange("radius", 2.45, 0.5, 4.0);
         this.kawaseBloomLevels = builder.comment("Number of downsample levels. More levels spread bloom farther and cost more passes.")
            .defineInRange("levels", 7, 2, 7);
         this.kawaseBloomThreshold = builder.comment("Minimum normalized block emission included in the source mask.")
            .defineInRange("sourceThreshold", 0.05, 0.0, 1.0);
         this.kawaseBloomSourceDistance = builder.comment("Camera-centred source registry volume radius in blocks.").defineInRange("sourceDistance", 24, 8, 32);
         this.kawaseBloomDepthOcclusion = builder.comment("Reject blurred samples hidden behind nearer scene geometry.").define("depthOcclusion", false);
         this.kawaseBloomDepthTolerance = builder.comment("Depth edge tolerance in view-space blocks.").defineInRange("depthTolerance", 0.35, 0.01, 2.0);
         builder.pop();
         builder.comment("Sky God Rays").push("skyGodRays");
         this.skyGodRaysEnabled = builder.comment("Whether Screen-Space Sky God Rays (Sun/Moon rays) are enabled.").define("skyGodRaysEnabled", true);
         this.skyGodRaysSunSize = builder.comment("Size multiplier for sun disc and halo response in Sky God Rays.").defineInRange("sunSize", 0.94, 0.5, 2.0);
         this.skyGodRaysMoonSize = builder.comment("Size multiplier for moon disc and halo response in Sky God Rays.")
            .defineInRange("moonSize", 0.65, 0.5, 2.0);
         this.skyGodRaysCelestialDiscEnabled = builder.comment("Enable the small additive cinematic Sun/Moon disc highlight used by Sky God Rays.")
            .define("celestialDiscEnabled", true);
         this.skyGodRaysHaloEnabled = builder.comment("Enable the separate cinematic Sun/Moon halo used by Sky God Rays.").define("haloEnabled", true);
         this.skyGodRaysVisualPreset = builder.comment("Visual preset for the Sky God Rays sliders.")
            .defineEnum("visualPreset", Config.SkyGodRaysVisualPreset.CUSTOM);
         this.skyGodRaysRayIntensity = builder.comment("Overall intensity multiplier for Sky God Rays shafts.").defineInRange("rayIntensity", 1.63, 0.0, 2.5);
         this.skyGodRaysHaloIntensity = builder.comment("Overall intensity multiplier for the separate Sun/Moon halo.")
            .defineInRange("haloIntensity", 1.63, 0.0, 2.5);
         this.skyGodRaysDiscIntensity = builder.comment("Overall intensity multiplier for the small Sun/Moon disc highlight.")
            .defineInRange("discIntensity", 0.97, 0.0, 2.0);
         this.skyGodRaysHaloSize = builder.comment("Size multiplier for the separate Sun/Moon halo.").defineInRange("haloSize", 1.25, 0.5, 2.5);
         this.skyGodRaysDiscSize = builder.comment("Size multiplier for the small Sun/Moon disc highlight.").defineInRange("discSize", 1.18, 0.5, 1.8);
         this.skyGodRaysSunDayColor = builder.comment("Sun color used when the sun is high in the sky, stored as 0xRRGGBB.")
            .defineInRange("sunDayColor", 16772295, 0, 16777215);
         this.skyGodRaysSunsetColor = builder.comment("Sun color used near sunrise and sunset, stored as 0xRRGGBB.")
            .defineInRange("sunsetColor", 16747584, 0, 16777215);
         this.skyGodRaysMoonBaseColor = builder.comment("Base moon color, stored as 0xRRGGBB.").defineInRange("moonBaseColor", 12108497, 0, 16777215);
         this.skyGodRaysMoonHaloColor = builder.comment("Moon halo color, stored as 0xRRGGBB.").defineInRange("moonHaloColor", 10400736, 0, 16777215);
         this.skyGodRaysWeatherInfluence = builder.comment("How strongly rain, thunder and haze affect Sky God Rays.")
            .defineInRange("weatherInfluence", 1.19, 0.0, 2.0);
         this.skyGodRaysCenterSuppression = builder.comment("How much the effect is suppressed in the center so the vanilla Sun/Moon texture stays readable.")
            .defineInRange("centerSuppression", 1.5, 0.0, 2.0);
         builder.pop();
         builder.comment("Volumetric Rays").push("volumetricRays");
         this.skyVolumetricGodRaysEnabled = builder.comment(
               "Enable world-space Sun/Moon volumetric shafts raymarched through the existing GPU sky-shadow cascades."
            )
            .define("skyVolumetricGodRaysEnabled", true);
         this.skyVolumetricGodRaysIntensity = builder.comment("Overall brightness multiplier for Sky Volumetric God Rays.")
            .defineInRange("intensity", 0.97, 0.0, 3.0);
         this.skyVolumetricGodRaysDensity = builder.comment(
               "Atmospheric scattering density used by Sky Volumetric God Rays. Higher values make shafts denser and more opaque-looking."
            )
            .defineInRange("density", 0.76, 0.0, 3.0);
         this.skyVolumetricGodRaysSamples = builder.comment(
               "Number of sparse shadow-map raymarch samples per pixel. Lower values are faster; 32 matches the original maximum-quality sampling. Jitter is applied automatically to reduce banding."
            )
            .defineInRange("raymarchSamples", 13, 8, 32);
         this.skyVolumetricGodRaysUniformity = builder.comment(
               "Distance brightness equalization for volumetric rays. 0 keeps physical path-length accumulation; 100 makes fully lit shafts nearly uniform regardless of how close the scene surface is."
            )
            .defineInRange("rayUniformityPercent", 0.0, 0.0, 100.0);
         this.skyVolumetricGodRaysSideVisibility = builder.comment(
               "Artistic side-angle visibility for volumetric shafts. 0 keeps the original strongly directional phase response; 100 raises side-angle scattering toward a neutral visibility floor without reducing the forward highlight."
            )
            .defineInRange("sideVisibilityPercent", 0.0, 0.0, 100.0);
         this.skyVolumetricGodRaysHazeSuppression = builder.comment(
               "Suppresses broad, low-structure volumetric haze created by the artistic Ray Uniformity and Side Visibility boosts without simply lowering Ray Intensity. 0 preserves the original output exactly. Higher values increasingly remove blanket-like screen tint in open views while preserving the physical directional component and shadow-structured shafts. Default: 40%."
            )
            .defineInRange("hazeSuppressionPercent", 41.0, 0.0, 100.0);
         this.skyVolumetricGodRaysEntityOcclusion = builder.comment(
               "Allow the separate Celestial Entity Shadow Map to occlude volumetric shafts. This only has an effect while Entity Shadows are enabled under Celestial Shadows."
            )
            .define("entityOcclusion", false);
         this.skyVolumetricGodRaysMaxDistance = builder.comment(
               "Maximum raymarch distance in blocks. Actual distance is capped by the far sky-shadow cascade coverage."
            )
            .defineInRange("maxDistance", 144.0, 16.0, 384.0);
         this.skyVolumetricGodRaysAnisotropy = builder.comment(
               "Forward-scattering focus. Higher values concentrate the brightest volumetric shafts around the Sun/Moon direction."
            )
            .defineInRange("anisotropy", 0.53, 0.0, 0.95);
         this.skyVolumetricGodRaysCelestialColorInfluence = builder.comment(
               "Percent of NeoSkyCelestiaLighting direct color mixed into volumetric rays. 0 is white, 100 fully follows dawn/day/sunset/moon color."
            )
            .defineInRange("celestialColorInfluencePercent", 100.0, 0.0, 100.0);
         this.skyVolumetricGodRaysAutoAdaptation = builder.comment(
               "Automatically raises only Ray Uniformity and Side Visibility as vanilla skylight at the camera falls. The configured slider values remain the minimum/base at skylight 15. The Full Adaptation Skylight setting controls the skylight level at which both adapted values have already reached 100%. Skylight is sampled every 2 game ticks (~10 Hz) and the response is smoothed over roughly 0.5 seconds. Intensity, Density, Anisotropy and all other volumetric settings are never modified."
            )
            .define("autoAdaptation", true);
         this.skyVolumetricGodRaysFullAdaptationSkylight = builder.comment(
               "Vanilla SKY light level at or below which Auto Adaptation has fully raised Ray Uniformity and Side Visibility to 100%. 15 is the most aggressive setting; 0 waits until complete skylight darkness. The slider is presented from 15 down to 0. Default: 4."
            )
            .defineInRange("fullAdaptationSkylight", 0, 0, 15);
         builder.pop();
         builder.comment("Camera").push("camera");
         this.lensFlareEnabled = builder.comment("Enable the cinematic lens flare effect from the Sun and Moon independently from Sky God Rays.")
            .define("lensFlareEnabled", true);
         this.lensFlareIntensity = builder.comment("Overall intensity multiplier for the entire lens flare effect.")
            .defineInRange("flareIntensity", 1.87, 0.0, 3.0);
         this.lensFlareStreakIntensity = builder.comment("Intensity multiplier for the anamorphic horizontal streak only.")
            .defineInRange("streakIntensity", 1.0, 0.0, 3.0);
         this.lensFlareStreakLength = builder.comment("Length multiplier for the anamorphic horizontal streak.").defineInRange("streakLength", 1.0, 0.25, 3.0);
         this.lensFlareStreakWidth = builder.comment("Width multiplier for the anamorphic horizontal streak.").defineInRange("streakWidth", 1.0, 0.25, 3.0);
         this.lensFlareChromaticSpread = builder.comment("How strongly the lens flare splits into red, green and blue channels.")
            .defineInRange("chromaticSpread", 1.0, 0.0, 3.0);
         this.lensFlareGhostIntensity = builder.comment("Intensity multiplier for the circular lens ghosts.").defineInRange("ghostIntensity", 1.0, 0.0, 3.0);
         this.lensFlareGhostSize = builder.comment("Size multiplier for the circular lens ghosts.").defineInRange("ghostSize", 1.0, 0.35, 3.0);
         this.lensFlareSpread = builder.comment("Spacing multiplier that pushes the flare ghosts farther across the screen.")
            .defineInRange("flareSpread", 1.0, 0.35, 3.0);
         builder.pop();
         builder.comment("Filmic Tonemap").push("tonemap");
         this.tonemapEnabled = builder.comment("Enable the low-cost filmic tonemap integrated into the Sky God Rays composite pass.").define("enabled", true);
         this.tonemapExposure = builder.comment("Exposure compensation in stops before the filmic curve.").defineInRange("exposure", 1.14, -2.0, 2.0);
         this.tonemapContrast = builder.comment("Strength of the gentle S-curve contrast.").defineInRange("contrast", 0.18, 0.0, 1.0);
         this.tonemapHighlightCompression = builder.comment("How strongly bright highlights roll off instead of clipping to white.")
            .defineInRange("highlightCompression", 0.65, 0.0, 1.0);
         this.tonemapShadowDepth = builder.comment("Adds a subtle toe to deepen flat shadows without crushing them.")
            .defineInRange("shadowDepth", 0.1, 0.0, 1.0);
         this.tonemapSaturation = builder.comment("Overall color saturation multiplier.").defineInRange("saturation", 1.0, 0.0, 2.0);
         this.tonemapVibrance = builder.comment("Luminance-aware saturation boost that mainly affects muted colors.").defineInRange("vibrance", 0.18, 0.0, 1.0);
         this.tonemapGamma = builder.comment("Display gamma used around the filmic operation.").defineInRange("gamma", 2.2, 1.6, 2.8);
         this.tonemapStrength = builder.comment("Blend between the original image and the filmic result.").defineInRange("strength", 1.0, 0.0, 1.0);
         builder.pop();
         builder.comment("Fog").push("fog");
         this.fogEnabled = builder.comment("Enable atmospheric distance fog.").define("fogEnabled", true);
         this.fogCelestialScatteringEnabled = builder.comment("Enable restrained Sun and Moon scattering glow, even when distance fog is disabled.")
            .define("fogCelestialScatteringEnabled", false);
         this.fogDensity = builder.comment(
               "True extinction multiplier. The shader squares this value so the upper range can produce very dense fog without changing its color toward white."
            )
            .defineInRange("density", 7.12, 0.0, 20.0);
         this.fogScatteringBrightness = builder.comment("Brightness of the fog scattering color, independent from extinction/density.")
            .defineInRange("scatteringBrightness", 1.2, 0.25, 1.2);
         this.fogMaxBrightness = builder.comment(
               "Maximum luminance allowed for fog color. Preserves hue while preventing dense fog from becoming a white wall."
            )
            .defineInRange("maxBrightness", 1.16, 0.15, 1.2);
         this.fogNearDensityBoost = builder.comment("Additional optical density inside nearby chunks.").defineInRange("nearDensityBoost", 0.0, 0.0, 5.0);
         this.fogNearBoostRange = builder.comment("Distance over which the nearby density boost builds up.").defineInRange("nearBoostRange", 55.0, 4.0, 128.0);
         this.fogDynamicCelestialColor = builder.comment(
               "Tint fog from the shared NeoSkyCelestiaLighting state so dawn, sunset, daytime and moonlight affect its color."
            )
            .define("dynamicCelestialColor", true);
         this.fogCelestialColorBlend = builder.comment("How strongly NeoSkyCelestiaLighting color is mixed into fog when dynamic color is enabled.")
            .defineInRange("celestialColorBlend", 0.63, 0.0, 1.0);
         this.fogStartDistance = builder.comment("Distance in blocks before the custom fog starts becoming visible.")
            .defineInRange("startDistance", 8.0, 0.0, 160.0);
         this.fogNearFade = builder.comment(
               "Distance in blocks over which fog reaches full local strength after its start point. Zero makes it appear almost immediately."
            )
            .defineInRange("nearFade", 42.6, 0.0, 64.0);
         this.fogMaxOpacity = builder.comment("Maximum opacity of the custom fog. Keeping this below one prevents a white wall.")
            .defineInRange("maxOpacity", 0.64, 0.0, 1.0);
         this.fogSkyTint = builder.comment("How strongly the fog inherits the blue sky gradient instead of a neutral gray.")
            .defineInRange("skyTint", 0.78, 0.0, 1.0);
         this.fogScatteringStrength = builder.comment("Intensity of directional Sun and Moon scattering in the fog.")
            .defineInRange("scatteringStrength", 2.06, 0.0, 5.0);
         this.fogDistanceCurve = builder.comment(
               "Controls how fog builds with distance. Values below 1 make nearby fog much stronger; values above 1 keep the foreground clearer."
            )
            .defineInRange("distanceCurve", 2.54, 0.35, 3.0);
         this.fogHeight = builder.comment(
               "Vertical fog thickness in blocks. Larger values let the fog extend higher and make its density change more slowly with altitude."
            )
            .defineInRange("height", 81.0, 50.0, 384.0);
         builder.pop();
         builder.comment("Experimental Water").push("water");
         this.waterEnabled = builder.comment("Enable Luxium's procedural realistic water. Disabled by default while experimental.").define("enabled", true);
         this.waterDepthAwareRefraction = builder.comment("Reject refracted samples that would pull foreground geometry through the water surface.")
            .define("depthAwareRefraction", true);
         this.waterDetailWaves = builder.comment(
               "Enable the finest texture-driven micro-ripple band. When disabled, the shader skips the extra high-frequency texture sample completely."
            )
            .define("detailWaves", true);
         this.waterRenderScale = builder.comment(
               "Resolution scale for the expensive water surface shader. The canonical scene color/depth stay native and are sampled directly; below 1.0 only the costly WATER material pass is rasterized at reduced resolution, then native WATER geometry resolves it with one filtered lookup. Non-water terrain remains full resolution."
            )
            .defineInRange("renderScale", 0.45, 0.25, 1.0);
         this.waterWaveStrength = builder.comment("Strength of the packed micro-ripple normal field. Geometry remains perfectly flat.")
            .defineInRange("waveStrength", 0.44, 0.0, 5.0);
         this.waterWaveScale = builder.comment("Spatial frequency multiplier for the irregular micro-ripple field.")
            .defineInRange("waveScale", 6.49, 0.25, 10.0);
         this.waterWaveSpeed = builder.comment("Animation speed multiplier for the moving micro-ripple field.").defineInRange("waveSpeed", 4.07, 0.0, 6.0);
         this.waterDetailStrength = builder.comment("Strength of the finest high-frequency micro-ripple band when Detail Waves is enabled.")
            .defineInRange("detailStrength", 0.58, 0.0, 1.0);
         this.waterRefractionStrength = builder.comment("Screen-space refraction amount. This is not SSR and performs no reflection tracing.")
            .defineInRange("refractionStrength", 3.27, 0.0, 4.0);
         this.waterAbsorptionStrength = builder.comment("Beer-Lambert absorption/scattering strength with water thickness.")
            .defineInRange("absorptionStrength", 2.34, 0.0, 3.0);
         this.waterVisibilityDepth = builder.comment("Approximate depth in blocks over which water transitions from shallow to deep appearance.")
            .defineInRange("visibilityDepth", 2.0, 2.0, 48.0);
         this.waterFresnelStrength = builder.comment("Extra surface presence at grazing view angles. No reflection sample is used.")
            .defineInRange("fresnelStrength", 1.5, 0.0, 1.5);
         this.waterFresnelPower = builder.comment("Shape of the grazing-angle Fresnel response.").defineInRange("fresnelPower", 3.9, 1.0, 10.0);
         this.waterSurfaceOpacity = builder.comment("Minimum surface tint mixed over shallow refracted water.").defineInRange("surfaceOpacity", 0.07, 0.0, 0.6);
         this.waterSpecularStrength = builder.comment("Sun/moon specular highlight intensity.").defineInRange("specularStrength", 2.0, 0.0, 2.0);
         this.waterSpecularSharpness = builder.comment(
               "Sun/moon microfacet glint sharpness. Higher values produce a tighter highlight while the finite roughness floor keeps the highlight water-like instead of a single plastic dot."
            )
            .defineInRange("specularSharpness", 281.0, 16.0, 512.0);
         this.waterSsrEnabled = builder.comment(
               "Enable water-only screen-space reflections. Disabled by default. SSR traces the already-rendered main scene color/depth and never renders the world a second time."
            )
            .define("ssrEnabled", true);
         this.waterSsrStrength = builder.comment("Strength of valid screen-space reflection hits before Fresnel/view-angle weighting.")
            .defineInRange("ssrStrength", 1.03, 0.0, 2.0);
         this.waterSsrMaxDistance = builder.comment(
               "Maximum view-space distance in blocks traced by water SSR. Longer rays can reflect farther terrain but cost the same number of samples at lower spatial precision."
            )
            .defineInRange("ssrMaxDistance", 45.0, 4.0, 128.0);
         this.waterSsrThickness = builder.comment(
               "Depth thickness accepted as a screen-space SSR hit. Raise this if thin/distant reflections have holes; lower it if reflections stick to unrelated geometry."
            )
            .defineInRange("ssrThickness", 2.0, 0.02, 2.0);
         this.waterSsrEdgeFade = builder.comment("Width of the screen-edge fade used to hide SSR rays leaving the visible scene.")
            .defineInRange("ssrEdgeFade", 0.01, 0.01, 0.4);
         builder.pop();
         builder.comment("Shared Screen-Space Reflections").push("ssr");
         this.ssrQuality = builder.comment(
               "Global screen-space reflection trace quality shared by water, command-created rain puddles, and Wet Surfaces. Controls ray steps and hit refinement, not whether a material uses SSR."
            )
            .defineInRange("quality", 0.4, 0.25, 1.0);
         builder.pop();
         builder.comment("Experimental Rain Puddles").push("rainPuddles");
         this.puddleMaxDepth = builder.comment(
               "Maximum virtual puddle depth in blocks. The PNG grayscale map scales this value; geometry remains on the block surface."
            )
            .defineInRange("maxDepth", 0.195, 0.005, 0.2);
         this.puddleDepthCurve = builder.comment(
               "Exponent applied to the grayscale depth map. Above 1 emphasizes deeper centers while keeping thin edges shallow."
            )
            .defineInRange("depthCurve", 2.95, 0.35, 3.0);
         this.puddleWetDarkening = builder.comment("How much the underlying block darkens under the wet fringe and puddle body.")
            .defineInRange("wetDarkening", 0.52, 0.0, 0.6);
         this.puddleSurfaceOpacity = builder.comment(
               "Visual coverage of the puddle material. Lower values reveal more of the original block while preserving depth-driven wetness."
            )
            .defineInRange("surfaceOpacity", 1.0, 0.1, 1.0);
         this.puddleEdgeSoftness = builder.comment("Width and softness of the transition from dry block to wet fringe to visible water.")
            .defineInRange("edgeSoftness", 0.51, 0.0, 1.0);
         this.puddleWaveStrength = builder.comment("Strength of packed micro-normal ripples on the puddle surface. Does not displace geometry.")
            .defineInRange("waveStrength", 0.05, 0.0, 2.0);
         this.puddleWaveScale = builder.comment("Spatial frequency of the puddle micro-ripple field.").defineInRange("waveScale", 9.15, 0.25, 10.0);
         this.puddleWaveSpeed = builder.comment("Animation speed of the small moving puddle surface ripples.").defineInRange("waveSpeed", 1.64, 0.0, 4.0);
         this.puddleRippleStrength = builder.comment("Strength of procedural circular rain rings layered over the micro-normal field.")
            .defineInRange("rippleStrength", 0.46, 0.0, 2.0);
         this.puddleRefractionStrength = builder.comment("Screen-space distortion of the block color seen through the virtual puddle depth.")
            .defineInRange("refractionStrength", 2.63, 0.0, 3.0);
         this.puddleSsrEnabled = builder.comment(
               "Enable non-planar screen-space reflections for command-created rain puddles. Uses the already-rendered scene color/depth only."
            )
            .define("ssrEnabled", false);
         this.puddleSsrStrength = builder.comment("Strength of valid puddle SSR hits after Fresnel and confidence weighting.")
            .defineInRange("ssrStrength", 1.03, 0.0, 2.0);
         this.puddleSsrMaxDistance = builder.comment("Maximum screen-space reflection ray distance in blocks for puddles.")
            .defineInRange("ssrMaxDistance", 45.0, 4.0, 96.0);
         this.puddleSsrThickness = builder.comment("Depth crossing tolerance for accepting puddle SSR hits.").defineInRange("ssrThickness", 2.0, 0.02, 2.0);
         this.puddleSsrEdgeFade = builder.comment("Fade width near screen borders where puddle SSR naturally loses information.")
            .defineInRange("ssrEdgeFade", 0.19, 0.01, 0.4);
         builder.pop();
         builder.comment("Experimental Wet Surfaces").push("wet");
         this.wetEnabled = builder.comment(
               "Apply Luxium's wet-film material to visible upward-facing SOLID terrain surfaces. This is a screen-space material pass and does not scan blocks on the CPU."
            )
            .define("enabled", false);
         this.wetMaxDepth = builder.comment(
               "Virtual optical wet-film depth in blocks. Geometry remains unchanged; this scales refraction and depth-driven shading."
            )
            .defineInRange("maxDepth", 0.2, 0.005, 0.2);
         this.wetDepthCurve = builder.comment(
               "Exponent applied to the procedural wet depth field. Higher values emphasize the deeper-looking parts of the film."
            )
            .defineInRange("depthCurve", 3.0, 0.35, 3.0);
         this.wetDarkening = builder.comment("How much the underlying solid terrain darkens under the wet film.").defineInRange("wetDarkening", 0.6, 0.0, 0.6);
         this.wetSurfaceOpacity = builder.comment("Visual presence of the wet material over the original terrain color.")
            .defineInRange("surfaceOpacity", 1.0, 0.1, 1.0);
         this.wetEdgeSoftness = builder.comment("Softness of the upward-surface selection and blend around geometric edges.")
            .defineInRange("edgeSoftness", 0.62, 0.0, 1.0);
         this.wetWaveStrength = builder.comment("Strength of packed micro-normal ripples on globally wet terrain. No geometry displacement is performed.")
            .defineInRange("waveStrength", 0.05, 0.0, 2.0);
         this.wetWaveScale = builder.comment("Spatial frequency of the moving wet micro-normal field.").defineInRange("waveScale", 9.15, 0.25, 10.0);
         this.wetWaveSpeed = builder.comment("Animation speed of the moving wet micro-normal layers.").defineInRange("waveSpeed", 1.64, 0.0, 4.0);
         this.wetRippleStrength = builder.comment("Strength of procedural circular rain rings on wet terrain.").defineInRange("rippleStrength", 0.46, 0.0, 2.0);
         this.wetRefractionStrength = builder.comment("Screen-space distortion of terrain color through the virtual wet film.")
            .defineInRange("refractionStrength", 2.71, 0.0, 3.0);
         this.wetEnvironmentReflectionStrength = builder.comment(
               "Strength of Luxium's analytic sky/environment fallback. It fills missing SSR and keeps wet-film normals readable at near-normal camera angles without a planar reflection pass."
            )
            .defineInRange("environmentReflectionStrength", 1.03, 0.0, 2.0);
         this.wetCelestialSpecularStrength = builder.comment(
               "Strength of the bounded sun/moon microfacet highlight on Wet Surfaces. Uses Luxium celestial direction/color and reacts to micro waves and rain rings."
            )
            .defineInRange("celestialSpecularStrength", 2.0, 0.0, 2.0);
         this.wetSheenFloor = builder.comment(
               "Minimum dielectric wet sheen at near-normal viewing angles. This does not raise SSR itself; it only prevents the wet film from visually disappearing when looking downward."
            )
            .defineInRange("sheenFloor", 0.145, 0.0, 0.25);
         this.wetRippleHighlightStrength = builder.comment(
               "Strength of subtle lighting contrast produced by rain-ring and micro-normal slopes. It modulates lighting from the normal rather than drawing bright ring decals."
            )
            .defineInRange("rippleHighlightStrength", 2.0, 0.0, 2.0);
         this.wetSsrEnabled = builder.comment(
               "Enable non-planar SSR for globally wet terrain. Rays trace the final visible scene depth; no mirrored camera or second world render is used."
            )
            .define("ssrEnabled", true);
         this.wetSsrStrength = builder.comment("Strength of valid wet-surface SSR hits after Fresnel and confidence weighting.")
            .defineInRange("ssrStrength", 2.0, 0.0, 2.0);
         this.wetSsrMaxDistance = builder.comment("Maximum screen-space reflection ray distance in blocks for Wet Surfaces.")
            .defineInRange("ssrMaxDistance", 4.0, 4.0, 96.0);
         this.wetSsrThickness = builder.comment("Depth crossing tolerance for accepting Wet Surfaces SSR hits.").defineInRange("ssrThickness", 2.0, 0.02, 2.0);
         this.wetSsrEdgeFade = builder.comment("Fade width near screen borders where Wet Surfaces SSR loses visible scene information.")
            .defineInRange("ssrEdgeFade", 0.01, 0.01, 0.4);
         builder.pop();
         builder.comment("LSR / Luxium Spatial Resolution").push("lsr");
         this.lsrEnabled = builder.comment(
               "Enable Luxium Spatial Resolution. The main Minecraft + Luxium world is rendered at a reduced internal resolution, then reconstructed to the native framebuffer before the live first-person hand and GUI. Disabled by default."
            )
            .define("enabled", false);
         this.lsrRenderScale = builder.comment(
               "Internal world render scale. 0.67 renders roughly 45% as many world pixels as native resolution. Lower values improve GPU performance but reduce fine texture detail. Intended range is 0.50 to 0.85."
            )
            .defineInRange("renderScale", 0.71, 0.5, 0.85);
         this.lsrSharpness = builder.comment(
               "Edge-adaptive reconstruction sharpness. 0 disables added sharpening; higher values restore more apparent texture detail after upscale but can produce ringing when pushed too far."
            )
            .defineInRange("sharpness", 1.0, 0.0, 1.0);
         builder.pop();
         builder.comment("TFR / Temporal Frame Reprojection").push("tfr");
         this.tfrEnabled = builder.comment(
               "Enable Luxium temporal frame reconstruction. Luxium always uses a fixed REAL/SYNTH cadence: one complete Minecraft + Luxium world frame followed by one reconstructed frame. In world-render-limited scenes this typically raises presented FPS by roughly 1.5x, though the exact gain depends on the GPU/CPU bottleneck. HUD, GUI and first-person hand remain live. Disabled by default."
            )
            .define("enabled", false);
         this.tfrReprojectionStrength = builder.comment(
               "How strongly camera motion is applied to the reconstructed frame. 1.0 uses the full depth-based camera reprojection; lower values reduce warping at the cost of visible image lag/ghosting."
            )
            .defineInRange("reprojectionStrength", 1.0, 0.0, 1.0);
         this.tfrHistoryStability = builder.comment(
               "Spatially stabilizes the already reprojected history sample. Unlike the old implementation this no longer mixes unwarped history, so increasing it softens synthetic-frame shimmer without deliberately adding camera-lag ghosting."
            )
            .defineInRange("historyStability", 0.06, 0.0, 0.35);
         this.tfrCameraCutDistance = builder.comment(
               "Camera movement in blocks from the last real frame that forces an immediate real render instead of attempting a bad reprojection. Lower values are safer; higher values preserve more synthetic frames during fast movement."
            )
            .defineInRange("cameraCutDistance", 9.25, 0.25, 16.0);
         this.tfrCameraCutAngle = builder.comment(
               "Camera rotation in degrees from the last real frame that forces an immediate real render. This prevents severe smearing after very fast mouse turns or camera cuts."
            )
            .defineInRange("cameraCutAngle", 14.0, 5.0, 90.0);
         builder.pop();
         builder.push("grass");
         this.plantsWaveEnabled = builder.define("enabled", false);
         this.plantsWaveStrength = builder.defineInRange("strength", 1.0, 0.0, 2.0);
         this.plantsWaveSpeed = builder.defineInRange("speed", 1.0, 0.0, 3.0);
         this.plantsWaveGustStrength = builder.defineInRange("gustStrength", 0.65, 0.0, 2.0);
         this.plantsWaveDistance = builder.defineInRange("distance", 64, 16, 192);
         this.plantsWaveGrassStrength = builder.defineInRange("grassStrength", 1.0, 0.0, 2.0);
         this.plantsWaveLeavesStrength = builder.defineInRange("leavesStrength", 0.55, 0.0, 2.0);
         this.plantsWaveAquaticStrength = builder.defineInRange("aquaticStrength", 0.65, 0.0, 2.0);
         this.plantsWaveBendStrength = builder.defineInRange("bendStrength", 1.0, 0.0, 2.0);
         builder.pop();
         builder.comment("Opaque Ice").push("opaqueIce");
         this.opaqueIceEnabled = builder.comment(
               "Render only vanilla minecraft:ice in the SOLID chunk layer instead of TRANSLUCENT. This makes normal ice fully opaque and removes translucent sorting/blending/overdraw for it, which can improve performance in large frozen areas. This does not affect frosted_ice, packed_ice, blue_ice, water, glass, or any other block. Disabled by default."
            )
            .define("enabled", true);
         builder.pop();
         builder.push("debugHud");
         this.debugHudEnabled = builder.define("enabled", false);
         this.debugHudFps = builder.define("fps", true);
         this.debugHudMemory = builder.define("memory", true);
         this.debugHudAllocated = builder.define("allocated", false);
         this.debugHudPosition = builder.defineEnum("position", Config.DebugHudPosition.TOP_RIGHT);
         this.debugHudOffsetX = builder.defineInRange("offsetX", 4, 0, 200);
         this.debugHudOffsetY = builder.defineInRange("offsetY", 4, 0, 200);
         this.debugHudTextScale = builder.defineInRange("textScale", 1.0, 0.1, 5.0);
         this.debugHudUpdateSeconds = builder.defineInRange("updateSeconds", 0.5, 0.1, 2.0);
         builder.pop();
         builder.comment("UI").push("ui");
         this.alphaWarningShown = builder.comment("Set to true after the alpha warning screen has been acknowledged. Do not change manually.")
            .define("alphaWarningShown", false);
         builder.pop();
      }
   }

   public static enum DebugHudPosition {
      TOP_LEFT,
      TOP_RIGHT,
      BOTTOM_LEFT,
      BOTTOM_RIGHT;
   }

   public static enum GpuLocalLightingMode {
      GPU_ONLY,
      HYBRID,
      NEOFLOOD_ONLY;
   }

   public static enum GpuLocalShadowMode {
      FAST_SPREAD,
      CUBEMAP_HARD,
      CUBEMAP_SOFT;
   }

   public static enum SkyBakeTileSize {
      SIZE_512(512),
      SIZE_256(256),
      SIZE_128(128);

      private final int pixels;

      private SkyBakeTileSize(int pixels) {
         this.pixels = pixels;
      }

      public int pixels() {
         return this.pixels;
      }
   }

   public static enum SkyGodRaysVisualPreset {
      DEFAULT,
      CINEMATIC,
      CUSTOM;
   }
}
