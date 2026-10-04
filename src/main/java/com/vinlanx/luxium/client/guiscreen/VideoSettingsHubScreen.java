package com.vinlanx.luxium.client.guiscreen;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.ConfigScreen.LuxiumConfigScreen;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.GridLayout.RowHelper;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class VideoSettingsHubScreen extends Screen {
   private static final Component TITLE = Component.translatable("options.videoTitle");
   private static final Component VIDEO_SETTINGS = Component.translatable("options.video");
   private static final Component LUXIUM = Component.literal("Luxium");
   private static final String YOUTUBE_URL = "https://www.youtube.com/@Vinlanx";
   private final Screen parent;
   private final Supplier<Screen> videoScreenFactory;
   private VideoSettingsHubScreen.YouTubeButton youTubeButton;

   public VideoSettingsHubScreen(Screen parent, Supplier<Screen> videoScreenFactory) {
      super(TITLE);
      this.parent = parent;
      this.videoScreenFactory = videoScreenFactory;
   }

   protected void init() {
      GridLayout layout = new GridLayout();
      layout.defaultCellSetting().paddingHorizontal(5).paddingBottom(4).alignHorizontallyCenter();
      RowHelper rowHelper = layout.createRowHelper(2);
      rowHelper.addChild(Button.builder(VIDEO_SETTINGS, button -> this.minecraft.setScreen(this.videoScreenFactory.get())).width(150).build());
      rowHelper.addChild(Button.builder(LUXIUM, button -> this.minecraft.setScreen(new LuxiumConfigScreen(this))).width(150).build());
      rowHelper.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build(), 2, rowHelper.newCellSettings().paddingTop(6));
      layout.arrangeElements();
      FrameLayout.alignInRectangle(layout, 0, this.height / 6, this.width, this.height, 0.5F, 0.0F);
      layout.visitWidgets(x$0 -> {
         AbstractWidget var10000 = (AbstractWidget)this.addRenderableWidget(x$0);
      });
      this.youTubeButton = (VideoSettingsHubScreen.YouTubeButton)this.addRenderableWidget(new VideoSettingsHubScreen.YouTubeButton(8, 8, this::openYouTube));
   }

   public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(guiGraphics);
      guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 16777215);
      super.render(guiGraphics, mouseX, mouseY, partialTick);
   }

   public void onClose() {
      this.minecraft.setScreen(this.parent);
   }

   private void openYouTube() {
      this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
         if (confirmed) {
            Util.getPlatform().openUri("https://www.youtube.com/@Vinlanx");
         }

         this.minecraft.setScreen(this);
      }, "https://www.youtube.com/@Vinlanx", false));
   }

   private static final class YouTubeButton extends AbstractWidget {
      private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("luxium", "textures/youtube.png");
      private static final int WIDTH = 64;
      private static final float TEXTURE_ASPECT = 0.9609375F;
      private final Runnable action;
      private float hover;

      YouTubeButton(int x, int y, Runnable action) {
         super(x, y, 64, Math.round(61.5F), Component.translatable("gui.luxium.youtube"));
         this.action = action;
      }

      public void onClick(double mouseX, double mouseY) {
         this.action.run();
      }

      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
         this.hover = animate(this.hover, this.isHovered() ? 1.0F : 0.0F, 0.18F);
         ShaderInstance shader = ShaderManager.getYouShader();
         if (shader == null) {
            graphics.blit(TEXTURE, this.getX(), this.getY(), 0.0F, 0.0F, this.getWidth(), this.getHeight(), this.getWidth(), this.getHeight());
         } else {
            float scale = 1.0F + this.hover * 0.08F;
            float centerX = (float)this.getX() + (float)this.getWidth() / 2.0F;
            float centerY = (float)this.getY() + (float)this.getHeight() / 2.0F;
            float glowTime = (float)(System.nanoTime() % 4000000000L) / 1.0E9F;
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, TEXTURE);
            shader.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
            if (shader.MODEL_VIEW_MATRIX != null) {
               shader.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
            }

            if (shader.PROJECTION_MATRIX != null) {
               shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
            }

            if (shader.getUniform("Center") != null) {
               shader.getUniform("Center").set(centerX, centerY);
            }

            if (shader.getUniform("ScaleAmount") != null) {
               shader.getUniform("ScaleAmount").set(scale);
            }

            if (shader.getUniform("HoverAmount") != null) {
               shader.getUniform("HoverAmount").set(this.hover);
            }

            if (shader.getUniform("GlowTime") != null) {
               shader.getUniform("GlowTime").set(glowTime);
            }

            GlStateManager._disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            shader.apply();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buffer.vertex((double)this.getX(), (double)(this.getY() + this.getHeight()), 0.0).uv(0.0F, 1.0F).endVertex();
            buffer.vertex((double)(this.getX() + this.getWidth()), (double)(this.getY() + this.getHeight()), 0.0).uv(1.0F, 1.0F).endVertex();
            buffer.vertex((double)(this.getX() + this.getWidth()), (double)this.getY(), 0.0).uv(1.0F, 0.0F).endVertex();
            buffer.vertex((double)this.getX(), (double)this.getY(), 0.0).uv(0.0F, 0.0F).endVertex();
            BufferUploader.draw(buffer.end());
            shader.clear();
            GlStateManager._enableDepthTest();
         }
      }

      protected void updateWidgetNarration(NarrationElementOutput output) {
         this.defaultButtonNarrationText(output);
      }

      private static float animate(float current, float target, float speed) {
         float next = current + (target - current) * speed;
         return Math.abs(next - target) < 0.001F ? target : next;
      }
   }
}
