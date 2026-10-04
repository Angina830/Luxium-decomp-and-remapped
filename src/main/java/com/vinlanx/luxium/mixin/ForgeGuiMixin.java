package com.vinlanx.luxium.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.client.postprocess.PostProcessStateGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
   targets = {"net.minecraftforge.client.gui.overlay.ForgeGui"}
)
public abstract class ForgeGuiMixin {
   @Inject(
      method = {"render"},
      at = {@At("HEAD")},
      require = 0
   )
   private void luxium$resetForgeGuiState(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
      PostProcessStateGuard.restoreGuiCompositeState();
   }

   @Inject(
      method = {"setupOverlayRenderState"},
      at = {@At("HEAD")},
      require = 0
   )
   private void luxium$restoreTargetBeforeOverlay(boolean blend, boolean depthTest, CallbackInfo ci) {
      PostProcessStateGuard.restoreGuiCompositeState();
      Minecraft mc = Minecraft.getInstance();
      RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
      RenderSystem.activeTexture(33984);
      GlStateManager._colorMask(true, true, true, true);
      GlStateManager._depthMask(true);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
   }
}
