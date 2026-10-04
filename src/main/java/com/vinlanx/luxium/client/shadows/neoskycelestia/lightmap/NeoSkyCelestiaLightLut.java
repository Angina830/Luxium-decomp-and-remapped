package com.vinlanx.luxium.client.shadows.neoskycelestia.lightmap;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestiaLighting;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.LightTexture;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

public final class NeoSkyCelestiaLightLut implements AutoCloseable {
   private static final int NORMAL_COUNT = 7;
   private static final int TILE_SIZE = 16;
   private static final int WIDTH = 32;
   private static final int HEIGHT = 112;
   private final FloatBuffer pixels = BufferUtils.createFloatBuffer(14336);
   private final int[] mainLightPixels = new int[256];
   private final int[] blockOnlyLightPixels = new int[256];
   private boolean cacheValid;
   private long lastLightmapRevision = Long.MIN_VALUE;
   private long snapshottedLightmapRevision = Long.MIN_VALUE;
   private boolean lastLightingEnabled;
   private boolean lastShadowsEnabled;
   private int lastDirectionX;
   private int lastDirectionY;
   private int lastDirectionZ;
   private int lastDirectColorX;
   private int lastDirectColorY;
   private int lastDirectColorZ;
   private int lastSkyAmbientX;
   private int lastSkyAmbientY;
   private int lastSkyAmbientZ;
   private int lastGroundAmbientX;
   private int lastGroundAmbientY;
   private int lastGroundAmbientZ;
   private int lastDirectStrength;
   private int lastAmbientStrength;
   private int lastSunShadowBlockVisibility;
   private int lastConfigHash;
   private static final int BUFFER_COUNT = 3;
   private final int[] textureIds = new int[3];
   private int currentTextureIndex = -1;

   public int update(LightTexture lightTexture, NeoSkyCelestiaLighting.State lighting, boolean shadowsEnabled, float sunShadowBlockVisibility) {
      RenderSystem.assertOnRenderThread();
      if (!(lightTexture instanceof NeoSkyLightTextureExtension extension)) {
         return -1;
      } else {
         this.ensureTextures();
         long lightmapRevision = extension.neosky$getLightmapRevision();
         int configHash = lightingConfigHash();
         if (this.matchesKey(lightmapRevision, lighting, shadowsEnabled, sunShadowBlockVisibility)) {
            return this.textureId();
         } else {
            this.snapshotLightmaps(extension, lightmapRevision);
            this.pixels.clear();

            for (int normalCode = 0; normalCode < 7; normalCode++) {
               float nx = normalX(normalCode);
               float ny = normalY(normalCode);
               float nz = normalZ(normalCode);
               float rawNdotL = nx * lighting.direction().x + ny * lighting.direction().y + nz * lighting.direction().z;

               for (int skyLevel = 0; skyLevel < 16; skyLevel++) {
                  int row = skyLevel << 4;

                  for (int blockLevel = 0; blockLevel < 16; blockLevel++) {
                     int index = row | blockLevel;
                     this.putEndpoint(
                        this.mainLightPixels[index],
                        this.blockOnlyLightPixels[index],
                        this.blockOnlyLightPixels[row],
                        this.mainLightPixels[row],
                        ny,
                        rawNdotL,
                        lighting,
                        shadowsEnabled,
                        sunShadowBlockVisibility,
                        0.0F
                     );
                  }

                  for (int blockLevel = 0; blockLevel < 16; blockLevel++) {
                     int index = row | blockLevel;
                     this.putEndpoint(
                        this.mainLightPixels[index],
                        this.blockOnlyLightPixels[index],
                        this.blockOnlyLightPixels[row],
                        this.mainLightPixels[row],
                        ny,
                        rawNdotL,
                        lighting,
                        shadowsEnabled,
                        sunShadowBlockVisibility,
                        1.0F
                     );
                  }
               }
            }

            this.pixels.flip();
            this.currentTextureIndex = (this.currentTextureIndex + 1) % 3;
            int uploadTexture = this.textureIds[this.currentTextureIndex];
            int oldActive = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(33990);
            GL15.glBindBuffer(35052, 0);
            GL11.glPixelStorei(3317, 1);
            GL11.glPixelStorei(3314, 0);
            GL11.glPixelStorei(3315, 0);
            GL11.glPixelStorei(3316, 0);
            GlStateManager._bindTexture(uploadTexture);
            GL11.glTexSubImage2D(3553, 0, 0, 0, 32, 112, 6408, 5126, this.pixels);
            GlStateManager._bindTexture(0);
            GL11.glPixelStorei(3317, 4);
            GL11.glPixelStorei(3314, 0);
            GL11.glPixelStorei(3315, 0);
            GL11.glPixelStorei(3316, 0);
            GL15.glBindBuffer(35052, 0);
            GlStateManager._activeTexture(oldActive);
            this.rememberKey(lightmapRevision, lighting, shadowsEnabled, sunShadowBlockVisibility, configHash);
            return this.textureId();
         }
      }
   }

