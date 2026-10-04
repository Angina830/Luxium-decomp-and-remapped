package com.vinlanx.luxium.Testing;

import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.Sky;
import com.vinlanx.luxium.client.posteffects.fog;
import com.vinlanx.luxium.client.shaders.LuxiumGpuShaderFeatures;
import com.vinlanx.luxium.client.shadows.GpuNeoShadows;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.client.tfrpluslsr.LsrSystem;
import com.vinlanx.luxium.mixin.GameRendererAccessor;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.FORGE
)
public final class LuxiumMasterKeyHandler {
   private LuxiumMasterKeyHandler() {
   }

   @SubscribeEvent
   public static void onClientTick(ClientTickEvent event) {
      if (event.phase == Phase.END) {
         while (LuxiumMasterKeyMapping.TOGGLE.consumeClick()) {
            Config.CLIENT.luxiumEnabled.set(!Config.isEnabled());
            Config.CLIENT.luxiumEnabled.save();
            refresh();
         }
      }
   }

   private static void refresh() {
      Minecraft minecraft = Minecraft.getInstance();
      LuxiumGpuShaderFeatures.refreshNow();
      fog.onConfigChanged();
      NeoGpuVanilla.onConfigChanged();
      GpuNeoShadows.onConfigChanged();
      if (!Config.isEnabled()) {
         GpuNeoShadows.releaseResources();
      }

      Sky.onConfigChanged();
      LsrSystem.onConfigChanged();
      ReflectionSystem.get().setEnabled(Config.isFeatureEnabled(Config.CLIENT.reflectionEnabled));
      NeoSkyCelestia.get().markAllDirty();
      if (minecraft.level != null) {
         minecraft.levelRenderer.allChanged();
      }

      ((GameRendererAccessor)minecraft.gameRenderer).luxium$reloadShaders(minecraft.getResourceManager());
   }
}
