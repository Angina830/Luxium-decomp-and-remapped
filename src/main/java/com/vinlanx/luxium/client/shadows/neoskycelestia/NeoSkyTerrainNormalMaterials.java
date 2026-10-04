package com.vinlanx.luxium.client.shadows.neoskycelestia;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import net.minecraft.core.Direction;

public final class NeoSkyTerrainNormalMaterials {
   private static final int NORMAL_SHIFT = 3;
   private static final int NORMAL_MASK = 56;
   private static final Material[] SOLID = wrap(DefaultMaterials.SOLID);
   private static final Material[] CUTOUT = wrap(DefaultMaterials.CUTOUT);
   private static final Material[] CUTOUT_MIPPED = wrap(DefaultMaterials.CUTOUT_MIPPED);
   private static final Material[] TRANSLUCENT = wrap(DefaultMaterials.TRANSLUCENT);

   private NeoSkyTerrainNormalMaterials() {
   }

   public static Material encode(Material material, Direction direction, boolean axisAligned) {
      if (axisAligned && direction != null) {
         int code = switch (direction) {
            case DOWN -> 1;
            case UP -> 2;
            case NORTH -> 3;
            case SOUTH -> 4;
            case WEST -> 5;
            case EAST -> 6;
            default -> throw new IncompatibleClassChangeError();
         };
         if (material == DefaultMaterials.SOLID) {
            return SOLID[code];
         } else if (material == DefaultMaterials.CUTOUT) {
            return CUTOUT[code];
         } else if (material == DefaultMaterials.CUTOUT_MIPPED) {
            return CUTOUT_MIPPED[code];
         } else {
            return material == DefaultMaterials.TRANSLUCENT ? TRANSLUCENT[code] : material;
         }
      } else {
         return material;
      }
   }

   private static Material[] wrap(Material base) {
      Material[] variants = new Material[7];
      variants[0] = base;

      for (int code = 1; code <= 6; code++) {
         variants[code] = new NeoSkyTerrainNormalMaterials.EncodedMaterial(base, code);
      }

      return variants;
   }

   private static final class EncodedMaterial extends Material {
      private final int encodedBits;

      private EncodedMaterial(Material base, int normalCode) {
         super(base.pass, base.alphaCutoff, base.mipped);
         this.encodedBits = base.bits() & -57 | (normalCode & 7) << 3;
      }

      public int bits() {
         return this.encodedBits;
      }
   }
}
