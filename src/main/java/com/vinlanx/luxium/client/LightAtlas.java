package com.vinlanx.luxium.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vinlanx.luxium.Testing.TestFlashLight;
import com.vinlanx.luxium.rtx.TorchRtxState;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent.Stage;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;

@EventBusSubscriber(
   modid = "luxium",
   value = {Dist.CLIENT}
)
public final class LightAtlas {
   public static final int SIZE_X = 48;
   public static final int SIZE_Y = 32;
   public static final int SIZE_Z = 48;
   private static final int ATLAS_W = 2304;
   private static final int ATLAS_H = 32;
   private static final int ANCHOR_SNAP = 8;
   private static final int ANCHOR_MARGIN = 10;
   private static final long UPLOAD_INTERVAL_MS = 120L;
   private static int textureId = -1;
   private static ByteBuffer buffer;
   private static int litVoxels = 0;
   private static final int[] litVoxelIndices = new int[73728];
   private static final byte[] rtxLightCache = new byte[73728];
   private static long cachedRtxSnapshotVersion = Long.MIN_VALUE;
   private static int cachedRtxAnchorX = Integer.MIN_VALUE;
   private static int cachedRtxAnchorY = Integer.MIN_VALUE;
   private static int cachedRtxAnchorZ = Integer.MIN_VALUE;
   private static long lastUploadMs = 0L;
   private static long lastFlashlightTick = Long.MIN_VALUE;
   private static final Vector3f volumeMin = new Vector3f();
   private static final Vector3f volumeSize = new Vector3f(48.0F, 32.0F, 48.0F);
   private static int anchorX = Integer.MIN_VALUE;
   private static int anchorY = Integer.MIN_VALUE;
   private static int anchorZ = Integer.MIN_VALUE;
   private static final MutableBlockPos cursor = new MutableBlockPos();

   private LightAtlas() {
   }

   public static int getTextureId() {
      return NeoGpuVanilla.isVolumeReady() ? NeoGpuVanilla.getVolumeTextureId() : textureId;
   }

   public static boolean isReady() {
      return NeoGpuVanilla.isVolumeReady() || textureId != -1 && litVoxels > 0;
   }

   public static Vector3f getMin() {
      return NeoGpuVanilla.isVolumeReady() ? NeoGpuVanilla.getVolumeMin(volumeMin) : volumeMin;
   }

   public static Vector3f getSize() {
      return NeoGpuVanilla.isVolumeReady() ? NeoGpuVanilla.getVolumeSize(volumeSize) : volumeSize;
   }

   public static boolean sampleRandomLitPosition(RandomSource random, Vector3f output) {
      if (NeoGpuVanilla.isVolumeReady()) {
         return NeoGpuVanilla.sampleRandomLitPosition(random, output);
      } else if (litVoxels > 0 && anchorX != Integer.MIN_VALUE) {
         int packedIndex = litVoxelIndices[random.nextInt(litVoxels)];
         int localX = packedIndex % 48;
         int yz = packedIndex / 48;
         int localZ = yz % 48;
         int localY = yz / 48;
         output.set(
            (float)(anchorX + localX) + random.nextFloat(), (float)(anchorY + localY) + random.nextFloat(), (float)(anchorZ + localZ) + random.nextFloat()
         );
         return true;
      } else {
         return false;
      }
   }

   public static float sampleLightFactor(double worldX, double worldY, double worldZ) {
      if (NeoGpuVanilla.isVolumeReady()) {
         return NeoGpuVanilla.sampleLightFactor(worldX, worldY, worldZ);
      } else if (buffer != null && litVoxels > 0 && anchorX != Integer.MIN_VALUE) {
         int localX = Mth.floor(worldX) - anchorX;
         int localY = Mth.floor(worldY) - anchorY;
         int localZ = Mth.floor(worldZ) - anchorZ;
         if (localX >= 0 && localY >= 0 && localZ >= 0 && localX < 48 && localY < 32 && localZ < 48) {
            int index = localY * 2304 + localZ * 48 + localX;
            return (float)(buffer.get(index) & 255) / 255.0F;
         } else {
            return 0.0F;
         }
      } else {
         return 0.0F;
      }
   }

