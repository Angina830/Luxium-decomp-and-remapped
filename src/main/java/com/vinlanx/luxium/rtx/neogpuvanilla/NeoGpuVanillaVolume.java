package com.vinlanx.luxium.rtx.neogpuvanilla;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.ShaderManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

final class NeoGpuVanillaVolume {
   static final int SIZE_X = 48;
   static final int SIZE_Y = 32;
   static final int SIZE_Z = 48;
   static final int ATLAS_W = 2304;
   static final int ATLAS_H = 32;
   private static final int VOXEL_COUNT = 73728;
   private static final int VOLUME_CHANNELS = 4;
   private static final int ANCHOR_SNAP = 8;
   private static final int ANCHOR_MARGIN = 10;
   private final int[] candidateTextures = new int[]{-1, -1};
   private final int[] receiverTextures = new int[]{-1, -1};
   private final int[] receiverScales = new int[]{0, 0};
   private final int[][] fastLightTextures = new int[][]{{-1, -1, -1, -1, -1, -1}, {-1, -1, -1, -1, -1, -1}};
   private final boolean[] fastLightReady = new boolean[]{false, false};
   private int fastActiveIndex;
   private int activeIndex;
   private int vanillaTexture = -1;
   private int metadataTexture = -1;
   private int fbo = -1;
   private final ByteBuffer vanillaPixels = ByteBuffer.allocateDirect(73728).order(ByteOrder.nativeOrder());
   private final ByteBuffer cpuMirror = ByteBuffer.allocateDirect(294912).order(ByteOrder.nativeOrder());
   private final ByteBuffer buildMirror = ByteBuffer.allocateDirect(294912).order(ByteOrder.nativeOrder());
   private final MutableBlockPos cursor = new MutableBlockPos();
   private final FloatBuffer metadataPixels = ByteBuffer.allocateDirect(5760).order(ByteOrder.nativeOrder()).asFloatBuffer();
   private final IntBuffer savedViewport = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asIntBuffer();
   private int activeAnchorX = Integer.MIN_VALUE;
   private int activeAnchorY = Integer.MIN_VALUE;
   private int activeAnchorZ = Integer.MIN_VALUE;
   private int fastAnchorX = Integer.MIN_VALUE;
   private int fastAnchorY = Integer.MIN_VALUE;
   private int fastAnchorZ = Integer.MIN_VALUE;
   private int buildAnchorX = Integer.MIN_VALUE;
   private int buildAnchorY = Integer.MIN_VALUE;
   private int buildAnchorZ = Integer.MIN_VALUE;
   private boolean ready;
   private int bakedSourceCount;
   private long revision;

   int textureId() {
      return this.ready ? this.candidateTextures[this.activeIndex] : -1;
   }

   int receiverTextureId() {
      return this.ready ? this.receiverTextures[this.activeIndex] : -1;
   }

   int receiverScale() {
      return this.ready ? Math.max(1, this.receiverScales[this.activeIndex]) : 1;
   }

   boolean fastShadowsReady() {
      return this.ready
         && this.fastLightReady[this.fastActiveIndex]
         && this.fastAnchorX == this.activeAnchorX
         && this.fastAnchorY == this.activeAnchorY
         && this.fastAnchorZ == this.activeAnchorZ;
   }

   int fastLightTextureId(int direction) {
      return this.fastShadowsReady() && direction >= 0 && direction < 6 ? this.fastLightTextures[this.fastActiveIndex][direction] : -1;
   }

   int metadataTextureId() {
      return this.metadataTexture;
   }

   int bakedSourceCount() {
      return this.bakedSourceCount;
   }

   boolean isReady() {
      return this.ready && this.candidateTextures[this.activeIndex] > 0 && this.receiverTextures[this.activeIndex] > 0;
   }

   boolean hasResources() {
      boolean fast = false;

      for (int i = 0; i < 2 && !fast; i++) {
         for (int d = 0; d < 6; d++) {
            fast |= this.fastLightTextures[i][d] > 0;
         }
      }

      return this.candidateTextures[0] > 0
         || this.candidateTextures[1] > 0
         || this.receiverTextures[0] > 0
         || this.receiverTextures[1] > 0
         || fast
         || this.vanillaTexture > 0
         || this.metadataTexture > 0
         || this.fbo > 0;
   }

