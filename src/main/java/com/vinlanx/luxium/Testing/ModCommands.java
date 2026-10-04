package com.vinlanx.luxium.Testing;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.vinlanx.luxium.Testing.ExplosionTesting.ExplosionWorld;
import com.vinlanx.luxium.client.posteffects.skyvolumetrigodrays;
import com.vinlanx.luxium.client.reflections.RainPuddleManager;
import com.vinlanx.luxium.client.reflections.RainPuddleSurface;
import com.vinlanx.luxium.client.reflections.ReflectionMaterial;
import com.vinlanx.luxium.client.reflections.ReflectionMaterialRegistry;
import com.vinlanx.luxium.rtx.Coloredlight;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
public final class ModCommands {
   private ModCommands() {
   }

   @SubscribeEvent
   public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
      LiteralArgumentBuilder<CommandSourceStack> skylightTest = (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal("skylighttest")
         .executes(ModCommands::executeSkylightTest);
      LiteralArgumentBuilder<CommandSourceStack> flashlight = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)Commands.literal(
               "flashlight"
            )
            .then(Commands.literal("off").executes(ctx -> executeFlashlightOff(ctx))))
         .then(
            ((LiteralArgumentBuilder)Commands.literal("on").executes(ctx -> executeFlashlightOn(ctx, 15, 180)))
               .then(
                  ((RequiredArgumentBuilder)Commands.argument("power", IntegerArgumentType.integer(1, 15))
                        .executes(ctx -> executeFlashlightOn(ctx, IntegerArgumentType.getInteger(ctx, "power"), 180)))
                     .then(
                        Commands.argument("angle", IntegerArgumentType.integer(1, 180))
                           .executes(
                              ctx -> executeFlashlightOn(ctx, IntegerArgumentType.getInteger(ctx, "power"), IntegerArgumentType.getInteger(ctx, "angle"))
                           )
                     )
               )
         );
      LiteralArgumentBuilder<CommandSourceStack> grass = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)Commands.literal("grass")
            .then(Commands.literal("off").executes(ctx -> executeGrassOff(ctx))))
         .then(
            ((LiteralArgumentBuilder)Commands.literal("on").executes(ctx -> executeGrassOn(ctx, Grass.getRenderRadius())))
               .then(
                  Commands.argument("radius", IntegerArgumentType.integer(1, 100))
                     .executes(ctx -> executeGrassOn(ctx, IntegerArgumentType.getInteger(ctx, "radius")))
               )
         );
      LiteralArgumentBuilder<CommandSourceStack> rtxColor = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                           "rtxcolor"
                        )
                        .then(
                           Commands.literal("spectre")
                              .then(
                                 Commands.argument("speed", IntegerArgumentType.integer(1, 100))
                                    .executes(ctx -> executeRtxColorSpectre(ctx, IntegerArgumentType.getInteger(ctx, "speed")))
                              )
                        ))
                     .then(Commands.literal("police").executes(ctx -> executeRtxColorPolice(ctx))))
                  .then(Commands.literal("police2").executes(ctx -> executeRtxColorPolice2(ctx))))
               .then(Commands.literal("off").executes(ctx -> executeRtxColorOff(ctx))))
            .then(Commands.literal("reset").executes(ctx -> executeRtxColorReset(ctx))))
         .then(
            Commands.argument("red", IntegerArgumentType.integer(0, 255))
               .then(
                  Commands.argument("green", IntegerArgumentType.integer(0, 255))
                     .then(
                        Commands.argument("blue", IntegerArgumentType.integer(0, 255))
                           .executes(
                              ctx -> executeRtxColorSet(
                                    ctx,
                                    IntegerArgumentType.getInteger(ctx, "red"),
                                    IntegerArgumentType.getInteger(ctx, "green"),
                                    IntegerArgumentType.getInteger(ctx, "blue")
                                 )
                           )
                     )
               )
         );
      LiteralArgumentBuilder<CommandSourceStack> rain = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)Commands.literal("rain")
            .executes(ctx -> executeRain(ctx, 1)))
         .then(Commands.argument("size", IntegerArgumentType.integer(1, 10)).executes(ctx -> executeRain(ctx, IntegerArgumentType.getInteger(ctx, "size"))));
      LiteralArgumentBuilder<CommandSourceStack> blackHole = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                  "blackhole"
               )
               .executes(ctx -> executeBlackHole(ctx, 1)))
            .then(Commands.literal("delete").executes(ctx -> executeBlackHoleDelete(ctx))))
         .then(
            Commands.argument("size", IntegerArgumentType.integer(1, 10)).executes(ctx -> executeBlackHole(ctx, IntegerArgumentType.getInteger(ctx, "size")))
         );
      LiteralArgumentBuilder<CommandSourceStack> airDistortion = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                     "airdistortion"
                  )
                  .executes(ctx -> executeAirDistortion(ctx, 1)))
               .then(
                  Commands.literal("explosion")
                     .then(
                        Commands.argument("position", Vec3Argument.vec3())
                           .then(
                              Commands.argument("power", IntegerArgumentType.integer(1, 40))
                                 .then(
                                    Commands.argument("speed", IntegerArgumentType.integer(1, 300))
                                       .executes(
                                          ctx -> executeAirDistortionExplosion(
                                                ctx,
                                                Vec3Argument.getVec3(ctx, "position"),
                                                IntegerArgumentType.getInteger(ctx, "power"),
                                                IntegerArgumentType.getInteger(ctx, "speed")
                                             )
                                       )
                                 )
                           )
                     )
               ))
            .then(Commands.literal("delete").executes(ctx -> executeAirDistortionDelete(ctx))))
         .then(
            Commands.argument("radius", IntegerArgumentType.integer(1, 10))
               .executes(ctx -> executeAirDistortion(ctx, IntegerArgumentType.getInteger(ctx, "radius")))
         );
      LiteralArgumentBuilder<CommandSourceStack> explosionXul = (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal("explosionxul")
         .then(
            Commands.argument("position", Vec3Argument.vec3())
               .then(
                  Commands.argument("power", IntegerArgumentType.integer(1, 100))
                     .executes(ctx -> executeExplosionXul(ctx, Vec3Argument.getVec3(ctx, "position"), IntegerArgumentType.getInteger(ctx, "power")))
               )
         );
      event.getDispatcher().register(skylightTest);
      event.getDispatcher().register(flashlight);
      event.getDispatcher().register(grass);
      event.getDispatcher().register(rtxColor);
      event.getDispatcher().register(rain);
      event.getDispatcher().register(blackHole);
      event.getDispatcher().register(airDistortion);
      event.getDispatcher().register(explosionXul);
   }

   private static int executeSkylightTest(CommandContext<CommandSourceStack> ctx) {
      int skyLight = skyvolumetrigodrays.sampleCameraSkylight();
      if (skyLight < 0) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Skylight test requires an active client world"));
         return 0;
      } else {
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("Volumetric effective camera light: " + skyLight + "/15"), false);
         return 1;
      }
   }

   private static int executeFlashlightOn(CommandContext<CommandSourceStack> ctx, int power, int angle) {
      TestFlashLight.enable(power, angle);
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
                  "Flashlight enabled: power="
                     + TestFlashLight.getPower()
                     + ", angle="
                     + TestFlashLight.getConeAngle()
                     + ", rays="
                     + TestFlashLight.getCurrentRayCount()
               ),
            false
         );
      return 1;
   }

   private static int executeFlashlightOff(CommandContext<CommandSourceStack> ctx) {
      TestFlashLight.disable();
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("Flashlight disabled"), false);
      return 1;
   }

   private static int executeGrassOn(CommandContext<CommandSourceStack> ctx, int radius) {
      Grass.enable();
      Grass.setRenderRadius(radius);
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(() -> Component.literal("Grass rendering enabled (radius=" + Grass.getRenderRadius() + " blocks)"), false);
      return 1;
   }

   private static int executeGrassOff(CommandContext<CommandSourceStack> ctx) {
      Grass.disable();
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("Grass rendering disabled"), false);
      return 1;
   }

   private static int executeRtxColorSet(CommandContext<CommandSourceStack> ctx, int red, int green, int blue) {
      Coloredlight.setColor(red, green, blue);
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
                  "RTX block light color set to RGB(" + Coloredlight.getRed() + ", " + Coloredlight.getGreen() + ", " + Coloredlight.getBlue() + ")"
               ),
            false
         );
      return 1;
   }

   private static int executeRtxColorReset(CommandContext<CommandSourceStack> ctx) {
      Coloredlight.reset();
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("RTX block light color reset to vanilla"), false);
      return 1;
   }

   private static int executeRtxColorSpectre(CommandContext<CommandSourceStack> ctx, int speed) {
      Coloredlight.enableSpectre(speed);
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("§aRTX Color Spectre enabled with speed: §e" + speed), false);
      return 1;
   }

   private static int executeRtxColorOff(CommandContext<CommandSourceStack> ctx) {
      Coloredlight.disableSpectre();
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("§cRTX Color disabled"), false);
      return 1;
   }

   private static int executeRtxColorPolice(CommandContext<CommandSourceStack> ctx) {
      Coloredlight.enablePolice();
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("§9§lPOLICE MODE ACTIVATED §c§l[WEE WOO WEE WOO]"), false);
      return 1;
   }

   private static int executeRtxColorPolice2(CommandContext<CommandSourceStack> ctx) {
      Coloredlight.enablePolice2();
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("§9§lPOLICE MODE 2 ACTIVATED §c§l[FLASH FLASH FLASH]"), false);
      return 1;
   }

   private static int executeRain(CommandContext<CommandSourceStack> ctx, int size) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null && mc.level != null) {
         BlockPos targetPos = BlockPos.containing(mc.player.getX(), mc.player.getBoundingBox().minY - 0.05, mc.player.getZ());
         BlockState state = mc.level.getBlockState(targetPos);
         ReflectionMaterial material = ReflectionMaterialRegistry.find(state);
         if (material != null && material.shouldReflectFace(mc, targetPos, state, Direction.UP)) {
            RainPuddleSurface surface = RainPuddleManager.get().placeRandomPuddle(mc, targetPos, size);
            if (surface == null) {
               ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("No rain heightmaps found or puddle footprint has no reflective neighbors"));
               return 0;
            } else {
               ((CommandSourceStack)ctx.getSource())
                  .sendSuccess(
                     () -> Component.literal(
                           "Rain puddle placed: map="
                              + surface.getHeightmap().getDisplayName()
                              + ", size="
                              + surface.getSpanBlocks()
                              + ", reflective blocks="
                              + surface.getCoveredBlockCount()
                        ),
                     false
                  );
               return 1;
            }
         } else {
            ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Block under player is not a supported reflective top face"));
            return 0;
         }
      } else {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Rain puddles require an active client world"));
         return 0;
      }
   }

   private static int executeBlackHole(CommandContext<CommandSourceStack> ctx, int radius) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Player is not available"));
         return 0;
      } else {
         BlackHole.spawnAtPlayer(radius);
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("Black hole spawned at your position (radius=" + radius + ")"), false);
         return 1;
      }
   }

   private static int executeBlackHoleDelete(CommandContext<CommandSourceStack> ctx) {
      BlackHole.clear();
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("All black holes removed"), false);
      return 1;
   }

   private static int executeAirDistortion(CommandContext<CommandSourceStack> ctx, int radius) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Player is not available"));
         return 0;
      } else {
         AirDistortion.spawnAtPlayer(radius);
         ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("Air distortion spawned at your position (radius=" + radius + ")"), false);
         return 1;
      }
   }

   private static int executeAirDistortionDelete(CommandContext<CommandSourceStack> ctx) {
      AirDistortion.clear();
      ShockWaveTest.clear();
      ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("All air distortions removed"), false);
      return 1;
   }

   private static int executeAirDistortionExplosion(CommandContext<CommandSourceStack> ctx, Vec3 position, int power, int speed) {
      ShockWaveTest.spawnExplosion(position, power, speed);
      ((CommandSourceStack)ctx.getSource())
         .sendSuccess(
            () -> Component.literal(
                  "Air distortion shockwave spawned at "
                     + formatCoordinate(position.x)
                     + " "
                     + formatCoordinate(position.y)
                     + " "
                     + formatCoordinate(position.z)
                     + " (power="
                     + power
                     + ", speed="
                     + speed
                     + " blocks/s)"
               ),
            false
         );
      return 1;
   }

   private static int executeExplosionXul(CommandContext<CommandSourceStack> ctx, Vec3 position, int power) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null) {
         ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal("Explosion simulation requires an active client world"));
         return 0;
      } else {
         ExplosionWorld.get().trigger(position, power);
         ((CommandSourceStack)ctx.getSource())
            .sendSuccess(
               () -> Component.literal(
                     "Explosion XUL spawned at "
                        + formatCoordinate(position.x)
                        + " "
                        + formatCoordinate(position.y)
                        + " "
                        + formatCoordinate(position.z)
                        + " (power="
                        + power
                        + ")"
                  ),
               false
            );
         return 1;
      }
   }

   private static String formatCoordinate(double value) {
      return String.format(Locale.ROOT, "%.2f", value);
   }
}
