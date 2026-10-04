package com.vinlanx.luxium.Testing;

import com.vinlanx.luxium.client.ShaderManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.FORGE
)
public final class BlackHole {
   private static final SphereWarpEffect EFFECT = new SphereWarpEffect(ShaderManager::getBlackHoleShader, true);

   private BlackHole() {
   }

   public static void spawnAtPlayer(int radiusBlocks) {
      EFFECT.spawnAtPlayer(radiusBlocks);
   }

   public static void clear() {
      EFFECT.clear();
   }

   @SubscribeEvent
   public static void onClientTick(ClientTickEvent event) {
      if (event.phase == Phase.END) {
         EFFECT.onClientTick();
      }
   }

   @SubscribeEvent(
      priority = EventPriority.LOW
   )
   public static void onRenderLevel(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_PARTICLES) {
         EFFECT.onRenderLevel(event.getPoseStack().last().pose());
      }
   }
}