   long revision() {
      return this.revision;
   }

   int anchorX() {
      return this.ready ? this.activeAnchorX : this.buildAnchorX;
   }

   int anchorY() {
      return this.ready ? this.activeAnchorY : this.buildAnchorY;
   }

   int anchorZ() {
      return this.ready ? this.activeAnchorZ : this.buildAnchorZ;
   }

   int buildAnchorX() {
      return this.buildAnchorX;
   }

   int buildAnchorY() {
      return this.buildAnchorY;
   }

   int buildAnchorZ() {
      return this.buildAnchorZ;
   }

   boolean updateAnchor(double cameraX, double cameraY, double cameraZ) {
      int desiredX = snapToGrid(Mth.floor(cameraX) - 24, 8);
      int desiredY = snapToGrid(Mth.floor(cameraY) - 16, 8);
      int desiredZ = snapToGrid(Mth.floor(cameraZ) - 24, 8);
      if (this.buildAnchorX == Integer.MIN_VALUE) {
         this.buildAnchorX = desiredX;
         this.buildAnchorY = desiredY;
         this.buildAnchorZ = desiredZ;
         return true;
      } else {
         int referenceX = this.ready ? this.activeAnchorX : this.buildAnchorX;
         int referenceY = this.ready ? this.activeAnchorY : this.buildAnchorY;
         int referenceZ = this.ready ? this.activeAnchorZ : this.buildAnchorZ;
         int bx = Mth.floor(cameraX);
         int by = Mth.floor(cameraY);
         int bz = Mth.floor(cameraZ);
         boolean outside = bx < referenceX + 10
            || bx > referenceX + 48 - 10
            || by < referenceY + 10
            || by > referenceY + 32 - 10
            || bz < referenceZ + 10
            || bz > referenceZ + 48 - 10;
         if (!outside) {
            return false;
         } else if (desiredX == this.buildAnchorX && desiredY == this.buildAnchorY && desiredZ == this.buildAnchorZ) {
            return false;
         } else {
            this.buildAnchorX = desiredX;
            this.buildAnchorY = desiredY;
            this.buildAnchorZ = desiredZ;
            return true;
         }
      }
   }

   void invalidate() {
   }

