package com.vinlanx.luxium.client.clouds;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.logging.LogUtils;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.ShaderManager;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.util.FastColor.ABGR32;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

public final class LuxiumCloudRenderer {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final LuxiumCloudRenderer INSTANCE = new LuxiumCloudRenderer();
   private static final ResourceLocation VANILLA_CLOUDS = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
   private static final int TEXTURE_SIZE = 256;
   private static final float UV_SCALE = 0.00390625F;
   private static final float EPSILON = 9.765625E-4F;
   private static final int TILE_SIZE = 8;
   private static final int MAX_RADIUS_TEXELS = 128;
   private static final float SHADOW_FOOTPRINT_PADDING = 1.0F;
   private static final float CLOUD_PROJECTION_MARGIN = 48.0F;
   private static final int[] PATTERN_OFFSET_X = new int[]{0, 73, 181};
   private static final int[] PATTERN_OFFSET_Z = new int[]{0, 151, 47};
   private final LuxiumCloudRenderer.LayerState[] layers = new LuxiumCloudRenderer.LayerState[]{
      new LuxiumCloudRenderer.LayerState(0), new LuxiumCloudRenderer.LayerState(1), new LuxiumCloudRenderer.LayerState(2)
   };
   private final LuxiumCloudRenderer.LayerState vanillaMaskLayer = new LuxiumCloudRenderer.LayerState(-1);
   private boolean sourceLoaded;
   private boolean sourceLoadFailed;
   private boolean[] sourceOpaque;
   private int[] sourcePixels;
   private int[] distanceToOpaque;
   private int[] distanceToClear;
   private int[] nearestOpaquePixel;
   private TextureTarget occlusionTarget;

   private LuxiumCloudRenderer() {
   }

   public static LuxiumCloudRenderer get() {
      return INSTANCE;
   }

   public void invalidateResources() {
      this.sourceLoaded = false;
      this.sourceLoadFailed = false;
      this.sourceOpaque = null;
      this.sourcePixels = null;
      this.distanceToOpaque = null;
      this.distanceToClear = null;
      this.nearestOpaquePixel = null;

      for (LuxiumCloudRenderer.LayerState state : this.layers) {
         state.textureKey = Long.MIN_VALUE;
         state.geometryKey = Long.MIN_VALUE;
         state.maskGeometryKey = Long.MIN_VALUE;
      }

      this.vanillaMaskLayer.maskGeometryKey = Long.MIN_VALUE;
   }

   public void invalidateGeometry() {
      for (LuxiumCloudRenderer.LayerState state : this.layers) {
         state.geometryKey = Long.MIN_VALUE;
         state.maskGeometryKey = Long.MIN_VALUE;
      }

      this.vanillaMaskLayer.maskGeometryKey = Long.MIN_VALUE;
   }

   public boolean render(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, double cameraX, double cameraY, double cameraZ, int ticks, boolean cloudShadowPass
   ) {
      Minecraft mc = Minecraft.getInstance();
      ClientLevel level = mc.level;
      if (level != null && mc.player != null) {
         float dimensionCloudHeight = level.effects().getCloudHeight();
         if (Float.isNaN(dimensionCloudHeight)) {
            return true;
         } else {
            CloudStatus cloudStatus = mc.options.getCloudsType();
            if (cloudStatus == CloudStatus.OFF) {
               return true;
            } else {
               LuxiumCloudRenderer.LayerConfig[] config = readConfig();
               boolean anyEnabled = false;

               for (LuxiumCloudRenderer.LayerConfig layer : config) {
                  anyEnabled |= layer.enabled && layer.opacity > 0.0;
               }

               if (!anyEnabled) {
                  return true;
               } else {
                  float weather = Mth.clamp(Math.max(level.getRainLevel(partialTick), level.getThunderLevel(partialTick)), 0.0F, 1.0F);
                  int weatherStep = Mth.clamp(Math.round(weather * 20.0F), 0, 20);
                  float textureWeather = (float)weatherStep / 20.0F;
                  this.ensureSourceTexture();
                  if (cloudShadowPass) {
                     this.renderShadowPass(poseStack, projectionMatrix, partialTick, cameraX, cameraY, cameraZ, ticks, cloudStatus, textureWeather, config);
                  } else {
                     this.renderColorPass(
                        level, poseStack, projectionMatrix, partialTick, cameraX, cameraY, cameraZ, ticks, cloudStatus, weather, textureWeather, config
                     );
                  }

                  return true;
               }
            }
         }
      } else {
         return false;
      }
   }

