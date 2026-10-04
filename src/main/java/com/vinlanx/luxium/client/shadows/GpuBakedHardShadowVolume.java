package com.vinlanx.luxium.client.shadows;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.vinlanx.luxium.client.ShaderManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

final class GpuBakedHardShadowVolume {
   static final int SIZE_X = 64;
   static final int SIZE_Y = 48;
   static final int SIZE_Z = 64;
   private static final int SURFACE_SAMPLES_PER_BLOCK = 8;
   private static final int DIRECTIONS = 6;
   private static final int SNAP_XZ = 8;
   private static final int SNAP_Y = 4;
   private static final int META_TEXELS_PER_SOURCE = 9;
   private static final int TOTAL_LAYERS = depthForDirection(0)
      + depthForDirection(1)
      + depthForDirection(2)
      + depthForDirection(3)
      + depthForDirection(4)
      + depthForDirection(5);
   private final int[] textures = new int[]{-1, -1, -1, -1, -1, -1};
   private boolean ready;
   private int buildTexture = -1;
   private final FloatBuffer metadataPixels = ByteBuffer.allocateDirect(4608).order(ByteOrder.nativeOrder()).asFloatBuffer();
   private int fbo = -1;
   private int metadataTexture = -1;
   private int buildDirection;
   private int buildLayerCursor;
   private boolean building;
   private long buildingSignature = Long.MIN_VALUE;
   private long activeSignature = Long.MIN_VALUE;
   private int activeAnchorX = Integer.MIN_VALUE;
   private int activeAnchorY = Integer.MIN_VALUE;
   private int activeAnchorZ = Integer.MIN_VALUE;
   private int buildAnchorX;
   private int buildAnchorY;
   private int buildAnchorZ;

   boolean isReady() {
      return this.ready;
   }

   int textureId(int direction) {
      return this.isReady() && direction >= 0 && direction < 6 ? this.textures[direction] : -1;
   }

   float minX() {
      return (float)this.activeAnchorX;
   }

   float minY() {
      return (float)this.activeAnchorY;
   }

   float minZ() {
      return (float)this.activeAnchorZ;
   }

   float sizeX() {
      return 64.0F;
   }

   float sizeY() {
      return 48.0F;
   }

   float sizeZ() {
      return 64.0F;
   }

   boolean matches(Vec3 cameraPos, long signature) {
      if (this.isReady() && !this.building && cameraPos != null) {
         int targetAnchorX = snap(Mth.floor(cameraPos.x) - 32, 8);
         int targetAnchorY = snap(Mth.floor(cameraPos.y) - 24, 4);
         int targetAnchorZ = snap(Mth.floor(cameraPos.z) - 32, 8);
         return this.activeSignature == signature
            && this.activeAnchorX == targetAnchorX
            && this.activeAnchorY == targetAnchorY
            && this.activeAnchorZ == targetAnchorZ;
      } else {
         return false;
      }
   }

