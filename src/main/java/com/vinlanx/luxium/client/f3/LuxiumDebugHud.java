package com.vinlanx.luxium.client.f3;

import com.vinlanx.luxium.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(
   modid = "luxium",
   bus = Bus.MOD,
   value = {Dist.CLIENT}
)
public final class LuxiumDebugHud {
   private static final int MARGIN = 4;
   private static final int LINE_HEIGHT = 10;
   private static final String[] lines = new String[3];
   private static long lastUpdate;
   private static int lastMetrics = -1;
   private static int lineCount;
   private static int textWidth;

   private LuxiumDebugHud() {
   }

   @SubscribeEvent
   public static void register(RegisterGuiOverlaysEvent event) {
      event.registerAboveAll("debug_hud", LuxiumDebugHud::render);
   }

   private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
      Config.Client config = Config.CLIENT;
      Minecraft minecraft = gui.getMinecraft();
      if ((Boolean)config.debugHudEnabled.get() && !minecraft.options.hideGui) {
         long now = System.nanoTime();
         int metrics = (config.debugHudFps.get() ? 1 : 0) | (config.debugHudMemory.get() ? 2 : 0) | (config.debugHudAllocated.get() ? 4 : 0);
         if (lastUpdate == 0L || metrics != lastMetrics || now - lastUpdate >= (long)((Double)config.debugHudUpdateSeconds.get() * 1.0E9)) {
            lastUpdate = now;
            lastMetrics = metrics;
            lineCount = 0;
            textWidth = 0;
            if ((Boolean)config.debugHudFps.get()) {
               addLine(minecraft, "FPS: " + minecraft.getFps());
            }

            if ((Boolean)config.debugHudMemory.get() || (Boolean)config.debugHudAllocated.get()) {
               Runtime runtime = Runtime.getRuntime();
               long total = runtime.totalMemory();
               long max = runtime.maxMemory();
               if ((Boolean)config.debugHudMemory.get()) {
                  addLine(minecraft, "RAM: " + (total - runtime.freeMemory()) / 1048576L + "/" + max / 1048576L + " MB");
               }

               if ((Boolean)config.debugHudAllocated.get()) {
                  addLine(minecraft, "Allocated: " + total / 1048576L + "/" + max / 1048576L + " MB");
               }
            }
         }

         if (lineCount != 0) {
            float scale = ((Double)config.debugHudTextScale.get()).floatValue();
            Config.DebugHudPosition position = (Config.DebugHudPosition)config.debugHudPosition.get();
            boolean right = position == Config.DebugHudPosition.TOP_RIGHT || position == Config.DebugHudPosition.BOTTOM_RIGHT;
            boolean bottom = position == Config.DebugHudPosition.BOTTOM_LEFT || position == Config.DebugHudPosition.BOTTOM_RIGHT;
            float width = (float)textWidth * scale;
            float height = (float)(lineCount * 10) * scale;
            float x = right ? (float)(screenWidth - 4 - (Integer)config.debugHudOffsetX.get()) - width : (float)(4 + (Integer)config.debugHudOffsetX.get());
            float y = bottom ? (float)(screenHeight - 4 - (Integer)config.debugHudOffsetY.get()) - height : (float)(4 + (Integer)config.debugHudOffsetY.get());
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);

            for (int index = 0; index < lineCount; index++) {
               graphics.drawString(minecraft.font, lines[index], 0, index * 10, -1, true);
            }

            graphics.pose().popPose();
         }
      } else {
         lastUpdate = 0L;
      }
   }

   private static void addLine(Minecraft minecraft, String text) {
      lines[lineCount++] = text;
      textWidth = Math.max(textWidth, minecraft.font.width(text));
   }
}
