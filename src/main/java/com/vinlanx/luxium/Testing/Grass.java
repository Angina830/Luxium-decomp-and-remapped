package com.vinlanx.luxium.Testing;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.client.ReflectionSystem;
import com.vinlanx.luxium.client.ShaderManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT},
   bus = Bus.FORGE
)
public final class Grass {
   private static final int SIN_BITS = 12;
   private static final int SIN_MASK = 4095;
   private static final int SIN_SIZE = 4096;
   private static final float SIN_TO_IDX = 651.8986F;
   private static final float[] SIN_TABLE = new float[4096];
   public static final int BLADES_PER_BLOCK_MIN = 30;
   public static final int BLADES_PER_BLOCK_MAX = 45;
   private static final float BLADE_HEIGHT = 0.5F;
   private static final float TWO_PI = (float) (Math.PI * 2);
   private static final int COLOR_VARIATION = 12;
   private static final float WIND_DIR_DRIFT = 0.018F;
   private static final float WIND_DIR_OFFSET = 0.65F;
   private static final long ANIMATION_START_NANOS;
   private static final ResourceLocation TEXTURE;
   private static final int LIGHT_REFRESH_TICKS = 40;
   private static final ExecutorService ASYNC;
   private static final AtomicBoolean asyncBusy;
   private static final AtomicReference<RenderedBuffer> pendingUpload;
   private static volatile Grass.ScanData currentScan;
   private static VertexBuffer grassVbo;
   private static volatile boolean enabled;
   private static volatile int renderRadius;
   private static volatile boolean needsRescan;
   private static long lastLightRefreshTick;
   private static float windDirX;
   private static float windDirZ;

   private Grass() {
   }

   private static float fastSin(float angle) {
      return SIN_TABLE[(int)(angle * 651.8986F) & 4095];
   }

   public static void enable() {
      enabled = true;
      needsRescan = true;
   }

   public static void disable() {
      enabled = false;
      currentScan = null;
      pendingUpload.set(null);
      if (grassVbo != null) {
         grassVbo.close();
         grassVbo = null;
      }
   }

   public static boolean isEnabled() {
      return enabled;
   }

   public static void setRenderRadius(int r) {
      renderRadius = Math.max(1, Math.min(100, r));
      needsRescan = true;
   }

   public static int getRenderRadius() {
      return renderRadius;
   }

