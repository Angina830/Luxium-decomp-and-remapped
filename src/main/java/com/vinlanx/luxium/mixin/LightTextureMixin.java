package com.vinlanx.luxium.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.shadows.neoskycelestia.lightmap.NeoSkyLightTextureExtension;
import com.vinlanx.luxium.rtx.Coloredlight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LightTexture.class})
public abstract class LightTextureMixin implements NeoSkyLightTextureExtension {
   @Shadow
   private boolean updateLightTexture;
   @Shadow(
      aliases = {"lightPixels"}
   )
   @Final
   private NativeImage lightPixels;
   @Shadow
   private float blockLightRedFlicker;
   @Shadow
   @Final
   private GameRenderer renderer;
   @Shadow
   @Final
   private Minecraft minecraft;
   @Unique
   private DynamicTexture luxium$blockOnlyTexture;
   @Unique
   private NativeImage luxium$blockOnlyPixels;
   @Unique
   private boolean luxium$lightmapWasDirty;
   @Unique
   private long luxium$lightmapRevision;

   @Shadow
   private float getDarknessGamma(float partialTick) {
      throw new AssertionError();
   }

   @Shadow
   private float calculateDarknessScale(LivingEntity entity, float darknessGamma, float partialTick) {
      throw new AssertionError();
   }

   @Shadow
   private float notGamma(float value) {
      throw new AssertionError();
   }

   @Inject(
      method = {"<init>"},
      at = {@At("RETURN")}
   )
   private void luxium$createBlockOnlyLightmap(GameRenderer renderer, Minecraft minecraft, CallbackInfo ci) {
      this.luxium$blockOnlyTexture = new DynamicTexture(16, 16, false);
      minecraft.getTextureManager().register("luxium_block_only_light_map", this.luxium$blockOnlyTexture);
      this.luxium$blockOnlyPixels = this.luxium$blockOnlyTexture.getPixels();

      for (int skyLevel = 0; skyLevel < 16; skyLevel++) {
         for (int blockLevel = 0; blockLevel < 16; blockLevel++) {
            this.luxium$blockOnlyPixels.setPixelRGBA(blockLevel, skyLevel, -1);
         }
      }

      this.luxium$blockOnlyTexture.upload();
      luxium$configureLinearLightmapTexture(this.luxium$blockOnlyTexture.getId());
   }

   @Inject(
      method = {"updateLightTexture"},
      at = {@At("HEAD")}
   )
   private void luxium$captureLightmapDirty(float partialTick, CallbackInfo ci) {
      this.luxium$lightmapWasDirty = this.updateLightTexture;
   }

   @Inject(
      method = {"updateLightTexture"},
      at = {@At("TAIL")}
   )
   private void luxium$updateBlockOnlyLightmap(float partialTick, CallbackInfo ci) {
      if (this.luxium$lightmapWasDirty) {
         this.luxium$rebuildBlockOnlyLightmap(partialTick);
         this.luxium$lightmapRevision++;
      }
   }

   @Inject(
      method = {"close"},
      at = {@At("TAIL")}
   )
   private void luxium$closeBlockOnlyLightmap(CallbackInfo ci) {
      if (this.luxium$blockOnlyTexture != null) {
         this.luxium$blockOnlyTexture.close();
         this.luxium$blockOnlyTexture = null;
         this.luxium$blockOnlyPixels = null;
      }
   }

   @Override
   public int neosky$getBlockOnlyLightTextureId() {
      return this.luxium$blockOnlyTexture != null ? this.luxium$blockOnlyTexture.getId() : -1;
   }

   @Override
   public int neosky$getMainLightPixel(int blockLevel, int skyLevel) {
      return this.lightPixels == null ? -1 : this.lightPixels.getPixelRGBA(Mth.clamp(blockLevel, 0, 15), Mth.clamp(skyLevel, 0, 15));
   }

   @Override
   public int neosky$getBlockOnlyLightPixel(int blockLevel, int skyLevel) {
      return this.luxium$blockOnlyPixels == null ? -1 : this.luxium$blockOnlyPixels.getPixelRGBA(Mth.clamp(blockLevel, 0, 15), Mth.clamp(skyLevel, 0, 15));
   }

   @Override
   public long neosky$getLightmapRevision() {
      return this.luxium$lightmapRevision;
   }

   @ModifyArg(
      method = {"updateLightTexture"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/LightTexture;getBrightness(Lnet/minecraft/world/level/dimension/DimensionType;I)F",
         ordinal = 1
      ),
      index = 1
   )
   private int luxium$forceSkyOnlyBlockLight(int blockLevel) {
      return NeoShadowsEngine.isAnyShadowCapturePass() ? 0 : blockLevel;
   }

   @Redirect(
      method = {"updateLightTexture"},
      at = @At(
         value = "INVOKE",
         target = "Lorg/joml/Vector3f;set(FFF)Lorg/joml/Vector3f;",
         remap = false
      )
   )
   private Vector3f luxium$colorizeBlockLight(Vector3f vector, float red, float green, float blue) {
      luxium$setColoredBlockLight(vector, red, green, blue);
      return vector;
   }