   public boolean renderCustomShadowCasters(
      PoseStack poseStack,
      Matrix4f projectionMatrix,
      Matrix4f lightViewRotation,
      float projectionRadius,
      Vec3 toLight,
      float partialTick,
      double centerX,
      double centerY,
      double centerZ,
      int ticks
   ) {
      if (!Config.isFeatureEnabled(Config.CLIENT.cloudsEnabled)) {
         return false;
      } else {
         Minecraft mc = Minecraft.getInstance();
         ClientLevel level = mc.level;
         if (level != null && mc.player != null && mc.options.getCloudsType() != CloudStatus.OFF) {
            float dimensionCloudHeight = level.effects().getCloudHeight();
            if (Float.isNaN(dimensionCloudHeight)) {
               return true;
            } else {
               CloudStatus cloudStatus = mc.options.getCloudsType();
               LuxiumCloudRenderer.LayerConfig[] config = readConfig();
               float weather = Mth.clamp(Math.max(level.getRainLevel(partialTick), level.getThunderLevel(partialTick)), 0.0F, 1.0F);
               int weatherStep = Mth.clamp(Math.round(weather * 20.0F), 0, 20);
               float textureWeather = (float)weatherStep / 20.0F;
               this.ensureSourceTexture();
               ShaderInstance shadowShader = ShaderManager.getCloudShadowCasterShader();
               if (shadowShader != null) {
                  RenderSystem.setShader(() -> shadowShader);
               } else {
                  RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
               }

               RenderSystem.disableBlend();
               RenderSystem.disableCull();
               RenderSystem.enableDepthTest();
               RenderSystem.depthMask(true);
               RenderSystem.colorMask(false, false, false, false);
               RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
               Vec3 center = new Vec3(centerX, centerY, centerZ);

               for (int i = 0; i < this.layers.length; i++) {
                  LuxiumCloudRenderer.LayerConfig layer = config[i];
                  if (layer.enabled && !(layer.opacity <= 0.0)) {
                     double casterY = shadowCasterHeight(layer, cloudStatus);
                     LuxiumCloudRenderer.ShadowPlane plane = buildShadowPlane(center, toLight, lightViewRotation, projectionRadius, casterY);
                     if (plane != null && !(plane.maxFront <= 0.0)) {
                        ResourceLocation texture = this.textureForLayer(i, layer, textureWeather);
                        RenderSystem.setShaderTexture(0, texture);
                        this.uploadShadowPlane(this.layers[i], plane, i, layer, partialTick, centerX, centerZ, ticks);
                        LuxiumCloudRenderer.LayerState state = this.layers[i];
                        if (state.shadowVertexBuffer != null) {
                           state.shadowVertexBuffer.bind();
                           ShaderInstance shader = RenderSystem.getShader();
                           if (shader != null) {
                              state.shadowVertexBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader);
                           }

                           VertexBuffer.unbind();
                        }
                     }
                  }
               }

               RenderSystem.disableBlend();
               RenderSystem.enableDepthTest();
               RenderSystem.depthMask(true);
               RenderSystem.colorMask(false, false, false, false);
               RenderSystem.enableCull();
               return true;
            }
         } else {
            return true;
         }
      }
   }

   public float computeCustomShadowCasterFront(Vec3 center, Vec3 toLight, Matrix4f lightViewRotation, float projectionRadius, CloudStatus cloudStatus) {
      if (Config.isFeatureEnabled(Config.CLIENT.cloudsEnabled) && cloudStatus != CloudStatus.OFF && toLight != null && !(toLight.y <= 1.0E-6)) {
         LuxiumCloudRenderer.LayerConfig[] config = readConfig();
         double maxFront = Double.NEGATIVE_INFINITY;

         for (LuxiumCloudRenderer.LayerConfig layer : config) {
            if (layer.enabled && !(layer.opacity <= 0.0)) {
               LuxiumCloudRenderer.ShadowPlane plane = buildShadowPlane(
                  center, toLight, lightViewRotation, projectionRadius, shadowCasterHeight(layer, cloudStatus)
               );
               if (plane != null && Double.isFinite(plane.maxFront)) {
                  maxFront = Math.max(maxFront, plane.maxFront);
               }
            }
         }

         return Double.isFinite(maxFront) && maxFront > 0.0 ? (float)maxFront : Float.NaN;
      } else {
         return Float.NaN;
      }
   }

   public int renderOcclusionMask(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, double cameraX, double cameraY, double cameraZ, int ticks, int width, int height
   ) {
      if (width > 0 && height > 0) {
         Minecraft mc = Minecraft.getInstance();
         ClientLevel level = mc.level;
         if (level != null && mc.player != null) {
            float dimensionCloudHeight = level.effects().getCloudHeight();
            CloudStatus cloudStatus = mc.options.getCloudsType();
            if (!Float.isNaN(dimensionCloudHeight) && cloudStatus != CloudStatus.OFF) {
               boolean customClouds = Config.isFeatureEnabled(Config.CLIENT.cloudsEnabled);
               LuxiumCloudRenderer.LayerConfig[] config = null;
               float textureWeather = 0.0F;
               if (customClouds) {
                  config = readConfig();
                  boolean anyEnabled = false;

                  for (LuxiumCloudRenderer.LayerConfig layer : config) {
                     anyEnabled |= layer.enabled && layer.opacity > 0.0;
                  }

                  if (!anyEnabled) {
                     return 0;
                  }

                  float weather = Mth.clamp(Math.max(level.getRainLevel(partialTick), level.getThunderLevel(partialTick)), 0.0F, 1.0F);
                  int weatherStep = Mth.clamp(Math.round(weather * 20.0F), 0, 20);
                  textureWeather = (float)weatherStep / 20.0F;
                  this.ensureSourceTexture();
               }

               this.ensureOcclusionTarget(width, height);
               if (this.occlusionTarget == null) {
                  return 0;
               } else {
                  this.occlusionTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                  RenderSystem.colorMask(true, true, true, true);
                  this.occlusionTarget.clear(Minecraft.ON_OSX);
                  this.occlusionTarget.bindWrite(true);
                  RenderSystem.viewport(0, 0, width, height);
                  RenderSystem.disableCull();
                  RenderSystem.disableDepthTest();
                  RenderSystem.depthMask(false);
                  RenderSystem.enableBlend();
                  RenderSystem.blendFuncSeparate(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
                  RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
                  if (customClouds) {
                     this.renderCustomOcclusionMask(
                        poseStack, projectionMatrix, partialTick, cameraX, cameraY, cameraZ, ticks, cloudStatus, textureWeather, config
                     );
                  } else {
                     this.renderVanillaOcclusionMask(
                        poseStack, projectionMatrix, partialTick, cameraX, cameraY, cameraZ, ticks, cloudStatus, dimensionCloudHeight
                     );
                  }

                  RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                  RenderSystem.colorMask(true, true, true, true);
                  RenderSystem.depthMask(true);
                  RenderSystem.enableDepthTest();
                  RenderSystem.enableCull();
                  RenderSystem.disableBlend();
                  RenderSystem.defaultBlendFunc();
                  return this.occlusionTarget.getColorTextureId();
               }
            } else {
               return 0;
            }
         } else {
            return 0;
         }
      } else {
         return 0;
      }
   }

   private void renderCustomOcclusionMask(
      PoseStack poseStack,
      Matrix4f projectionMatrix,
      float partialTick,
      double cameraX,
      double cameraY,
      double cameraZ,
      int ticks,
      CloudStatus cloudStatus,
      float textureWeather,
      LuxiumCloudRenderer.LayerConfig[] config
   ) {
      for (int i = 0; i < this.layers.length; i++) {
         LuxiumCloudRenderer.LayerConfig layer = config[i];
         if (layer.enabled && !(layer.opacity <= 0.0)) {
            LuxiumCloudRenderer.MaskLayerFrame frame = this.prepareMaskLayer(
               i, layer, cloudStatus, partialTick, cameraX, cameraY, cameraZ, ticks, textureWeather
            );
            if (frame != null) {
               Matrix4f cloudProjection = cloudProjectionMatrix(projectionMatrix, layer, cloudStatus, cameraY);
               drawOcclusionMaskLayer(poseStack, cloudProjection, this.layers[i], frame, (float)(layer.opacity / 100.0));
            }
         }
      }
   }

   private void renderVanillaOcclusionMask(
      PoseStack poseStack,
      Matrix4f projectionMatrix,
      float partialTick,
      double cameraX,
      double cameraY,
      double cameraZ,
      int ticks,
      CloudStatus cloudStatus,
      float cloudHeight
   ) {
      LuxiumCloudRenderer.MaskLayerFrame frame = this.prepareVanillaMaskLayer(cloudStatus, partialTick, cameraX, cameraY, cameraZ, ticks, cloudHeight);
      if (frame != null) {
         drawOcclusionMaskLayer(poseStack, projectionMatrix, this.vanillaMaskLayer, frame, 0.8F);
      }
   }

   private static void drawOcclusionMaskLayer(
      PoseStack poseStack, Matrix4f projectionMatrix, LuxiumCloudRenderer.LayerState state, LuxiumCloudRenderer.MaskLayerFrame frame, float opacity
   ) {
      RenderSystem.setShaderTexture(0, frame.texture);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, opacity);
      poseStack.pushPose();
      poseStack.scale(frame.blockScale, 1.0F, frame.blockScale);
      poseStack.translate(-frame.fractionX, frame.relativeY, -frame.fractionZ);
      if (state.maskVertexBuffer != null) {
         state.maskVertexBuffer.bind();
         ShaderInstance shader = RenderSystem.getShader();
         if (shader != null) {
            state.maskVertexBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader);
         }

         VertexBuffer.unbind();
      }

      poseStack.popPose();
   }

   private void renderColorPass(
      ClientLevel level,
      PoseStack poseStack,
      Matrix4f projectionMatrix,
      float partialTick,
      double cameraX,
      double cameraY,
      double cameraZ,
      int ticks,
      CloudStatus cloudStatus,
      float weather,
      float textureWeather,
      LuxiumCloudRenderer.LayerConfig[] config
   ) {
      RenderSystem.disableCull();
      RenderSystem.enableBlend();
      RenderSystem.enableDepthTest();
      RenderSystem.blendFuncSeparate(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
      RenderSystem.depthMask(true);
      FogRenderer.levelFogColor();
      float savedFogStart = RenderSystem.getShaderFogStart();
      float savedFogEnd = RenderSystem.getShaderFogEnd();
      FogShape savedFogShape = RenderSystem.getShaderFogShape();
      boolean ordinaryDistanceFog = isOrdinaryTerrainDistanceFog(savedFogEnd, savedFogShape);
      Vec3 cloudColor = level.getCloudColor(partialTick);

      for (int i = 0; i < this.layers.length; i++) {
         LuxiumCloudRenderer.LayerConfig layer = config[i];
         if (layer.enabled && !(layer.opacity <= 0.0)) {
            LuxiumCloudRenderer.LayerFrame frame = this.prepareLayer(i, layer, cloudStatus, partialTick, cameraX, cameraY, cameraZ, ticks, textureWeather);
            if (frame != null) {
               Matrix4f cloudProjection = cloudProjectionMatrix(projectionMatrix, layer, cloudStatus, cameraY);
               if (ordinaryDistanceFog) {
                  applyLayerDistanceFog(layer, cloudStatus, cameraY);
               }

               float stormFactor = 1.0F - weather * (float)(layer.stormDarkening / 100.0) * 0.65F;
               float brightness = (float)layer.brightness * Math.max(0.05F, stormFactor);
               float red = (float)cloudColor.x * brightness;
               float green = (float)cloudColor.y * brightness;
               float blue = (float)cloudColor.z * brightness;
               float alpha = (float)(layer.opacity / 100.0);
               RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
               RenderSystem.setShaderTexture(0, frame.texture);
               RenderSystem.setShaderColor(red, green, blue, alpha);
               poseStack.pushPose();
               poseStack.scale(frame.blockScale, 1.0F, frame.blockScale);
               poseStack.translate(-frame.fractionX, frame.fractionY, -frame.fractionZ);
               LuxiumCloudRenderer.LayerState state = this.layers[i];
               if (state.vertexBuffer != null) {
                  state.vertexBuffer.bind();
                  if (cloudStatus == CloudStatus.FANCY) {
                     RenderSystem.colorMask(false, false, false, false);
                     ShaderInstance shader = RenderSystem.getShader();
                     if (shader != null) {
                        state.vertexBuffer.drawWithShader(poseStack.last().pose(), cloudProjection, shader);
                     }
                  }

                  RenderSystem.colorMask(true, true, true, true);
                  ShaderInstance shader = RenderSystem.getShader();
                  if (shader != null) {
                     state.vertexBuffer.drawWithShader(poseStack.last().pose(), cloudProjection, shader);
                  }

                  VertexBuffer.unbind();
               }

               poseStack.popPose();
            }
         }
      }

      RenderSystem.setShaderFogStart(savedFogStart);
      RenderSystem.setShaderFogEnd(savedFogEnd);
      RenderSystem.setShaderFogShape(savedFogShape);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      RenderSystem.colorMask(true, true, true, true);
      RenderSystem.depthMask(true);
      RenderSystem.enableCull();
      RenderSystem.disableBlend();
      RenderSystem.defaultBlendFunc();
   }

   private void renderShadowPass(
      PoseStack poseStack,
      Matrix4f projectionMatrix,
      float partialTick,
      double cameraX,
      double cameraY,
      double cameraZ,
      int ticks,
      CloudStatus cloudStatus,
      float textureWeather,
      LuxiumCloudRenderer.LayerConfig[] config
   ) {
      ShaderInstance shadowShader = ShaderManager.getCloudShadowCasterShader();
      if (shadowShader != null) {
         RenderSystem.setShader(() -> shadowShader);
      } else {
         RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
      }

      RenderSystem.disableBlend();
      RenderSystem.disableCull();
      RenderSystem.enableDepthTest();
      RenderSystem.depthMask(true);
      RenderSystem.colorMask(false, false, false, false);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

      for (int i = 0; i < this.layers.length; i++) {
         LuxiumCloudRenderer.LayerConfig layer = config[i];
         if (layer.enabled && !(layer.opacity <= 0.0)) {
            LuxiumCloudRenderer.LayerFrame frame = this.prepareLayer(i, layer, cloudStatus, partialTick, cameraX, cameraY, cameraZ, ticks, textureWeather);
            if (frame != null) {
               RenderSystem.setShaderTexture(0, frame.texture);
               poseStack.pushPose();
               poseStack.scale(frame.blockScale, 1.0F, frame.blockScale);
               poseStack.translate(-frame.fractionX, frame.fractionY, -frame.fractionZ);
               LuxiumCloudRenderer.LayerState state = this.layers[i];
               if (state.vertexBuffer != null) {
                  state.vertexBuffer.bind();
                  ShaderInstance shader = RenderSystem.getShader();
                  if (shader != null) {
                     state.vertexBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader);
                  }

                  VertexBuffer.unbind();
               }

               poseStack.popPose();
            }
         }
      }

      RenderSystem.disableBlend();
      RenderSystem.enableDepthTest();
      RenderSystem.depthMask(true);
      RenderSystem.colorMask(false, false, false, false);
      RenderSystem.enableCull();
   }

   private LuxiumCloudRenderer.LayerFrame prepareLayer(
      int index,
      LuxiumCloudRenderer.LayerConfig layer,
      CloudStatus cloudStatus,
      float partialTick,
      double cameraX,
      double cameraY,
      double cameraZ,
      int ticks,
      float weather
   ) {
      float blockScale = (float)(12.0 * layer.scale);
      if (blockScale <= 0.0F) {
         return null;
      } else {
         double time = ((double)ticks + (double)partialTick) * 0.03 * layer.speed;
         double radians = Math.toRadians(layer.direction);
         double windX = Math.cos(radians) * time;
         double windZ = Math.sin(radians) * time;
         double cloudX = (cameraX + windX) / (double)blockScale + (double)PATTERN_OFFSET_X[index];
         double cloudZ = (cameraZ + windZ) / (double)blockScale + (double)PATTERN_OFFSET_Z[index] + 0.33;
         int absoluteCellX = Mth.floor(cloudX);
         int absoluteCellZ = Mth.floor(cloudZ);
         int textureCellX = Math.floorMod(absoluteCellX, 256);
         int textureCellZ = Math.floorMod(absoluteCellZ, 256);
         float fractionX = (float)(cloudX - Math.floor(cloudX));
         float fractionZ = (float)(cloudZ - Math.floor(cloudZ));
         double relativeY = layer.height - cameraY + 0.33;
         double thickness = Math.max(0.5, layer.thickness);
         int yCell = Mth.floor(relativeY / thickness);
         float yBase = (float)((double)yCell * thickness);
         float fractionY = (float)(relativeY - (double)yBase);
         int radiusTexels = layerRadiusTexels(layer);
         long geometryKey = geometryKey(textureCellX, textureCellZ, yCell, radiusTexels, cloudStatus, layer.thickness);
         LuxiumCloudRenderer.LayerState state = this.layers[index];
         if (state.geometryKey != geometryKey || state.vertexBuffer == null) {
            this.rebuildGeometry(state, textureCellX, textureCellZ, yBase, (float)thickness, radiusTexels, cloudStatus);
            state.geometryKey = geometryKey;
         }

         ResourceLocation texture = this.textureForLayer(index, layer, weather);
         return new LuxiumCloudRenderer.LayerFrame(blockScale, fractionX, fractionY, fractionZ, texture);
      }
   }

   private LuxiumCloudRenderer.MaskLayerFrame prepareMaskLayer(
      int index,
      LuxiumCloudRenderer.LayerConfig layer,
      CloudStatus cloudStatus,
      float partialTick,
      double cameraX,
      double cameraY,
      double cameraZ,
      int ticks,
      float weather
   ) {
      float blockScale = (float)(12.0 * layer.scale);
      if (blockScale <= 0.0F) {
         return null;
      } else {
         double time = ((double)ticks + (double)partialTick) * 0.03 * layer.speed;
         double radians = Math.toRadians(layer.direction);
         double windX = Math.cos(radians) * time;
         double windZ = Math.sin(radians) * time;
         double cloudX = (cameraX + windX) / (double)blockScale + (double)PATTERN_OFFSET_X[index];
         double cloudZ = (cameraZ + windZ) / (double)blockScale + (double)PATTERN_OFFSET_Z[index] + 0.33;
         int absoluteCellX = Mth.floor(cloudX);
         int absoluteCellZ = Mth.floor(cloudZ);
         int textureCellX = Math.floorMod(absoluteCellX, 256);
         int textureCellZ = Math.floorMod(absoluteCellZ, 256);
         float fractionX = (float)(cloudX - Math.floor(cloudX));
         float fractionZ = (float)(cloudZ - Math.floor(cloudZ));
         double thickness = Math.max(0.5, layer.thickness);
         double bottomY = layer.height + 0.33;
         double maskY = bottomY;
         if (cloudStatus == CloudStatus.FANCY) {
            double topY = bottomY + thickness;
            if (cameraY >= topY) {
               maskY = topY;
            } else if (cameraY > bottomY) {
               maskY = cameraY - bottomY <= topY - cameraY ? bottomY : topY;
            }
         }

         float relativeY = (float)(maskY - cameraY);
         int radiusTexels = layerRadiusTexels(layer);
         long maskKey = geometryKey(textureCellX, textureCellZ, 0, radiusTexels, cloudStatus, 1.0);
         LuxiumCloudRenderer.LayerState state = this.layers[index];
         if (state.maskGeometryKey != maskKey || state.maskVertexBuffer == null) {
            this.rebuildMaskGeometry(state, textureCellX, textureCellZ, radiusTexels, cloudStatus);
            state.maskGeometryKey = maskKey;
         }

         ResourceLocation texture = this.textureForLayer(index, layer, weather);
         return new LuxiumCloudRenderer.MaskLayerFrame(blockScale, fractionX, relativeY, fractionZ, texture);
      }
   }

   private LuxiumCloudRenderer.MaskLayerFrame prepareVanillaMaskLayer(
      CloudStatus cloudStatus, float partialTick, double cameraX, double cameraY, double cameraZ, int ticks, float cloudHeight
   ) {
      float blockScale = 12.0F;
      float thickness = 4.0F;
      int radiusTexels = 32;
      double time = ((double)ticks + (double)partialTick) * 0.03;
      double cloudX = (cameraX + time) / 12.0;
      double cloudZ = cameraZ / 12.0 + 0.33;
      int absoluteCellX = Mth.floor(cloudX);
      int absoluteCellZ = Mth.floor(cloudZ);
      int textureCellX = Math.floorMod(absoluteCellX, 256);
      int textureCellZ = Math.floorMod(absoluteCellZ, 256);
      float fractionX = (float)(cloudX - Math.floor(cloudX));
      float fractionZ = (float)(cloudZ - Math.floor(cloudZ));
      double bottomY = (double)cloudHeight + 0.33;
      double maskY = bottomY;
      if (cloudStatus == CloudStatus.FANCY) {
         double topY = bottomY + 4.0 - 9.765625E-4;
         if (cameraY >= topY) {
            maskY = topY;
         } else if (cameraY > bottomY) {
            maskY = cameraY - bottomY <= topY - cameraY ? bottomY : topY;
         }
      }

      float relativeY = (float)(maskY - cameraY);
      long maskKey = geometryKey(textureCellX, textureCellZ, 0, 32, cloudStatus, 1.0);
      if (this.vanillaMaskLayer.maskGeometryKey != maskKey || this.vanillaMaskLayer.maskVertexBuffer == null) {
         this.rebuildMaskGeometry(this.vanillaMaskLayer, textureCellX, textureCellZ, 32, cloudStatus);
         this.vanillaMaskLayer.maskGeometryKey = maskKey;
      }

      return new LuxiumCloudRenderer.MaskLayerFrame(12.0F, fractionX, relativeY, fractionZ, VANILLA_CLOUDS);
   }

   private static double shadowCasterHeight(LuxiumCloudRenderer.LayerConfig layer, CloudStatus cloudStatus) {
      double casterY = layer.height + 0.33;
      if (cloudStatus == CloudStatus.FANCY) {
         casterY += Math.max(0.5, layer.thickness) - 9.765625E-4;
      }

      return casterY;
   }

   private static LuxiumCloudRenderer.ShadowPlane buildShadowPlane(Vec3 center, Vec3 toLight, Matrix4f lightViewRotation, float projectionRadius, double cloudY) {
      if (center != null && toLight != null && lightViewRotation != null && Double.isFinite(cloudY) && !(toLight.y <= 1.0E-6)) {
         float radius = Math.max(1.0F, projectionRadius) + 1.0F;
         Matrix4f inverseLightRotation = new Matrix4f(lightViewRotation).invert();
         Vector3f[] corners = new Vector3f[4];
         float[] lightX = new float[]{-radius, radius, radius, -radius};
         float[] lightY = new float[]{-radius, -radius, radius, radius};
         double maxFront = Double.NEGATIVE_INFINITY;

         for (int i = 0; i < corners.length; i++) {
            Vector3f base = new Vector3f(lightX[i], lightY[i], 0.0F);
            inverseLightRotation.transformDirection(base);
            double front = (cloudY - center.y - (double)base.y) / toLight.y;
            maxFront = Math.max(maxFront, front);
            base.add((float)(toLight.x * front), (float)(toLight.y * front), (float)(toLight.z * front));
            corners[i] = base;
         }

         return new LuxiumCloudRenderer.ShadowPlane(corners[0], corners[1], corners[2], corners[3], maxFront);
      } else {
         return null;
      }
   }

   private void uploadShadowPlane(
      LuxiumCloudRenderer.LayerState state,
      LuxiumCloudRenderer.ShadowPlane plane,
      int layerIndex,
      LuxiumCloudRenderer.LayerConfig layer,
      float partialTick,
      double centerX,
      double centerZ,
      int ticks
   ) {
      float blockScale = (float)(12.0 * layer.scale);
      if (!(blockScale <= 0.0F)) {
         double time = ((double)ticks + (double)partialTick) * 0.03 * layer.speed;
         double radians = Math.toRadians(layer.direction);
         double windX = Math.cos(radians) * time;
         double windZ = Math.sin(radians) * time;
         double centerCloudX = (centerX + windX) / (double)blockScale + (double)PATTERN_OFFSET_X[layerIndex];
         double centerCloudZ = (centerZ + windZ) / (double)blockScale + (double)PATTERN_OFFSET_Z[layerIndex] + 0.33;
         double uPeriodBase = Math.floor(centerCloudX / 256.0) * 256.0;
         double vPeriodBase = Math.floor(centerCloudZ / 256.0) * 256.0;
         BufferBuilder builder = Tesselator.getInstance().getBuilder();
         builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
         shadowVertex(builder, plane.p0, centerX, centerZ, windX, windZ, blockScale, layerIndex, uPeriodBase, vPeriodBase);
         shadowVertex(builder, plane.p1, centerX, centerZ, windX, windZ, blockScale, layerIndex, uPeriodBase, vPeriodBase);
         shadowVertex(builder, plane.p2, centerX, centerZ, windX, windZ, blockScale, layerIndex, uPeriodBase, vPeriodBase);
         shadowVertex(builder, plane.p3, centerX, centerZ, windX, windZ, blockScale, layerIndex, uPeriodBase, vPeriodBase);
         RenderedBuffer rendered = builder.end();
         if (state.shadowVertexBuffer == null) {
            state.shadowVertexBuffer = new VertexBuffer(Usage.DYNAMIC);
         }

         state.shadowVertexBuffer.bind();
         state.shadowVertexBuffer.upload(rendered);
         VertexBuffer.unbind();
      }
   }

   private static void shadowVertex(
      BufferBuilder builder,
      Vector3f point,
      double centerX,
      double centerZ,
      double windX,
      double windZ,
      float blockScale,
      int layerIndex,
      double uPeriodBase,
      double vPeriodBase
   ) {
      double worldX = centerX + (double)point.x;
      double worldZ = centerZ + (double)point.z;
      float u = (float)(((worldX + windX) / (double)blockScale + (double)PATTERN_OFFSET_X[layerIndex] - uPeriodBase) * 0.00390625);
      float v = (float)(((worldZ + windZ) / (double)blockScale + (double)PATTERN_OFFSET_Z[layerIndex] + 0.33 - vPeriodBase) * 0.00390625);
      builder.vertex((double)point.x, (double)point.y, (double)point.z).uv(u, v).color(1.0F, 1.0F, 1.0F, 1.0F).normal(0.0F, 1.0F, 0.0F).endVertex();
   }

   private void rebuildMaskGeometry(LuxiumCloudRenderer.LayerState state, int centerCellX, int centerCellZ, int radiusTexels, CloudStatus cloudStatus) {
      BufferBuilder builder = Tesselator.getInstance().getBuilder();
      RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
      builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
      if (cloudStatus == CloudStatus.FANCY) {
         buildFancyOcclusionPlane(builder, centerCellX, centerCellZ, radiusTexels);
      } else {
         buildFast(builder, centerCellX, centerCellZ, 0.0F, radiusTexels);
      }

      RenderedBuffer rendered = builder.end();
      if (state.maskVertexBuffer == null) {
         state.maskVertexBuffer = new VertexBuffer(Usage.STATIC);
      }

      state.maskVertexBuffer.bind();
      state.maskVertexBuffer.upload(rendered);
      VertexBuffer.unbind();
   }

   private void rebuildGeometry(
      LuxiumCloudRenderer.LayerState state, int centerCellX, int centerCellZ, float yBase, float thickness, int radiusTexels, CloudStatus cloudStatus
   ) {
      BufferBuilder builder = Tesselator.getInstance().getBuilder();
      RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
      builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
      if (cloudStatus == CloudStatus.FANCY) {
         buildFancy(builder, centerCellX, centerCellZ, yBase, thickness, radiusTexels);
      } else {
         buildFast(builder, centerCellX, centerCellZ, yBase, radiusTexels);
      }

      RenderedBuffer rendered = builder.end();
      if (state.vertexBuffer == null) {
         state.vertexBuffer = new VertexBuffer(Usage.STATIC);
      }

      state.vertexBuffer.bind();
      state.vertexBuffer.upload(rendered);
      VertexBuffer.unbind();
   }

   private static void buildFancyOcclusionPlane(BufferBuilder builder, int centerCellX, int centerCellZ, int radiusTexels) {
      int start = -radiusTexels + 8;
      int end = radiusTexels;

      for (int x0i = start; x0i <= end; x0i += 8) {
         for (int z0i = start; z0i <= end; z0i += 8) {
            float x0 = (float)x0i;
            float x1 = x0 + 8.0F;
            float z0 = (float)z0i;
            float z1 = z0 + 8.0F;
            float u0 = ((float)centerCellX + x0) * 0.00390625F;
            float u1 = ((float)centerCellX + x1) * 0.00390625F;
            float v0 = ((float)centerCellZ + z0) * 0.00390625F;
            float v1 = ((float)centerCellZ + z1) * 0.00390625F;
            quad(builder, x0, 0.0F, z1, u0, v1, x1, 0.0F, z1, u1, v1, x1, 0.0F, z0, u1, v0, x0, 0.0F, z0, u0, v0, 1.0F, 0.0F, 1.0F, 0.0F);
         }
      }
   }

   private static void buildFancy(BufferBuilder builder, int centerCellX, int centerCellZ, float yBase, float thickness, int radiusTexels) {
      boolean drawBottom = yBase > -(thickness + 1.0F);
      boolean drawTop = yBase <= thickness + 1.0F;
      int start = -radiusTexels + 8;
      int end = radiusTexels;

      for (int x0i = start; x0i <= end; x0i += 8) {
         for (int z0i = start; z0i <= end; z0i += 8) {
            float x0 = (float)x0i;
            float x1 = x0 + 8.0F;
            float z0 = (float)z0i;
            float z1 = z0 + 8.0F;
            float u0 = ((float)centerCellX + x0) * 0.00390625F;
            float u1 = ((float)centerCellX + x1) * 0.00390625F;
            float v0 = ((float)centerCellZ + z0) * 0.00390625F;
            float v1 = ((float)centerCellZ + z1) * 0.00390625F;
            if (drawBottom) {
               quad(builder, x0, yBase, z1, u0, v1, x1, yBase, z1, u1, v1, x1, yBase, z0, u1, v0, x0, yBase, z0, u0, v0, 0.7F, 0.0F, -1.0F, 0.0F);
            }

            if (drawTop) {
               float yTop = yBase + thickness - 9.765625E-4F;
               quad(builder, x0, yTop, z1, u0, v1, x1, yTop, z1, u1, v1, x1, yTop, z0, u1, v0, x0, yTop, z0, u0, v0, 1.0F, 0.0F, 1.0F, 0.0F);
            }

            if (x0i >= 0) {
               for (int px = 0; px < 8; px++) {
                  float x = x0 + (float)px;
                  float u = ((float)centerCellX + x + 0.5F) * 0.00390625F;
                  quad(
                     builder,
                     x,
                     yBase,
                     z1,
                     u,
                     v1,
                     x,
                     yBase + thickness,
                     z1,
                     u,
                     v1,
                     x,
                     yBase + thickness,
                     z0,
                     u,
                     v0,
                     x,
                     yBase,
                     z0,
                     u,
                     v0,
                     0.9F,
                     -1.0F,
                     0.0F,
                     0.0F
                  );
               }
            }

            if (x0i <= 8) {
               for (int px = 0; px < 8; px++) {
                  float x = x0 + (float)px + 1.0F - 9.765625E-4F;
                  float u = ((float)centerCellX + x0 + (float)px + 0.5F) * 0.00390625F;
                  quad(
                     builder,
                     x,
                     yBase,
                     z1,
                     u,
                     v1,
                     x,
                     yBase + thickness,
                     z1,
                     u,
                     v1,
                     x,
                     yBase + thickness,
                     z0,
                     u,
                     v0,
                     x,
                     yBase,
                     z0,
                     u,
                     v0,
                     0.9F,
                     1.0F,
                     0.0F,
                     0.0F
                  );
               }
            }

            if (z0i >= 0) {
               for (int pz = 0; pz < 8; pz++) {
                  float z = z0 + (float)pz;
                  float v = ((float)centerCellZ + z + 0.5F) * 0.00390625F;
                  quad(
                     builder,
                     x0,
                     yBase + thickness,
                     z,
                     u0,
                     v,
                     x1,
                     yBase + thickness,
                     z,
                     u1,
                     v,
                     x1,
                     yBase,
                     z,
                     u1,
                     v,
                     x0,
                     yBase,
                     z,
                     u0,
                     v,
                     0.8F,
                     0.0F,
                     0.0F,
                     -1.0F
                  );
               }
            }

            if (z0i <= 8) {
               for (int pz = 0; pz < 8; pz++) {
                  float z = z0 + (float)pz + 1.0F - 9.765625E-4F;
                  float v = ((float)centerCellZ + z0 + (float)pz + 0.5F) * 0.00390625F;
                  quad(
                     builder,
                     x0,
                     yBase + thickness,
                     z,
                     u0,
                     v,
                     x1,
                     yBase + thickness,
                     z,
                     u1,
                     v,
                     x1,
                     yBase,
                     z,
                     u1,
                     v,
                     x0,
                     yBase,
                     z,
                     u0,
                     v,
                     0.8F,
                     0.0F,
                     0.0F,
                     1.0F
                  );
               }
            }
         }
      }
   }

   private static void buildFast(BufferBuilder builder, int centerCellX, int centerCellZ, float yBase, int radiusTexels) {
      int tile = 32;
      int radius = Math.max(32, roundUp(radiusTexels, 32));

      for (int x0i = -radius; x0i < radius; x0i += 32) {
         for (int z0i = -radius; z0i < radius; z0i += 32) {
            float x0 = (float)x0i;
            float x1 = x0 + 32.0F;
            float z0 = (float)z0i;
            float z1 = z0 + 32.0F;
            float u0 = ((float)centerCellX + x0) * 0.00390625F;
            float u1 = ((float)centerCellX + x1) * 0.00390625F;
            float v0 = ((float)centerCellZ + z0) * 0.00390625F;
            float v1 = ((float)centerCellZ + z1) * 0.00390625F;
            quad(builder, x0, yBase, z1, u0, v1, x1, yBase, z1, u1, v1, x1, yBase, z0, u1, v0, x0, yBase, z0, u0, v0, 1.0F, 0.0F, -1.0F, 0.0F);
         }
      }
   }

   private static void quad(
      BufferBuilder builder,
      float x0,
      float y0,
      float z0,
      float u0,
      float v0,
      float x1,
      float y1,
      float z1,
      float u1,
      float v1,
      float x2,
      float y2,
      float z2,
      float u2,
      float v2,
      float x3,
      float y3,
      float z3,
      float u3,
      float v3,
      float shade,
      float nx,
      float ny,
      float nz
   ) {
      builder.vertex((double)x0, (double)y0, (double)z0).uv(u0, v0).color(shade, shade, shade, 1.0F).normal(nx, ny, nz).endVertex();
      builder.vertex((double)x1, (double)y1, (double)z1).uv(u1, v1).color(shade, shade, shade, 1.0F).normal(nx, ny, nz).endVertex();
      builder.vertex((double)x2, (double)y2, (double)z2).uv(u2, v2).color(shade, shade, shade, 1.0F).normal(nx, ny, nz).endVertex();
      builder.vertex((double)x3, (double)y3, (double)z3).uv(u3, v3).color(shade, shade, shade, 1.0F).normal(nx, ny, nz).endVertex();
   }

   private ResourceLocation textureForLayer(int index, LuxiumCloudRenderer.LayerConfig config, float weather) {
      if (this.sourceLoaded && this.sourceOpaque != null) {
         double effectiveCoverage = config.coverage + (100.0 - config.coverage) * (config.weatherInfluence / 100.0) * (double)weather;
         effectiveCoverage = Mth.clamp(effectiveCoverage, 0.0, 100.0);
         long key = 17L;
         key = mix(key, effectiveCoverage);
         key = mix(key, config.edgeSoftness);
         key = mix(key, config.variation);
         key = 31L * key + (long)index;
         LuxiumCloudRenderer.LayerState state = this.layers[index];
         if (state.textureKey != key || state.textureLocation == null || state.dynamicTexture == null) {
            NativeImage image = this.buildCoverageTexture(index, effectiveCoverage, config.edgeSoftness, config.variation);
            Minecraft mc = Minecraft.getInstance();
            if (state.dynamicTexture == null) {
               state.dynamicTexture = new DynamicTexture(image);
               state.textureLocation = mc.getTextureManager().register("luxium_cloud_layer_" + (index + 1), state.dynamicTexture);
            } else {
               state.dynamicTexture.setPixels(image);
               state.dynamicTexture.upload();
            }

            state.textureKey = key;
         }

         return state.textureLocation;
      } else {
         return VANILLA_CLOUDS;
      }
   }

   private NativeImage buildCoverageTexture(int layerIndex, double coverage, double softness, double variation) {
      NativeImage output = new NativeImage(256, 256, false);
      double centered = (coverage - 50.0) / 50.0;
      double coverageShift = centered < 0.0 ? centered * 2.5 : centered * 5.5;
      double variationAmplitude = variation / 100.0 * 2.0;
      double feather = Math.max(0.0, softness);
      int seedX = PATTERN_OFFSET_X[layerIndex];
      int seedZ = PATTERN_OFFSET_Z[layerIndex];

      for (int y = 0; y < 256; y++) {
         for (int x = 0; x < 256; x++) {
            int index = y * 256 + x;
            double signedDistance = this.sourceOpaque[index] ? -((double)this.distanceToClear[index] - 0.5) : (double)this.distanceToOpaque[index] - 0.5;
            double periodic = 0.5 * Math.sin((double)(x + seedX) * 0.09817477042468103)
               + 0.3 * Math.cos((double)(y + seedZ) * (Math.PI / 16))
               + 0.2 * Math.sin((double)(x + y + seedX - seedZ) * (Math.PI / 8));
            double dither = (hash01(x, y, layerIndex) - 0.5) * 0.8;
            double adjustedDistance = signedDistance + periodic * variationAmplitude + dither;
            double edge = coverageShift - adjustedDistance;
            int alpha;
            if (feather <= 1.0E-5) {
               alpha = edge >= 0.0 ? 255 : 0;
            } else {
               double t = Mth.clamp(edge / (feather * 2.0) + 0.5, 0.0, 1.0);
               t = t * t * (3.0 - 2.0 * t);
               alpha = Mth.clamp((int)Math.round(t * 255.0), 0, 255);
            }

            int basePixel;
            if (this.sourcePixels == null) {
               basePixel = ABGR32.color(255, 255, 255, 255);
            } else if (this.sourceOpaque[index]) {
               basePixel = this.sourcePixels[index];
            } else {
               int nearest = this.nearestOpaquePixel != null ? this.nearestOpaquePixel[index] : -1;
               basePixel = nearest >= 0 ? this.sourcePixels[nearest] : ABGR32.color(255, 255, 255, 255);
            }

            output.setPixelRGBA(x, y, ABGR32.color(alpha, basePixel));
         }
      }

      return output;
   }

   private void ensureSourceTexture() {
      if (!this.sourceLoaded && !this.sourceLoadFailed) {
         Minecraft mc = Minecraft.getInstance();
         Optional<Resource> resource = mc.getResourceManager().getResource(VANILLA_CLOUDS);
         if (resource.isEmpty()) {
            LOGGER.warn("Luxium custom clouds could not find {}. Falling back to the vanilla texture without coverage shaping.", VANILLA_CLOUDS);
            this.sourceLoadFailed = true;
         } else {
            try {
               try (InputStream stream = resource.get().open()) {
                  NativeImage image = NativeImage.read(stream);

                  label132: {
                     try {
                        if (image.getWidth() != 256 || image.getHeight() != 256) {
                           LOGGER.warn(
                              "Luxium custom clouds expected a 256x256 cloud texture but resource pack supplied {}x{}. Coverage shaping is disabled for this pack.",
                              image.getWidth(),
                              image.getHeight()
                           );
                           this.sourceLoadFailed = true;
                           break label132;
                        }

                        this.sourceOpaque = new boolean[65536];
                        this.sourcePixels = new int[65536];

                        for (int y = 0; y < 256; y++) {
                           for (int x = 0; x < 256; x++) {
                              int index = y * 256 + x;
                              int pixel = image.getPixelRGBA(x, y);
                              this.sourcePixels[index] = pixel;
                              this.sourceOpaque[index] = ABGR32.alpha(pixel) >= 128;
                           }
                        }

                        this.distanceToOpaque = distanceTransform(this.sourceOpaque, true);
                        this.distanceToClear = distanceTransform(this.sourceOpaque, false);
                        this.nearestOpaquePixel = nearestOpaqueSource(this.sourceOpaque);
                        this.sourceLoaded = true;
                     } catch (Throwable var11) {
                        if (image != null) {
                           try {
                              image.close();
                           } catch (Throwable var10) {
                              var11.addSuppressed(var10);
                           }
                        }

                        throw var11;
                     }

                     if (image != null) {
                        image.close();
                     }

                     return;
                  }

                  if (image != null) {
                     image.close();
                  }
               }
            } catch (RuntimeException | IOException var13) {
               LOGGER.warn("Luxium custom clouds failed to read the active vanilla cloud texture; using the original texture without coverage shaping.", var13);
               this.sourceLoadFailed = true;
            }
         }
      }
   }

   private static int[] nearestOpaqueSource(boolean[] opaque) {
      int count = 65536;
      int[] nearest = new int[count];
      Arrays.fill(nearest, -1);
      int[] queue = new int[count];
      int head = 0;
      int tail = 0;

      for (int i = 0; i < count; i++) {
         if (opaque[i]) {
            nearest[i] = i;
            queue[tail++] = i;
         }
      }

      while (head < tail) {
         int index = queue[head++];
         int x = index & 0xFF;
         int y = index >>> 8;
         int source = nearest[index];

         for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
               if (dx != 0 || dy != 0) {
                  int nx = x + dx & 0xFF;
                  int ny = y + dy & 0xFF;
                  int next = ny << 8 | nx;
                  if (nearest[next] == -1) {
                     nearest[next] = source;
                     queue[tail++] = next;
                  }
               }
            }
         }
      }

      return nearest;
   }

   private static int[] distanceTransform(boolean[] opaque, boolean seedOpaque) {
      int count = 65536;
      int[] distance = new int[count];
      Arrays.fill(distance, Integer.MAX_VALUE);
      int[] queue = new int[count];
      int head = 0;
      int tail = 0;

      for (int i = 0; i < count; i++) {
         if (opaque[i] == seedOpaque) {
            distance[i] = 0;
            queue[tail++] = i;
         }
      }

      while (head < tail) {
         int index = queue[head++];
         int x = index & 0xFF;
         int y = index >>> 8;
         int nextDistance = distance[index] + 1;

         for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
               if (dx != 0 || dy != 0) {
                  int nx = x + dx & 0xFF;
                  int ny = y + dy & 0xFF;
                  int next = ny << 8 | nx;
                  if (distance[next] == Integer.MAX_VALUE) {
                     distance[next] = nextDistance;
                     queue[tail++] = next;
                  }
               }
            }
         }
      }

      return distance;
   }

   private static double hash01(int x, int y, int layer) {
      int h = x * 522133279 ^ y * 73244475 ^ (layer + 1) * 668265261;
      h ^= h >>> 16;
      h *= 2146121005;
      h ^= h >>> 15;
      h *= -2073254261;
      h ^= h >>> 16;
      return (double)(h & 2147483647) / 2.147483647E9;
   }

   private static int layerRadiusTexels(LuxiumCloudRenderer.LayerConfig layer) {
      float blockScale = (float)(12.0 * layer.scale);
      if (blockScale <= 0.0F) {
         return 16;
      } else {
         int radiusTexels = Mth.ceil((float)layer.renderDistance / blockScale);
         return Math.max(16, Math.min(128, roundUp(radiusTexels, 8)));
      }
   }

   private static float layerGeometryRadiusBlocks(LuxiumCloudRenderer.LayerConfig layer, CloudStatus cloudStatus) {
      float blockScale = (float)(12.0 * layer.scale);
      int radiusTexels = layerRadiusTexels(layer);
      int meshRadiusTexels;
      if (cloudStatus == CloudStatus.FANCY) {
         meshRadiusTexels = radiusTexels + 8;
      } else {
         int fastTile = 32;
         meshRadiusTexels = Math.max(32, roundUp(radiusTexels, 32));
      }

      return Math.max(1.0F, (float)meshRadiusTexels * blockScale);
   }

   private static float layerVerticalReach(LuxiumCloudRenderer.LayerConfig layer, CloudStatus cloudStatus, double cameraY) {
      double bottom = layer.height + 0.33 - cameraY;
      double top = bottom;
      if (cloudStatus == CloudStatus.FANCY) {
         top = bottom + Math.max(0.5, layer.thickness);
      }

      return (float)Math.max(Math.abs(bottom), Math.abs(top));
   }

   private static float layerRequiredFar(LuxiumCloudRenderer.LayerConfig layer, CloudStatus cloudStatus, double cameraY) {
      double horizontal = (double)layerGeometryRadiusBlocks(layer, cloudStatus);
      double vertical = (double)layerVerticalReach(layer, cloudStatus, cameraY);
      double cornerDistance = Math.sqrt(horizontal * horizontal * 2.0 + vertical * vertical);
      return (float)Math.min(16384.0, cornerDistance + 48.0);
   }

   private static Matrix4f cloudProjectionMatrix(Matrix4f projectionMatrix, LuxiumCloudRenderer.LayerConfig layer, CloudStatus cloudStatus, double cameraY) {
      return extendPerspectiveFar(projectionMatrix, layerRequiredFar(layer, cloudStatus, cameraY));
   }

   private static Matrix4f extendPerspectiveFar(Matrix4f source, float requestedFar) {
      if (source != null
         && Float.isFinite(requestedFar)
         && !(requestedFar <= 0.0F)
         && !(Math.abs(source.m23() + 1.0F) > 0.001F)
         && !(Math.abs(source.m33()) > 0.001F)) {
         float m22 = source.m22();
         float m32 = source.m32();
         float nearDenominator = m22 - 1.0F;
         float farDenominator = m22 + 1.0F;
         if (!(Math.abs(nearDenominator) < 1.0E-6F) && !(Math.abs(farDenominator) < 1.0E-6F)) {
            float near = m32 / nearDenominator;
            float currentFar = m32 / farDenominator;
            if (Float.isFinite(near) && Float.isFinite(currentFar) && !(near <= 0.0F) && !(currentFar <= near) && !(requestedFar <= currentFar + 1.0F)) {
               Matrix4f extended = new Matrix4f(source);
               float invDepth = 1.0F / (requestedFar - near);
               extended.m22(-(requestedFar + near) * invDepth);
               extended.m32(-(2.0F * requestedFar * near) * invDepth);
               return extended;
            } else {
               return source;
            }
         } else {
            return source;
         }
      } else {
         return source;
      }
   }

   private static boolean isOrdinaryTerrainDistanceFog(float fogEnd, FogShape fogShape) {
      if (fogShape != FogShape.CYLINDER) {
         return false;
      } else {
         Minecraft mc = Minecraft.getInstance();
         float vanillaTerrainFogEnd = Math.max(mc.gameRenderer.getRenderDistance(), 32.0F);
         return Float.isFinite(fogEnd) && Math.abs(fogEnd - vanillaTerrainFogEnd) <= 0.75F;
      }
   }

   private static void applyLayerDistanceFog(LuxiumCloudRenderer.LayerConfig layer, CloudStatus cloudStatus, double cameraY) {
      float horizontalRadius = layerGeometryRadiusBlocks(layer, cloudStatus);
      float vertical = layerVerticalReach(layer, cloudStatus, cameraY);
      float fadeWidthHorizontal = Mth.clamp(horizontalRadius * 0.125F, 24.0F, 96.0F);
      float startHorizontal = Math.max(0.0F, horizontalRadius - fadeWidthHorizontal);
      float fogStart = (float)Math.sqrt((double)startHorizontal * (double)startHorizontal + (double)vertical * (double)vertical);
      float fogEnd = (float)Math.sqrt((double)horizontalRadius * (double)horizontalRadius + (double)vertical * (double)vertical);
      if (fogEnd <= fogStart + 1.0F) {
         fogEnd = fogStart + 1.0F;
      }

      RenderSystem.setShaderFogStart(fogStart);
      RenderSystem.setShaderFogEnd(fogEnd);
      RenderSystem.setShaderFogShape(FogShape.SPHERE);
   }

   private static int roundUp(int value, int multiple) {
      return (Math.max(1, value) + multiple - 1) / multiple * multiple;
   }

   private static long geometryKey(int x, int z, int y, int radius, CloudStatus status, double thickness) {
      long key = 1469598103934665603L;
      key = (key ^ (long)x) * 1099511628211L;
      key = (key ^ (long)z) * 1099511628211L;
      key = (key ^ (long)y) * 1099511628211L;
      key = (key ^ (long)radius) * 1099511628211L;
      key = (key ^ (long)status.ordinal()) * 1099511628211L;
      return (key ^ Double.doubleToLongBits(thickness)) * 1099511628211L;
   }

   private static long mix(long key, double value) {
      return 31L * key + Double.doubleToLongBits(value);
   }

   private void ensureOcclusionTarget(int width, int height) {
      if (this.occlusionTarget == null) {
         this.occlusionTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
         this.occlusionTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         this.occlusionTarget.setFilterMode(9729);
      } else if (this.occlusionTarget.width != width || this.occlusionTarget.height != height) {
         this.occlusionTarget.resize(width, height, Minecraft.ON_OSX);
         this.occlusionTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         this.occlusionTarget.setFilterMode(9729);
      }
   }

   private static LuxiumCloudRenderer.LayerConfig[] readConfig() {
      Config.Client c = Config.CLIENT;
      return new LuxiumCloudRenderer.LayerConfig[]{
         new LuxiumCloudRenderer.LayerConfig(
            Config.isFeatureEnabled(c.cloudLayer1Enabled),
            (Double)c.cloudLayer1Height.get(),
            (Double)c.cloudLayer1Speed.get(),
            (Double)c.cloudLayer1Direction.get(),
            (Double)c.cloudLayer1Scale.get(),
            (Double)c.cloudLayer1Opacity.get(),
            (Double)c.cloudLayer1Thickness.get(),
            (Double)c.cloudLayer1Brightness.get(),
            (Double)c.cloudLayer1Coverage.get(),
            (Double)c.cloudLayer1EdgeSoftness.get(),
            (Double)c.cloudLayer1Variation.get(),
            (Double)c.cloudLayer1WeatherInfluence.get(),
            (Double)c.cloudLayer1StormDarkening.get(),
            (Integer)c.cloudLayer1RenderDistance.get()
         ),
         new LuxiumCloudRenderer.LayerConfig(
            Config.isFeatureEnabled(c.cloudLayer2Enabled),
            (Double)c.cloudLayer2Height.get(),
            (Double)c.cloudLayer2Speed.get(),
            (Double)c.cloudLayer2Direction.get(),
            (Double)c.cloudLayer2Scale.get(),
            (Double)c.cloudLayer2Opacity.get(),
            (Double)c.cloudLayer2Thickness.get(),
            (Double)c.cloudLayer2Brightness.get(),
            (Double)c.cloudLayer2Coverage.get(),
            (Double)c.cloudLayer2EdgeSoftness.get(),
            (Double)c.cloudLayer2Variation.get(),
            (Double)c.cloudLayer2WeatherInfluence.get(),
            (Double)c.cloudLayer2StormDarkening.get(),
            (Integer)c.cloudLayer2RenderDistance.get()
         ),
         new LuxiumCloudRenderer.LayerConfig(
            Config.isFeatureEnabled(c.cloudLayer3Enabled),
            (Double)c.cloudLayer3Height.get(),
            (Double)c.cloudLayer3Speed.get(),
            (Double)c.cloudLayer3Direction.get(),
            (Double)c.cloudLayer3Scale.get(),
            (Double)c.cloudLayer3Opacity.get(),
            (Double)c.cloudLayer3Thickness.get(),
            (Double)c.cloudLayer3Brightness.get(),
            (Double)c.cloudLayer3Coverage.get(),
            (Double)c.cloudLayer3EdgeSoftness.get(),
            (Double)c.cloudLayer3Variation.get(),
            (Double)c.cloudLayer3WeatherInfluence.get(),
            (Double)c.cloudLayer3StormDarkening.get(),
            (Integer)c.cloudLayer3RenderDistance.get()
         )
      };
   }

   private static record LayerConfig(
      boolean enabled,
      double height,
      double speed,
      double direction,
      double scale,
      double opacity,
      double thickness,
      double brightness,
      double coverage,
      double edgeSoftness,
      double variation,
      double weatherInfluence,
      double stormDarkening,
      int renderDistance
   ) {
   }

   private static record LayerFrame(float blockScale, float fractionX, float fractionY, float fractionZ, ResourceLocation texture) {
   }

   private static final class LayerState {
      final int index;
      VertexBuffer vertexBuffer;
      long geometryKey = Long.MIN_VALUE;
      VertexBuffer maskVertexBuffer;
      long maskGeometryKey = Long.MIN_VALUE;
      VertexBuffer shadowVertexBuffer;
      DynamicTexture dynamicTexture;
      ResourceLocation textureLocation;
      long textureKey = Long.MIN_VALUE;

      LayerState(int index) {
         this.index = index;
      }
   }

   private static record MaskLayerFrame(float blockScale, float fractionX, float relativeY, float fractionZ, ResourceLocation texture) {
   }

   private static record ShadowPlane(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, double maxFront) {
   }
}
