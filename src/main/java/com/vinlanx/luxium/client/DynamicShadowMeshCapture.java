package com.vinlanx.luxium.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class DynamicShadowMeshCapture {
   private static final int MAX_QUADS_PER_CASTER = 192;
   private static final int MAX_CAPTURED_VERTICES = 768;

   private DynamicShadowMeshCapture() {
   }

   public static DynamicShadowMeshCapture.Snapshot capture(ClientLevel level, BlockPos center, double radius, int maxCasters, float partialTick) {
      Minecraft mc = Minecraft.getInstance();
      EntityRenderDispatcher entityDispatcher = mc.getEntityRenderDispatcher();
      BlockEntityRenderDispatcher blockEntityDispatcher = mc.getBlockEntityRenderDispatcher();
      entityDispatcher.prepare(level, mc.gameRenderer.getMainCamera(), mc.getCameraEntity());
      blockEntityDispatcher.prepare(level, mc.gameRenderer.getMainCamera(), mc.hitResult);
      Vec3 centerPos = Vec3.atCenterOf(center);
      double radiusSq = radius * radius;
      Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
      List<DynamicShadowMeshCapture.CapturedCaster> casters = new ArrayList<>(Math.min(maxCasters, 64));
      long nextId = 1L;

      for (Entity entity : level.entitiesForRendering()) {
         if (casters.size() >= maxCasters) {
            return new DynamicShadowMeshCapture.Snapshot(level, List.copyOf(casters));
         }

         if (entity != null && !entity.isRemoved() && !entity.isInvisible()) {
            AABB box = entity.getBoundingBox();
            if (box != null && !(distanceToAabbSq(centerPos, box) > radiusSq)) {
               DynamicShadowMeshCapture.CapturedCaster captured = captureEntity(entityDispatcher, entity, nextId++, partialTick, box.inflate(0.03));
               if (captured != null) {
                  casters.add(captured);
               }
            }
         }
      }

      int chunkRadius = Math.max(1, (int)Math.ceil(radius / 16.0));
      int centerChunkX = center.getX() >> 4;
      int centerChunkZ = center.getZ() >> 4;

      for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
         for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            if (casters.size() >= maxCasters) {
               return new DynamicShadowMeshCapture.Snapshot(level, List.copyOf(casters));
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
            if (chunk != null) {
               for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                  if (casters.size() >= maxCasters) {
                     return new DynamicShadowMeshCapture.Snapshot(level, List.copyOf(casters));
                  }

                  if (blockEntity != null && !blockEntity.isRemoved()) {
                     BlockEntityRenderer renderer = blockEntityDispatcher.getRenderer(blockEntity);
                     if (renderer != null && renderer.shouldRender(blockEntity, cameraPos)) {
                        AABB box = blockEntity.getRenderBoundingBox();
                        if (box == null) {
                           box = new AABB(blockEntity.getBlockPos());
                        }

                        if (!(distanceToAabbSq(centerPos, box) > radiusSq)) {
                           DynamicShadowMeshCapture.CapturedCaster captured = captureBlockEntity(
                              renderer, blockEntity, nextId++, partialTick, box.inflate(0.02)
                           );
                           if (captured != null) {
                              casters.add(captured);
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return new DynamicShadowMeshCapture.Snapshot(level, List.copyOf(casters));
   }

   @Nullable
   private static <E extends Entity> DynamicShadowMeshCapture.CapturedCaster captureEntity(
      EntityRenderDispatcher dispatcher, E entity, long id, float partialTick, AABB fallbackBounds
   ) {
      EntityRenderer<? super E> renderer = dispatcher.getRenderer(entity);
      if (renderer == null) {
         return null;
      } else {
         DynamicShadowMeshCapture.QuadCaptureBufferSource capture = new DynamicShadowMeshCapture.QuadCaptureBufferSource();
         PoseStack poseStack = new PoseStack();
         Vec3 renderOffset = renderer.getRenderOffset(entity, partialTick);
         double renderX = Mth.lerp((double)partialTick, entity.xOld, entity.getX()) + renderOffset.x;
         double renderY = Mth.lerp((double)partialTick, entity.yOld, entity.getY()) + renderOffset.y;
         double renderZ = Mth.lerp((double)partialTick, entity.zOld, entity.getZ()) + renderOffset.z;
         poseStack.translate(renderX, renderY, renderZ);
         int packedLight = dispatcher.getPackedLightCoords(entity, partialTick);
         float renderYaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
         renderer.render(entity, renderYaw, partialTick, poseStack, capture, packedLight);
         return capture.buildCaster(id, fallbackBounds);
      }
   }

   @Nullable
   private static DynamicShadowMeshCapture.CapturedCaster captureBlockEntity(
      BlockEntityRenderer renderer, BlockEntity blockEntity, long id, float partialTick, AABB fallbackBounds
   ) {
      DynamicShadowMeshCapture.QuadCaptureBufferSource capture = new DynamicShadowMeshCapture.QuadCaptureBufferSource();
      PoseStack poseStack = new PoseStack();
      BlockPos pos = blockEntity.getBlockPos();
      poseStack.translate((float)pos.getX(), (float)pos.getY(), (float)pos.getZ());
      int packedLight = blockEntity.getLevel() != null ? LevelRenderer.getLightColor(blockEntity.getLevel(), pos) : 15728880;
      renderer.render(blockEntity, partialTick, poseStack, capture, packedLight, OverlayTexture.NO_OVERLAY);
      return capture.buildCaster(id, fallbackBounds);
   }

   private static double distanceToAabbSq(Vec3 point, AABB box) {
      double dx = 0.0;
      if (point.x < box.minX) {
         dx = box.minX - point.x;
      } else if (point.x > box.maxX) {
         dx = point.x - box.maxX;
      }

      double dy = 0.0;
      if (point.y < box.minY) {
         dy = box.minY - point.y;
      } else if (point.y > box.maxY) {
         dy = point.y - box.maxY;
      }

      double dz = 0.0;
      if (point.z < box.minZ) {
         dz = box.minZ - point.z;
      } else if (point.z > box.maxZ) {
         dz = point.z - box.maxZ;
      }

      return dx * dx + dy * dy + dz * dz;
   }

   public static final class CapturedCaster {
      public final long id;
      public final AABB bounds;
      public final List<Vec3[]> quads;

      CapturedCaster(long id, AABB bounds, List<Vec3[]> quads) {
         this.id = id;
         this.bounds = bounds;
         this.quads = quads;
      }
   }

   private static final class CapturingVertexConsumer implements VertexConsumer {
      private static final int MIN_VERTEX_ALPHA = 16;
      private final boolean capture;
      private final Mode mode;
      private final List<Vec3[]> quads = new ArrayList<>();
      private final DynamicShadowMeshCapture.VertexRecord[] pending = new DynamicShadowMeshCapture.VertexRecord[4];
      private int pendingCount;
      private int capturedVertexCount;
      private int defaultR = 255;
      private int defaultG = 255;
      private int defaultB = 255;
      private int defaultA = 255;
      private boolean useDefaultColor;
      private double x;
      private double y;
      private double z;
      private int a = 255;

      CapturingVertexConsumer(RenderType renderType) {
         this.capture = renderType.mode() == Mode.QUADS;
         this.mode = renderType.mode();
      }

      public VertexConsumer vertex(double x, double y, double z) {
         this.x = x;
         this.y = y;
         this.z = z;
         return this;
      }

      public VertexConsumer color(int red, int green, int blue, int alpha) {
         this.a = alpha;
         return this;
      }

      public VertexConsumer uv(float u, float v) {
         return this;
      }

      public VertexConsumer overlayCoords(int u, int v) {
         return this;
      }

      public VertexConsumer uv2(int u, int v) {
         return this;
      }

      public VertexConsumer normal(float x, float y, float z) {
         return this;
      }

      public void endVertex() {
         if (this.capture && this.mode == Mode.QUADS && this.quads.size() < 192 && this.capturedVertexCount < 768) {
            int alpha = this.useDefaultColor ? this.defaultA : this.a;
            this.pending[this.pendingCount++] = new DynamicShadowMeshCapture.VertexRecord(this.x, this.y, this.z, alpha);
            this.capturedVertexCount++;
            if (this.pendingCount == 4) {
               int averageAlpha = (this.pending[0].alpha + this.pending[1].alpha + this.pending[2].alpha + this.pending[3].alpha) / 4;
               if (averageAlpha >= 16) {
                  this.quads.add(new Vec3[]{this.pending[0].position(), this.pending[1].position(), this.pending[2].position(), this.pending[3].position()});
               }

               this.pendingCount = 0;
            }
         } else {
            this.pendingCount = this.mode == Mode.QUADS && this.pendingCount >= 4 ? 0 : this.pendingCount;
         }
      }

      public void putBulkData(
         Pose pose,
         BakedQuad bakedQuad,
         float[] brightness,
         float red,
         float green,
         float blue,
         float alpha,
         int[] lightmap,
         int overlay,
         boolean readExistingColor
      ) {
         if (this.capture && this.mode == Mode.QUADS && this.quads.size() < 192 && this.capturedVertexCount + 4 <= 768) {
            int[] vertices = bakedQuad.getVertices();
            if (vertices.length < 32) {
               super.putBulkData(pose, bakedQuad, brightness, red, green, blue, alpha, lightmap, overlay, readExistingColor);
            } else {
               Matrix4f matrix = pose.pose();
               Vec3[] quad = new Vec3[4];
               int alphaByte = Mth.clamp((int)(alpha * 255.0F), 0, 255);

               for (int index = 0; index < 4; index++) {
                  int vertexBase = index * 8;
                  float x = Float.intBitsToFloat(vertices[vertexBase]);
                  float y = Float.intBitsToFloat(vertices[vertexBase + 1]);
                  float z = Float.intBitsToFloat(vertices[vertexBase + 2]);
                  Vector4f transformed = matrix.transform(new Vector4f(x, y, z, 1.0F));
                  quad[index] = new Vec3((double)transformed.x(), (double)transformed.y(), (double)transformed.z());
                  int bakedAlpha = vertices[vertexBase + 3] >>> 24;
                  if (bakedAlpha > alphaByte) {
                     alphaByte = bakedAlpha;
                  }
               }

               if (alphaByte >= 16) {
                  this.quads.add(quad);
                  this.capturedVertexCount += 4;
               }
            }
         }
      }

      public void defaultColor(int red, int green, int blue, int alpha) {
         this.defaultR = red;
         this.defaultG = green;
         this.defaultB = blue;
         this.defaultA = alpha;
         this.useDefaultColor = true;
      }

      public void unsetDefaultColor() {
         this.useDefaultColor = false;
      }

      void appendInto(List<Vec3[]> output) {
         int limit = Math.min(this.quads.size(), 192 - output.size());

         for (int index = 0; index < limit; index++) {
            output.add(this.quads.get(index));
         }
      }
   }

   private static final class QuadCaptureBufferSource implements MultiBufferSource {
      private final Map<RenderType, DynamicShadowMeshCapture.CapturingVertexConsumer> consumers = new HashMap<>();

      public VertexConsumer getBuffer(RenderType renderType) {
         return this.consumers.computeIfAbsent(renderType, DynamicShadowMeshCapture.CapturingVertexConsumer::new);
      }

      @Nullable
      DynamicShadowMeshCapture.CapturedCaster buildCaster(long id, AABB fallbackBounds) {
         List<Vec3[]> quads = new ArrayList<>();

         for (DynamicShadowMeshCapture.CapturingVertexConsumer consumer : this.consumers.values()) {
            consumer.appendInto(quads);
            if (quads.size() >= 192) {
               break;
            }
         }

         return quads.isEmpty()
            ? new DynamicShadowMeshCapture.CapturedCaster(id, fallbackBounds, Collections.emptyList())
            : new DynamicShadowMeshCapture.CapturedCaster(id, quadBounds(quads).inflate(0.02), List.copyOf(quads));
      }

      private static AABB quadBounds(List<Vec3[]> quads) {
         double minX = Double.POSITIVE_INFINITY;
         double minY = Double.POSITIVE_INFINITY;
         double minZ = Double.POSITIVE_INFINITY;
         double maxX = Double.NEGATIVE_INFINITY;
         double maxY = Double.NEGATIVE_INFINITY;
         double maxZ = Double.NEGATIVE_INFINITY;

         for (Vec3[] quad : quads) {
            for (Vec3 vertex : quad) {
               minX = Math.min(minX, vertex.x);
               minY = Math.min(minY, vertex.y);
               minZ = Math.min(minZ, vertex.z);
               maxX = Math.max(maxX, vertex.x);
               maxY = Math.max(maxY, vertex.y);
               maxZ = Math.max(maxZ, vertex.z);
            }
         }

         return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
      }
   }

   public static final class Snapshot {
      private static final DynamicShadowMeshCapture.Snapshot EMPTY = new DynamicShadowMeshCapture.Snapshot(null, Collections.emptyList());
      @Nullable
      public final ClientLevel level;
      public final List<DynamicShadowMeshCapture.CapturedCaster> casters;

      Snapshot(@Nullable ClientLevel level, List<DynamicShadowMeshCapture.CapturedCaster> casters) {
         this.level = level;
         this.casters = casters;
      }

      public static DynamicShadowMeshCapture.Snapshot empty() {
         return EMPTY;
      }
   }

   private static record VertexRecord(double x, double y, double z, int alpha) {
      Vec3 position() {
         return new Vec3(this.x, this.y, this.z);
      }
   }
}
