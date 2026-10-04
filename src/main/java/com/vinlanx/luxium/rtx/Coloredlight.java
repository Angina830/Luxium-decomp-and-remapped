package com.vinlanx.luxium.rtx;

import com.vinlanx.luxium.mixin.LightTextureAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

public final class Coloredlight {
   private static volatile boolean worldRenderActive = false;
   private static volatile boolean enabled = false;
   private static volatile int red = 255;
   private static volatile int green = 255;
   private static volatile int blue = 255;
   private static volatile Coloredlight.AnimationMode mode = Coloredlight.AnimationMode.NONE;
   private static volatile int spectreSpeed = 50;
   private static volatile long animationStartTime = 0L;

   private Coloredlight() {
   }

   public static void setColor(int newRed, int newGreen, int newBlue) {
      mode = Coloredlight.AnimationMode.NONE;
      red = Mth.clamp(newRed, 0, 255);
      green = Mth.clamp(newGreen, 0, 255);
      blue = Mth.clamp(newBlue, 0, 255);
      enabled = true;
      requestLightTextureRefresh();
   }

   public static void enableSpectre(int speed) {
      mode = Coloredlight.AnimationMode.SPECTRE;
      spectreSpeed = Mth.clamp(speed, 1, 100);
      animationStartTime = System.currentTimeMillis();
      enabled = true;
      requestLightTextureRefresh();
   }

   public static void enablePolice() {
      mode = Coloredlight.AnimationMode.POLICE;
      animationStartTime = System.currentTimeMillis();
      enabled = true;
      requestLightTextureRefresh();
   }

   public static void enablePolice2() {
      mode = Coloredlight.AnimationMode.POLICE2;
      animationStartTime = System.currentTimeMillis();
      enabled = true;
      requestLightTextureRefresh();
   }

   public static void disableSpectre() {
      reset();
   }

   public static void reset() {
      mode = Coloredlight.AnimationMode.NONE;
      red = 255;
      green = 255;
      blue = 255;
      enabled = false;
      requestLightTextureRefresh();
   }

   public static boolean isEnabled() {
      return enabled;
   }

   public static void beginWorldRender() {
      worldRenderActive = true;
      if (mode == Coloredlight.AnimationMode.SPECTRE) {
         updateSpectreColors();
      } else if (mode == Coloredlight.AnimationMode.POLICE) {
         updatePoliceColors();
      } else if (mode == Coloredlight.AnimationMode.POLICE2) {
         updatePolice2Colors();
      }
   }

   public static void endWorldRender() {
      worldRenderActive = false;
   }

   public static boolean isWorldRenderActive() {
      return worldRenderActive;
   }

   public static int getRed() {
      return red;
   }

   public static int getGreen() {
      return green;
   }

   public static int getBlue() {
      return blue;
   }

   public static float getRedMultiplier() {
      return (float)red / 255.0F;
   }

   public static float getGreenMultiplier() {
      return (float)green / 255.0F;
   }

   public static float getBlueMultiplier() {
      return (float)blue / 255.0F;
   }

   private static void updateSpectreColors() {
      long elapsed = System.currentTimeMillis() - animationStartTime;
      double cycleDuration = 10000.0 / (double)spectreSpeed;
      double phase = (double)(elapsed % (long)cycleDuration) / cycleDuration;
      float hue = (float)phase;
      float saturation = 1.0F;
      float value = 1.0F;
      int rgb = hsvToRgb(hue, saturation, value);
      red = rgb >> 16 & 0xFF;
      green = rgb >> 8 & 0xFF;
      blue = rgb & 0xFF;
      requestLightTextureRefresh();
   }

   private static void updatePoliceColors() {
      long elapsed = System.currentTimeMillis() - animationStartTime;
      long cycleTime = elapsed % 400L;
      if (cycleTime < 200L) {
         red = 255;
         green = 0;
         blue = 0;
      } else {
         red = 0;
         green = 0;
         blue = 255;
      }

      requestLightTextureRefresh();
   }

   private static void updatePolice2Colors() {
      long elapsed = System.currentTimeMillis() - animationStartTime;
      long cycleTime = elapsed % 2480L;
      if (cycleTime < 240L) {
         long flashTime = cycleTime % 80L;
         if (flashTime < 40L) {
            red = 255;
            green = 0;
            blue = 0;
         } else {
            red = 1;
            green = 1;
            blue = 1;
         }
      } else if (cycleTime < 1240L) {
         red = 1;
         green = 1;
         blue = 1;
      } else if (cycleTime < 1480L) {
         long flashTime = (cycleTime - 1240L) % 80L;
         if (flashTime < 40L) {
            red = 0;
            green = 0;
            blue = 255;
         } else {
            red = 1;
            green = 1;
            blue = 1;
         }
      } else {
         red = 1;
         green = 1;
         blue = 1;
      }

      requestLightTextureRefresh();
   }

   private static int hsvToRgb(float hue, float saturation, float value) {
      int h = (int)(hue * 6.0F);
      float f = hue * 6.0F - (float)h;
      float p = value * (1.0F - saturation);
      float q = value * (1.0F - f * saturation);
      float t = value * (1.0F - (1.0F - f) * saturation);
      float r;
      float g;
      float b;
      switch (h % 6) {
         case 0:
            r = value;
            g = t;
            b = p;
            break;
         case 1:
            r = q;
            g = value;
            b = p;
            break;
         case 2:
            r = p;
            g = value;
            b = t;
            break;
         case 3:
            r = p;
            g = q;
            b = value;
            break;
         case 4:
            r = t;
            g = p;
            b = value;
            break;
         case 5:
            r = value;
            g = p;
            b = q;
            break;
         default:
            b = 0.0F;
            g = 0.0F;
            r = 0.0F;
      }

      int ri = (int)(r * 255.0F);
      int gi = (int)(g * 255.0F);
      int bi = (int)(b * 255.0F);
      return ri << 16 | gi << 8 | bi;
   }

   private static void requestLightTextureRefresh() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.gameRenderer != null) {
         ((LightTextureAccessor)mc.gameRenderer.lightTexture()).luxium$setUpdateLightTexture(true);
      }
   }

   private static enum AnimationMode {
      NONE,
      SPECTRE,
      POLICE,
      POLICE2;
   }
}
