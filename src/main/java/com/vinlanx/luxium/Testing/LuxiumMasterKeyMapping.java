package com.vinlanx.luxium.Testing;

import com.mojang.blaze3d.platform.InputConstants.Type;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.MOD
)
public final class LuxiumMasterKeyMapping {
   public static final KeyMapping TOGGLE = new KeyMapping("key.luxium.toggle_master", Type.KEYSYM, 75, "key.categories.luxium");

   private LuxiumMasterKeyMapping() {
   }

   @SubscribeEvent
   public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
      event.register(TOGGLE);
   }
}
