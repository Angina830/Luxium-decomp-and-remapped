package com.vinlanx.luxium.client.guiscreen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class LuxiumMenuBackgroundRenderer {
   private static final ResourceLocation[] BACKGROUNDS = new ResourceLocation[]{
      image("1.png"), image("2.png"), image("3.png"), image("4.png"), image("5.png"), image("6.png")
   };
   private static final int TEXTURE_WIDTH = 1920;
   private static final int TEXTURE_HEIGHT = 1080;
   private static final float SLIDE_DURATION_TICKS = 100.0F;
   private static final float CROSSFADE_TICKS = 20.0F;
   private static final float SLIDE_LIFETIME_TICKS = 120.0F;
   private static final float START_ZOOM = 1.05F;
   private static final float END_ZOOM = 1.07F;
   private static final float PARALLAX_X = 26.0F;
   private static final float PARALLAX_Y = 18.0F;
   private int ticks;
   private float parallaxX;
   private float parallaxY;

   public void tick() {
      this.ticks++;
   }

   public void render(GuiGraphics graphics, int width, int height, int mouseX, int mouseY, float partialTick) {
      this.updateParallax(width, height, mouseX, mouseY);
      float time = (float)this.ticks + partialTick;
      int slideIndex = Mth.floor(time / 100.0F);
      float localTicks = time - (float)slideIndex * 100.0F;
      float fadeStart = 80.0F;
      ResourceLocation current = BACKGROUNDS[Math.floorMod(slideIndex, BACKGROUNDS.length)];
      ResourceLocation next = BACKGROUNDS[Math.floorMod(slideIndex + 1, BACKGROUNDS.length)];
      float currentAgeTicks = slideIndex == 0 ? localTicks : localTicks + 20.0F;
      float currentLifetime = slideIndex == 0 ? 100.0F : 120.0F;
      float currentProgress = Mth.clamp(currentAgeTicks / currentLifetime, 0.0F, 1.0F);
      graphics.fill(0, 0, width, height, -16381942);
      if (localTicks < fadeStart) {
         this.drawSlide(current, 1.0F, currentProgress, width, height);
      } else {
         float fadeTicks = localTicks - fadeStart;
         float blend = smootherStep(fadeTicks / 20.0F);
         float nextProgress = Mth.clamp(fadeTicks / 120.0F, 0.0F, 1.0F);
         this.drawSlide(current, 1.0F - blend, currentProgress, width, height);
         this.drawSlide(next, blend, nextProgress, width, height);
      }

      RenderSystem.disableBlend();
      graphics.fillGradient(0, 0, width, height, 1543833354, 2114587664);
      graphics.fill(0, 0, width, height, 687865856);
      graphics.fillGradient(0, height - 120, width, height, 0, 2113929216);
   }

   private void updateParallax(int width, int height, int mouseX, int mouseY) {
      float targetX = ((float)mouseX / (float)Math.max(1, width) - 0.5F) * 26.0F;
      float targetY = ((float)mouseY / (float)Math.max(1, height) - 0.5F) * 18.0F;
      this.parallaxX = Mth.lerp(0.08F, this.parallaxX, targetX);
      this.parallaxY = Mth.lerp(0.08F, this.parallaxY, targetY);
   }

   private void drawSlide(ResourceLocation texture, float alpha, float progress, int width, int height) {
      if (!(alpha <= 0.001F)) {
         float zoom = Mth.lerp(Mth.clamp(progress, 0.0F, 1.0F), 1.05F, 1.07F);
         float coverScale = Math.max((float)width / 1920.0F, (float)height / 1080.0F);
         float drawWidth = 1920.0F * coverScale * zoom;
         float drawHeight = 1080.0F * coverScale * zoom;
         float drawX = ((float)width - drawWidth) * 0.5F - this.parallaxX;
         float drawY = ((float)height - drawHeight) * 0.5F - this.parallaxY;
         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
         RenderSystem.setShader(GameRenderer::getPositionTexShader);
         RenderSystem.setShaderTexture(0, texture);
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
         BufferBuilder buffer = Tesselator.getInstance().getBuilder();
         buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
         buffer.vertex((double)drawX, (double)(drawY + drawHeight), 0.0).uv(0.0F, 1.0F).endVertex();
         buffer.vertex((double)(drawX + drawWidth), (double)(drawY + drawHeight), 0.0).uv(1.0F, 1.0F).endVertex();
         buffer.vertex((double)(drawX + drawWidth), (double)drawY, 0.0).uv(1.0F, 0.0F).endVertex();
         buffer.vertex((double)drawX, (double)drawY, 0.0).uv(0.0F, 0.0F).endVertex();
         BufferUploader.drawWithShader(buffer.end());
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      }
   }

   private static float smootherStep(float value) {
      float t = Mth.clamp(value, 0.0F, 1.0F);
      return t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F);
   }

   private static ResourceLocation image(String name) {
      return ResourceLocation.fromNamespaceAndPath("luxium", "ekrannn/" + name);
   }
}
