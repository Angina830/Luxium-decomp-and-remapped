package com.vinlanx.luxium.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.posteffects.skygodrays;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import com.vinlanx.luxium.client.sunmoonapi.CelestialPath;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class Sky {
   private static final ResourceLocation SUN_LOCATION = new ResourceLocation("textures/environment/sun.png");
   private static final ResourceLocation MOON_LOCATION = new ResourceLocation("textures/environment/moon_phases.png");
   private static final int CACHE_FACE_SIZE = 1024;
   private static final int FACE_COUNT = 6;
   private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(4);
   private static final IntBuffer SCISSOR_BOX = BufferUtils.createIntBuffer(4);
   private static final Matrix4f PROJ_INVERSE = new Matrix4f();
   private static final Matrix4f VIEW_INVERSE = new Matrix4f();
   private static int cacheTextureId;
   private static int stagingCacheTextureId;
   private static int cacheFramebufferId;
   private static long nextCacheUpdateNanos;
   private static ClientLevel cachedLevel;
   private static boolean bakeInProgress;
   private static int bakeWorkIndex;
   private static boolean bakeSmooth;
   private static int bakeTileSize = 512;
   private static int bakeWorkPerFrame = 2;
   private static float bakeSunX;
   private static float bakeSunY = 1.0F;
   private static float bakeSunZ;
   private static ClientLevel bakeLevel;
   private static int cachedSamplerProgramId = -1;
   private static int cachedSamplerLocation = -1;

   private Sky() {
   }

   public static int getReflectionCacheTextureId() {
      Minecraft mc = Minecraft.getInstance();
      return cacheTextureId != 0 && cachedLevel == mc.level ? cacheTextureId : 0;
   }

   public static void onConfigChanged() {
      bakeInProgress = false;
      bakeWorkIndex = 0;
      bakeLevel = null;
      nextCacheUpdateNanos = 0L;
   }

   public static void render(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level != null) {
         ShaderInstance displayShader = ShaderManager.getSkyShader();
         ShaderInstance bakeShader = ShaderManager.getSkyBakeShader();
         if (displayShader != null && bakeShader != null) {
            CelestialPath.State celestialPath = NeoSkyFrameCache.celestial(mc.level, partialTick);
            updateCache(bakeShader, celestialPath.sunDirection(), mc.level);
            if (cacheTextureId != 0 && cachedLevel == mc.level) {
               Matrix4f projInverse = NeoSkyFrameCache.copyInverseProjection(projectionMatrix, PROJ_INVERSE);
               Matrix4f viewInverse = NeoSkyFrameCache.copyInverseView(poseStack.last().pose(), VIEW_INVERSE);
               if (displayShader.getUniform("ProjInverseMat") != null) {
                  displayShader.getUniform("ProjInverseMat").set(projInverse);
               }

               if (displayShader.getUniform("ModelViewInverseMat") != null) {
                  displayShader.getUniform("ModelViewInverseMat").set(viewInverse);
               }

               RenderSystem.enableBlend();
               RenderSystem.defaultBlendFunc();
               RenderSystem.depthMask(false);
               GlStateManager._disableDepthTest();
               RenderSystem.disableCull();
               displayShader.apply();

               try {
                  bindCacheTexture(displayShader);
                  drawFullscreenQuad();
               } finally {
                  displayShader.clear();
               }

               renderVanillaCelestials(mc, poseStack, projectionMatrix, partialTick);
               GlStateManager._enableDepthTest();
               RenderSystem.depthMask(true);
               RenderSystem.enableCull();
               RenderSystem.disableBlend();
            }
         }
      }
   }

   private static void updateCache(ShaderInstance bakeShader, Vec3 sunDirection, ClientLevel level) {
      long now = System.nanoTime();
      if (bakeInProgress && bakeLevel != level) {
         bakeInProgress = false;
         bakeWorkIndex = 0;
         bakeLevel = null;
         nextCacheUpdateNanos = 0L;
      }

      boolean displayCacheValidForLevel = cacheTextureId != 0 && cachedLevel == level;
      if (!bakeInProgress) {
         if (displayCacheValidForLevel && now < nextCacheUpdateNanos) {
            return;
         }

         beginBake(level, sunDirection);
      }

      renderBakeWork(bakeShader);
   }

   private static void beginBake(ClientLevel level, Vec3 sunDirection) {
      ensureCacheStorage();
      bakeInProgress = true;
      bakeWorkIndex = 0;
      bakeLevel = level;
      bakeSunX = (float)sunDirection.x;
      bakeSunY = (float)sunDirection.y;
      bakeSunZ = (float)sunDirection.z;
      bakeSmooth = (Boolean)Config.CLIENT.skyBakeSmoothEnabled.get();
      bakeWorkPerFrame = Mth.clamp((Integer)Config.CLIENT.skyBakeWorkPerFrame.get(), 1, 6);
      bakeTileSize = ((Config.SkyBakeTileSize)Config.CLIENT.skyBakeTileSize.get()).pixels();
   }

   private static void renderBakeWork(ShaderInstance bakeShader) {
      int totalWork = getTotalBakeWork();
      if (bakeWorkIndex >= totalWork) {
         finishBake();
      } else {
         int previousFramebuffer = GL11.glGetInteger(36006);
         int previousActiveTexture = GlStateManager._getActiveTexture();
         int previousCubeTexture = GL11.glGetInteger(34068);
         boolean previousScissorEnabled = GL11.glIsEnabled(3089);
         boolean previousDepthTestEnabled = GL11.glIsEnabled(2929);
         boolean previousDepthMask = GL11.glGetBoolean(2930);
         boolean previousBlendEnabled = GL11.glIsEnabled(3042);
         boolean previousCullEnabled = GL11.glIsEnabled(2884);
         VIEWPORT.clear();
         GL11.glGetIntegerv(2978, VIEWPORT);
         int previousViewportX = VIEWPORT.get(0);
         int previousViewportY = VIEWPORT.get(1);
         int previousViewportWidth = VIEWPORT.get(2);
         int previousViewportHeight = VIEWPORT.get(3);
         SCISSOR_BOX.clear();
         GL11.glGetIntegerv(3088, SCISSOR_BOX);
         int previousScissorX = SCISSOR_BOX.get(0);
         int previousScissorY = SCISSOR_BOX.get(1);
         int previousScissorWidth = SCISSOR_BOX.get(2);
         int previousScissorHeight = SCISSOR_BOX.get(3);

         try {
            GlStateManager._glBindFramebuffer(36160, cacheFramebufferId);
            GlStateManager._viewport(0, 0, 1024, 1024);
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            if (bakeShader.getUniform("SunDir") != null) {
               bakeShader.getUniform("SunDir").set(bakeSunX, bakeSunY, bakeSunZ);
            }

            if (bakeShader.getUniform("CachePass") != null) {
               bakeShader.getUniform("CachePass").set(1);
            }

            if (bakeShader.getUniform("CacheFaceSize") != null) {
               bakeShader.getUniform("CacheFaceSize").set(1024);
            }

            int workThisFrame = Math.min(bakeWorkPerFrame, totalWork - bakeWorkIndex);

            for (int i = 0; i < workThisFrame; bakeWorkIndex++) {
               renderBakeUnit(bakeShader, bakeWorkIndex);
               i++;
            }
         } finally {
            if (previousScissorEnabled) {
               GL11.glEnable(3089);
               GL11.glScissor(previousScissorX, previousScissorY, previousScissorWidth, previousScissorHeight);
            } else {
               GL11.glDisable(3089);
            }

            if (previousDepthTestEnabled) {
               GlStateManager._enableDepthTest();
            } else {
               GlStateManager._disableDepthTest();
            }

            GlStateManager._depthMask(previousDepthMask);
            if (previousBlendEnabled) {
               RenderSystem.enableBlend();
            } else {
               RenderSystem.disableBlend();
            }

            if (previousCullEnabled) {
               RenderSystem.enableCull();
            } else {
               RenderSystem.disableCull();
            }

            GL11.glBindTexture(34067, previousCubeTexture);
            GlStateManager._glBindFramebuffer(36160, previousFramebuffer);
            GlStateManager._viewport(previousViewportX, previousViewportY, previousViewportWidth, previousViewportHeight);
            GlStateManager._activeTexture(previousActiveTexture);
         }

         if (bakeWorkIndex >= totalWork) {
            finishBake();
         }
      }
   }

   private static void renderBakeUnit(ShaderInstance bakeShader, int workIndex) {
      int face;
      if (bakeSmooth) {
         int tilesPerAxis = 1024 / bakeTileSize;
         int tilesPerFace = tilesPerAxis * tilesPerAxis;
         face = workIndex / tilesPerFace;
         int tileIndex = workIndex % tilesPerFace;
         int tileX = tileIndex % tilesPerAxis;
         int tileY = tileIndex / tilesPerAxis;
         GL11.glEnable(3089);
         GL11.glScissor(tileX * bakeTileSize, tileY * bakeTileSize, bakeTileSize, bakeTileSize);
      } else {
         face = workIndex;
         GL11.glDisable(3089);
      }

      GL30.glFramebufferTexture2D(36160, 36064, 34069 + face, stagingCacheTextureId, 0);
      if (bakeShader.getUniform("CubeFace") != null) {
         bakeShader.getUniform("CubeFace").set(face);
      }

      bakeShader.apply();

      try {
         drawFullscreenQuad();
      } finally {
         bakeShader.clear();
      }
   }

   private static int getTotalBakeWork() {
      if (!bakeSmooth) {
         return 6;
      } else {
         int tilesPerAxis = 1024 / bakeTileSize;
         return 6 * tilesPerAxis * tilesPerAxis;
      }
   }

   private static void finishBake() {
      int completedTexture = stagingCacheTextureId;
      stagingCacheTextureId = cacheTextureId;
      cacheTextureId = completedTexture;
      cachedLevel = bakeLevel;
      bakeLevel = null;
      bakeInProgress = false;
      bakeWorkIndex = 0;
      nextCacheUpdateNanos = System.nanoTime() + getCacheUpdateIntervalNanos();
   }

   private static long getCacheUpdateIntervalNanos() {
      double seconds = Mth.clamp((Double)Config.CLIENT.skyBakeUpdateIntervalSeconds.get(), 0.1, 2.0);
      return Math.max(1L, (long)(seconds * 1.0E9));
   }

   private static void ensureCacheStorage() {
      if (cacheTextureId == 0 || stagingCacheTextureId == 0) {
         int previousFramebuffer = GL11.glGetInteger(36006);
         int previousActiveTexture = GlStateManager._getActiveTexture();
         int previousCubeTexture = GL11.glGetInteger(34068);
         cacheTextureId = GlStateManager._genTexture();
         stagingCacheTextureId = GlStateManager._genTexture();
         cacheFramebufferId = GlStateManager.glGenFramebuffers();
         configureCubemapTexture(cacheTextureId);
         configureCubemapTexture(stagingCacheTextureId);
         GlStateManager._glBindFramebuffer(36160, cacheFramebufferId);
         GL30.glFramebufferTexture2D(36160, 36064, 34069, stagingCacheTextureId, 0);
         if (GL30.glCheckFramebufferStatus(36160) != 36053) {
            throw new IllegalStateException("Luxium sky cubemap framebuffer is incomplete");
         } else {
            GL11.glBindTexture(34067, previousCubeTexture);
            GlStateManager._glBindFramebuffer(36160, previousFramebuffer);
            GlStateManager._activeTexture(previousActiveTexture);
         }
      }
   }

   private static void configureCubemapTexture(int textureId) {
      GL11.glBindTexture(34067, textureId);
      GL11.glTexParameteri(34067, 10241, 9729);
      GL11.glTexParameteri(34067, 10240, 9729);
      GL11.glTexParameteri(34067, 10242, 33071);
      GL11.glTexParameteri(34067, 10243, 33071);
      GL11.glTexParameteri(34067, 32882, 33071);

      for (int face = 0; face < 6; face++) {
         GL11.glTexImage2D(34069 + face, 0, 34842, 1024, 1024, 0, 6408, 5131, 0L);
      }
   }

   private static void bindCacheTexture(ShaderInstance shader) {
      int programId = shader.getId();
      if (cachedSamplerProgramId != programId) {
         cachedSamplerProgramId = programId;
         cachedSamplerLocation = GL20.glGetUniformLocation(programId, "SkyCache");
      }

      if (cachedSamplerLocation >= 0) {
         GlStateManager._activeTexture(33984);
         GL20.glUniform1i(cachedSamplerLocation, 0);
         GL11.glBindTexture(34067, cacheTextureId);
      }
   }

   private static void drawFullscreenQuad() {
      Tesselator tesselator = Tesselator.getInstance();
      BufferBuilder bufferBuilder = tesselator.getBuilder();
      bufferBuilder.begin(Mode.QUADS, DefaultVertexFormat.POSITION);
      bufferBuilder.vertex(-1.0, -1.0, 0.0).endVertex();
      bufferBuilder.vertex(1.0, -1.0, 0.0).endVertex();
      bufferBuilder.vertex(1.0, 1.0, 0.0).endVertex();
      bufferBuilder.vertex(-1.0, 1.0, 0.0).endVertex();
      BufferUploader.draw(bufferBuilder.end());
   }

   private static void renderVanillaCelestials(Minecraft mc, PoseStack poseStack, Matrix4f projectionMatrix, float partialTick) {
      skygodrays.beginSkyCapture();
      Tesselator tesselator = Tesselator.getInstance();
      BufferBuilder bufferBuilder = tesselator.getBuilder();
      RenderSystem.blendFuncSeparate(SourceFactor.SRC_ALPHA, DestFactor.ONE, SourceFactor.ONE, DestFactor.ZERO);
      poseStack.pushPose();
      float rainFade = 1.0F - mc.level.getRainLevel(partialTick);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, rainFade);
      CelestialPath.applyOrbit(poseStack, mc.level.getSunAngle(partialTick));
      Matrix4f celestialMatrix = poseStack.last().pose();
      RenderSystem.setShader(GameRenderer::getPositionTexShader);
      skygodrays.captureCelestial(new Matrix4f(celestialMatrix), new Matrix4f(projectionMatrix), false, partialTick);
      RenderSystem.setShaderTexture(0, SUN_LOCATION);
      float sunSize = 30.0F * Mth.clamp(((Double)Config.CLIENT.skySunSize.get()).floatValue(), 0.1F, 3.0F);
      bufferBuilder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
      bufferBuilder.vertex(celestialMatrix, -sunSize, 100.0F, -sunSize).uv(0.0F, 0.0F).endVertex();
      bufferBuilder.vertex(celestialMatrix, sunSize, 100.0F, -sunSize).uv(1.0F, 0.0F).endVertex();
      bufferBuilder.vertex(celestialMatrix, sunSize, 100.0F, sunSize).uv(1.0F, 1.0F).endVertex();
      bufferBuilder.vertex(celestialMatrix, -sunSize, 100.0F, sunSize).uv(0.0F, 1.0F).endVertex();
      BufferUploader.drawWithShader(bufferBuilder.end());
      skygodrays.captureCelestial(new Matrix4f(celestialMatrix), new Matrix4f(projectionMatrix), true, partialTick);
      RenderSystem.setShaderTexture(0, MOON_LOCATION);
      float moonSize = 20.0F * Mth.clamp(((Double)Config.CLIENT.skyMoonSize.get()).floatValue(), 0.1F, 3.0F);
      int moonPhase = mc.level.getMoonPhase();
      int phaseX = moonPhase % 4;
      int phaseY = moonPhase / 4 % 2;
      float minU = (float)phaseX / 4.0F;
      float minV = (float)phaseY / 2.0F;
      float maxU = (float)(phaseX + 1) / 4.0F;
      float maxV = (float)(phaseY + 1) / 2.0F;
      bufferBuilder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
      bufferBuilder.vertex(celestialMatrix, -moonSize, -100.0F, moonSize).uv(maxU, maxV).endVertex();
      bufferBuilder.vertex(celestialMatrix, moonSize, -100.0F, moonSize).uv(minU, maxV).endVertex();
      bufferBuilder.vertex(celestialMatrix, moonSize, -100.0F, -moonSize).uv(minU, minV).endVertex();
      bufferBuilder.vertex(celestialMatrix, -moonSize, -100.0F, -moonSize).uv(maxU, minV).endVertex();
      BufferUploader.drawWithShader(bufferBuilder.end());
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      RenderSystem.defaultBlendFunc();
      poseStack.popPose();
   }
}
