package com.vinlanx.luxium.client.kawase;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.SharedPostResources;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT}
)
public final class KawaseBloomRenderer {
   private static final int MAX_LEVELS = 7;
   private static final int MAX_SOURCE_DISTANCE = 32;
   private static final TextureTarget[] DOWN_TARGETS = new TextureTarget[7];
   private static final TextureTarget[] UP_TARGETS = new TextureTarget[7];
   private static TextureTarget sourceTarget;
   private static int sourceAtlas = -1;
   private static ByteBuffer sourcePixels;
   private static int volumeSize;
   private static int atlasSize;
   private static int atlasTilesPerRow;
   private static int volumeMinX;
   private static int volumeMinY;
   private static int volumeMinZ;
   private static int anchorX = Integer.MIN_VALUE;
   private static int anchorY = Integer.MIN_VALUE;
   private static int anchorZ = Integer.MIN_VALUE;
   private static int uploadedRadius = -1;
   private static long uploadedVersion = Long.MIN_VALUE;
   private static int previousSourceAtlasBinding;
   private static boolean sourceAtlasBound;
   private static final Matrix4f VIEW_ROTATION = new Matrix4f();
   private static final Matrix4f INVERSE_VIEW_PROJECTION = new Matrix4f();
   private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();

   private KawaseBloomRenderer() {
   }