   boolean bake(
      ClientLevel level, int shadowAtlasTexture, int sourceCount, float[] sourceData, float[] sourceGeometry, float[] sourceColors, float[] atlasRects
   ) {
      if (level == null || sourceCount < 0 || sourceCount > 40 || this.buildAnchorX == Integer.MIN_VALUE) {
         return false;
      } else if (sourceCount > 0 && shadowAtlasTexture <= 0) {
         return false;
      } else {
         ShaderInstance coarseShader = ShaderManager.getNeoGpuVanillaBakeShader();
         ShaderInstance resolveShader = ShaderManager.getNeoGpuVanillaResolveShader();
         boolean fastEnabled = Config.isFeatureEnabled(Config.CLIENT.neoGpuVanillaFastShadowsEnabled);
         ShaderInstance fastShader = fastEnabled ? ShaderManager.getNeoGpuVanillaFastResolveShader() : null;
         if (coarseShader != null && resolveShader != null && (!fastEnabled || fastShader != null)) {
            this.ensureResources();
            this.uploadVanillaLight(level);
            this.uploadSourceMetadata(sourceCount, sourceData, sourceGeometry, sourceColors, atlasRects);
            boolean fastSnapshotComplete = true;
            if (fastEnabled && sourceGeometry != null) {
               int max = Math.min(sourceCount, sourceGeometry.length / 4);

               for (int i = 0; i < max; i++) {
                  int flags = Math.round(sourceGeometry[i * 4 + 3]);
                  if ((flags & 128) != 0) {
                     fastSnapshotComplete = false;
                     break;
                  }
               }
            }

            int buildIndex = this.ready ? 1 - this.activeIndex : this.activeIndex;
            int fineScale = receiverScaleForPixelsPerBlock((Integer)Config.CLIENT.neoGpuVanillaCaptureResolution.get());
            this.ensureReceiverTexture(buildIndex, fineScale);
            this.ensureFastLightTextures(buildIndex, fastEnabled && fastSnapshotComplete);
            int oldFramebuffer = GL11.glGetInteger(36006);
            boolean oldDepth = GL11.glIsEnabled(2929);
            boolean oldCull = GL11.glIsEnabled(2884);
            boolean oldBlend = GL11.glIsEnabled(3042);
            boolean oldScissor = GL11.glIsEnabled(3089);
            boolean oldDither = GL11.glIsEnabled(3024);
            boolean oldFramebufferSrgb = GL11.glIsEnabled(36281);
            Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
            VertexSorting oldSorting = RenderSystem.getVertexSorting();
            this.savedViewport.clear();
            GL11.glGetIntegerv(2978, this.savedViewport);
            int oldViewportX = this.savedViewport.get(0);
            int oldViewportY = this.savedViewport.get(1);
            int oldViewportW = this.savedViewport.get(2);
            int oldViewportH = this.savedViewport.get(3);
            GL11.glDisable(2929);
            GL11.glDisable(2884);
            GL11.glDisable(3042);
            GL11.glDisable(3089);
            GL11.glDisable(3024);
            GL11.glDisable(36281);
            GlStateManager._colorMask(true, true, true, true);

            boolean var50;
            try {
               this.attachColor(this.candidateTextures[buildIndex]);
               GL11.glViewport(0, 0, 2304, 32);
               Matrix4f coarseOrtho = new Matrix4f().setOrtho(0.0F, 2304.0F, 32.0F, 0.0F, -1.0F, 1.0F);
               RenderSystem.setProjectionMatrix(coarseOrtho, VertexSorting.ORTHOGRAPHIC_Z);
               coarseShader.setSampler("VanillaLightVolume", this.vanillaTexture);
               coarseShader.setSampler("ShadowAtlas", Math.max(shadowAtlasTexture, 0));
               coarseShader.setSampler("SourceMetadata", this.metadataTexture);
               coarseShader.MODEL_VIEW_MATRIX.set(new Matrix4f());
               coarseShader.PROJECTION_MATRIX.set(coarseOrtho);
               if (coarseShader.getUniform("SourceCount") != null) {
                  coarseShader.getUniform("SourceCount").set(sourceCount);
               }

               if (coarseShader.getUniform("VolumeMin") != null) {
                  coarseShader.getUniform("VolumeMin").set((float)this.buildAnchorX, (float)this.buildAnchorY, (float)this.buildAnchorZ);
               }

               if (coarseShader.getUniform("VolumeSize") != null) {
                  coarseShader.getUniform("VolumeSize").set(48.0F, 32.0F, 48.0F);
               }

               coarseShader.apply();
               drawFullscreen(2304, 32);
               coarseShader.clear();
               this.buildMirror.clear();
               GlStateManager._pixelStore(3333, 1);
               GL11.glReadPixels(0, 0, 2304, 32, 6408, 5121, this.buildMirror);
               this.buildMirror.position(0).limit(this.buildMirror.capacity());
               int fineX = 48 * fineScale;
               int fineY = 32 * fineScale;
               int fineZ = 48 * fineScale;
               int slicesPerRow = slicesPerRow(fineX, fineY, fineZ);
               int rows = (fineZ + slicesPerRow - 1) / slicesPerRow;
               int fineAtlasW = fineX * slicesPerRow;
               int fineAtlasH = fineY * rows;
               this.attachColor(this.receiverTextures[buildIndex]);
               GL11.glViewport(0, 0, fineAtlasW, fineAtlasH);
               Matrix4f fineOrtho = new Matrix4f().setOrtho(0.0F, (float)fineAtlasW, (float)fineAtlasH, 0.0F, -1.0F, 1.0F);
               RenderSystem.setProjectionMatrix(fineOrtho, VertexSorting.ORTHOGRAPHIC_Z);
               resolveShader.setSampler("CandidateVolume", this.candidateTextures[buildIndex]);
               resolveShader.setSampler("VanillaLightVolume", this.vanillaTexture);
               resolveShader.setSampler("ShadowAtlas", Math.max(shadowAtlasTexture, 0));
               resolveShader.setSampler("SourceMetadata", this.metadataTexture);
               resolveShader.MODEL_VIEW_MATRIX.set(new Matrix4f());
               resolveShader.PROJECTION_MATRIX.set(fineOrtho);
               if (resolveShader.getUniform("SourceCount") != null) {
                  resolveShader.getUniform("SourceCount").set(sourceCount);
               }

               if (resolveShader.getUniform("FineScale") != null) {
                  resolveShader.getUniform("FineScale").set(fineScale);
               }

               if (resolveShader.getUniform("FineSlicesPerRow") != null) {
                  resolveShader.getUniform("FineSlicesPerRow").set(slicesPerRow);
               }

               NeoGpuVanillaGpuDebug.beginFineBake();

               try {
                  resolveShader.apply();
                  drawFullscreen(fineAtlasW, fineAtlasH);
               } finally {
                  resolveShader.clear();
                  NeoGpuVanillaGpuDebug.endFineBake();
               }

               if (fastEnabled && fastSnapshotComplete) {
                  this.fastLightReady[buildIndex] = false;
                  Matrix4f fastOrtho = new Matrix4f().setOrtho(0.0F, 48.0F, 32.0F, 0.0F, -1.0F, 1.0F);
                  RenderSystem.setProjectionMatrix(fastOrtho, VertexSorting.ORTHOGRAPHIC_Z);
                  GL11.glViewport(0, 0, 48, 32);
                  fastShader.setSampler("ShadowAtlas", Math.max(shadowAtlasTexture, 0));
                  fastShader.setSampler("SourceMetadata", this.metadataTexture);
                  fastShader.MODEL_VIEW_MATRIX.set(new Matrix4f());
                  fastShader.PROJECTION_MATRIX.set(fastOrtho);
                  if (fastShader.getUniform("SourceCount") != null) {
                     fastShader.getUniform("SourceCount").set(sourceCount);
                  }

                  if (fastShader.getUniform("FastStrength") != null) {
                     fastShader.getUniform("FastStrength").set(((Double)Config.CLIENT.neoGpuVanillaFastShadowStrength.get()).floatValue());
                  }

                  if (fastShader.getUniform("DiffuseWrap") != null) {
                     fastShader.getUniform("DiffuseWrap").set(((Double)Config.CLIENT.neoGpuVanillaFastDiffuseWrap.get()).floatValue());
                  }

                  for (int z = 0; z < 48; z++) {
                     if (fastShader.getUniform("SliceZ") != null) {
                        fastShader.getUniform("SliceZ").set(z);
                     }

                     for (int direction = 0; direction < 6; direction++) {
                        this.attachFastLightLayer(buildIndex, z, direction);
                        if (fastShader.getUniform("DirectionIndex") != null) {
                           fastShader.getUniform("DirectionIndex").set(direction);
                        }

                        fastShader.apply();
                        drawFullscreen(48, 32);
                        fastShader.clear();
                     }
                  }

                  this.fastLightReady[buildIndex] = true;
                  this.fastActiveIndex = buildIndex;
                  this.fastAnchorX = this.buildAnchorX;
                  this.fastAnchorY = this.buildAnchorY;
                  this.fastAnchorZ = this.buildAnchorZ;
               }

               this.cpuMirror.clear();
               this.buildMirror.position(0);
               this.cpuMirror.put(this.buildMirror);
               this.cpuMirror.position(0).limit(this.cpuMirror.capacity());
               this.activeIndex = buildIndex;
               this.activeAnchorX = this.buildAnchorX;
               this.activeAnchorY = this.buildAnchorY;
               this.activeAnchorZ = this.buildAnchorZ;
               this.bakedSourceCount = sourceCount;
               this.ready = true;
               this.revision++;
               var50 = true;
            } finally {
               GL30.glBindFramebuffer(36160, oldFramebuffer);
               GL11.glViewport(oldViewportX, oldViewportY, oldViewportW, oldViewportH);
               if (oldDepth) {
                  GL11.glEnable(2929);
               } else {
                  GL11.glDisable(2929);
               }

               if (oldCull) {
                  GL11.glEnable(2884);
               } else {
                  GL11.glDisable(2884);
               }

               if (oldBlend) {
                  GL11.glEnable(3042);
               } else {
                  GL11.glDisable(3042);
               }

               if (oldScissor) {
                  GL11.glEnable(3089);
               } else {
                  GL11.glDisable(3089);
               }

               if (oldDither) {
                  GL11.glEnable(3024);
               } else {
                  GL11.glDisable(3024);
               }

               if (oldFramebufferSrgb) {
                  GL11.glEnable(36281);
               } else {
                  GL11.glDisable(36281);
               }

               RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
               GlStateManager._activeTexture(33984);
            }

            return var50;
         } else {
            return false;
         }
      }
   }

