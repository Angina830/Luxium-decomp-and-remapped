package com.vinlanx.luxium.client.wet;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
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
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestiaLighting;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import com.vinlanx.luxium.client.ssr.ScreenSpaceReflectionSystem;
import com.vinlanx.luxium.client.ssr.SsrConsumer;
import com.vinlanx.luxium.client.ssr.SsrSettings;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.FORGE
)
public final class WetSurfaceRenderer {
   private static final ResourceLocation MICRO_NORMAL = ResourceLocation.fromNamespaceAndPath("luxium", "textures/effects/water_micro_normal.png");
   private static TextureTarget terrainDepthTarget;
   private static boolean terrainDepthReady;
   private static final IntBuffer SAVED_VIEWPORT = BufferUtils.createIntBuffer(4);
   private static final IntBuffer SAVED_SCISSOR = BufferUtils.createIntBuffer(4);
   private static final Matrix4f VIEW_PROJ = new Matrix4f();
   private static final Matrix4f INV_VIEW_PROJ = new Matrix4f();

   private WetSurfaceRenderer() {
   }

   @SubscribeEvent(
      priority = EventPriority.HIGHEST
   )
   public static void captureSolidTerrainDepth(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_SOLID_BLOCKS) {
         if (Config.isFeatureEnabled(Config.CLIENT.wetEnabled)) {
            if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
               terrainDepthReady = false;
               Minecraft mc = Minecraft.getInstance();
               if (mc.level != null) {
                  RenderTarget main = mc.getMainRenderTarget();
                  if (main != null && main.width > 0 && main.height > 0 && main.getDepthTextureId() > 0) {
                     ensureTerrainDepthTarget(main);
                     terrainDepthTarget.copyDepthFrom(main);
                     terrainDepthReady = terrainDepthTarget.getDepthTextureId() > 0;
                     main.bindWrite(true);
                  }
               }
            }
         }
      }
   }

   @SubscribeEvent(
      priority = EventPriority.HIGHEST
   )
   public static void renderWetSurfaces(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_TRANSLUCENT_BLOCKS) {
         if (Config.isFeatureEnabled(Config.CLIENT.wetEnabled) && terrainDepthReady) {
            if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
               Minecraft mc = Minecraft.getInstance();
               if (mc.level != null && mc.player != null) {
                  ShaderInstance shader = ShaderManager.getWetSurfaceShader();
                  if (shader != null && terrainDepthTarget != null) {
                     RenderTarget main = mc.getMainRenderTarget();
                     if (main != null && main.width > 0 && main.height > 0 && main.getColorTextureId() > 0 && main.getDepthTextureId() > 0) {
                        SharedPostResources.captureColor(main);
                        SharedPostResources.captureDepth(main);
                        int sceneColor = SharedPostResources.getColorTextureId();
                        int sceneDepth = SharedPostResources.getDepthTextureId();
                        int terrainDepth = terrainDepthTarget.getDepthTextureId();
                        if (sceneColor > 0 && sceneDepth > 0 && terrainDepth > 0) {
                           mc.getTextureManager().bindForSetup(MICRO_NORMAL);
                           AbstractTexture microTexture = mc.getTextureManager().getTexture(MICRO_NORMAL);
                           int microNormal = microTexture.getId();
                           if (microNormal <= 0) {
                              main.bindWrite(true);
                           } else {
                              Vec3 camera = event.getCamera().getPosition();
                              Matrix4f viewRotation = event.getPoseStack().last().pose();
                              Matrix4f viewProj = NeoSkyFrameCache.copyViewProjection(event.getProjectionMatrix(), viewRotation, VIEW_PROJ);
                              Matrix4f invViewProj = NeoSkyFrameCache.copyInverseViewProjection(event.getProjectionMatrix(), viewRotation, INV_VIEW_PROJ);
                              SsrSettings ssr = ScreenSpaceReflectionSystem.settings(SsrConsumer.WET);
                              NeoSkyCelestiaLighting.State lighting = NeoSkyFrameCache.lighting(mc.level, event.getPartialTick());
                              boolean hasSkyEnvironment = mc.level.dimensionType().hasSkyLight();
                              float time = ((float)mc.level.getGameTime() + event.getPartialTick()) / 20.0F % 4096.0F;
                              renderFullscreen(
                                 shader,
                                 main,
                                 sceneColor,
                                 sceneDepth,
                                 terrainDepth,
                                 microNormal,
                                 viewProj,
                                 invViewProj,
                                 camera,
                                 time,
                                 ssr,
                                 lighting,
                                 hasSkyEnvironment
                              );
                           }
                        } else {
                           main.bindWrite(true);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void ensureTerrainDepthTarget(RenderTarget main) {
      if (terrainDepthTarget == null) {
         terrainDepthTarget = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
         terrainDepthTarget.setFilterMode(9728);
      } else if (terrainDepthTarget.width != main.width || terrainDepthTarget.height != main.height) {
         terrainDepthTarget.resize(main.width, main.height, Minecraft.ON_OSX);
         terrainDepthTarget.setFilterMode(9728);
      }
   }

   private static void renderFullscreen(
      ShaderInstance shader,
      RenderTarget main,
      int sceneColor,
      int sceneDepth,
      int terrainDepth,
      int microNormal,
      Matrix4f viewProj,
      Matrix4f invViewProj,
      Vec3 camera,
      float time,
      SsrSettings ssr,
      NeoSkyCelestiaLighting.State lighting,
      boolean hasSkyEnvironment
   ) {
      float[] savedShaderColor = RenderSystem.getShaderColor();
      Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
      VertexSorting previousSorting = RenderSystem.getVertexSorting();
      SAVED_VIEWPORT.clear();
      SAVED_SCISSOR.clear();
      GL11.glGetIntegerv(2978, SAVED_VIEWPORT);
      GL11.glGetIntegerv(3088, SAVED_SCISSOR);
      boolean previousScissorEnabled = GL11.glIsEnabled(3089);
      int passWidth = Math.max(1, main.width);
      int passHeight = Math.max(1, main.height);
      Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float)passWidth, (float)passHeight, 0.0F, 1000.0F, 3000.0F);
      PoseStack modelViewStack = RenderSystem.getModelViewStack();
      modelViewStack.pushPose();
      modelViewStack.setIdentity();
      modelViewStack.translate(0.0F, 0.0F, -2000.0F);
      RenderSystem.applyModelViewMatrix();

      try {
         main.bindWrite(true);
         RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
         RenderSystem.disableScissor();
         RenderSystem.viewport(0, 0, passWidth, passHeight);
         if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
         }

         if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(ortho);
         }

         shader.setSampler("SceneSampler", sceneColor);
         shader.setSampler("DepthSampler", sceneDepth);
         shader.setSampler("TerrainDepthSampler", terrainDepth);
         shader.setSampler("MicroNormalSampler", microNormal);
         setMat4(shader, "ViewProj", viewProj);
         setMat4(shader, "InvViewProj", invViewProj);
         set3f(shader, "CameraPos", (float)camera.x, (float)camera.y, (float)camera.z);
         set2f(shader, "ScreenSize", (float)main.width, (float)main.height);
         set1f(shader, "Time", time);
         set1f(shader, "MaxVirtualDepth", ((Double)Config.CLIENT.wetMaxDepth.get()).floatValue());
         set1f(shader, "DepthCurve", ((Double)Config.CLIENT.wetDepthCurve.get()).floatValue());
         set1f(shader, "WetDarkening", ((Double)Config.CLIENT.wetDarkening.get()).floatValue());
         set1f(shader, "SurfaceOpacity", ((Double)Config.CLIENT.wetSurfaceOpacity.get()).floatValue());
         set1f(shader, "EdgeSoftness", ((Double)Config.CLIENT.wetEdgeSoftness.get()).floatValue());
         set1f(shader, "WaveStrength", ((Double)Config.CLIENT.wetWaveStrength.get()).floatValue());
         set1f(shader, "WaveScale", ((Double)Config.CLIENT.wetWaveScale.get()).floatValue());
         set1f(shader, "WaveSpeed", ((Double)Config.CLIENT.wetWaveSpeed.get()).floatValue());
         set1f(shader, "RippleStrength", ((Double)Config.CLIENT.wetRippleStrength.get()).floatValue());
         set1f(shader, "RefractionStrength", ((Double)Config.CLIENT.wetRefractionStrength.get()).floatValue());
         Vector3f lightDirection = lighting.direction();
         Vector3f lightColor = lighting.directColor();
         Vector3f skyColor = lighting.skyAmbientColor();
         Vector3f groundColor = lighting.groundAmbientColor();
         set3f(shader, "CelestialDirection", lightDirection.x, lightDirection.y, lightDirection.z);
         set3f(shader, "CelestialColor", lightColor.x, lightColor.y, lightColor.z);
         set3f(shader, "SkyAmbientColor", skyColor.x, skyColor.y, skyColor.z);
         set3f(shader, "GroundAmbientColor", groundColor.x, groundColor.y, groundColor.z);
         set1f(shader, "CelestialStrength", hasSkyEnvironment ? lighting.directStrength() : 0.0F);
         set1f(shader, "AmbientStrength", hasSkyEnvironment ? lighting.ambientStrength() : 0.0F);
         set1f(shader, "EnvironmentReflectionStrength", hasSkyEnvironment ? ((Double)Config.CLIENT.wetEnvironmentReflectionStrength.get()).floatValue() : 0.0F);
         set1f(shader, "CelestialSpecularStrength", ((Double)Config.CLIENT.wetCelestialSpecularStrength.get()).floatValue());
         set1f(shader, "SheenFloor", ((Double)Config.CLIENT.wetSheenFloor.get()).floatValue());
         set1f(shader, "RippleHighlightStrength", ((Double)Config.CLIENT.wetRippleHighlightStrength.get()).floatValue());
         set1i(shader, "SsrEnabled", ssr.enabled() ? 1 : 0);
         set1i(shader, "SsrSteps", ssr.coarseSteps());
         set1i(shader, "SsrRefinementSteps", ssr.refinementSteps());
         set1f(shader, "SsrMaxDistance", ssr.maxDistance());
         set1f(shader, "SsrThickness", ssr.thickness());
         set1f(shader, "SsrEdgeFade", ssr.edgeFade());
         set1f(shader, "SsrStrength", ssr.strength());
         GlStateManager._disableDepthTest();
         GlStateManager._depthMask(false);
         GlStateManager._colorMask(true, true, true, true);
         RenderSystem.disableBlend();
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         shader.apply();
         Tesselator tessellator = RenderSystem.renderThreadTesselator();
         BufferBuilder buffer = tessellator.getBuilder();
         buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
         buffer.vertex(0.0, (double)passHeight, 0.0).uv(0.0F, 0.0F).endVertex();
         buffer.vertex((double)passWidth, (double)passHeight, 0.0).uv(1.0F, 0.0F).endVertex();
         buffer.vertex((double)passWidth, 0.0, 0.0).uv(1.0F, 1.0F).endVertex();
         buffer.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).endVertex();
         BufferUploader.draw(buffer.end());
      } finally {
         shader.clear();
         modelViewStack.popPose();
         RenderSystem.applyModelViewMatrix();
         RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
         RenderSystem.viewport(SAVED_VIEWPORT.get(0), SAVED_VIEWPORT.get(1), SAVED_VIEWPORT.get(2), SAVED_VIEWPORT.get(3));
         if (previousScissorEnabled) {
            GL11.glEnable(3089);
            GL11.glScissor(SAVED_SCISSOR.get(0), SAVED_SCISSOR.get(1), SAVED_SCISSOR.get(2), SAVED_SCISSOR.get(3));
         } else {
            GL11.glDisable(3089);
         }

         RenderSystem.setShaderColor(savedShaderColor[0], savedShaderColor[1], savedShaderColor[2], savedShaderColor[3]);
         GlStateManager._depthMask(true);
         GlStateManager._enableDepthTest();
         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
      }
   }

   private static void set1f(ShaderInstance shader, String name, float value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void set1i(ShaderInstance shader, String name, int value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }

   private static void set2f(ShaderInstance shader, String name, float x, float y) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y);
      }
   }

   private static void set3f(ShaderInstance shader, String name, float x, float y, float z) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(x, y, z);
      }
   }

   private static void setMat4(ShaderInstance shader, String name, Matrix4f value) {
      Uniform uniform = shader.getUniform(name);
      if (uniform != null) {
         uniform.set(value);
      }
   }
}
