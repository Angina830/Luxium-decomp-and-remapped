package com.vinlanx.luxium.client.plantswave;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import net.minecraft.core.Direction;

public final class PlantWaveMaterialEncoder {
   private static final int NORMAL_SHIFT = 3;
   private static final int NORMAL_MASK = 56;
   private static final int WAVE_SHIFT = 6;
   private static final int WAVE_MASK = 192;
   private static final Material[][] SOLID = wrap(DefaultMaterials.SOLID);
   private static final Material[][] CUTOUT = wrap(DefaultMaterials.CUTOUT);
   private static final Material[][] CUTOUT_MIPPED = wrap(DefaultMaterials.CUTOUT_MIPPED);
   private static final Material[][] TRANSLUCENT = wrap(DefaultMaterials.TRANSLUCENT);

   private PlantWaveMaterialEncoder() {
   }

   public static Material encode(Material material, Direction direction, boolean axisAligned, PlantWaveProfile profile) {
      int normalCode = normalCode(direction, axisAligned);
      int waveCode = profile.materialCode();
      if (normalCode == 0 && waveCode == 0) {
         return material;
      } else if (material == DefaultMaterials.SOLID) {
         return SOLID[normalCode][waveCode];
      } else if (material == DefaultMaterials.CUTOUT) {
         return CUTOUT[normalCode][waveCode];
      } else if (material == DefaultMaterials.CUTOUT_MIPPED) {
         return CUTOUT_MIPPED[normalCode][waveCode];
      } else {
         return material == DefaultMaterials.TRANSLUCENT ? TRANSLUCENT[normalCode][waveCode] : material;
      }
   }

   private static int normalCode(Direction direction, boolean axisAligned) {
      if (axisAligned && direction != null) {
         return switch (direction) {
            case DOWN -> 1;
            case UP -> 2;
            case NORTH -> 3;
            case SOUTH -> 4;
            case WEST -> 5;
            case EAST -> 6;
            default -> throw new IncompatibleClassChangeError();
         };
      } else {
         return 0;
      }
   }

   private static Material[][] wrap(Material base) {
      Material[][] variants = new Material[7][4];

      for (int normalCode = 0; normalCode < 7; normalCode++) {
         for (int waveCode = 0; waveCode < 4; waveCode++) {
            variants[normalCode][waveCode] = (Material)(normalCode == 0 && waveCode == 0
               ? base
               : new PlantWaveMaterialEncoder.EncodedMaterial(base, normalCode, waveCode));
         }
      }

      return variants;
   }

   private static final class EncodedMaterial extends Material {
      private final int encodedBits;

      private EncodedMaterial(Material base, int normalCode, int waveCode) {
         super(base.pass, base.alphaCutoff, base.mipped);
         this.encodedBits = base.bits() & -249 | (normalCode & 7) << 3 | (waveCode & 3) << 6;
      }

      public int bits() {
         return this.encodedBits;
      }
   }
}
