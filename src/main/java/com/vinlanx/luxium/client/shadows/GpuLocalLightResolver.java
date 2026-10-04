package com.vinlanx.luxium.client.shadows;

import com.vinlanx.luxium.rtx.Coloredlight;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class GpuLocalLightResolver {
   static final int META_CENTER_OFFSET_X = 10;
   static final int META_CENTER_OFFSET_Y = 11;
   static final int META_CENTER_OFFSET_Z = 14;
   static final int META_BLOCKED_FACE_MASK = 15;
   static final int META_BOX_EMITTER = 18;
   static final int META_VISIBILITY_SLOT = 19;
   private static final double FIXTURE_HORIZONTAL_OFFSET = 0.28;
   private static final double TORCH_VERTICAL_OFFSET = 0.2;
   private static final double END_ROD_OFFSET = 0.32;

   private GpuLocalLightResolver() {
   }

   public static void resolve(ClientLevel level, long sourceKey, int emission, float[] output, int base) {
      float red = 1.0F;
      float green = 0.76F;
      float blue = 0.48F;
      if (level != null) {
         BlockPos pos = BlockPos.of(sourceKey);
         BlockState state = level.getBlockState(pos);
         ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
         String path = id == null ? "" : id.getPath();
         if ((!path.contains("soul") || !path.contains("torch")) && !containsAny(path, "soul_lantern", "soul_fire", "soul_campfire")) {
            if ((!path.contains("redstone") || !path.contains("torch")) && !containsAny(path, "redstone_ore", "deepslate_redstone_ore")) {
               if (containsAny(path, "sea_lantern", "conduit", "prismarine")) {
                  red = 0.58F;
                  green = 0.95F;
                  blue = 1.0F;
               } else if (containsAny(path, "end_rod", "pearlescent_froglight")) {
                  red = 1.0F;
                  green = 0.72F;
                  blue = 0.95F;
               } else if (containsAny(path, "verdant_froglight")) {
                  red = 0.68F;
                  green = 1.0F;
                  blue = 0.66F;
               } else if (containsAny(path, "ochre_froglight", "shroomlight", "glowstone")) {
                  red = 1.0F;
                  green = 0.82F;
                  blue = 0.48F;
               } else if (containsAny(path, "amethyst", "budding_amethyst")) {
                  red = 0.78F;
                  green = 0.48F;
                  blue = 1.0F;
               } else if (containsAny(path, "sculk", "respawn_anchor")) {
                  red = 0.2F;
                  green = 0.78F;
                  blue = 0.92F;
               } else if (containsAny(
                  path, "lava", "fire", "campfire", "magma", "furnace", "blast_furnace", "smoker", "jack_o_lantern", "torch", "lantern", "candle"
               )) {
                  red = 1.0F;
                  green = 0.58F;
                  blue = 0.26F;
               } else if (containsAny(path, "glow_lichen", "glow_berries")) {
                  red = 0.72F;
                  green = 1.0F;
                  blue = 0.58F;
               } else if (containsAny(path, "beacon", "light")) {
                  red = 1.0F;
                  green = 0.98F;
                  blue = 0.9F;
               }
            } else {
               red = 1.0F;
               green = 0.2F;
               blue = 0.08F;
            }
         } else {
            red = 0.28F;
            green = 0.78F;
            blue = 1.0F;
         }
      }

      if (Coloredlight.isEnabled()) {
         red = Coloredlight.getRedMultiplier();
         green = Coloredlight.getGreenMultiplier();
         blue = Coloredlight.getBlueMultiplier();
      }

      float normalizedEmission = Mth.clamp((float)emission / 15.0F, 0.0F, 1.0F);
      float intensity = 1.18F * (float)Math.pow((double)normalizedEmission, 0.78);
      output[base] = red * intensity;
      output[base + 1] = green * intensity;
      output[base + 2] = blue * intensity;
      output[base + 3] = 1.0F;
   }

   static GpuLocalLightResolver.SourceGeometry resolveGeometry(ClientLevel level, long sourceKey) {
      int blockX = BlockPos.getX(sourceKey);
      int blockY = BlockPos.getY(sourceKey);
      int blockZ = BlockPos.getZ(sourceKey);
      double centerX = (double)blockX + 0.5;
      double centerY = (double)blockY + 0.5;
      double centerZ = (double)blockZ + 0.5;
      double sourceX = centerX;
      double sourceY = centerY;
      double sourceZ = centerZ;
      boolean boxEmitter = false;
      int blockedFaceMask = 0;
      if (level != null) {
         BlockPos pos = BlockPos.of(sourceKey);
         BlockState state = level.getBlockState(pos);
         ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
         String path = id == null ? "" : id.getPath();
         boxEmitter = state.isCollisionShapeFullBlock(level, pos);
         if (path.contains("torch")) {
            boxEmitter = false;
            Direction facing = fixtureFacing(state);
            if (facing == null) {
               sourceY = centerY + 0.2;
            } else if (facing.getAxis().isHorizontal()) {
               sourceX = centerX + (double)facing.getStepX() * 0.28;
               sourceY = centerY + 0.2;
               sourceZ = centerZ + (double)facing.getStepZ() * 0.28;
            } else {
               sourceX = centerX + (double)facing.getStepX() * 0.28;
               sourceY = centerY + (double)facing.getStepY() * 0.28;
               sourceZ = centerZ + (double)facing.getStepZ() * 0.28;
            }
         } else if (path.contains("end_rod")) {
            boxEmitter = false;
            Direction facing = fixtureFacing(state);
            if (facing != null) {
               sourceX = centerX + (double)facing.getStepX() * 0.32;
               sourceY = centerY + (double)facing.getStepY() * 0.32;
               sourceZ = centerZ + (double)facing.getStepZ() * 0.32;
            }
         } else if (!isFixtureLantern(path)) {
            if (path.contains("candle")) {
               boxEmitter = false;
               sourceY = centerY + 0.18;
            }
         } else {
            boxEmitter = false;
            boolean hanging = state.hasProperty(BlockStateProperties.HANGING) && (Boolean)state.getValue(BlockStateProperties.HANGING);
            sourceY = centerY + (hanging ? -0.12 : 0.12);
         }

         blockedFaceMask = resolveBlockedFaceMask(level, pos);
      }

      return new GpuLocalLightResolver.SourceGeometry(sourceX, sourceY, sourceZ, centerX, centerY, centerZ, boxEmitter, blockedFaceMask);
   }

   static void writeGeometryMetadata(GpuLocalLightResolver.SourceGeometry geometry, float[] output, int rectBase) {
      output[rectBase + 10] = (float)(geometry.centerX - geometry.sourceX);
      output[rectBase + 11] = (float)(geometry.centerY - geometry.sourceY);
      output[rectBase + 14] = (float)(geometry.centerZ - geometry.sourceZ);
      output[rectBase + 15] = (float)geometry.blockedFaceMask;
      output[rectBase + 18] = geometry.boxEmitter ? 1.0F : 0.0F;
   }

   private static Direction fixtureFacing(BlockState state) {
      if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
         return (Direction)state.getValue(BlockStateProperties.HORIZONTAL_FACING);
      } else {
         return state.hasProperty(BlockStateProperties.FACING) ? (Direction)state.getValue(BlockStateProperties.FACING) : null;
      }
   }

   private static int resolveBlockedFaceMask(ClientLevel level, BlockPos sourcePos) {
      int mask = 0;

      for (Direction direction : Direction.values()) {
         BlockPos neighbourPos = sourcePos.relative(direction);
         BlockState neighbour = level.getBlockState(neighbourPos);
         if (neighbour.canOcclude() && neighbour.isCollisionShapeFullBlock(level, neighbourPos)) {
            mask |= switch (direction) {
               case EAST -> 1;
               case WEST -> 2;
               case UP -> 4;
               case DOWN -> 8;
               case SOUTH -> 16;
               case NORTH -> 32;
               default -> throw new IncompatibleClassChangeError();
            };
         }
      }

      return mask;
   }

   private static boolean isFixtureLantern(String path) {
      return path.contains("lantern") && !path.contains("sea_lantern") && !path.contains("jack_o_lantern");
   }

   private static boolean containsAny(String value, String... needles) {
      for (String needle : needles) {
         if (value.contains(needle)) {
            return true;
         }
      }

      return false;
   }

   static final class SourceGeometry {
      final double sourceX;
      final double sourceY;
      final double sourceZ;
      final double centerX;
      final double centerY;
      final double centerZ;
      final boolean boxEmitter;
      final int blockedFaceMask;

      SourceGeometry(double sourceX, double sourceY, double sourceZ, double centerX, double centerY, double centerZ, boolean boxEmitter, int blockedFaceMask) {
         this.sourceX = sourceX;
         this.sourceY = sourceY;
         this.sourceZ = sourceZ;
         this.centerX = centerX;
         this.centerY = centerY;
         this.centerZ = centerZ;
         this.boxEmitter = boxEmitter;
         this.blockedFaceMask = blockedFaceMask;
      }
   }
}
