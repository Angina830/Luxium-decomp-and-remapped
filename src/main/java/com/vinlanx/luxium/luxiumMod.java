package com.vinlanx.luxium;

import com.vinlanx.luxium.client.ConfigScreen.LuxiumConfigScreen;
import com.vinlanx.luxium.client.f3.LuxiumF3Info;
import com.vinlanx.luxium.client.guiscreen.AlphaWarningScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod("luxium")
public final class luxiumMod {
   public static final String MOD_ID = "luxium";
   private static boolean warningScheduled = false;

   public luxiumMod() {
      Config.register();
      ModLoadingContext.get().registerExtensionPoint(ConfigScreenFactory.class, () -> new ConfigScreenFactory((mc, parent) -> new LuxiumConfigScreen(parent)));
      MinecraftForge.EVENT_BUS.register(luxiumMod.class);
      MinecraftForge.EVENT_BUS.register(LuxiumF3Info.class);
   }

   @SubscribeEvent
   public static void onClientTick(ClientTickEvent event) {
      if (event.phase == Phase.END) {
         if (!warningScheduled) {
            if (AlphaWarningScreen.shouldShow()) {
               Minecraft mc = Minecraft.getInstance();
               if (mc.screen instanceof TitleScreen) {
                  warningScheduled = true;
                  mc.execute(() -> mc.setScreen(new AlphaWarningScreen()));
               }
            }
         }
      }
   }
}