   public int textureId() {
      return this.currentTextureIndex >= 0 ? this.textureIds[this.currentTextureIndex] : 0;
   }

   private void ensureTextures() {
      if (this.textureIds[0] == 0) {
         int oldActive = GlStateManager._getActiveTexture();
         GlStateManager._activeTexture(33990);

         for (int i = 0; i < 3; i++) {
            this.textureIds[i] = GlStateManager._genTexture();
            GlStateManager._bindTexture(this.textureIds[i]);
            GL11.glTexImage2D(3553, 0, 34842, 32, 112, 0, 6408, 5126, 0L);
            GL11.glTexParameteri(3553, 10241, 9729);
            GL11.glTexParameteri(3553, 10240, 9729);
            GL11.glTexParameteri(3553, 10242, 33071);
            GL11.glTexParameteri(3553, 10243, 33071);
         }

         GlStateManager._bindTexture(0);
         GlStateManager._activeTexture(oldActive);
      }
   }

   private void snapshotLightmaps(NeoSkyLightTextureExtension extension, long revision) {
      if (this.snapshottedLightmapRevision != revision) {
         for (int skyLevel = 0; skyLevel < 16; skyLevel++) {
            int row = skyLevel << 4;

            for (int blockLevel = 0; blockLevel < 16; blockLevel++) {
               int index = row | blockLevel;
               this.mainLightPixels[index] = extension.neosky$getMainLightPixel(blockLevel, skyLevel);
               this.blockOnlyLightPixels[index] = extension.neosky$getBlockOnlyLightPixel(blockLevel, skyLevel);
            }
         }

         this.snapshottedLightmapRevision = revision;
      }
   }

   private boolean matchesKey(long lightmapRevision, NeoSkyCelestiaLighting.State lighting, boolean shadowsEnabled, float sunShadowBlockVisibility) {
      return this.cacheValid
         && this.lastLightmapRevision == lightmapRevision
         && this.lastLightingEnabled == lighting.enabled()
         && this.lastShadowsEnabled == shadowsEnabled
         && this.lastDirectionX == bits(lighting.direction().x)
         && this.lastDirectionY == bits(lighting.direction().y)
         && this.lastDirectionZ == bits(lighting.direction().z)
         && this.lastDirectColorX == bits(lighting.directColor().x)
         && this.lastDirectColorY == bits(lighting.directColor().y)
         && this.lastDirectColorZ == bits(lighting.directColor().z)
         && this.lastSkyAmbientX == bits(lighting.shadowSkyAmbientColor().x)
         && this.lastSkyAmbientY == bits(lighting.shadowSkyAmbientColor().y)
         && this.lastSkyAmbientZ == bits(lighting.shadowSkyAmbientColor().z)
         && this.lastGroundAmbientX == bits(lighting.shadowGroundAmbientColor().x)
         && this.lastGroundAmbientY == bits(lighting.shadowGroundAmbientColor().y)
         && this.lastGroundAmbientZ == bits(lighting.shadowGroundAmbientColor().z)
         && this.lastDirectStrength == bits(lighting.directStrength())
         && this.lastAmbientStrength == bits(lighting.ambientStrength())
         && this.lastSunShadowBlockVisibility == bits(sunShadowBlockVisibility);
   }

   private void rememberKey(
      long lightmapRevision, NeoSkyCelestiaLighting.State lighting, boolean shadowsEnabled, float sunShadowBlockVisibility, int configHash
   ) {
      this.lastLightmapRevision = lightmapRevision;
      this.lastLightingEnabled = lighting.enabled();
      this.lastShadowsEnabled = shadowsEnabled;
      this.lastDirectionX = bits(lighting.direction().x);
      this.lastDirectionY = bits(lighting.direction().y);
      this.lastDirectionZ = bits(lighting.direction().z);
      this.lastDirectColorX = bits(lighting.directColor().x);
      this.lastDirectColorY = bits(lighting.directColor().y);
      this.lastDirectColorZ = bits(lighting.directColor().z);
      this.lastSkyAmbientX = bits(lighting.shadowSkyAmbientColor().x);
      this.lastSkyAmbientY = bits(lighting.shadowSkyAmbientColor().y);
      this.lastSkyAmbientZ = bits(lighting.shadowSkyAmbientColor().z);
      this.lastGroundAmbientX = bits(lighting.shadowGroundAmbientColor().x);
      this.lastGroundAmbientY = bits(lighting.shadowGroundAmbientColor().y);
      this.lastGroundAmbientZ = bits(lighting.shadowGroundAmbientColor().z);
      this.lastDirectStrength = bits(lighting.directStrength());
      this.lastAmbientStrength = bits(lighting.ambientStrength());
      this.lastSunShadowBlockVisibility = bits(sunShadowBlockVisibility);
      this.lastConfigHash = configHash;
      this.cacheValid = true;
   }