   void update(
      Vec3 cameraPos,
      int sourceCount,
      float[] lightPosRadius,
      float[] sourceGeometry,
      float[] sourceColors,
      float[] atlasRects,
      int shadowAtlasTexture,
      long signature,
      int layerBudget
   ) {
      if (sourceCount > 0 && shadowAtlasTexture > 0) {
         ShaderInstance shader = ShaderManager.getGpuHardShadowBakeShader();
         if (shader != null) {
            int targetAnchorX = snap(Mth.floor(cameraPos.x) - 32, 8);
            int targetAnchorY = snap(Mth.floor(cameraPos.y) - 24, 4);
            int targetAnchorZ = snap(Mth.floor(cameraPos.z) - 32, 8);
            if (!this.isReady()
               || this.building
               || this.activeSignature != signature
               || this.activeAnchorX != targetAnchorX
               || this.activeAnchorY != targetAnchorY
               || this.activeAnchorZ != targetAnchorZ) {
               if (!this.building
                  || this.buildingSignature != signature
                  || this.buildAnchorX != targetAnchorX
                  || this.buildAnchorY != targetAnchorY
                  || this.buildAnchorZ != targetAnchorZ) {
                  this.ensureResources();
                  this.buildAnchorX = targetAnchorX;
                  this.buildAnchorY = targetAnchorY;
                  this.buildAnchorZ = targetAnchorZ;
                  this.buildingSignature = signature;
                  this.buildDirection = 0;
                  this.buildLayerCursor = 0;
                  this.building = true;
                  boolean sameAnchor = this.ready
                     && this.activeAnchorX == this.buildAnchorX
                     && this.activeAnchorY == this.buildAnchorY
                     && this.activeAnchorZ == this.buildAnchorZ;
                  if (!sameAnchor) {
                     this.ready = false;
                  }

                  if (this.buildTexture != -1) {
                     TextureUtil.releaseTextureId(this.buildTexture);
                     this.buildTexture = -1;
                  }

                  this.uploadMetadata(cameraPos, sourceCount, lightPosRadius, sourceGeometry, sourceColors, atlasRects);
               }

               int oldFramebuffer = GL11.glGetInteger(36006);
               IntBuffer oldViewport = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asIntBuffer();
               GL11.glGetIntegerv(2978, oldViewport);
               int oldViewportX = oldViewport.get(0);
               int oldViewportY = oldViewport.get(1);
               int oldViewportW = oldViewport.get(2);
               int oldViewportH = oldViewport.get(3);
               boolean oldDepth = GL11.glIsEnabled(2929);
               boolean oldCull = GL11.glIsEnabled(2884);
               boolean oldBlend = GL11.glIsEnabled(3042);
               boolean oldScissor = GL11.glIsEnabled(3089);
               Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
               VertexSorting oldSorting = RenderSystem.getVertexSorting();

               try {
                  GL11.glDisable(2929);
                  GL11.glDisable(2884);
                  GL11.glDisable(3042);
                  GL11.glDisable(3089);
                  shader.setSampler("ShadowAtlas", shadowAtlasTexture);
                  shader.setSampler("SourceMetadata", this.metadataTexture);
                  shader.MODEL_VIEW_MATRIX.set(new Matrix4f());
                  if (shader.getUniform("SourceCount") != null) {
                     shader.getUniform("SourceCount").set(sourceCount);
                  }

                  int budget = Math.max(1, Math.min(TOTAL_LAYERS, layerBudget));

                  while (budget > 0 && this.buildDirection < 6) {
                     int direction = this.buildDirection;
                     if (this.buildTexture == -1) {
                        this.buildTexture = createRgba16fTexture3D(widthForDirection(direction), heightForDirection(direction), depthForDirection(direction));
                     }

                     int width = widthForDirection(direction);
                     int height = heightForDirection(direction);
                     int depth = depthForDirection(direction);
                     int remaining = depth - this.buildLayerCursor;
                     int layersThisFrame = Math.min(remaining, budget);
                     int endLayer = this.buildLayerCursor + layersThisFrame;
                     GL11.glViewport(0, 0, width, height);
                     Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float)width, (float)height, 0.0F, -1.0F, 1.0F);
                     RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
                     shader.PROJECTION_MATRIX.set(ortho);
                     if (shader.getUniform("DirectionIndex") != null) {
                        shader.getUniform("DirectionIndex").set(direction);
                     }

                     if (shader.getUniform("ReceiverScaleX") != null) {
                        shader.getUniform("ReceiverScaleX").set(scaleXForDirection(direction));
                     }

                     if (shader.getUniform("ReceiverScaleY") != null) {
                        shader.getUniform("ReceiverScaleY").set(scaleYForDirection(direction));
                     }

                     if (shader.getUniform("ReceiverScaleZ") != null) {
                        shader.getUniform("ReceiverScaleZ").set(scaleZForDirection(direction));
                     }

                     while (this.buildLayerCursor < endLayer) {
                        this.attachLayer(this.buildTexture, this.buildLayerCursor);
                        if (shader.getUniform("SliceZ") != null) {
                           shader.getUniform("SliceZ").set(this.buildLayerCursor);
                        }

                        shader.apply();
                        drawFullscreen(width, height);
                        shader.clear();
                        this.buildLayerCursor++;
                     }

                     budget -= layersThisFrame;
                     if (this.buildLayerCursor >= depth) {
                        if (this.textures[direction] != -1) {
                           TextureUtil.releaseTextureId(this.textures[direction]);
                        }

                        this.textures[direction] = this.buildTexture;
                        this.buildTexture = -1;
                        this.buildDirection++;
                        this.buildLayerCursor = 0;
                     }
                  }

                  if (this.buildDirection >= 6) {
                     this.ready = true;
                     this.activeSignature = this.buildingSignature;
                     this.activeAnchorX = this.buildAnchorX;
                     this.activeAnchorY = this.buildAnchorY;
                     this.activeAnchorZ = this.buildAnchorZ;
                     this.building = false;
                  }
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

                  RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
                  GlStateManager._activeTexture(33984);
               }
            }
         }
      }
   }

   void release() {
      if (this.fbo != -1) {
         GL30.glDeleteFramebuffers(this.fbo);
         this.fbo = -1;
      }

      if (this.metadataTexture != -1) {
         TextureUtil.releaseTextureId(this.metadataTexture);
         this.metadataTexture = -1;
      }

      for (int d = 0; d < 6; d++) {
         if (this.textures[d] != -1) {
            TextureUtil.releaseTextureId(this.textures[d]);
            this.textures[d] = -1;
         }
      }

      if (this.buildTexture != -1) {
         TextureUtil.releaseTextureId(this.buildTexture);
         this.buildTexture = -1;
      }

      this.ready = false;
      this.buildDirection = 0;
      this.buildLayerCursor = 0;
      this.building = false;
      this.buildingSignature = Long.MIN_VALUE;
      this.activeSignature = Long.MIN_VALUE;
      this.activeAnchorX = this.activeAnchorY = this.activeAnchorZ = Integer.MIN_VALUE;
   }

   private void ensureResources() {
      if (this.fbo == -1) {
         this.fbo = GL30.glGenFramebuffers();
      }

      if (this.metadataTexture == -1) {
         this.metadataTexture = TextureUtil.generateTextureId();
         GL11.glBindTexture(3553, this.metadataTexture);
         GL11.glTexParameteri(3553, 10241, 9728);
         GL11.glTexParameteri(3553, 10240, 9728);
         GL11.glTexParameteri(3553, 10242, 33071);
         GL11.glTexParameteri(3553, 10243, 33071);
         GL11.glTexImage2D(3553, 0, 34836, 288, 1, 0, 6408, 5126, (ByteBuffer)null);
         GL11.glBindTexture(3553, 0);
      }
   }

   private void uploadMetadata(Vec3 cameraPos, int sourceCount, float[] lightPosRadius, float[] sourceGeometry, float[] sourceColors, float[] atlasRects) {
      this.metadataPixels.clear();

      for (int i = 0; i < 32; i++) {
         if (i < sourceCount) {
            int base = i * 4;
            int rectBase = i * 24;
            this.metadataPixels.put((float)(cameraPos.x + (double)lightPosRadius[base] - (double)this.buildAnchorX));
            this.metadataPixels.put((float)(cameraPos.y + (double)lightPosRadius[base + 1] - (double)this.buildAnchorY));
            this.metadataPixels.put((float)(cameraPos.z + (double)lightPosRadius[base + 2] - (double)this.buildAnchorZ));
            this.metadataPixels.put(lightPosRadius[base + 3]);
            this.metadataPixels.put((float)(cameraPos.x + (double)sourceGeometry[base] - (double)this.buildAnchorX));
            this.metadataPixels.put((float)(cameraPos.y + (double)sourceGeometry[base + 1] - (double)this.buildAnchorY));
            this.metadataPixels.put((float)(cameraPos.z + (double)sourceGeometry[base + 2] - (double)this.buildAnchorZ));
            this.metadataPixels.put(sourceGeometry[base + 3]);
            this.metadataPixels.put(sourceColors[base]);
            this.metadataPixels.put(sourceColors[base + 1]);
            this.metadataPixels.put(sourceColors[base + 2]);
            this.metadataPixels.put(atlasRects[rectBase + 7]);
            this.metadataPixels.put(atlasRects, rectBase, 24);
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
      GL11.glTexSubImage2D(3553, 0, 0, 0, 288, 1, 6408, 5126, this.metadataPixels);
      GL11.glBindTexture(3553, 0);
   }

   private void attachLayer(int texture, int z) {
      GL30.glBindFramebuffer(36160, this.fbo);
      GL30.glFramebufferTextureLayer(36160, 36064, texture, 0, z);
      GL11.glDrawBuffer(36064);
   }

   private static int createRgba16fTexture3D(int width, int height, int depth) {
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

   private static int widthForDirection(int direction) {
      return switch (direction) {
         case 0, 1 -> 64;
         case 2, 3, 4, 5 -> 512;
         default -> 64;
      };
   }

   private static int heightForDirection(int direction) {
      return switch (direction) {
         case 0, 1, 4, 5 -> 384;
         case 2, 3 -> 48;
         default -> 48;
      };
   }

   private static int depthForDirection(int direction) {
      return switch (direction) {
         case 0, 1, 2, 3 -> 512;
         case 4, 5 -> 64;
         default -> 64;
      };
   }

   private static int scaleXForDirection(int direction) {
      return direction != 0 && direction != 1 ? 8 : 1;
   }

   private static int scaleYForDirection(int direction) {
      return direction != 2 && direction != 3 ? 8 : 1;
   }

   private static int scaleZForDirection(int direction) {
      return direction != 4 && direction != 5 ? 8 : 1;
   }

   private static int snap(int value, int grid) {
      return Mth.floorDiv(value, grid) * grid;
   }
}
