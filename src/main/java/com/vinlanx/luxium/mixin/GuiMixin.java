package com.vinlanx.luxium.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.postprocess.PostProcessStateGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Gui.class})
public abstract class GuiMixin {
   @Shadow
   public abstract void renderVignette(GuiGraphics var1, Entity var2);

   private static boolean luxium$hasUnsafePostEffects() {
      return Config.isFeatureEnabled(Config.CLIENT.skyGodRaysEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.lensFlareEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.realisticShadowsEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.neoCpuShadowsEnabled)
         || Config.isFeatureEnabled(Config.CLIENT.reflectionEnabled);
   }

   private static boolean luxium$shouldCancelVignette() {
      return PostProcessStateGuard.unsafeGuiCompositeStateSeenThisSession()
         || PostProcessStateGuard.repairedGuiCompositeStateThisFrame()
         || luxium$hasUnsafePostEffects();
   }

   @Redirect(
      method = {"render"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/Gui;renderVignette(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/Entity;)V"
      ),
      require = 0
   )
   private void luxium$conditionallyRenderVignette(Gui instance, GuiGraphics guiGraphics, Entity entity) {
      if (luxium$shouldCancelVignette()) {
         PostProcessStateGuard.restoreGuiCompositeState();
      } else {
         this.renderVignette(guiGraphics, entity);
      }
   }

   @Inject(
      method = {"render"},
      at = {@At("HEAD")},
      require = 0
   )
   private void luxium$resetGuiRenderState(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
      PostProcessStateGuard.restoreGuiCompositeState();
   }

   @Inject(
      method = {"renderVignette"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void luxium$resetVignetteRenderState(GuiGraphics guiGraphics, Entity entity, CallbackInfo ci) {
      if (luxium$shouldCancelVignette()) {
         ci.cancel();
      } else {
         PostProcessStateGuard.restoreGuiCompositeState();
         Minecraft mc = Minecraft.getInstance();
         RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
         RenderSystem.activeTexture(33984);
         GlStateManager._colorMask(true, true, true, true);
         GlStateManager._depthMask(true);
         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
         RenderSystem.depthFunc(515);
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      }
   }
}