   float sampleLightFactor(double worldX, double worldY, double worldZ) {
      if (!this.isReady()) {
         return 0.0F;
      } else {
         int x = Mth.floor(worldX) - this.activeAnchorX;
         int y = Mth.floor(worldY) - this.activeAnchorY;
         int z = Mth.floor(worldZ) - this.activeAnchorZ;
         if (x >= 0 && y >= 0 && z >= 0 && x < 48 && y < 32 && z < 48) {
            int index = y * 2304 + z * 48 + x;
            return (float)(this.cpuMirror.get(index * 4) & 255) / 255.0F;
         } else {
            return 0.0F;
         }
      }
   }

   boolean sampleRandomLitPosition(RandomSource random, Vector3f output) {
      if (!this.isReady()) {
         return false;
      } else {
         int start = random.nextInt(73728);

         for (int i = 0; i < 96; i++) {
            int index = (start + i * 977) % 73728;
            if ((this.cpuMirror.get(index * 4) & 255) != 0) {
               int x = index % 48;
               int yz = index / 48;
               int z = yz % 48;
               int y = yz / 48;
               output.set(
                  (float)(this.activeAnchorX + x) + random.nextFloat(),
                  (float)(this.activeAnchorY + y) + random.nextFloat(),
                  (float)(this.activeAnchorZ + z) + random.nextFloat()
               );
               return true;
            }
         }

         return false;
      }
   }

