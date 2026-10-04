package com.vinlanx.luxium.Testing.ExplosionTesting;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.ReflectionSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.FORGE
)
public final class ExplosionRenderer {
   private static final ResourceLocation[] DUST_SPRITES = new ResourceLocation[8];
   private static final TextureAtlasSprite[] CACHED_SPRITES = new TextureAtlasSprite[8];
   private static TextureAtlas cachedAtlas;
   private static long lastFrameNanos = -1L;

   private ExplosionRenderer() {
   }

   @SubscribeEvent(
      priority = EventPriority.LOW
   )
   public static void render(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_PARTICLES) {
         if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
               float dt = frameDelta();
               ExplosionWorld world = ExplosionWorld.get();
               world.requestStep(dt);
               ExplosionRenderSnapshot snapshot = world.snapshot();
               synchronized (snapshot) {
                  if (snapshot.totalCount != 0) {
                     ShaderInstance shader = ExplosionShader.get();
                     if (shader != null) {
                        Vec3 camera = event.getCamera().getPosition();
                        Vector3f up = event.getCamera().getUpVector();
                        Vector3f right = new Vector3f(event.getCamera().getLeftVector()).negate();
                        PoseStack poseStack = event.getPoseStack();
                        poseStack.pushPose();
                        poseStack.translate(-camera.x, -camera.y, -camera.z);
                        Matrix4f pose = poseStack.last().pose();
                        int savedTexture0 = RenderSystem.getShaderTexture(0);
                        float[] savedColor = RenderSystem.getShaderColor();
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthMask(false);
                        RenderSystem.disableCull();
                        RenderSystem.enableBlend();
                        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);

                        try {
                           drawBatch(mc, shader, snapshot, 0, snapshot.smokeCount, pose, right, up, false);
                           drawBatch(mc, shader, snapshot, snapshot.smokeCount, snapshot.totalCount, pose, right, up, true);
                        } finally {
                           shader.clear();
                           RenderSystem.defaultBlendFunc();
                           RenderSystem.disableBlend();
                           RenderSystem.enableCull();
                           RenderSystem.depthMask(true);
                           RenderSystem.depthFunc(515);
                           RenderSystem.setShaderColor(savedColor[0], savedColor[1], savedColor[2], savedColor[3]);
                           RenderSystem.setShaderTexture(0, savedTexture0);
                           RenderSystem.activeTexture(33984);
                           poseStack.popPose();
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void drawBatch(
      Minecraft mc, ShaderInstance shader, ExplosionRenderSnapshot snapshot, int start, int end, Matrix4f pose, Vector3f right, Vector3f up, boolean emissive
   ) {
      if (start < end) {
         if (shader.getUniform("EmissivePass") != null) {
            shader.getUniform("EmissivePass").set(emissive ? 1.0F : 0.0F);
         }

         if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
         }

         if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
         }

         TextureAtlas particleAtlas = (TextureAtlas)mc.getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
         cacheSprites(particleAtlas);
         shader.setSampler("Sampler0", particleAtlas);
         shader.apply();
         GlStateManager._blendFunc(770, emissive ? 1 : 771);
         BufferBuilder buffer = Tesselator.getInstance().getBuilder();
         buffer.begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

         for (int index = start; index < end; index++) {
            TextureAtlasSprite sprite = CACHED_SPRITES[snapshot.frame[index] & 7];
            float half = snapshot.size[index] * 0.5F;
            float rx = right.x * half;
            float ry = right.y * half;
            float rz = right.z * half;
            float ux = up.x * half;
            float uy = up.y * half;
            float uz = up.z * half;
            float hdrScale = Math.max(1.0F, Math.max(snapshot.r[index], Math.max(snapshot.g[index], snapshot.b[index])));
            int red = Math.round(MthClamp(snapshot.r[index] / hdrScale) * 255.0F);
            int green = Math.round(MthClamp(snapshot.g[index] / hdrScale) * 255.0F);
            int blue = Math.round(MthClamp(snapshot.b[index] / hdrScale) * 255.0F);
            int alpha = Math.round(MthClamp(snapshot.alpha[index]) * 255.0F);
            int encodedHdr = Math.round(hdrScale * 1024.0F);
            float x = snapshot.x[index];
            float y = snapshot.y[index];
            float z = snapshot.z[index];
            vertex(buffer, pose, x - rx - ux, y - ry - uy, z - rz - uz, red, green, blue, alpha, sprite.getU0(), sprite.getV1(), encodedHdr);
            vertex(buffer, pose, x + rx - ux, y + ry - uy, z + rz - uz, red, green, blue, alpha, sprite.getU1(), sprite.getV1(), encodedHdr);
            vertex(buffer, pose, x + rx + ux, y + ry + uy, z + rz + uz, red, green, blue, alpha, sprite.getU1(), sprite.getV0(), encodedHdr);
            vertex(buffer, pose, x - rx + ux, y - ry + uy, z - rz + uz, red, green, blue, alpha, sprite.getU0(), sprite.getV0(), encodedHdr);
         }

         BufferUploader.draw(buffer.end());
         shader.clear();
      }
   }

   private static void vertex(BufferBuilder buffer, Matrix4f pose, float x, float y, float z, int r, int g, int b, int a, float u, float v, int encodedHdr) {
      buffer.vertex(pose, x, y, z).color(r, g, b, a).uv(u, v).uv2(0, encodedHdr).endVertex();
   }

   private static void cacheSprites(TextureAtlas atlas) {
      if (cachedAtlas != atlas || CACHED_SPRITES[0] == null) {
         cachedAtlas = atlas;

         for (int i = 0; i < CACHED_SPRITES.length; i++) {
            CACHED_SPRITES[i] = atlas.getSprite(DUST_SPRITES[i]);
         }
      }
   }

   private static float MthClamp(float value) {
      return Math.max(0.0F, Math.min(1.0F, value));
   }

   private static float frameDelta() {
      long now = System.nanoTime();
      if (lastFrameNanos < 0L) {
         lastFrameNanos = now;
         return 0.016666668F;
      } else {
         float dt = (float)(now - lastFrameNanos) / 1.0E9F;
         lastFrameNanos = now;
         return Math.max(0.0F, Math.min(dt, 0.05F));
      }
   }

   static {
      for (int i = 0; i < DUST_SPRITES.length; i++) {
         DUST_SPRITES[i] = ResourceLocation.fromNamespaceAndPath("minecraft", "generic_" + i);
      }
   }
}