   @SubscribeEvent(
      priority = EventPriority.LOW
   )
   public static void render(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_PARTICLES) {
         if (!Config.isFeatureEnabled(Config.CLIENT.kawaseBloomEnabled)) {
            releaseResources();
         } else if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.getMainRenderTarget();
            int depthTexture = SharedPostResources.getDepthTextureId();
            if (mc.level != null
               && mc.player != null
               && main != null
               && main.viewWidth > 0
               && main.viewHeight > 0
               && main.getColorTextureId() > 0
               && depthTexture > 0) {
               ShaderInstance sourceShader = ShaderManager.getKawaseSourceShader();
               ShaderInstance downShader = ShaderManager.getKawaseDownShader();
               ShaderInstance upShader = ShaderManager.getKawaseUpShader();
               ShaderInstance compositeShader = ShaderManager.getKawaseCompositeShader();
               if (sourceShader != null && downShader != null && upShader != null && compositeShader != null) {
                  int levels = Mth.clamp((Integer)Config.CLIENT.kawaseBloomLevels.get(), 2, 7);
                  ensureTargets(main.viewWidth, main.viewHeight, levels);
                  updateSourceVolume(event.getCamera().getPosition());
                  if (sourceAtlas > 0 && sourceTarget != null) {
                     Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
                     VertexSorting previousSorting = RenderSystem.getVertexSorting();
                     float[] previousColor = RenderSystem.getShaderColor();
                     PoseStack modelViewStack = RenderSystem.getModelViewStack();
                     modelViewStack.pushPose();
                     modelViewStack.setIdentity();
                     modelViewStack.translate(0.0F, 0.0F, -2000.0F);
                     RenderSystem.applyModelViewMatrix();

                     try {
                        Matrix4f viewRotation = VIEW_ROTATION.set(event.getPoseStack().last().pose());
                        Matrix4f inverseViewProjection = NeoSkyFrameCache.copyInverseViewProjection(
                           event.getProjectionMatrix(), viewRotation, INVERSE_VIEW_PROJECTION
                        );
                        Matrix4f inverseProjection = NeoSkyFrameCache.copyInverseProjection(event.getProjectionMatrix(), INVERSE_PROJECTION);
                        Vec3 camera = event.getCamera().getPosition();
                        sourceShader.setSampler("SceneSampler", main.getColorTextureId());
                        sourceShader.setSampler("DepthSampler", depthTexture);
                        setMatrix(sourceShader, "InverseViewProjection", inverseViewProjection);
                        setMatrix(sourceShader, "InverseProjection", inverseProjection);
                        set3f(
                           sourceShader,
                           "VolumeMinRelative",
                           (float)((double)volumeMinX - camera.x),
                           (float)((double)volumeMinY - camera.y),
                           (float)((double)volumeMinZ - camera.z)
                        );
                        set1f(sourceShader, "VolumeSize", (float)volumeSize);
                        set1f(sourceShader, "SourceAtlasSize", (float)atlasSize);
                        set1f(sourceShader, "SourceTilesPerRow", (float)atlasTilesPerRow);
                        set1f(sourceShader, "Threshold", ((Double)Config.CLIENT.kawaseBloomThreshold.get()).floatValue());
                        sourceTarget.bindWrite(true);
                        draw(sourceShader, sourceTarget.viewWidth, sourceTarget.viewHeight, false, () -> bindSourceVolume(sourceShader));
                        RenderTarget input = sourceTarget;
                        float radius = ((Double)Config.CLIENT.kawaseBloomRadius.get()).floatValue();

                        for (int level = 0; level < levels; level++) {
                           TextureTarget output = DOWN_TARGETS[level];
                           downShader.setSampler("InputSampler", input.getColorTextureId());
                           set2f(downShader, "InputTexelSize", 1.0F / (float)input.viewWidth, 1.0F / (float)input.viewHeight);
                           set1f(downShader, "Radius", radius);
                           output.bindWrite(true);
                           draw(downShader, output.viewWidth, output.viewHeight, false, null);
                           input = output;
                        }

                        for (int level = levels - 2; level >= 0; level--) {
                           TextureTarget output = UP_TARGETS[level];
                           RenderTarget high = DOWN_TARGETS[level];
                           upShader.setSampler("LowSampler", input.getColorTextureId());
                           upShader.setSampler("HighSampler", high.getColorTextureId());
                           set2f(upShader, "LowTexelSize", 1.0F / (float)input.viewWidth, 1.0F / (float)input.viewHeight);
                           set1f(upShader, "Radius", radius);
                           output.bindWrite(true);
                           draw(upShader, output.viewWidth, output.viewHeight, false, null);
                           input = output;
                        }

                        compositeShader.setSampler("GlowSampler", input.getColorTextureId());
                        compositeShader.setSampler("DepthSampler", depthTexture);
                        setMatrix(compositeShader, "InverseProjection", inverseProjection);
                        set2f(compositeShader, "GlowSize", (float)input.viewWidth, (float)input.viewHeight);
                        set1f(compositeShader, "Intensity", ((Double)Config.CLIENT.kawaseBloomIntensity.get()).floatValue());
                        set1f(compositeShader, "DepthTolerance", ((Double)Config.CLIENT.kawaseBloomDepthTolerance.get()).floatValue());
                        set1i(compositeShader, "DepthOcclusion", Config.CLIENT.kawaseBloomDepthOcclusion.get() ? 1 : 0);
                        main.bindWrite(true);
                        draw(compositeShader, main.viewWidth, main.viewHeight, true, null);
                     } finally {
                        restoreSourceAtlasBinding();
                        modelViewStack.popPose();
                        RenderSystem.applyModelViewMatrix();
                        RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
                        RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
                        GlStateManager._depthMask(true);
                        GlStateManager._enableDepthTest();
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                        main.bindWrite(true);
                        RenderSystem.viewport(0, 0, main.viewWidth, main.viewHeight);
                     }
                  }
               }
            }
         }
      }
   }

   public static void releaseResources() {
      destroyTarget(sourceTarget);
      sourceTarget = null;

      for (int i = 0; i < 7; i++) {
         destroyTarget(DOWN_TARGETS[i]);
         destroyTarget(UP_TARGETS[i]);
         DOWN_TARGETS[i] = null;
         UP_TARGETS[i] = null;
      }

      if (sourceAtlas > 0) {
         TextureUtil.releaseTextureId(sourceAtlas);
         sourceAtlas = -1;
      }

      sourcePixels = null;
      volumeSize = 0;
      atlasSize = 0;
      atlasTilesPerRow = 0;
      uploadedRadius = -1;
      uploadedVersion = Long.MIN_VALUE;
      anchorZ = Integer.MIN_VALUE;
      anchorY = Integer.MIN_VALUE;
      anchorX = Integer.MIN_VALUE;
   }

   private static void ensureTargets(int width, int height, int levels) {
      sourceTarget = ensureTarget(sourceTarget, width, height);
      int levelWidth = width;
      int levelHeight = height;

      for (int i = 0; i < levels; i++) {
         levelWidth = Math.max(1, levelWidth / 2);
         levelHeight = Math.max(1, levelHeight / 2);
         DOWN_TARGETS[i] = ensureTarget(DOWN_TARGETS[i], levelWidth, levelHeight);
         if (i < levels - 1) {
            UP_TARGETS[i] = ensureTarget(UP_TARGETS[i], levelWidth, levelHeight);
         }
      }

      for (int ix = levels; ix < 7; ix++) {
         destroyTarget(DOWN_TARGETS[ix]);
         DOWN_TARGETS[ix] = null;
      }

      for (int ix = Math.max(0, levels - 1); ix < 7; ix++) {
         destroyTarget(UP_TARGETS[ix]);
         UP_TARGETS[ix] = null;
      }
   }

   private static TextureTarget ensureTarget(TextureTarget target, int width, int height) {
      if (target == null) {
         target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
         configureHdrTarget(target);
      } else if (target.width != width || target.height != height) {
         target.resize(width, height, Minecraft.ON_OSX);
         configureHdrTarget(target);
      }

      return target;
   }

   private static void configureHdrTarget(TextureTarget target) {
      int previousActiveTexture = GL11.glGetInteger(34016);
      GL13.glActiveTexture(33984);
      int previousTexture = GL11.glGetInteger(32873);
      GL11.glBindTexture(3553, target.getColorTextureId());
      GL11.glTexImage2D(3553, 0, 34842, target.width, target.height, 0, 6408, 5131, (ByteBuffer)null);
      GL11.glTexParameteri(3553, 10241, 9729);
      GL11.glTexParameteri(3553, 10240, 9729);
      GL11.glTexParameteri(3553, 10242, 33071);
      GL11.glTexParameteri(3553, 10243, 33071);
      GL11.glBindTexture(3553, previousTexture);
      GL13.glActiveTexture(previousActiveTexture);
      target.setFilterMode(9729);
   }

   private static void updateSourceVolume(Vec3 camera) {
      KawaseSourceRegistry.Snapshot snapshot = KawaseSourceRegistry.get().snapshot();
      int radius = Mth.clamp((Integer)Config.CLIENT.kawaseBloomSourceDistance.get(), 8, 32);
      int cameraX = Mth.floor(camera.x);
      int cameraY = Mth.floor(camera.y);
      int cameraZ = Mth.floor(camera.z);
      boolean moved = anchorX == Integer.MIN_VALUE || Math.abs(cameraX - anchorX) >= 8 || Math.abs(cameraY - anchorY) >= 8 || Math.abs(cameraZ - anchorZ) >= 8;
      if (moved || uploadedRadius != radius || uploadedVersion != snapshot.version()) {
         if (moved || uploadedRadius != radius) {
            anchorX = cameraX;
            anchorY = cameraY;
            anchorZ = cameraZ;
         }

         int size = radius * 2 + 3;
         volumeMinX = anchorX - radius - 1;
         volumeMinY = anchorY - radius - 1;
         volumeMinZ = anchorZ - radius - 1;
         ensureSourceVolume(size);
         MemoryUtil.memSet(MemoryUtil.memAddress(sourcePixels), 0, (long)sourcePixels.capacity());
         long[] positions = snapshot.positions();
         byte[] emissions = snapshot.emissions();

         for (int i = 0; i < positions.length; i++) {
            int x = BlockPos.getX(positions[i]) - volumeMinX;
            int y = BlockPos.getY(positions[i]) - volumeMinY;
            int z = BlockPos.getZ(positions[i]) - volumeMinZ;
            if (x >= 0 && y >= 0 && z >= 0 && x < size && y < size && z < size) {
               int tileX = z % atlasTilesPerRow;
               int tileY = z / atlasTilesPerRow;
               int index = (tileY * size + y) * atlasSize + tileX * size + x;
               sourcePixels.put(index, (byte)((emissions[i] & 255) * 17));
            }
         }

         sourcePixels.position(0).limit(sourcePixels.capacity());
         int previousActiveTexture = GL11.glGetInteger(34016);
         GL13.glActiveTexture(33984);
         int previousTexture = GL11.glGetInteger(32873);
         int previousAlignment = GL11.glGetInteger(3317);
         int previousRowLength = GL11.glGetInteger(3314);
         int previousSkipRows = GL11.glGetInteger(3315);
         int previousSkipPixels = GL11.glGetInteger(3316);
         int previousUnpackBuffer = GL11.glGetInteger(35055);

         try {
            GL21.glBindBuffer(35052, 0);
            GL11.glBindTexture(3553, sourceAtlas);
            GlStateManager._pixelStore(3317, 1);
            GlStateManager._pixelStore(3314, 0);
            GlStateManager._pixelStore(3315, 0);
            GlStateManager._pixelStore(3316, 0);
            GL11.glTexSubImage2D(3553, 0, 0, 0, atlasSize, atlasSize, 6403, 5121, sourcePixels);
         } finally {
            GlStateManager._pixelStore(3317, previousAlignment);
            GlStateManager._pixelStore(3314, previousRowLength);
            GlStateManager._pixelStore(3315, previousSkipRows);
            GlStateManager._pixelStore(3316, previousSkipPixels);
            GL11.glBindTexture(3553, previousTexture);
            GL21.glBindBuffer(35052, previousUnpackBuffer);
            GL13.glActiveTexture(previousActiveTexture);
         }

         uploadedRadius = radius;
         uploadedVersion = snapshot.version();
      }
   }

   private static void ensureSourceVolume(int size) {
      if (sourceAtlas <= 0 || volumeSize != size || sourcePixels == null) {
         if (sourceAtlas > 0) {
            TextureUtil.releaseTextureId(sourceAtlas);
         }

         sourceAtlas = TextureUtil.generateTextureId();
         volumeSize = size;
         atlasTilesPerRow = Mth.ceil((float)Math.sqrt((double)size));
         atlasSize = atlasTilesPerRow * size;
         sourcePixels = BufferUtils.createByteBuffer(atlasSize * atlasSize);
         int previousActiveTexture = GL11.glGetInteger(34016);
         GL13.glActiveTexture(33984);
         int previousTexture = GL11.glGetInteger(32873);
         GL11.glBindTexture(3553, sourceAtlas);
         GL11.glTexParameteri(3553, 10241, 9728);
         GL11.glTexParameteri(3553, 10240, 9728);
         GL11.glTexParameteri(3553, 10242, 33071);
         GL11.glTexParameteri(3553, 10243, 33071);
         GL11.glTexImage2D(3553, 0, 33321, atlasSize, atlasSize, 0, 6403, 5121, (ByteBuffer)null);
         GL11.glBindTexture(3553, previousTexture);
         GL13.glActiveTexture(previousActiveTexture);
      }
   }

   private static void bindSourceVolume(ShaderInstance shader) {
      int previousActiveTexture = GL11.glGetInteger(34016);
      GL13.glActiveTexture(33991);
      previousSourceAtlasBinding = GL11.glGetInteger(32873);
      GL11.glBindTexture(3553, sourceAtlas);
      sourceAtlasBound = true;
      int location = GL20.glGetUniformLocation(shader.getId(), "SourceAtlas");
      if (location >= 0) {
         GL20.glUniform1i(location, 7);
      }

      GL13.glActiveTexture(previousActiveTexture);
   }

   private static void restoreSourceAtlasBinding() {
      if (sourceAtlasBound) {
         int previousActiveTexture = GL11.glGetInteger(34016);
         GL13.glActiveTexture(33991);
         GL11.glBindTexture(3553, previousSourceAtlasBinding);
         GL13.glActiveTexture(previousActiveTexture);
         sourceAtlasBound = false;
      }
   }

   private static void draw(ShaderInstance shader, int width, int height, boolean additive, Runnable afterApply) {
      Matrix4f projection = new Matrix4f().setOrtho(0.0F, (float)width, (float)height, 0.0F, 1000.0F, 3000.0F);
      RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
      RenderSystem.viewport(0, 0, width, height);
      if (shader.MODEL_VIEW_MATRIX != null) {
         shader.MODEL_VIEW_MATRIX.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
      }

      if (shader.PROJECTION_MATRIX != null) {
         shader.PROJECTION_MATRIX.set(projection);
      }

      GlStateManager._disableDepthTest();
      GlStateManager._depthMask(false);
      GlStateManager._colorMask(true, true, true, true);
      shader.apply();

      try {
         if (afterApply != null) {
            afterApply.run();
         }

         if (additive) {
            RenderSystem.enableBlend();
            GlStateManager._blendFuncSeparate(1, 1, 0, 1);
         } else {
            RenderSystem.disableBlend();
         }

         Tesselator tessellator = RenderSystem.renderThreadTesselator();
         BufferBuilder buffer = tessellator.getBuilder();
         buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
         buffer.vertex(0.0, (double)height, 0.0).uv(0.0F, 0.0F).endVertex();
         buffer.vertex((double)width, (double)height, 0.0).uv(1.0F, 0.0F).endVertex();
         buffer.vertex((double)width, 0.0, 0.0).uv(1.0F, 1.0F).endVertex();
         buffer.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).endVertex();
         BufferUploader.draw(buffer.end());
      } finally {
         shader.clear();
      }
   }

   private static void set1f(ShaderInstance shader, String name, float value) {
      if (shader.getUniform(name) != null) {
         shader.getUniform(name).set(value);
      }
   }

   private static void set1i(ShaderInstance shader, String name, int value) {
      if (shader.getUniform(name) != null) {
         shader.getUniform(name).set(value);
      }
   }

   private static void set2f(ShaderInstance shader, String name, float x, float y) {
      if (shader.getUniform(name) != null) {
         shader.getUniform(name).set(x, y);
      }
   }

   private static void set3f(ShaderInstance shader, String name, float x, float y, float z) {
      if (shader.getUniform(name) != null) {
         shader.getUniform(name).set(x, y, z);
      }
   }

   private static void setMatrix(ShaderInstance shader, String name, Matrix4f matrix) {
      if (shader.getUniform(name) != null) {
         shader.getUniform(name).set(matrix);
      }
   }

   private static void destroyTarget(TextureTarget target) {
      if (target != null) {
         target.destroyBuffers();
      }
   }
}