   @Unique
   private void luxium$rebuildBlockOnlyLightmap(float partialTick) {
      ClientLevel level = this.minecraft.level;
      if (level != null && this.minecraft.player != null && this.luxium$blockOnlyPixels != null && this.luxium$blockOnlyTexture != null) {
         float skyDarken = level.getSkyDarken(1.0F);
         float skyBrightnessScale = level.getSkyFlashTime() > 0 ? 1.0F : skyDarken * 0.95F + 0.05F;
         float darknessEffectScale = ((Double)this.minecraft.options.darknessEffectScale().get()).floatValue();
         float darknessGamma = this.getDarknessGamma(partialTick) * darknessEffectScale;
         float darknessScale = this.calculateDarknessScale(this.minecraft.player, darknessGamma, partialTick) * darknessEffectScale;
         float waterVision = this.minecraft.player.getWaterVision();
         float nightVision;
         if (this.minecraft.player.hasEffect(MobEffects.NIGHT_VISION)) {
            nightVision = GameRenderer.getNightVisionScale(this.minecraft.player, partialTick);
         } else if (waterVision > 0.0F && this.minecraft.player.hasEffect(MobEffects.CONDUIT_POWER)) {
            nightVision = waterVision;
         } else {
            nightVision = 0.0F;
         }

         Vector3f skyColor = new Vector3f(skyDarken, skyDarken, 1.0F).lerp(new Vector3f(1.0F, 1.0F, 1.0F), 0.35F);
         float blockScale = this.blockLightRedFlicker + 1.5F;
         Vector3f color = new Vector3f();

         for (int skyLevel = 0; skyLevel < 16; skyLevel++) {
            for (int blockLevel = 0; blockLevel < 16; blockLevel++) {
               float skyBrightness = LightTexture.getBrightness(level.dimensionType(), 0) * skyBrightnessScale;
               float blockBrightness = LightTexture.getBrightness(level.dimensionType(), blockLevel) * blockScale;
               float green = blockBrightness * ((blockBrightness * 0.6F + 0.4F) * 0.6F + 0.4F);
               float blue = blockBrightness * (blockBrightness * blockBrightness * 0.6F + 0.4F);
               luxium$setColoredBlockLight(color, blockBrightness, green, blue);
               boolean forceBright = level.effects().forceBrightLightmap();
               if (forceBright) {
                  color.lerp(new Vector3f(0.99F, 1.12F, 1.0F), 0.25F);
                  luxium$clampColor(color);
               } else {
                  color.add(new Vector3f(skyColor).mul(skyBrightness));
                  color.lerp(new Vector3f(0.75F, 0.75F, 0.75F), 0.04F);
                  float darkenWorld = this.renderer.getDarkenWorldAmount(partialTick);
                  if (darkenWorld > 0.0F) {
                     color.lerp(new Vector3f(color).mul(0.7F, 0.6F, 0.6F), darkenWorld);
                  }
               }

               level.effects().adjustLightmapColors(level, partialTick, skyDarken, blockScale, skyBrightness, blockLevel, skyLevel, color);
               if (nightVision > 0.0F) {
                  float max = Math.max(color.x(), Math.max(color.y(), color.z()));
                  if (max > 1.0E-5F && max < 1.0F) {
                     color.lerp(new Vector3f(color).mul(1.0F / max), nightVision);
                  }
               }

               if (!forceBright) {
                  if (darknessScale > 0.0F) {
                     color.add(-darknessScale, -darknessScale, -darknessScale);
                  }

                  luxium$clampColor(color);
               }

               float gamma = ((Double)this.minecraft.options.gamma().get()).floatValue();
               Vector3f gammaColor = new Vector3f(this.notGamma(color.x()), this.notGamma(color.y()), this.notGamma(color.z()));
               color.lerp(gammaColor, Math.max(0.0F, gamma - darknessGamma));
               color.lerp(new Vector3f(0.75F, 0.75F, 0.75F), 0.04F);
               luxium$clampColor(color);
               color.mul(255.0F);
               int red = (int)color.x();
               int greenChannel = (int)color.y();
               int blueChannel = (int)color.z();
               this.luxium$blockOnlyPixels.setPixelRGBA(blockLevel, skyLevel, 0xFF000000 | blueChannel << 16 | greenChannel << 8 | red);
            }
         }

         this.luxium$blockOnlyTexture.upload();
      }
   }

   @Unique
   private static void luxium$setColoredBlockLight(Vector3f out, float red, float green, float blue) {
      if (Coloredlight.isEnabled() && Coloredlight.isWorldRenderActive()) {
         float intensity = Math.max(red, Math.max(green, blue));
         out.set(intensity * Coloredlight.getRedMultiplier(), intensity * Coloredlight.getGreenMultiplier(), intensity * Coloredlight.getBlueMultiplier());
      } else {
         out.set(red, green, blue);
      }
   }

   @Unique
   private static void luxium$clampColor(Vector3f color) {
      color.set(Mth.clamp(color.x(), 0.0F, 1.0F), Mth.clamp(color.y(), 0.0F, 1.0F), Mth.clamp(color.z(), 0.0F, 1.0F));
   }

   @Unique
   private static void luxium$configureLinearLightmapTexture(int textureId) {
      int oldActive = GlStateManager._getActiveTexture();
      GlStateManager._activeTexture(33984);
      GlStateManager._bindTexture(textureId);
      RenderSystem.texParameter(3553, 10241, 9729);
      RenderSystem.texParameter(3553, 10240, 9729);
      RenderSystem.texParameter(3553, 10242, 33071);
      RenderSystem.texParameter(3553, 10243, 33071);
      GlStateManager._activeTexture(oldActive);
   }
}
