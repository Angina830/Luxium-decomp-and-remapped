package com.vinlanx.luxium.client.rainpuddles;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.ShaderManager;
import com.vinlanx.luxium.client.SharedPostResources;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyFrameCache;
import com.vinlanx.luxium.client.ssr.ScreenSpaceReflectionSystem;
import com.vinlanx.luxium.client.ssr.SsrConsumer;
import com.vinlanx.luxium.client.ssr.SsrSettings;
import java.util.List;
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

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.FORGE
)
public final class RainPuddleRenderer {
   private static final ResourceLocation MICRO_NORMAL = ResourceLocation.fromNamespaceAndPath("luxium", "textures/effects/water_micro_normal.png");
   private static final Matrix4f VIEW_ROTATION = new Matrix4f();
   private static final Matrix4f VIEW_PROJ = new Matrix4f();
   private static final Matrix4f INV_VIEW_PROJ = new Matrix4f();

   private RainPuddleRenderer() {
   }

   @SubscribeEvent(
      priority = EventPriority.NORMAL
   )
   public static void onRenderLevel(RenderLevelStageEvent event) {
      if (Config.isEnabled()) {
         if (event.getStage() == Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
               Minecraft mc = Minecraft.getInstance();
               if (mc.level != null && mc.player != null) {
                  List<RainPuddleInstance> puddles = RainPuddleManager.get().snapshot(mc.level);
                  if (!puddles.isEmpty()) {
                     ShaderInstance shader = ShaderManager.getRainPuddleShader();
                     if (shader != null) {
                        RenderTarget main = mc.getMainRenderTarget();
                        if (main != null && main.width > 0 && main.height > 0 && main.getColorTextureId() > 0 && main.getDepthTextureId() > 0) {
                           SharedPostResources.captureColor(main);
                           SharedPostResources.captureDepth(main);
                           int sceneColor = SharedPostResources.getColorTextureId();
                           int sceneDepth = SharedPostResources.getDepthTextureId();
                           if (sceneColor > 0 && sceneDepth > 0) {
                              main.bindWrite(true);
                              Vec3 camera = event.getCamera().getPosition();
                              Matrix4f viewRotation = VIEW_ROTATION.set(event.getPoseStack().last().pose());
                              Matrix4f viewProj = NeoSkyFrameCache.copyViewProjection(event.getProjectionMatrix(), viewRotation, VIEW_PROJ);
                              Matrix4f invViewProj = NeoSkyFrameCache.copyInverseViewProjection(event.getProjectionMatrix(), viewRotation, INV_VIEW_PROJ);
                              mc.getTextureManager().bindForSetup(MICRO_NORMAL);
                              AbstractTexture microTexture = mc.getTextureManager().getTexture(MICRO_NORMAL);
                              int microNormal = microTexture.getId();
                              SsrSettings ssr = ScreenSpaceReflectionSystem.settings(SsrConsumer.PUDDLE);
                              RenderSystem.enableDepthTest();
                              RenderSystem.depthMask(false);
                              RenderSystem.disableCull();
                              RenderSystem.enableBlend();
                              RenderSystem.defaultBlendFunc();
                              RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                              RenderSystem.setShader(() -> shader);
                              float time = ((float)mc.level.getGameTime() + event.getPartialTick()) / 20.0F % 4096.0F;
                              setCommonUniforms(shader, viewProj, invViewProj, camera, main.width, main.height, time, ssr);
                              shader.setSampler("SceneSampler", sceneColor);
                              shader.setSampler("DepthSampler", sceneDepth);
                              shader.setSampler("MicroNormalSampler", microNormal);

                              try {
                                 for (RainPuddleInstance puddle : puddles) {
                                    mc.getTextureManager().bindForSetup(puddle.depthMap());
                                    AbstractTexture depthMap = mc.getTextureManager().getTexture(puddle.depthMap());
                                    int depthMapTexture = depthMap.getId();
                                    if (depthMapTexture > 0) {
                                       shader.setSampler("PuddleDepthSampler", depthMapTexture);
                                       drawPuddle(puddle, camera);
                                    }
                                 }
                              } finally {
                                 RenderSystem.depthMask(true);
                                 RenderSystem.enableCull();
                                 RenderSystem.enableBlend();
                                 RenderSystem.defaultBlendFunc();
                                 main.bindWrite(true);
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
   }

   private static void drawPuddle(RainPuddleInstance puddle, Vec3 camera) {
      BufferBuilder builder = Tesselator.getInstance().getBuilder();
      builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

      for (RainPuddlePatch p : puddle.patches()) {
         float x0 = (float)(p.minX() - camera.x);
         float x1 = (float)(p.maxX() - camera.x);
         float y = (float)(p.y() - camera.y);
         float z0 = (float)(p.minZ() - camera.z);
         float z1 = (float)(p.maxZ() - camera.z);
         builder.vertex((double)x0, (double)y, (double)z0).uv(p.u0(), p.v0()).endVertex();
         builder.vertex((double)x0, (double)y, (double)z1).uv(p.u0(), p.v1()).endVertex();
         builder.vertex((double)x1, (double)y, (double)z1).uv(p.u1(), p.v1()).endVertex();
         builder.vertex((double)x1, (double)y, (double)z0).uv(p.u1(), p.v0()).endVertex();
      }

      BufferUploader.drawWithShader(builder.end());
   }

   private static void setCommonUniforms(
      ShaderInstance shader, Matrix4f viewProj, Matrix4f invViewProj, Vec3 camera, int width, int height, float time, SsrSettings ssr
   ) {
      setMat4(shader, "ViewProj", viewProj);
      setMat4(shader, "InvViewProj", invViewProj);
      set3f(shader, "CameraPos", (float)camera.x, (float)camera.y, (float)camera.z);
      set2f(shader, "ScreenSize", (float)width, (float)height);
      set1f(shader, "Time", time);
      set1f(shader, "MaxVirtualDepth", ((Double)Config.CLIENT.puddleMaxDepth.get()).floatValue());
      set1f(shader, "DepthCurve", ((Double)Config.CLIENT.puddleDepthCurve.get()).floatValue());
      set1f(shader, "WetDarkening", ((Double)Config.CLIENT.puddleWetDarkening.get()).floatValue());
      set1f(shader, "SurfaceOpacity", ((Double)Config.CLIENT.puddleSurfaceOpacity.get()).floatValue());
      set1f(shader, "EdgeSoftness", ((Double)Config.CLIENT.puddleEdgeSoftness.get()).floatValue());
      set1f(shader, "WaveStrength", ((Double)Config.CLIENT.puddleWaveStrength.get()).floatValue());
      set1f(shader, "WaveScale", ((Double)Config.CLIENT.puddleWaveScale.get()).floatValue());
      set1f(shader, "WaveSpeed", ((Double)Config.CLIENT.puddleWaveSpeed.get()).floatValue());
      set1f(shader, "RippleStrength", ((Double)Config.CLIENT.puddleRippleStrength.get()).floatValue());
      set1f(shader, "RefractionStrength", ((Double)Config.CLIENT.puddleRefractionStrength.get()).floatValue());
      set1i(shader, "SsrEnabled", ssr.enabled() ? 1 : 0);
      set1i(shader, "SsrSteps", ssr.coarseSteps());
      set1i(shader, "SsrRefinementSteps", ssr.refinementSteps());
      set1f(shader, "SsrMaxDistance", ssr.maxDistance());
      set1f(shader, "SsrThickness", ssr.thickness());
      set1f(shader, "SsrEdgeFade", ssr.edgeFade());
      set1f(shader, "SsrStrength", ssr.strength());
   }

   private static void set1f(ShaderInstance shader, String name, float value) {
      Uniform u = shader.getUniform(name);
      if (u != null) {
         u.set(value);
      }
   }

   private static void set1i(ShaderInstance shader, String name, int value) {
      Uniform u = shader.getUniform(name);
      if (u != null) {
         u.set(value);
      }
   }

   private static void set2f(ShaderInstance shader, String name, float x, float y) {
      Uniform u = shader.getUniform(name);
      if (u != null) {
         u.set(x, y);
      }
   }

   private static void set3f(ShaderInstance shader, String name, float x, float y, float z) {
      Uniform u = shader.getUniform(name);
      if (u != null) {
         u.set(x, y, z);
      }
   }

   private static void setMat4(ShaderInstance shader, String name, Matrix4f value) {
      Uniform u = shader.getUniform(name);
      if (u != null) {
         u.set(value);
      }
   }
}