   void release() {
      if (this.fbo != -1) {
         GL30.glDeleteFramebuffers(this.fbo);
         this.fbo = -1;
      }

      for (int i = 0; i < 2; i++) {
         if (this.candidateTextures[i] != -1) {
            TextureUtil.releaseTextureId(this.candidateTextures[i]);
            this.candidateTextures[i] = -1;
         }

         if (this.receiverTextures[i] != -1) {
            TextureUtil.releaseTextureId(this.receiverTextures[i]);
            this.receiverTextures[i] = -1;
         }

         for (int d = 0; d < 6; d++) {
            if (this.fastLightTextures[i][d] != -1) {
               TextureUtil.releaseTextureId(this.fastLightTextures[i][d]);
               this.fastLightTextures[i][d] = -1;
            }
         }

         this.fastLightReady[i] = false;
         this.receiverScales[i] = 0;
      }

      if (this.vanillaTexture != -1) {
         TextureUtil.releaseTextureId(this.vanillaTexture);
         this.vanillaTexture = -1;
      }

      if (this.metadataTexture != -1) {
         TextureUtil.releaseTextureId(this.metadataTexture);
         this.metadataTexture = -1;
      }

      this.ready = false;
      this.bakedSourceCount = 0;
      this.activeIndex = 0;
      this.fastActiveIndex = 0;
      this.activeAnchorX = this.activeAnchorY = this.activeAnchorZ = Integer.MIN_VALUE;
      this.fastAnchorX = this.fastAnchorY = this.fastAnchorZ = Integer.MIN_VALUE;
      this.buildAnchorX = this.buildAnchorY = this.buildAnchorZ = Integer.MIN_VALUE;
      this.revision++;
   }

   private void ensureResources() {
      if (this.fbo == -1) {
         this.fbo = GL30.glGenFramebuffers();
      }

      for (int i = 0; i < 2; i++) {
         if (this.candidateTextures[i] == -1) {
            this.candidateTextures[i] = createRgba8Texture(2304, 32);
         }
      }

      if (this.vanillaTexture == -1) {
         this.vanillaTexture = createR8Texture(2304, 32);
      }

      if (this.metadataTexture == -1) {
         this.metadataTexture = createMetadataTexture();
      }
   }

