package com.vinlanx.luxium.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.Testing.TestFlashLight;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.Sky;
import com.vinlanx.luxium.client.clouds.LuxiumCloudRenderer;
import com.vinlanx.luxium.client.posteffects.skygodrays;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.client.sunmoonapi.CelestialPath;
import com.vinlanx.luxium.client.water.WaterSurfaceRenderer;
import com.vinlanx.luxium.client.water.WaterSurfaceState;
import com.vinlanx.luxium.mixin.sky.SodiumWorldRendererAccessor;
import com.vinlanx.luxium.rtx.EntityShadowManager;
import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.VanillaLavaLightEngine;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanillaCutoutPrepass;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.world.WorldRendererExtended;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(
   value = {LevelRenderer.class},
   priority = 1100
)
public abstract class LevelRendererMixin {
   @Shadow
   private int ticks;
   @Unique
   private boolean luxium$neoCutoutPrepassArmed;

   @Inject(
      method = {"getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void luxium$overrideLight(BlockAndTintGetter getter, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
      TorchRtxState rtx = TorchRtxState.get();
      if (rtx.isEnabled()) {
         int blockLight = rtx.sampleLightForRender(pos);
         if (blockLight < 0) {
            if (!VanillaLavaLightEngine.get().isActive()) {
               return;
            }

            blockLight = 0;
         }

         if (VanillaLavaLightEngine.get().isActive()) {
            blockLight = Math.max(blockLight, VanillaLavaLightEngine.get().sampleLight(pos));
         }

         int flashlightLight = TestFlashLight.sampleLight(pos);
         if (flashlightLight > blockLight) {
            blockLight = flashlightLight;
         }

         if (blockLight > 0 && rtx.usesEntityOcclusionShadows() && EntityShadowManager.get().isBlockInShadow(pos.asLong())) {
            blockLight = Math.max(0, blockLight - 10);
         }

         int emission = state.getLightEmission(getter, pos);
         blockLight = Math.max(blockLight, emission);
         blockLight = Math.max(0, Math.min(15, blockLight));
         int sky = getter.getBrightness(LightLayer.SKY, pos);
         cir.setReturnValue(sky << 20 | blockLight << 4);
      }
   }

   @ModifyVariable(
      method = {"Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V"},
      at = @At("HEAD"),
      require = 0,
      argsOnly = true,
      ordinal = 1
   )
   private boolean luxium$shadowPassSpectator(boolean spectator) {
      return NeoShadowsEngine.isShadowCapturePass() || spectator;
   }

   @ModifyArgs(
      method = {"setupRender"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/ViewArea;repositionCamera(DD)V"
      ),
      require = 0
   )
   private void luxium$moveViewAreaToRenderCamera(Args args) {
      Vec3 cameraPos = ReflectionSystem.getActiveRenderCameraPos();
      if (cameraPos != null) {
         args.set(0, cameraPos.x);
         args.set(1, cameraPos.z);
      }
   }

   @ModifyArgs(
      method = {"setupRender"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"
      ),
      require = 0
   )
   private void luxium$useRenderCameraForTerrainOrigin(Args args) {
      Vec3 cameraPos = ReflectionSystem.getActiveRenderCameraPos();
      if (cameraPos != null) {
         args.set(0, cameraPos.x);
         args.set(1, cameraPos.y);
         args.set(2, cameraPos.z);
      }
   }

   @Inject(
      method = {"renderClouds"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void luxium$renderThreeLayerClouds(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo ci
   ) {
      if (Config.isFeatureEnabled(Config.CLIENT.cloudsEnabled)) {
         boolean cloudShadowPass = NeoSkyCelestia.get().isRenderingCloudShadowPass();
         if (LuxiumCloudRenderer.get().render(poseStack, projectionMatrix, partialTick, cameraX, cameraY, cameraZ, this.ticks, cloudShadowPass)) {
            ci.cancel();
         }
      }
   }

   @Inject(
      method = {"allChanged"},
      at = {@At("RETURN")},
      require = 0
   )
   private void luxium$reloadCloudResources(CallbackInfo ci) {
      LuxiumCloudRenderer.get().invalidateResources();
   }

   @Inject(
      method = {"setLevel"},
      at = {@At("RETURN")},
      require = 0
   )
   private void luxium$resetCloudWorldCache(ClientLevel level, CallbackInfo ci) {
      LuxiumCloudRenderer.get().invalidateGeometry();
   }

   @Inject(
      method = {"renderClouds"},
      at = {@At("HEAD")},
      require = 0
   )
   private void luxium$prepareCloudShadowDepthPass(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo ci
   ) {
      if (NeoSkyCelestia.get().isRenderingCloudShadowPass()) {
         ShaderInstance shader = ShaderManager.getCloudShadowCasterShader();
         if (shader != null) {
            RenderSystem.setShader(() -> shader);
         }

         RenderSystem.disableBlend();
         RenderSystem.enableDepthTest();
         RenderSystem.depthMask(true);
         RenderSystem.colorMask(false, false, false, false);
      }
   }

   @Inject(
      method = {"renderClouds"},
      at = {@At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V",
         shift = Shift.BEFORE
      )},
      require = 0
   )
   private void luxium$forceCloudShadowDepthWrite(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo ci
   ) {
      if (NeoSkyCelestia.get().isRenderingCloudShadowPass()) {
         ShaderInstance shader = ShaderManager.getCloudShadowCasterShader();
         if (shader != null) {
            RenderSystem.setShader(() -> shader);
         }

         RenderSystem.disableBlend();
         RenderSystem.enableDepthTest();
         RenderSystem.depthMask(true);
         RenderSystem.colorMask(false, false, false, false);
      }
   }

   @Inject(
      method = {"renderClouds"},
      at = {@At("RETURN")},
      require = 0
   )
   private void luxium$preserveCloudShadowDepthState(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo ci
   ) {
      if (NeoSkyCelestia.get().isRenderingCloudShadowPass()) {
         RenderSystem.disableBlend();
         RenderSystem.enableDepthTest();
         RenderSystem.depthMask(true);
         RenderSystem.colorMask(false, false, false, false);
      }
   }

   @Inject(
      method = {"renderSky"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void luxium$customSkyRendering(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean isFoggy, Runnable setupFog, CallbackInfo ci
   ) {
      if (Config.isFeatureEnabled(Config.CLIENT.skyEnabled)) {
         Sky.render(poseStack, projectionMatrix, partialTick);
         ci.cancel();
      }
   }

   @ModifyConstant(
      method = {"renderSky"},
      constant = {@Constant(
         floatValue = 30.0F
      )},
      require = 1
   )
   private float luxium$scaleVanillaSunSize(float vanillaSize) {
      return Config.isEnabled() ? vanillaSize * Mth.clamp(((Double)Config.CLIENT.skySunSize.get()).floatValue(), 0.1F, 3.0F) : vanillaSize;
   }

   @ModifyConstant(
      method = {"renderSky"},
      constant = {@Constant(
         floatValue = 20.0F
      )},
      require = 1
   )
   private float luxium$scaleVanillaMoonSize(float vanillaSize) {
      return Config.isEnabled() ? vanillaSize * Mth.clamp(((Double)Config.CLIENT.skyMoonSize.get()).floatValue(), 0.1F, 3.0F) : vanillaSize;
   }

   @Inject(
      method = {"renderSky"},
      at = {@At("HEAD")},
      require = 0
   )
   private void luxium$beginSkyGodRayCapture(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean isFoggy, Runnable setupFog, CallbackInfo ci
   ) {
      if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
         skygodrays.beginSkyCapture();
      }
   }

   @Inject(
      method = {"renderSky"},
      at = {@At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
         ordinal = 1,
         shift = Shift.AFTER
      )},
      require = 1
   )
   private void luxium$applyVanillaCelestialPath(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean isFoggy, Runnable setupFog, CallbackInfo ci
   ) {
      CelestialPath.applyOrbitTilt(poseStack);
   }

   @Inject(
      method = {"renderSky"},
      at = {@At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V",
         ordinal = 0
      )},
      require = 0
   )
   private void luxium$captureSunSkyGodRayAnchor(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean isFoggy, Runnable setupFog, CallbackInfo ci
   ) {
      if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
         skygodrays.captureCelestial(new Matrix4f(poseStack.last().pose()), new Matrix4f(projectionMatrix), false, partialTick);
      }
   }

