package com.vinlanx.luxium.client.f3;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent.DebugText;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;

public final class LuxiumF3Info {
   private static final List<String> LUXIUM_INFO = List.of(
      "",
      ChatFormatting.RED
         + "Luxium v"
         + ((ModContainer)ModList.get().getModContainerById("luxium").orElseThrow(() -> new IllegalStateException("Luxium mod metadata is unavailable")))
            .getModInfo()
            .getVersion()
   );

   private LuxiumF3Info() {
   }

   @SubscribeEvent(
      priority = EventPriority.HIGHEST
   )
   public static void onDebugText(DebugText event) {
      if (Minecraft.getInstance().options.renderDebug) {
         List<String> right = event.getRight();
         int targetIndex = 0;

         for (int blankLines = 0; targetIndex < right.size(); targetIndex++) {
            String line = right.get(targetIndex);
            if (line == null || line.isEmpty()) {
               blankLines++;
            }

            if (blankLines == 3) {
               break;
            }
         }

         right.addAll(targetIndex, LUXIUM_INFO);
      }
   }
}
