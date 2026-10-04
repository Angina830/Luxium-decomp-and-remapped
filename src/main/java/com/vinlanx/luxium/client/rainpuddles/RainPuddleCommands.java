package com.vinlanx.luxium.client.rainpuddles;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.FORGE
)
public final class RainPuddleCommands {
   private RainPuddleCommands() {
   }

   @SubscribeEvent
   public static void register(RegisterClientCommandsEvent event) {
      event.getDispatcher()
         .register(
            (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("rainnew").executes(ctx -> place((CommandSourceStack)ctx.getSource(), 1)))
               .then(
                  Commands.argument("size", IntegerArgumentType.integer(1, 10))
                     .executes(ctx -> place((CommandSourceStack)ctx.getSource(), IntegerArgumentType.getInteger(ctx, "size")))
               )
         );
   }

   private static int place(CommandSourceStack source, int size) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null && mc.level != null) {
         BlockPos target = BlockPos.containing(mc.player.getX(), mc.player.getBoundingBox().minY - 0.05, mc.player.getZ());
         RainPuddleInstance puddle = RainPuddleManager.get().placeRandom(mc, target, size);
         if (puddle == null) {
            source.sendFailure(Component.literal("Could not place a puddle here or no rain depth maps were found"));
            return 0;
         } else {
            source.sendSuccess(
               () -> Component.literal(
                     "New rain puddle placed: map="
                        + puddle.depthMap().getPath()
                        + ", size="
                        + puddle.sizeBlocks()
                        + ", surface patches="
                        + puddle.patchCount()
                  ),
               false
            );
            return 1;
         }
      } else {
         source.sendFailure(Component.literal("New rain puddles require an active client world"));
         return 0;
      }
   }
}