   @SubscribeEvent
   public static void onRenderLevel(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_PARTICLES) {
         if (!ReflectionSystem.isRenderingWorldPass() && !NeoShadowsEngine.isAnyShadowCapturePass()) {
            clearVolumeData();
         }
      }
   }

   private static void clearVolumeData() {
      litVoxels = 0;
      lastFlashlightTick = Long.MIN_VALUE;
   }

   private static void ensureTexture() {
      if (textureId == -1) {
         textureId = TextureUtil.generateTextureId();
         RenderSystem.activeTexture(33986);
         GL11.glBindTexture(3553, textureId);
         GL21.glBindBuffer(35052, 0);
         GL11.glTexParameteri(3553, 10241, 9729);
         GL11.glTexParameteri(3553, 10240, 9729);
         GL11.glTexParameteri(3553, 10242, 33071);
         GL11.glTexParameteri(3553, 10243, 33071);
         GL11.glTexImage2D(3553, 0, 33321, 2304, 32, 0, 6403, 5121, (ByteBuffer)null);
         GL11.glBindTexture(3553, 0);
         RenderSystem.activeTexture(33984);
         buffer = ByteBuffer.allocateDirect(73728);
      }
   }

   private static void upload(Minecraft mc) {
      boolean flashlight = TestFlashLight.isEnabled();
      if (flashlight && mc.level != null) {
         long tick = mc.level.getGameTime();
         if (tick == lastFlashlightTick) {
            return;
         }

         lastFlashlightTick = tick;
      } else {
         lastFlashlightTick = Long.MIN_VALUE;
         long now = System.currentTimeMillis();
         if (now - lastUploadMs < 120L) {
            return;
         }

         lastUploadMs = now;
      }

      if (buffer != null) {
         TorchRtxState rtx = TorchRtxState.get();
         boolean rtxOn = rtx.isEnabled();
         Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
         updateAnchor(cam.x, cam.y, cam.z);
         volumeMin.set((float)anchorX, (float)anchorY, (float)anchorZ);
         if (rtxOn) {
            long snapshotVersion = rtx.getLightSnapshotVersion();
            if (snapshotVersion != cachedRtxSnapshotVersion || anchorX != cachedRtxAnchorX || anchorY != cachedRtxAnchorY || anchorZ != cachedRtxAnchorZ) {
               rtx.fillLightVolume(anchorX, anchorY, anchorZ, 48, 32, 48, rtxLightCache);
               cachedRtxSnapshotVersion = snapshotVersion;
               cachedRtxAnchorX = anchorX;
               cachedRtxAnchorY = anchorY;
               cachedRtxAnchorZ = anchorZ;
            }
         } else if (cachedRtxSnapshotVersion != Long.MIN_VALUE) {
            Arrays.fill(rtxLightCache, (byte)0);
            cachedRtxSnapshotVersion = Long.MIN_VALUE;
            cachedRtxAnchorZ = Integer.MIN_VALUE;
            cachedRtxAnchorY = Integer.MIN_VALUE;
            cachedRtxAnchorX = Integer.MIN_VALUE;
         }

         int lit = 0;

         for (int y = 0; y < 32; y++) {
            int wy = anchorY + y;
            int row = y * 2304;

            for (int z = 0; z < 48; z++) {
               int wz = anchorZ + z;
               int slice = row + z * 48;

               for (int x = 0; x < 48; x++) {
                  cursor.set(anchorX + x, wy, wz);
                  int packedIndex = slice + x;
                  int level = rtxOn ? rtxLightCache[packedIndex] & 255 : 0;
                  if (flashlight) {
                     int l = TestFlashLight.sampleLight(cursor);
                     if (l > level) {
                        level = l;
                     }
                  }

                  level = Mth.clamp(level, 0, 15);
                  if (level > 0) {
                     litVoxelIndices[lit] = (y * 48 + z) * 48 + x;
                     lit++;
                  }

                  buffer.put(slice + x, (byte)(level * 255 / 15 & 0xFF));
               }
            }
         }

         litVoxels = lit;
         buffer.position(0).limit(buffer.capacity());
         RenderSystem.activeTexture(33986);
         GL11.glBindTexture(3553, textureId);
         GL21.glBindBuffer(35052, 0);
         GlStateManager._pixelStore(3314, 0);
         GlStateManager._pixelStore(3316, 0);
         GlStateManager._pixelStore(3315, 0);
         GlStateManager._pixelStore(3317, 1);
         GL11.glTexSubImage2D(3553, 0, 0, 0, 2304, 32, 6403, 5121, buffer);
         GL11.glBindTexture(3553, 0);
         RenderSystem.activeTexture(33984);
      }
   }

   private static void updateAnchor(double cx, double cy, double cz) {
      int hx = 24;
      int hy = 16;
      int hz = 24;
      int dx = snapToGrid(Mth.floor(cx) - hx, 8);
      int dy = snapToGrid(Mth.floor(cy) - hy, 8);
      int dz = snapToGrid(Mth.floor(cz) - hz, 8);
      if (anchorX == Integer.MIN_VALUE) {
         anchorX = dx;
         anchorY = dy;
         anchorZ = dz;
      } else {
         int bx = Mth.floor(cx);
         int by = Mth.floor(cy);
         int bz = Mth.floor(cz);
         if (bx < anchorX + 10 || bx > anchorX + 48 - 10) {
            anchorX = dx;
         }

         if (by < anchorY + 10 || by > anchorY + 32 - 10) {
            anchorY = dy;
         }

         if (bz < anchorZ + 10 || bz > anchorZ + 48 - 10) {
            anchorZ = dz;
         }
      }
   }

   private static int snapToGrid(int v, int g) {
      return Mth.floorDiv(v, g) * g;
   }
}