   private static int bits(float value) {
      return Float.floatToIntBits(value);
   }

   private static int lightingConfigHash() {
      int hash = 1;
      hash = mixHash(hash, Config.isFeatureEnabled(Config.CLIENT.skyLightEnabled) ? 1 : 0);
      hash = mixHash(hash, Config.isFeatureEnabled(Config.CLIENT.skyLightColorsEnabled) ? 1 : 0);
      hash = mixHash(hash, (Integer)Config.CLIENT.skyLightSunZenithColor.get());
      hash = mixHash(hash, (Integer)Config.CLIENT.skyLightSunsetColor.get());
      hash = mixHash(hash, (Integer)Config.CLIENT.skyLightMoonColor.get());
      hash = mixHash(hash, bits(((Double)Config.CLIENT.skyLightSunStrength.get()).floatValue()));
      hash = mixHash(hash, bits(((Double)Config.CLIENT.skyLightMoonStrength.get()).floatValue()));
      hash = mixHash(hash, bits(((Double)Config.CLIENT.skyLightSunZenithStrength.get()).floatValue()));
      hash = mixHash(hash, bits(((Double)Config.CLIENT.skyLightSunsetStrength.get()).floatValue()));
      hash = mixHash(hash, bits(((Double)Config.CLIENT.skyLightMoonBaseStrength.get()).floatValue()));
      hash = mixHash(hash, bits(((Double)Config.CLIENT.skyLightAmbientStrength.get()).floatValue()));
      hash = mixHash(hash, Config.isFeatureEnabled(Config.CLIENT.realisticShadowTemperature) ? 1 : 0);
      hash = mixHash(hash, bits(((Double)Config.CLIENT.shadowTemperatureStrength.get()).floatValue()));
      hash = mixHash(hash, (Integer)Config.CLIENT.shadowTemperatureBias.get());
      return mixHash(hash, bits(((Double)Config.CLIENT.vanillaBlockLightInSunShadows.get()).floatValue()));
   }

   private static int mixHash(int hash, int value) {
      return hash * 31 + value;
   }