   @SubscribeEvent
   public static void onRenderLevel(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_PARTICLES) {
         if (enabled) {
            if (!ReflectionSystem.isRenderingWorldPass()) {
               ShaderInstance shader = ShaderManager.getGrassShader();
               if (shader != null) {
                  Minecraft mc = Minecraft.getInstance();
                  if (mc.level != null && mc.player != null) {
                     if (needsRescan) {
                        needsRescan = false;
                        scheduleAsyncScan(mc);
                     }

                     long gameTick = mc.level.getGameTime();
                     if (gameTick - lastLightRefreshTick >= 40L) {
                        lastLightRefreshTick = gameTick;
                        Grass.ScanData sd = currentScan;
                        if (sd != null) {
                           refreshLightInPlace(mc, sd);
                           scheduleAsyncBuild(sd);
                        }
                     }

                     RenderedBuffer ready = pendingUpload.getAndSet(null);
                     if (ready != null) {
                        if (grassVbo == null) {
                           grassVbo = new VertexBuffer(Usage.DYNAMIC);
                        }

                        grassVbo.bind();
                        grassVbo.upload(ready);
                        VertexBuffer.unbind();
                     }

                     if (grassVbo != null) {
                        float time = (float)(System.nanoTime() - ANIMATION_START_NANOS) * 1.0E-9F;
                        float windAngle = time * 0.018F + 0.65F;
                        windDirX = fastSin(windAngle + (float) (Math.PI / 2));
                        windDirZ = fastSin(windAngle);
                        Vec3 cam = event.getCamera().getPosition();
                        setGrassUniforms(shader, time, cam);
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthMask(true);
                        RenderSystem.disableCull();
                        RenderSystem.setShaderTexture(0, TEXTURE);
                        mc.gameRenderer.lightTexture().turnOnLightLayer();
                        PoseStack ps = event.getPoseStack();
                        ps.pushPose();
                        ps.translate(-cam.x, -cam.y, -cam.z);
                        grassVbo.bind();
                        grassVbo.drawWithShader(ps.last().pose(), RenderSystem.getProjectionMatrix(), shader);
                        VertexBuffer.unbind();
                        ps.popPose();
                        mc.gameRenderer.lightTexture().turnOffLightLayer();
                        RenderSystem.enableCull();
                     }
                  }
               }
            }
         }
      }
   }

   private static void scheduleAsyncScan(Minecraft mc) {
      if (asyncBusy.compareAndSet(false, true)) {
         int radius = renderRadius;
         BlockPos origin = mc.player.blockPosition();
         ClientLevel levelRef = mc.level;
         ASYNC.submit(() -> {
            try {
               Grass.ScanData sd = doScan(levelRef, origin, radius);
               currentScan = sd;
               pendingUpload.set(doFill(sd));
            } catch (Throwable var7) {
               var7.printStackTrace();
            } finally {
               asyncBusy.set(false);
            }
         });
      }
   }

   private static void scheduleAsyncBuild(Grass.ScanData sd) {
      if (asyncBusy.compareAndSet(false, true)) {
         int[] ls = (int[])sd.blockPackedLight.clone();
         Grass.ScanData snap = new Grass.ScanData(
            sd.totalBlades,
            sd.totalBlocks,
            sd.bladePx,
            sd.bladePz,
            sd.bladeBaseY,
            sd.bladePhi1,
            sd.bladeCR,
            sd.bladeCG,
            sd.bladeCB,
            sd.bladeBlkIdx,
            sd.blockAbovePos,
            ls
         );
         ASYNC.submit(() -> {
            try {
               pendingUpload.set(doFill(snap));
            } catch (Throwable var5) {
               var5.printStackTrace();
            } finally {
               asyncBusy.set(false);
            }
         });
      }
   }

   private static void setGrassUniforms(ShaderInstance s, float time, Vec3 camera) {
      Uniform u = s.getUniform("Time");
      if (u != null) {
         u.set(time);
      }

      u = s.getUniform("WindDirX");
      if (u != null) {
         u.set(windDirX);
      }

      u = s.getUniform("WindDirZ");
      if (u != null) {
         u.set(windDirZ);
      }

      u = s.getUniform("CameraX");
      if (u != null) {
         u.set((float)camera.x);
      }

      u = s.getUniform("CameraZ");
      if (u != null) {
         u.set((float)camera.z);
      }
   }

   private static Grass.ScanData doScan(ClientLevel level, BlockPos origin, int radius) {
      int minY = origin.getY() - 5;
      int maxY = origin.getY() + 4;
      int blockCount = 0;

      for (int dx = -radius; dx <= radius; dx++) {
         for (int dz = -radius; dz <= radius; dz++) {
            for (int by = minY; by <= maxY; by++) {
               BlockPos p = new BlockPos(origin.getX() + dx, by, origin.getZ() + dz);
               if (level.getBlockState(p).is(Blocks.GRASS_BLOCK) && level.getBlockState(p.above()).isAir()) {
                  blockCount++;
               }
            }
         }
      }

      long[] blockAbovePos = new long[blockCount];
      int[] blockPackedLight = new int[blockCount];
      int maxBlades = blockCount * 45;
      float[] tPx = new float[maxBlades];
      float[] tPz = new float[maxBlades];
      float[] tBaseY = new float[maxBlades];
      float[] tPhi1 = new float[maxBlades];
      int[] tCR = new int[maxBlades];
      int[] tCG = new int[maxBlades];
      int[] tCB = new int[maxBlades];
      int[] tBlkIdx = new int[maxBlades];
      int blkIdx = 0;
      int bladeIdx = 0;

      for (int dx = -radius; dx <= radius; dx++) {
         for (int dz = -radius; dz <= radius; dz++) {
            int bx = origin.getX() + dx;
            int bz = origin.getZ() + dz;

            for (int byx = minY; byx <= maxY; byx++) {
               BlockPos pos = new BlockPos(bx, byx, bz);
               if (level.getBlockState(pos).is(Blocks.GRASS_BLOCK) && level.getBlockState(pos.above()).isAir()) {
                  BlockPos above = pos.above();
                  blockAbovePos[blkIdx] = above.asLong();
                  blockPackedLight[blkIdx] = LightTexture.pack(level.getBrightness(LightLayer.BLOCK, above), level.getBrightness(LightLayer.SKY, above));
                  int color = BiomeColors.getAverageGrassColor(level, pos);
                  int cr = color >> 16 & 0xFF;
                  int cg = color >> 8 & 0xFF;
                  int cb = color & 0xFF;
                  long blockSeed = pos.asLong();
                  float hOffX = (float)((double)(blockSeed * -7046029254386353131L >>> 33) / 2.1474836E9F);
                  float hOffZ = (float)((double)(blockSeed * 7809847782465536322L >>> 33) / 2.1474836E9F);
                  int bladeCount = bladesForBlock(blockSeed);

                  for (int i = 0; i < bladeCount; i++) {
                     tPx[bladeIdx] = (float)bx + frac(halton2(i) + hOffX);
                     tPz[bladeIdx] = (float)bz + frac(halton3(i) + hOffZ);
                     tBaseY[bladeIdx] = (float)byx + 1.0F;
                     long seed = blockSeed * 31L + (long)i * 197L;
                     float bs = (float)((double)(seed & 4294967295L) / 4.2949673E9F);
                     tPhi1[bladeIdx] = bs * (float) (Math.PI * 2);
                     tCR[bladeIdx] = cr;
                     tCG[bladeIdx] = cg;
                     tCB[bladeIdx] = cb;
                     tBlkIdx[bladeIdx] = blkIdx;
                     bladeIdx++;
                  }

                  blkIdx++;
               }
            }
         }
      }

      return new Grass.ScanData(
         bladeIdx,
         blkIdx,
         trim(tPx, bladeIdx),
         trim(tPz, bladeIdx),
         trim(tBaseY, bladeIdx),
         trim(tPhi1, bladeIdx),
         trimInt(tCR, bladeIdx),
         trimInt(tCG, bladeIdx),
         trimInt(tCB, bladeIdx),
         trimInt(tBlkIdx, bladeIdx),
         blockAbovePos,
         blockPackedLight
      );
   }

   private static RenderedBuffer doFill(Grass.ScanData sd) {
      BufferBuilder bb = new BufferBuilder(8388608);
      bb.begin(Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
      float H = 0.5F;
      int V = 12;

      for (int idx = 0; idx < sd.totalBlades; idx++) {
         float bx = sd.bladePx[idx];
         float bz = sd.bladePz[idx];
         float base = sd.bladeBaseY[idx];
         float phi1 = sd.bladePhi1[idx];
         int light = sd.blockPackedLight[sd.bladeBlkIdx[idx]];
         int hash = Float.floatToRawIntBits(phi1);
         int vr = (hash * -1640531527 >>> 24 & 0xFF) * 2 * 12 / 255 - 12;
         int vg = (hash * 1818371887 >>> 24 & 0xFF) * 2 * 12 / 255 - 12;
         int vb = (hash * 608135817 >>> 24 & 0xFF) * 2 * 12 / 255 - 12;
         int r = Math.max(0, Math.min(255, sd.bladeCR[idx] + vr));
         int g = Math.max(0, Math.min(255, sd.bladeCG[idx] + vg));
         int b = Math.max(0, Math.min(255, sd.bladeCB[idx] + vb));
         int phi1Enc = (int)(phi1 / (float) (Math.PI * 2) * 32767.0F) & 32767;
         int ov_right = phi1Enc | 65536;
         emitQuad(bb, bx, bz, base, 1.0F, base + 0.125F, 0.75F, r, g, b, light, phi1Enc, ov_right);
         emitQuad(bb, bx, bz, base + 0.125F, 0.75F, base + 0.25F, 0.5F, r, g, b, light, phi1Enc, ov_right);
         emitQuad(bb, bx, bz, base + 0.25F, 0.5F, base + 0.375F, 0.25F, r, g, b, light, phi1Enc, ov_right);
         emitQuad(bb, bx, bz, base + 0.375F, 0.25F, base + 0.5F, 0.0F, r, g, b, light, phi1Enc, ov_right);
      }

      return bb.end();
   }

   private static void emitQuad(
      BufferBuilder bb, float bx, float bz, float yBot, float uvBot, float yTop, float uvTop, int r, int g, int b, int light, int ov_left, int ov_right
   ) {
      bb.vertex((double)bx, (double)yBot, (double)bz)
         .color(r, g, b, 255)
         .uv(0.0F, uvBot)
         .overlayCoords(ov_left)
         .uv2(light)
         .normal(0.0F, 1.0F, 0.0F)
         .endVertex();
      bb.vertex((double)bx, (double)yBot, (double)bz)
         .color(r, g, b, 255)
         .uv(1.0F, uvBot)
         .overlayCoords(ov_right)
         .uv2(light)
         .normal(0.0F, 1.0F, 0.0F)
         .endVertex();
      bb.vertex((double)bx, (double)yTop, (double)bz)
         .color(r, g, b, 255)
         .uv(1.0F, uvTop)
         .overlayCoords(ov_right)
         .uv2(light)
         .normal(0.0F, 1.0F, 0.0F)
         .endVertex();
      bb.vertex((double)bx, (double)yTop, (double)bz)
         .color(r, g, b, 255)
         .uv(0.0F, uvTop)
         .overlayCoords(ov_left)
         .uv2(light)
         .normal(0.0F, 1.0F, 0.0F)
         .endVertex();
   }

   private static void refreshLightInPlace(Minecraft mc, Grass.ScanData sd) {
      if (mc.level != null) {
         for (int i = 0; i < sd.totalBlocks; i++) {
            BlockPos p = BlockPos.of(sd.blockAbovePos[i]);
            sd.blockPackedLight[i] = LightTexture.pack(mc.level.getBrightness(LightLayer.BLOCK, p), mc.level.getBrightness(LightLayer.SKY, p));
         }
      }
   }

   private static float halton2(int idx) {
      float r = 0.0F;
      float f = 1.0F;

      for (int i = idx + 1; i > 0; i >>= 1) {
         f *= 0.5F;
         r += f * (float)(i & 1);
      }

      return r;
   }

   private static float halton3(int idx) {
      float r = 0.0F;
      float f = 1.0F;

      for (int i = idx + 1; i > 0; i /= 3) {
         f /= 3.0F;
         r += f * (float)(i % 3);
      }

      return r;
   }

   private static float frac(float x) {
      return x - (float)Math.floor((double)x);
   }

   private static int bladesForBlock(long seed) {
      int range = 16;
      return 30 + Math.floorMod(Long.hashCode(seed), range);
   }

   private static float[] trim(float[] src, int len) {
      float[] r = new float[len];
      System.arraycopy(src, 0, r, 0, len);
      return r;
   }

   private static int[] trimInt(int[] src, int len) {
      int[] r = new int[len];
      System.arraycopy(src, 0, r, 0, len);
      return r;
   }

   static {
      for (int i = 0; i < 4096; i++) {
         SIN_TABLE[i] = (float)Math.sin((double)i * 0.0015339807878856412);
      }

      ANIMATION_START_NANOS = System.nanoTime();
      TEXTURE = ResourceLocation.fromNamespaceAndPath("luxium", "textures/grass.png");
      ASYNC = Executors.newSingleThreadExecutor(r -> {
         Thread t = new Thread(r, "luxium-GrassBuilder");
         t.setDaemon(true);
         t.setPriority(4);
         return t;
      });
      asyncBusy = new AtomicBoolean(false);
      pendingUpload = new AtomicReference<>();
      currentScan = null;
      grassVbo = null;
      enabled = false;
      renderRadius = 12;
      needsRescan = false;
      lastLightRefreshTick = -999L;
      windDirX = 1.0F;
      windDirZ = 0.0F;
   }

   private static final class ScanData {
      final int totalBlades;
      final int totalBlocks;
      final float[] bladePx;
      final float[] bladePz;
      final float[] bladeBaseY;
      final float[] bladePhi1;
      final int[] bladeCR;
      final int[] bladeCG;
      final int[] bladeCB;
      final int[] bladeBlkIdx;
      final long[] blockAbovePos;
      final int[] blockPackedLight;

      ScanData(
         int totalBlades,
         int totalBlocks,
         float[] px,
         float[] pz,
         float[] baseY,
         float[] phi1,
         int[] cr,
         int[] cg,
         int[] cb,
         int[] blkIdx,
         long[] abovePos,
         int[] packedLight
      ) {
         this.totalBlades = totalBlades;
         this.totalBlocks = totalBlocks;
         this.bladePx = px;
         this.bladePz = pz;
         this.bladeBaseY = baseY;
         this.bladePhi1 = phi1;
         this.bladeCR = cr;
         this.bladeCG = cg;
         this.bladeCB = cb;
         this.bladeBlkIdx = blkIdx;
         this.blockAbovePos = abovePos;
         this.blockPackedLight = packedLight;
      }
   }
}