   private void ensureReceiverTexture(int index, int scale) {
      if (this.receiverTextures[index] == -1 || this.receiverScales[index] != scale) {
         if (this.receiverTextures[index] != -1) {
            TextureUtil.releaseTextureId(this.receiverTextures[index]);
         }

         int fineX = 48 * scale;
         int fineY = 32 * scale;
         int fineZ = 48 * scale;
         int slicesPerRow = slicesPerRow(fineX, fineY, fineZ);
         int rows = (fineZ + slicesPerRow - 1) / slicesPerRow;
         this.receiverTextures[index] = createR8Texture(fineX * slicesPerRow, fineY * rows);
         this.receiverScales[index] = scale;
      }
   }

   private void ensureFastLightTextures(int index, boolean enabled) {
      if (!enabled) {
         this.fastLightReady[index] = false;
      } else {
         for (int d = 0; d < 6; d++) {
            if (this.fastLightTextures[index][d] == -1) {
               this.fastLightTextures[index][d] = createRgba16f3dTexture(48, 32, 48);
            }
         }
      }
   }

   private void attachColor(int texture) {
      GL30.glBindFramebuffer(36160, this.fbo);
      GL30.glFramebufferTexture2D(36160, 36064, 3553, texture, 0);

      for (int attachment = 1; attachment < 4; attachment++) {
         GL30.glFramebufferTexture2D(36160, 36064 + attachment, 3553, 0, 0);
      }

      GL11.glDrawBuffer(36064);
   }

   private void attachFastLightLayer(int index, int z, int direction) {
      GL30.glBindFramebuffer(36160, this.fbo);
      GL30.glFramebufferTextureLayer(36160, 36064, this.fastLightTextures[index][direction], 0, z);

      for (int attachment = 1; attachment < 4; attachment++) {
         GL30.glFramebufferTexture2D(36160, 36064 + attachment, 3553, 0, 0);
      }

      GL11.glDrawBuffer(36064);
   }

   private static void drawFullscreen(int width, int height) {
      Tesselator tessellator = RenderSystem.renderThreadTesselator();
      BufferBuilder builder = tessellator.getBuilder();
      builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
      builder.vertex(0.0, (double)height, 0.0).uv(0.0F, 0.0F).endVertex();
      builder.vertex((double)width, (double)height, 0.0).uv(1.0F, 0.0F).endVertex();
      builder.vertex((double)width, 0.0, 0.0).uv(1.0F, 1.0F).endVertex();
      builder.vertex(0.0, 0.0, 0.0).uv(0.0F, 1.0F).endVertex();
      BufferUploader.draw(builder.end());
   }

   private static int createRgba8Texture(int width, int height) {
      int texture = TextureUtil.generateTextureId();
      GL11.glBindTexture(3553, texture);
      GL11.glTexParameteri(3553, 10241, 9728);
      GL11.glTexParameteri(3553, 10240, 9728);
      GL11.glTexParameteri(3553, 10242, 33071);
      GL11.glTexParameteri(3553, 10243, 33071);
      GL11.glTexImage2D(3553, 0, 32856, width, height, 0, 6408, 5121, (ByteBuffer)null);
      GL11.glBindTexture(3553, 0);
      return texture;
   }

   private static int createRgba16f3dTexture(int width, int height, int depth) {
      int texture = TextureUtil.generateTextureId();
      GL11.glBindTexture(32879, texture);
      GL11.glTexParameteri(32879, 10241, 9729);
      GL11.glTexParameteri(32879, 10240, 9729);
      GL11.glTexParameteri(32879, 10242, 33071);
      GL11.glTexParameteri(32879, 10243, 33071);
      GL11.glTexParameteri(32879, 32882, 33071);
      GL12.glTexImage3D(32879, 0, 34842, width, height, depth, 0, 6408, 5131, (ByteBuffer)null);
      GL11.glBindTexture(32879, 0);
      return texture;
   }