   private void putEndpoint(
      int full,
      int block,
      int zero,
      int sky,
      float normalY,
      float rawNdotL,
      NeoSkyCelestiaLighting.State lighting,
      boolean shadowsEnabled,
      float sunShadowBlockVisibility,
      float visibility
   ) {
      float fullR = red(full);
      float fullG = green(full);
      float fullB = blue(full);
      if (!lighting.enabled()) {
         this.put(fullR, fullG, fullB, rawNdotL);
      } else {
         float blockR = red(block);
         float blockG = green(block);
         float blockB = blue(block);
         float zeroR = red(zero);
         float zeroG = green(zero);
         float zeroB = blue(zero);
         float skyR = red(sky);
         float skyG = green(sky);
         float skyB = blue(sky);
         float vanillaSkyR = Math.max(skyR - zeroR, 0.0F);
         float vanillaSkyG = Math.max(skyG - zeroG, 0.0F);
         float vanillaSkyB = Math.max(skyB - zeroB, 0.0F);
         float skyAccess = smoothstep(0.015F, 0.3F, Math.max(vanillaSkyR, Math.max(vanillaSkyG, vanillaSkyB)));
         float hemisphere = clamp(normalY * 0.5F + 0.5F);
         float ambientR = lerp(lighting.shadowGroundAmbientColor().x, lighting.shadowSkyAmbientColor().x, hemisphere) * lighting.ambientStrength() * skyAccess;
         float ambientG = lerp(lighting.shadowGroundAmbientColor().y, lighting.shadowSkyAmbientColor().y, hemisphere) * lighting.ambientStrength() * skyAccess;
         float ambientB = lerp(lighting.shadowGroundAmbientColor().z, lighting.shadowSkyAmbientColor().z, hemisphere) * lighting.ambientStrength() * skyAccess;
         float directAccess = shadowsEnabled ? clamp(visibility) * skyAccess : skyAccess;
         float geometricSunFacing = rawNdotL >= 0.005F ? 1.0F : 0.0F;
         float wrappedNdotL = clamp((rawNdotL + 0.16F) / 1.16F) * geometricSunFacing;
         float directScale = wrappedNdotL * lighting.directStrength() * directAccess;
         float celestialR = ambientR + lighting.directColor().x * directScale;
         float celestialG = ambientG + lighting.directColor().y * directScale;
         float celestialB = ambientB + lighting.directColor().z * directScale;
         float sunShadowAmount = shadowsEnabled ? 1.0F - clamp(visibility) * geometricSunFacing : 0.0F;
         float blockContributionR = Math.max(blockR - zeroR, 0.0F);
         float blockContributionG = Math.max(blockG - zeroG, 0.0F);
         float blockContributionB = Math.max(blockB - zeroB, 0.0F);
         float skyReferenceR = Math.max(skyR - zeroR, 0.0F);
         float skyReferenceG = Math.max(skyG - zeroG, 0.0F);
         float skyReferenceB = Math.max(skyB - zeroB, 0.0F);
         float blockIrradiance = luminance(blockContributionR, blockContributionG, blockContributionB);
         float adaptationIrradiance = Math.max(luminance(skyReferenceR, skyReferenceG, skyReferenceB), luminance(celestialR, celestialG, celestialB));
         float dominantVisibleBlockIrradiance = Math.max(blockIrradiance - adaptationIrradiance, 0.0F);
         float dominantVisibility = blockIrradiance > 1.0E-5F ? dominantVisibleBlockIrradiance / blockIrradiance : 0.0F;
         float daylightPresence = smoothstep(0.1F, 0.32F, luminance(skyReferenceR, skyReferenceG, skyReferenceB));
         float setting = clamp(sunShadowBlockVisibility);
         float requestedShadowVisibility = setting * daylightPresence * clamp(sunShadowAmount);
         float sourceCoreCompression = 1.0F / (1.0F + blockIrradiance * (1.0F - setting));
         float shadowVisibility = requestedShadowVisibility * sourceCoreCompression;
         float blockVisibility = Math.max(dominantVisibility, shadowVisibility);
         this.put(
            zeroR + celestialR + blockContributionR * blockVisibility,
            zeroG + celestialG + blockContributionG * blockVisibility,
            zeroB + celestialB + blockContributionB * blockVisibility,
            rawNdotL
         );
      }
   }

   private void put(float r, float g, float b, float a) {
      this.pixels.put(r).put(g).put(b).put(a);
   }

   private static float red(int rgba) {
      return (float)(rgba & 0xFF) / 255.0F;
   }

   private static float green(int rgba) {
      return (float)(rgba >>> 8 & 0xFF) / 255.0F;
   }

   private static float blue(int rgba) {
      return (float)(rgba >>> 16 & 0xFF) / 255.0F;
   }

   private static float luminance(float r, float g, float b) {
      return Math.max(r, 0.0F) * 0.2126F + Math.max(g, 0.0F) * 0.7152F + Math.max(b, 0.0F) * 0.0722F;
   }

   private static float smoothstep(float edge0, float edge1, float value) {
      float t = clamp((value - edge0) / Math.max(edge1 - edge0, 1.0E-5F));
      return t * t * (3.0F - 2.0F * t);
   }

   private static float clamp(float value) {
      return Math.max(0.0F, Math.min(1.0F, value));
   }

   private static float lerp(float a, float b, float t) {
      return a + (b - a) * t;
   }

   private static float normalX(int code) {
      if (code == 5) {
         return -1.0F;
      } else {
         return code == 6 ? 1.0F : 0.0F;
      }
   }

   private static float normalY(int code) {
      if (code == 1) {
         return -1.0F;
      } else {
         return code != 2 && code != 0 ? 0.0F : 1.0F;
      }
   }

   private static float normalZ(int code) {
      if (code == 3) {
         return -1.0F;
      } else {
         return code == 4 ? 1.0F : 0.0F;
      }
   }

   @Override
   public void close() {
      RenderSystem.assertOnRenderThread();

      for (int i = 0; i < 3; i++) {
         if (this.textureIds[i] != 0) {
            GlStateManager._deleteTexture(this.textureIds[i]);
            this.textureIds[i] = 0;
         }
      }

      this.currentTextureIndex = -1;
      this.cacheValid = false;
      this.lastLightmapRevision = Long.MIN_VALUE;
      this.snapshottedLightmapRevision = Long.MIN_VALUE;
      this.lastConfigHash = 0;
   }
}
