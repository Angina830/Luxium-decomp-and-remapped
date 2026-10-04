package com.vinlanx.luxium.Testing.ExplosionTesting;

import net.minecraft.util.Mth;

public record ExplosionProfile(
   int power,
   double tntKg,
   float scale,
   float blastStrength,
   float coreRadius,
   float durationScale,
   float particleMultiplier,
   float particleSizeScale,
   float heatMultiplier,
   float dustMultiplier,
   float afterburnMultiplier,
   float flashMultiplier
) {
   public static ExplosionProfile fromPower(int requestedPower) {
      int power = Mth.clamp(requestedPower, 1, 100);
      double t = ((double)power - 1.0) / 99.0;
      double tntKg = 0.18 * Math.pow(2222.222222222222, t);
      float scale = (float)Math.cbrt(tntKg / 5.0);
      float particleMultiplier = clamp((float)Math.pow((double)scale, 1.35), 0.22F, 4.3F);
      return new ExplosionProfile(
         power,
         tntKg,
         scale,
         7.2F * (float)Math.pow((double)scale, 0.42),
         clamp(1.35F * (float)Math.pow((double)scale, 0.88), 0.48F, 4.9F),
         clamp((float)Math.pow((double)scale, 0.62), 0.5F, 2.55F),
         particleMultiplier,
         clamp((float)Math.pow((double)scale, 0.38), 0.68F, 1.75F),
         clamp(0.94F + 0.09F * log2(Math.max(scale, 0.25F)), 0.78F, 1.22F),
         clamp(0.72F + 0.24F * scale, 0.62F, 1.95F),
         clamp((float)Math.pow((double)particleMultiplier, 0.68), 0.38F, 2.75F),
         clamp((float)Math.pow((double)scale, 0.75), 0.34F, 3.0F)
      );
   }

   private static float log2(float value) {
      return (float)(Math.log((double)value) / Math.log(2.0));
   }

   private static float clamp(float value, float min, float max) {
      return Math.max(min, Math.min(max, value));
   }
}
