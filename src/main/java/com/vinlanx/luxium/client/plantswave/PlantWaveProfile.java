package com.vinlanx.luxium.client.plantswave;

public enum PlantWaveProfile {
   NONE(0, PlantWaveProfile.Anchor.NONE),
   ROOTED(1, PlantWaveProfile.Anchor.ROOTED),
   FREE(1, PlantWaveProfile.Anchor.FREE),
   HANGING(1, PlantWaveProfile.Anchor.HANGING),
   LEAVES(2, PlantWaveProfile.Anchor.FREE),
   AQUATIC_ROOTED(3, PlantWaveProfile.Anchor.ROOTED),
   AQUATIC_FREE(3, PlantWaveProfile.Anchor.FREE);

   private final int materialCode;
   private final PlantWaveProfile.Anchor anchor;

   private PlantWaveProfile(int materialCode, PlantWaveProfile.Anchor anchor) {
      this.materialCode = materialCode;
      this.anchor = anchor;
   }

   public int materialCode() {
      return this.materialCode;
   }

   public int vertexWeight(float y) {
      if (this == AQUATIC_ROOTED) {
         return quantize(smooth(clamp(y * 1.08F)));
      } else if (this == AQUATIC_FREE) {
         return 15;
      } else {
         return switch (this.anchor) {
            case NONE -> 0;
            case FREE -> 15;
            case ROOTED -> quantize(clamp(y));
            case HANGING -> quantize(clamp(1.0F - y));
         };
      }
   }

   private static float clamp(float value) {
      return Math.max(0.0F, Math.min(1.0F, value));
   }

   private static float smooth(float value) {
      return value * value * (3.0F - 2.0F * value);
   }

   private static int quantize(float value) {
      return Math.max(0, Math.min(15, Math.round(value * 15.0F)));
   }

   private static enum Anchor {
      NONE,
      ROOTED,
      FREE,
      HANGING;
   }
}