   @Inject(
      method = {"renderSky"},
      at = {@At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V",
         ordinal = 1
      )},
      require = 0
   )
   private void luxium$captureMoonSkyGodRayAnchor(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean isFoggy, Runnable setupFog, CallbackInfo ci
   ) {
      if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
         skygodrays.captureCelestial(new Matrix4f(poseStack.last().pose()), new Matrix4f(projectionMatrix), true, partialTick);
      }
   }

   @Inject(
      method = {"renderEntity"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void luxium$skipShadowCaptureEntities(
      Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo ci
   ) {
      if (NeoShadowsEngine.isShadowCapturePass() && !NeoShadowsEngine.shouldRenderDynamicCastersInShadowPass()) {
         ci.cancel();
      }
   }

   @Inject(
      method = {"renderLevel"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
         ordinal = 0,
         shift = Shift.BEFORE
      )},
      require = 0
   )
   private void luxium$beginNeoSkyCelestiaInlineTerrain(
      PoseStack poseStack,
      float partialTick,
      long finishNano,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f projectionMatrix,
      CallbackInfo ci
   ) {
      NeoSkyCelestia.get().beginInlineTerrainPass();
   }

   @Inject(
      method = {"renderLevel"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
         ordinal = 2,
         shift = Shift.AFTER
      )},
      require = 0
   )
   private void luxium$finishNeoSkyCelestiaInlineTerrain(
      PoseStack poseStack,
      float partialTick,
      long finishNano,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f projectionMatrix,
      CallbackInfo ci
   ) {
      NeoSkyCelestia.get().finishInlineTerrainPass();
   }

   @Inject(
      method = {"renderLevel"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
         ordinal = 0,
         shift = Shift.BEFORE
      )},
      require = 0
   )
   private void luxium$neoGpuCutoutDepthPrepass(
      PoseStack poseStack,
      float partialTick,
      long finishNano,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f projectionMatrix,
      CallbackInfo ci
   ) {
      this.luxium$neoCutoutPrepassArmed = false;
      if (!NeoGpuVanillaCutoutPrepass.shouldRun()) {
         NeoGpuVanillaCutoutPrepass.finish();
      } else {
         SodiumWorldRenderer sodium = ((WorldRendererExtended)this).sodium$getWorldRenderer();
         if (sodium == null) {
            NeoGpuVanillaCutoutPrepass.finish();
         } else {
            RenderSectionManager manager = ((SodiumWorldRendererAccessor)sodium).luxium$getRenderSectionManager();
            if (manager == null) {
               NeoGpuVanillaCutoutPrepass.finish();
            } else {
               Vec3 cameraPos = camera.getPosition();
               ChunkRenderMatrices matrices = new ChunkRenderMatrices(projectionMatrix, new Matrix4f(poseStack.last().pose()));
               NeoGpuVanillaCutoutPrepass.beginDepthPrepass();
               RenderDevice.enterManagedCode();
               boolean completed = false;

               try {
                  manager.renderLayer(matrices, DefaultTerrainRenderPasses.CUTOUT, cameraPos.x, cameraPos.y, cameraPos.z);
                  completed = true;
               } finally {
                  RenderDevice.exitManagedCode();
                  if (!completed) {
                     NeoGpuVanillaCutoutPrepass.finish();
                  }
               }

               if (completed) {
                  NeoGpuVanillaCutoutPrepass.armColorPass();
                  this.luxium$neoCutoutPrepassArmed = true;
               }
            }
         }
      }
   }

   @Inject(
      method = {"renderLevel"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
         ordinal = 0,
         shift = Shift.AFTER
      )},
      require = 0
   )
   private void luxium$finishNeoGpuCutoutColorPass(
      PoseStack poseStack,
      float partialTick,
      long finishNano,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f projectionMatrix,
      CallbackInfo ci
   ) {
      if (this.luxium$neoCutoutPrepassArmed) {
         NeoGpuVanillaCutoutPrepass.finish();
         this.luxium$neoCutoutPrepassArmed = false;
      }
   }

   @Inject(
      method = {"renderChunkLayer"},
      at = {@At("HEAD")},
      require = 0
   )
   private void luxium$captureWaterBackground(
      RenderType renderType, PoseStack poseStack, double camX, double camY, double camZ, Matrix4f projectionMatrix, CallbackInfo ci
   ) {
      if (renderType == RenderType.translucent()) {
         if (WaterSurfaceState.canRenderMainPass()) {
            SodiumWorldRenderer sodium = ((WorldRendererExtended)this).sodium$getWorldRenderer();
            if (sodium != null) {
               RenderSectionManager manager = ((SodiumWorldRendererAccessor)sodium).luxium$getRenderSectionManager();
               if (manager != null) {
                  ChunkRenderMatrices matrices = new ChunkRenderMatrices(projectionMatrix, new Matrix4f(poseStack.last().pose()));
                  WaterSurfaceRenderer.renderWater(manager, matrices, camX, camY, camZ);
               }
            }
         }
      }
   }

   @Inject(
      method = {"renderChunkLayer"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void luxium$skipShadowCaptureTranslucency(
      RenderType renderType, PoseStack poseStack, double camX, double camY, double camZ, Matrix4f projectionMatrix, CallbackInfo ci
   ) {
      if (NeoShadowsEngine.isShadowCapturePass() && (renderType == RenderType.translucent() || renderType == RenderType.tripwire())) {
         ci.cancel();
      }
   }
}