   private static int createR8Texture(int width, int height) {
      int texture = TextureUtil.generateTextureId();
      GL11.glBindTexture(3553, texture);
      GL11.glTexParameteri(3553, 10241, 9728);
      GL11.glTexParameteri(3553, 10240, 9728);
      GL11.glTexParameteri(3553, 10242, 33071);
      GL11.glTexParameteri(3553, 10243, 33071);
      GL11.glTexImage2D(3553, 0, 33321, width, height, 0, 6403, 5121, (ByteBuffer)null);
      GL11.glBindTexture(3553, 0);
      return texture;
   }

   private static int createMetadataTexture() {
      int texture = TextureUtil.generateTextureId();
      GL11.glBindTexture(3553, texture);
      GL11.glTexParameteri(3553, 10241, 9728);
      GL11.glTexParameteri(3553, 10240, 9728);
      GL11.glTexParameteri(3553, 10242, 33071);
      GL11.glTexParameteri(3553, 10243, 33071);
      GL11.glTexImage2D(3553, 0, 34836, 360, 1, 0, 6408, 5126, (ByteBuffer)null);
      GL11.glBindTexture(3553, 0);
      return texture;
   }

   private void uploadSourceMetadata(int sourceCount, float[] sourceData, float[] sourceGeometry, float[] sourceColors, float[] atlasRects) {
      this.metadataPixels.clear();

      for (int i = 0; i < 40; i++) {
         if (i < sourceCount) {
            this.metadataPixels.put(sourceData, i * 4, 4);
            this.metadataPixels.put(sourceGeometry, i * 4, 4);
            this.metadataPixels.put(sourceColors, i * 4, 4);
            this.metadataPixels.put(atlasRects, i * 24, 24);
         } else {
            for (int j = 0; j < 36; j++) {
               this.metadataPixels.put(0.0F);
            }
         }
      }

      this.metadataPixels.flip();
      GL21.glBindBuffer(35052, 0);
      GL11.glBindTexture(3553, this.metadataTexture);
      GlStateManager._pixelStore(3317, 1);
      GL11.glTexSubImage2D(3553, 0, 0, 0, 360, 1, 6408, 5126, this.metadataPixels);
      GL11.glBindTexture(3553, 0);
   }

   private void uploadVanillaLight(ClientLevel level) {
      this.vanillaPixels.clear();

      for (int y = 0; y < 32; y++) {
         int wy = this.buildAnchorY + y;
         int row = y * 2304;

         for (int z = 0; z < 48; z++) {
            int wz = this.buildAnchorZ + z;
            int slice = row + z * 48;

            for (int x = 0; x < 48; x++) {
               this.cursor.set(this.buildAnchorX + x, wy, wz);
               int block = Mth.clamp(level.getBrightness(LightLayer.BLOCK, this.cursor), 0, 15);
               this.vanillaPixels.put(slice + x, (byte)(block * 255 / 15));
            }
         }
      }

      this.vanillaPixels.position(0).limit(this.vanillaPixels.capacity());
      GL21.glBindBuffer(35052, 0);
      GL11.glBindTexture(3553, this.vanillaTexture);
      GlStateManager._pixelStore(3314, 0);
      GlStateManager._pixelStore(3316, 0);
      GlStateManager._pixelStore(3315, 0);
      GlStateManager._pixelStore(3317, 1);
      GL11.glTexSubImage2D(3553, 0, 0, 0, 2304, 32, 6403, 5121, this.vanillaPixels);
      GL11.glBindTexture(3553, 0);
   }

   private static int slicesPerRow(int fineX, int fineY, int fineZ) {
      return Math.max(1, (int)Math.ceil(Math.sqrt((double)fineZ * (double)fineY / (double)Math.max(1, fineX))));
   }

   private static int receiverScaleForPixelsPerBlock(int pixelsPerBlock) {
      int p = Math.max(1, pixelsPerBlock);
      if (p >= 512) {
         return 10;
      } else if (p >= 256) {
         return 9;
      } else if (p >= 128) {
         return 8;
      } else if (p >= 64) {
         return 7;
      } else if (p >= 32) {
         return 6;
      } else if (p >= 16) {
         return 5;
      } else if (p >= 8) {
         return 4;
      } else if (p >= 4) {
         return 3;
      } else {
         return p >= 2 ? 2 : 1;
      }
   }

   private static int snapToGrid(int value, int grid) {
      return Mth.floorDiv(value, grid) * grid;
   }
}
