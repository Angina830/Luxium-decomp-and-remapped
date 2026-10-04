package com.vinlanx.luxium.client.guiscreen;

import com.vinlanx.luxium.Config;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class AlphaWarningScreen extends Screen {
   private static final int CONTINUE_LOCK_TICKS = 200;
   private static final int MAX_TEXT_WIDTH = 480;
   private static final int LOGO_SIZE = 84;
   private static final int SCREEN_MARGIN = 8;
   private static final float MIN_CONTENT_SCALE = 0.4F;
   private static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath("luxium", "textures/logow.png");
   private int ticksOpen = 0;
   private final LuxiumMenuBackgroundRenderer backgroundRenderer = new LuxiumMenuBackgroundRenderer();
   private Button continueButton;
   private Button youtubeButton;
   private int controlsTop;

   public AlphaWarningScreen() {
      super(Component.translatable("screen.luxium.alpha_warning.title"));
   }

   public static boolean shouldShow() {
      return !(Boolean)Config.CLIENT.alphaWarningShown.get();
   }

   protected void init() {
      this.continueButton = Button.builder(Component.translatable("screen.luxium.alpha_warning.continue"), btn -> this.acknowledge())
         .bounds(0, 0, 136, 20)
         .build();
      this.continueButton.active = false;
      this.addRenderableWidget(this.continueButton);
      this.youtubeButton = Button.builder(Component.translatable("screen.luxium.alpha_warning.youtube"), btn -> this.openYoutube())
         .bounds(0, 0, 62, 20)
         .build();
      this.addRenderableWidget(this.youtubeButton);
      this.layoutControls();
   }

   public void tick() {
      this.ticksOpen++;
      this.backgroundRenderer.tick();
      if (this.ticksOpen >= 200) {
         this.continueButton.active = true;
         this.continueButton.setMessage(Component.translatable("screen.luxium.alpha_warning.continue"));
      } else {
         int remaining = (200 - this.ticksOpen + 19) / 20;
         this.continueButton.setMessage(Component.translatable("screen.luxium.alpha_warning.continue_wait", new Object[]{remaining}));
      }
   }

   public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
      int h = this.height;
      this.backgroundRenderer.render(gfx, this.width, h, mouseX, mouseY, partialTick);
      float contentScale = this.calculateContentScale();
      int virtualWidth = Math.max(1, Mth.floor((float)this.width / contentScale));
      int cx = virtualWidth / 2;
      int textWidth = Math.min(480, Math.max(120, Mth.floor((float)(this.width - 16) / contentScale)));
      int textY = Mth.ceil(8.0F / contentScale);
      gfx.pose().pushPose();
      gfx.pose().scale(contentScale, contentScale, 1.0F);
      gfx.blit(LOGO, cx - 42, textY, 0, 0.0F, 0.0F, 84, 84, 84, 84);
      textY += 92;
      Component titleComp = Component.translatable("screen.luxium.alpha_warning.mod_title");
      int titleW = this.font.width(titleComp);
      gfx.pose().pushPose();
      gfx.pose().scale(2.0F, 2.0F, 1.0F);
      gfx.drawString(this.font, titleComp, cx / 2 - titleW / 2, textY / 2, -462632, true);
      gfx.pose().popPose();
      textY += 9 * 2 + 14;
      int dividerHalfWidth = Math.min(160, textWidth / 2);
      gfx.fill(cx - dividerHalfWidth, textY, cx + dividerHalfWidth, textY + 1, 1442829419);
      textY += 9;

      for (Component line : bodyText()) {
         for (FormattedCharSequence seq : this.font.split(line, textWidth)) {
            int lineW = this.font.width(seq);
            gfx.drawString(this.font, seq, cx - lineW / 2, textY, -1317421, false);
            textY += 9 + 2;
         }

         textY += 2;
      }

      Component ytHint = Component.translatable("screen.luxium.alpha_warning.youtube_hint");

      for (FormattedCharSequence seq : this.font.split(ytHint, textWidth)) {
         int lineW = this.font.width(seq);
         gfx.drawString(this.font, seq, cx - lineW / 2, textY, -6303489, false);
         textY += 9 + 1;
      }

      gfx.pose().popPose();
      super.render(gfx, mouseX, mouseY, partialTick);
   }

   private void layoutControls() {
      int buttonHeight = 20;
      int gap = 6;
      if (this.width >= 220) {
         int youtubeWidth = Mth.clamp(this.width / 8, 62, 76);
         int continueWidth = Mth.clamp(this.width / 5, 112, 150);
         int totalWidth = continueWidth + gap + youtubeWidth;
         int startX = (this.width - totalWidth) / 2;
         this.controlsTop = Math.max(8, this.height - buttonHeight - 8);
         this.continueButton.setX(startX);
         this.continueButton.setY(this.controlsTop);
         this.continueButton.setWidth(continueWidth);
         this.youtubeButton.setX(startX + continueWidth + gap);
         this.youtubeButton.setY(this.controlsTop);
         this.youtubeButton.setWidth(youtubeWidth);
      } else {
         int buttonWidth = Math.max(80, this.width - 16);
         this.controlsTop = Math.max(8, this.height - buttonHeight * 2 - gap - 8);
         this.continueButton.setX((this.width - buttonWidth) / 2);
         this.continueButton.setY(this.controlsTop);
         this.continueButton.setWidth(buttonWidth);
         this.youtubeButton.setX((this.width - buttonWidth) / 2);
         this.youtubeButton.setY(this.controlsTop + buttonHeight + gap);
         this.youtubeButton.setWidth(buttonWidth);
      }
   }

   private float calculateContentScale() {
      float availableHeight = Math.max(1.0F, (float)this.controlsTop - 16.0F);
      float scale = Mth.clamp(Math.min((float)this.width / 560.0F, availableHeight / 300.0F), 0.4F, 1.0F);

      for (int i = 0; i < 2; i++) {
         int textWidth = Math.min(480, Math.max(120, Mth.floor((float)(this.width - 16) / scale)));
         float requiredHeight = this.contentHeight(textWidth, scale);
         if (requiredHeight * scale <= availableHeight) {
            break;
         }

         scale = Math.max(0.4F, scale * availableHeight / (requiredHeight * scale));
      }

      return scale;
   }

   private float contentHeight(int textWidth, float scale) {
      float height = 8.0F / scale + 84.0F + 8.0F + (float)(9 * 2) + 23.0F;

      for (Component line : bodyText()) {
         height += (float)(this.font.split(line, textWidth).size() * (9 + 2) + 2);
      }

      Component hint = Component.translatable("screen.luxium.alpha_warning.youtube_hint");
      return height + (float)(this.font.split(hint, textWidth).size() * (9 + 1));
   }

   private static List<Component> bodyText() {
      return List.of(
         Component.translatable("screen.luxium.alpha_warning.line1"),
         Component.translatable("screen.luxium.alpha_warning.line2"),
         Component.translatable("screen.luxium.alpha_warning.line3"),
         Component.translatable("screen.luxium.alpha_warning.line4"),
         Component.translatable("screen.luxium.alpha_warning.line5")
      );
   }

   private void acknowledge() {
      Config.CLIENT.alphaWarningShown.set(true);
      Config.CLIENT.alphaWarningShown.save();
      this.onClose();
   }

   private void openYoutube() {
      Minecraft.getInstance().setScreen(new ConfirmLinkScreen(confirmed -> {
         if (confirmed) {
            Util.getPlatform().openUri("https://www.youtube.com/@Vinlanx");
         }

         Minecraft.getInstance().setScreen(this);
      }, "https://www.youtube.com/@Vinlanx", false));
   }

   public void onClose() {
      Minecraft.getInstance().setScreen(null);
   }

   public boolean isPauseScreen() {
      return false;
   }
}
