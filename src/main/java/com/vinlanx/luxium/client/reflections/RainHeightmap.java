package com.vinlanx.luxium.client.reflections;

import net.minecraft.resources.ResourceLocation;

public final class RainHeightmap {
   private static final int MIN_BLOCK_SUBDIVISION = 4;
   private static final int MIN_SUBDIVISION = 12;
   private static final int MAX_SUBDIVISION = 48;
   private static final float MIN_VISIBLE_OPACITY = 0.01F;
   private final ResourceLocation id;
   private final String displayName;
   private final int width;
   private final int height;
   private final float[] opacityByPixel;
   private final float offsetU;
   private final float offsetV;
   private final float scaledWidth;
   private final float scaledHeight;
   private final int recommendedSubdivision;
   private final float maxOpacity;

   public RainHeightmap(ResourceLocation id, int width, int height, float[] opacityByPixel) {
      if (id == null) {
         throw new IllegalArgumentException("id");
      } else if (width > 0 && height > 0) {
         if (opacityByPixel != null && opacityByPixel.length == width * height) {
            this.id = id;
            this.displayName = toDisplayName(id);
            this.width = width;
            this.height = height;
            this.opacityByPixel = (float[])opacityByPixel.clone();
            if (width >= height) {
               this.scaledWidth = 1.0F;
               this.scaledHeight = (float)height / (float)width;
               this.offsetU = 0.0F;
               this.offsetV = (1.0F - this.scaledHeight) * 0.5F;
            } else {
               this.scaledWidth = (float)width / (float)height;
               this.scaledHeight = 1.0F;
               this.offsetU = (1.0F - this.scaledWidth) * 0.5F;
               this.offsetV = 0.0F;
            }

            this.recommendedSubdivision = computeRecommendedSubdivision(width, height);
            float max = 0.0F;

            for (float opacity : this.opacityByPixel) {
               if (opacity > max) {
                  max = opacity;
               }
            }

            this.maxOpacity = max;
         } else {
            throw new IllegalArgumentException("Invalid rain heightmap pixel data");
         }
      } else {
         throw new IllegalArgumentException("Heightmap dimensions must be positive");
      }
   }

   public ResourceLocation id() {
      return this.id;
   }

   public String getDisplayName() {
      return this.displayName;
   }

   public int getRecommendedSubdivision() {
      return this.getRecommendedSubdivision(1);
   }

   public int getRecommendedSubdivision(int blockSpan) {
      int span = Math.max(1, blockSpan);
      int subdivision = (int)Math.ceil((double)this.recommendedSubdivision / (double)span);
      if (subdivision < 4) {
         subdivision = 4;
      }

      if (subdivision > 48) {
         subdivision = 48;
      }

      return subdivision;
   }

   public boolean hasVisiblePixels() {
      return this.maxOpacity > 0.01F;
   }

   public float sample(float faceU, float faceV) {
      if (!(faceU < this.offsetU) && !(faceU > this.offsetU + this.scaledWidth) && !(faceV < this.offsetV) && !(faceV > this.offsetV + this.scaledHeight)) {
         float textureU = this.scaledWidth <= 1.0E-6F ? 0.0F : (faceU - this.offsetU) / this.scaledWidth;
         float textureV = this.scaledHeight <= 1.0E-6F ? 0.0F : (faceV - this.offsetV) / this.scaledHeight;
         textureU = clamp01(textureU);
         textureV = clamp01(textureV);
         float pixelX = textureU * (float)(this.width - 1);
         float pixelY = textureV * (float)(this.height - 1);
         int x0 = (int)Math.floor((double)pixelX);
         int y0 = (int)Math.floor((double)pixelY);
         int x1 = Math.min(this.width - 1, x0 + 1);
         int y1 = Math.min(this.height - 1, y0 + 1);
         float tx = pixelX - (float)x0;
         float ty = pixelY - (float)y0;
         float top = lerp(this.opacityAt(x0, y0), this.opacityAt(x1, y0), tx);
         float bottom = lerp(this.opacityAt(x0, y1), this.opacityAt(x1, y1), tx);
         return lerp(top, bottom, ty);
      } else {
         return 0.0F;
      }
   }

   private float opacityAt(int x, int y) {
      return this.opacityByPixel[y * this.width + x];
   }

   private static String toDisplayName(ResourceLocation id) {
      String path = id.getPath();
      int slashIndex = path.lastIndexOf(47);
      int dotIndex = path.lastIndexOf(46);
      int start = slashIndex >= 0 ? slashIndex + 1 : 0;
      int end = dotIndex > start ? dotIndex : path.length();
      return path.substring(start, end);
   }

   private static int computeRecommendedSubdivision(int width, int height) {
      int longestSide = Math.max(width, height);
      int subdivision = longestSide / 4;
      if (subdivision < 12) {
         subdivision = 12;
      }

      if (subdivision > 48) {
         subdivision = 48;
      }

      return subdivision;
   }

   private static float lerp(float a, float b, float t) {
      return a + (b - a) * t;
   }

   private static float clamp01(float value) {
      if (value < 0.0F) {
         return 0.0F;
      } else {
         return value > 1.0F ? 1.0F : value;
      }
   }
}
