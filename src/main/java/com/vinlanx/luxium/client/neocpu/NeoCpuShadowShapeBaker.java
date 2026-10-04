package com.vinlanx.luxium.client.neocpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.FastColor.ARGB32;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

final class NeoCpuShadowShapeBaker {
   private static final double SURFACE_EPSILON = 1.0E-5;
   private static final double SHADOW_EPSILON = 1.0E-6;
   private static final Direction[] ALL_FACES = Direction.values();
   private static final int SPRITE_ALPHA_THRESHOLD = 16;
   private static final float MIN_MODEL_OPACITY = 0.003921569F;
   private static final NeoCpuShadowTypes.Box[] EMPTY_BOXES = new NeoCpuShadowTypes.Box[0];
   private static final NeoCpuShadowShape.LocalQuadTemplate[] EMPTY_QUADS = new NeoCpuShadowShape.LocalQuadTemplate[0];
   private static final ThreadLocal<RandomSource> RANDOM_SOURCE_TL = ThreadLocal.withInitial(RandomSource::create);
   private final ConcurrentHashMap<TextureAtlasSprite, NeoCpuShadowShapeBaker.SpriteAlphaMask> spriteMaskCache = new ConcurrentHashMap<>();

   NeoCpuShadowShape bake(BlockState state, ClientLevel level, BlockPos pos) {
      List<AABB> surfaceBoxes = collectRenderSurfaceBoxes(state, level, pos);
      NeoCpuShadowShape.LocalQuadTemplate[] modelOccluders = this.collectModelOccluders(state, level, pos);
      NeoCpuShadowTypes.Box[] occluderBoxes = modelOccluders.length > 0 ? EMPTY_BOXES : collectOccluderBoxes(state, level, pos);
      if (surfaceBoxes.isEmpty() && occluderBoxes.length == 0 && modelOccluders.length == 0) {
         return NeoCpuShadowShape.EMPTY;
      } else {
         ArrayList<NeoCpuShadowShape.ReceiverTemplate> receivers = new ArrayList<>(surfaceBoxes.size() * 6);

         for (int boxIndex = 0; boxIndex < surfaceBoxes.size(); boxIndex++) {
            AABB box = surfaceBoxes.get(boxIndex);

            for (Direction face : ALL_FACES) {
               NeoCpuShadowShapeBaker.SurfaceRect initialRect = canonicalFaceRect(face, box);
               if (initialRect != null) {
                  double plane = facePlaneCoordinate(face, box);
                  List<NeoCpuShadowShapeBaker.SurfaceRect> fragments = new ArrayList<>();
                  fragments.add(initialRect);

                  for (int otherIndex = 0; otherIndex < surfaceBoxes.size() && !fragments.isEmpty(); otherIndex++) {
                     if (otherIndex != boxIndex) {
                        AABB other = surfaceBoxes.get(otherIndex);
                        if (coversFacePlane(face, plane, other)) {
                           fragments = subtractSurfaceRectList(fragments, canonicalFaceRect(face, other));
                        }
                     }
                  }

                  for (NeoCpuShadowShapeBaker.SurfaceRect fragment : fragments) {
                     if (fragment != null && !(fragment.width() <= 1.0E-5) && !(fragment.height() <= 1.0E-5)) {
                        receivers.add(new NeoCpuShadowShape.ReceiverTemplate(face, plane, fragment.minU, fragment.maxU, fragment.minV, fragment.maxV));
                     }
                  }
               }
            }
         }

         boolean floodBlocking = state.canOcclude() && state.isCollisionShapeFullBlock(level, pos);
         return new NeoCpuShadowShape(occluderBoxes, modelOccluders, receivers.toArray(new NeoCpuShadowShape.ReceiverTemplate[0]), floodBlocking);
      }
   }

   private NeoCpuShadowShape.LocalQuadTemplate[] collectModelOccluders(BlockState state, ClientLevel level, BlockPos pos) {
      if (state.getRenderShape() != RenderShape.MODEL) {
         return EMPTY_QUADS;
      } else {
         BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
         if (model == null) {
            return EMPTY_QUADS;
         } else {
            List<BakedQuad> quads = collectModelQuads(model, state);
            if (quads.isEmpty()) {
               return EMPTY_QUADS;
            } else {
               boolean needsModelOccluders = !state.isCollisionShapeFullBlock(level, pos);

               for (BakedQuad quad : quads) {
                  TextureAtlasSprite sprite = quad.getSprite();
                  if (sprite != null) {
                     NeoCpuShadowShapeBaker.SpriteAlphaMask mask = this.spriteMaskCache.computeIfAbsent(sprite, this::buildSpriteMask);
                     if (!mask.fullyOpaque || mask.averageOpacity < 0.999F) {
                        needsModelOccluders = true;
                        break;
                     }
                  }
               }

               if (!needsModelOccluders) {
                  return EMPTY_QUADS;
               } else {
                  ArrayList<NeoCpuShadowShape.LocalQuadTemplate> result = new ArrayList<>(quads.size() * 4);

                  for (BakedQuad quadx : quads) {
                     TextureAtlasSprite sprite = quadx.getSprite();
                     if (sprite != null) {
                        NeoCpuShadowShapeBaker.LocalTexturedQuad texturedQuad = NeoCpuShadowShapeBaker.LocalTexturedQuad.from(quadx);
                        if (texturedQuad != null) {
                           NeoCpuShadowShapeBaker.SpriteAlphaMask mask = this.spriteMaskCache.computeIfAbsent(sprite, this::buildSpriteMask);
                           if (!mask.fullyTransparent && !(mask.averageOpacity < 0.003921569F)) {
                              if (mask.fullyOpaque) {
                                 result.add(new NeoCpuShadowShape.LocalQuadTemplate(texturedQuad.worldVertices, mask.averageOpacity));
                              } else {
                                 NeoCpuShadowShapeBaker.SpritePixelBounds usedPixels = computeQuadPixelBounds(texturedQuad, sprite);
                                 if (usedPixels != null) {
                                    for (NeoCpuShadowShapeBaker.SpriteRect rect : mask.rectangles) {
                                       double minPixelX = Math.max((double)rect.minX, usedPixels.minX);
                                       double maxPixelX = Math.min((double)rect.maxX, usedPixels.maxX);
                                       double minPixelY = Math.max((double)rect.minY, usedPixels.minY);
                                       double maxPixelY = Math.min((double)rect.maxY, usedPixels.maxY);
                                       if (!(maxPixelX - minPixelX <= 1.0E-6) && !(maxPixelY - minPixelY <= 1.0E-6)) {
                                          Vec3[] vertices = buildOpaqueRectVertices(texturedQuad, sprite, minPixelX, maxPixelX, minPixelY, maxPixelY);
                                          if (vertices != null) {
                                             result.add(new NeoCpuShadowShape.LocalQuadTemplate(vertices, mask.averageOpacity));
                                          }
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }

                  return result.isEmpty() ? EMPTY_QUADS : result.toArray(EMPTY_QUADS);
               }
            }
         }
      }
   }

   private static List<AABB> collectRenderSurfaceBoxes(BlockState state, ClientLevel level, BlockPos pos) {
      VoxelShape shape = state.getShape(level, pos);
      if (shape.isEmpty()) {
         return List.of();
      } else {
         List<AABB> aabbs = shape.toAabbs();
         if (aabbs.isEmpty()) {
            return List.of();
         } else {
            ArrayList<AABB> filtered = new ArrayList<>(aabbs.size());

            for (AABB aabb : aabbs) {
               if (!(aabb.getXsize() <= 1.0E-5) && !(aabb.getYsize() <= 1.0E-5) && !(aabb.getZsize() <= 1.0E-5)) {
                  filtered.add(aabb);
               }
            }

            return filtered;
         }
      }
   }

   private static NeoCpuShadowTypes.Box[] collectOccluderBoxes(BlockState state, ClientLevel level, BlockPos pos) {
      VoxelShape shape = state.getCollisionShape(level, pos);
      if (shape.isEmpty()) {
         return EMPTY_BOXES;
      } else if (state.isCollisionShapeFullBlock(level, pos)) {
         return new NeoCpuShadowTypes.Box[]{new NeoCpuShadowTypes.Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0L, 1.0F)};
      } else {
         ArrayList<NeoCpuShadowTypes.Box> boxes = new ArrayList<>();
         shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            if (!(maxX - minX <= 1.0E-5) && !(maxY - minY <= 1.0E-5) && !(maxZ - minZ <= 1.0E-5)) {
               boxes.add(new NeoCpuShadowTypes.Box(minX, minY, minZ, maxX, maxY, maxZ, 0L, 1.0F));
            }
         });
         return boxes.isEmpty() ? EMPTY_BOXES : boxes.toArray(EMPTY_BOXES);
      }
   }

   private static List<BakedQuad> collectModelQuads(BakedModel model, BlockState state) {
      List<BakedQuad> quads = new ArrayList<>();
      RandomSource random = RANDOM_SOURCE_TL.get();

      for (Direction face : ALL_FACES) {
         random.setSeed(42L);
         quads.addAll(model.getQuads(state, face, random));
      }

      random.setSeed(42L);
      quads.addAll(model.getQuads(state, null, random));
      return quads;
   }

   private NeoCpuShadowShapeBaker.SpriteAlphaMask buildSpriteMask(TextureAtlasSprite sprite) {
      int width = Math.max(1, sprite.contents().width());
      int height = Math.max(1, sprite.contents().height());
      boolean[] opaque = new boolean[width * height];
      boolean anyOpaque = false;
      boolean anyTransparent = false;
      long opaqueAlphaSum = 0L;
      int opaquePixelCount = 0;

      for (int pixelY = 0; pixelY < height; pixelY++) {
         int rowOffset = pixelY * width;

         for (int pixelX = 0; pixelX < width; pixelX++) {
            int alpha = ARGB32.alpha(sprite.getPixelRGBA(0, pixelX, pixelY));
            boolean solid = alpha >= 16;
            opaque[rowOffset + pixelX] = solid;
            anyOpaque |= solid;
            anyTransparent |= !solid;
            if (solid) {
               opaqueAlphaSum += (long)alpha;
               opaquePixelCount++;
            }
         }
      }

      if (!anyOpaque) {
         return NeoCpuShadowShapeBaker.SpriteAlphaMask.transparentMask();
      } else {
         float averageOpacity = opaquePixelCount <= 0 ? 0.0F : Mth.clamp((float)opaqueAlphaSum / (float)((double)opaquePixelCount * 255.0), 0.0F, 1.0F);
         if (!anyTransparent) {
            return averageOpacity >= 0.999F
               ? NeoCpuShadowShapeBaker.SpriteAlphaMask.opaqueMask()
               : new NeoCpuShadowShapeBaker.SpriteAlphaMask(new NeoCpuShadowShapeBaker.SpriteRect[0], true, false, averageOpacity);
         } else {
            boolean[] consumed = new boolean[opaque.length];
            ArrayList<NeoCpuShadowShapeBaker.SpriteRect> rectangles = new ArrayList<>();

            for (int pixelY = 0; pixelY < height; pixelY++) {
               for (int pixelXx = 0; pixelXx < width; pixelXx++) {
                  int index = pixelY * width + pixelXx;
                  if (opaque[index] && !consumed[index]) {
                     int rectWidth;
                     for (rectWidth = 1; pixelXx + rectWidth < width; rectWidth++) {
                        int nextIndex = pixelY * width + pixelXx + rectWidth;
                        if (!opaque[nextIndex] || consumed[nextIndex]) {
                           break;
                        }
                     }

                     int rectHeight = 1;
                     boolean canGrow = true;

                     while (pixelY + rectHeight < height && canGrow) {
                        int nextRowOffset = (pixelY + rectHeight) * width;
                        int scanX = 0;

                        while (true) {
                           if (scanX < rectWidth) {
                              int nextIndex = nextRowOffset + pixelXx + scanX;
                              if (opaque[nextIndex] && !consumed[nextIndex]) {
                                 scanX++;
                                 continue;
                              }

                              canGrow = false;
                           }

                           if (canGrow) {
                              rectHeight++;
                           }
                           break;
                        }
                     }

                     for (int fillY = 0; fillY < rectHeight; fillY++) {
                        int fillRowOffset = (pixelY + fillY) * width;

                        for (int fillX = 0; fillX < rectWidth; fillX++) {
                           consumed[fillRowOffset + pixelXx + fillX] = true;
                        }
                     }

                     rectangles.add(new NeoCpuShadowShapeBaker.SpriteRect(pixelXx, pixelXx + rectWidth, pixelY, pixelY + rectHeight));
                  }
               }
            }

            return new NeoCpuShadowShapeBaker.SpriteAlphaMask(rectangles.toArray(new NeoCpuShadowShapeBaker.SpriteRect[0]), false, false, averageOpacity);
         }
      }
   }

   @Nullable
   private static NeoCpuShadowShapeBaker.SpritePixelBounds computeQuadPixelBounds(NeoCpuShadowShapeBaker.LocalTexturedQuad quad, TextureAtlasSprite sprite) {
      int spriteWidth = Math.max(1, sprite.contents().width());
      int spriteHeight = Math.max(1, sprite.contents().height());
      double minPixelX = Double.POSITIVE_INFINITY;
      double maxPixelX = Double.NEGATIVE_INFINITY;
      double minPixelY = Double.POSITIVE_INFINITY;
      double maxPixelY = Double.NEGATIVE_INFINITY;

      for (NeoCpuShadowShapeBaker.AtlasUv uv : quad.atlasUvs) {
         double pixelX = (double)(sprite.getUOffset((float)uv.u) * (float)spriteWidth) / 16.0;
         double pixelY = (double)(sprite.getVOffset((float)uv.v) * (float)spriteHeight) / 16.0;
         minPixelX = Math.min(minPixelX, pixelX);
         maxPixelX = Math.max(maxPixelX, pixelX);
         minPixelY = Math.min(minPixelY, pixelY);
         maxPixelY = Math.max(maxPixelY, pixelY);
      }

      if (Double.isFinite(minPixelX) && Double.isFinite(minPixelY) && Double.isFinite(maxPixelX) && Double.isFinite(maxPixelY)) {
         minPixelX = Mth.clamp(minPixelX, 0.0, (double)spriteWidth);
         maxPixelX = Mth.clamp(maxPixelX, 0.0, (double)spriteWidth);
         minPixelY = Mth.clamp(minPixelY, 0.0, (double)spriteHeight);
         maxPixelY = Mth.clamp(maxPixelY, 0.0, (double)spriteHeight);
         return !(maxPixelX - minPixelX <= 1.0E-6) && !(maxPixelY - minPixelY <= 1.0E-6)
            ? new NeoCpuShadowShapeBaker.SpritePixelBounds(minPixelX, maxPixelX, minPixelY, maxPixelY)
            : null;
      } else {
         return null;
      }
   }

   @Nullable
   private static Vec3[] buildOpaqueRectVertices(
      NeoCpuShadowShapeBaker.LocalTexturedQuad quad, TextureAtlasSprite sprite, double minPixelX, double maxPixelX, double minPixelY, double maxPixelY
   ) {
      int spriteWidth = Math.max(1, sprite.contents().width());
      int spriteHeight = Math.max(1, sprite.contents().height());
      double minAtlasU = (double)sprite.getU(minPixelX * 16.0 / (double)spriteWidth);
      double maxAtlasU = (double)sprite.getU(maxPixelX * 16.0 / (double)spriteWidth);
      double minAtlasV = (double)sprite.getV(minPixelY * 16.0 / (double)spriteHeight);
      double maxAtlasV = (double)sprite.getV(maxPixelY * 16.0 / (double)spriteHeight);
      Vec3 topLeft = mapAtlasUvToWorld(quad, minAtlasU, minAtlasV);
      Vec3 topRight = mapAtlasUvToWorld(quad, maxAtlasU, minAtlasV);
      Vec3 bottomRight = mapAtlasUvToWorld(quad, maxAtlasU, maxAtlasV);
      Vec3 bottomLeft = mapAtlasUvToWorld(quad, minAtlasU, maxAtlasV);
      return topLeft != null && topRight != null && bottomRight != null && bottomLeft != null ? new Vec3[]{topLeft, topRight, bottomRight, bottomLeft} : null;
   }

   @Nullable
   private static Vec3 mapAtlasUvToWorld(NeoCpuShadowShapeBaker.LocalTexturedQuad quad, double atlasU, double atlasV) {
      Vec3 mapped = mapAtlasUvToWorldTriangle(quad, 0, 1, 2, atlasU, atlasV);
      return mapped != null ? mapped : mapAtlasUvToWorldTriangle(quad, 0, 2, 3, atlasU, atlasV);
   }

   @Nullable
   private static Vec3 mapAtlasUvToWorldTriangle(
      NeoCpuShadowShapeBaker.LocalTexturedQuad quad, int indexA, int indexB, int indexC, double atlasU, double atlasV
   ) {
      NeoCpuShadowShapeBaker.AtlasUv uvA = quad.atlasUvs[indexA];
      NeoCpuShadowShapeBaker.AtlasUv uvB = quad.atlasUvs[indexB];
      NeoCpuShadowShapeBaker.AtlasUv uvC = quad.atlasUvs[indexC];
      double denominator = (uvB.v - uvC.v) * (uvA.u - uvC.u) + (uvC.u - uvB.u) * (uvA.v - uvC.v);
      if (Math.abs(denominator) <= 1.0E-6) {
         return null;
      } else {
         double weightA = ((uvB.v - uvC.v) * (atlasU - uvC.u) + (uvC.u - uvB.u) * (atlasV - uvC.v)) / denominator;
         double weightB = ((uvC.v - uvA.v) * (atlasU - uvC.u) + (uvA.u - uvC.u) * (atlasV - uvC.v)) / denominator;
         double weightC = 1.0 - weightA - weightB;
         double epsilon = 1.0E-4;
         if (!(weightA < -epsilon) && !(weightB < -epsilon) && !(weightC < -epsilon)) {
            Vec3 vertexA = quad.worldVertices[indexA];
            Vec3 vertexB = quad.worldVertices[indexB];
            Vec3 vertexC = quad.worldVertices[indexC];
            return new Vec3(
               vertexA.x * weightA + vertexB.x * weightB + vertexC.x * weightC,
               vertexA.y * weightA + vertexB.y * weightB + vertexC.y * weightC,
               vertexA.z * weightA + vertexB.z * weightB + vertexC.z * weightC
            );
         } else {
            return null;
         }
      }
   }

   private static List<NeoCpuShadowShapeBaker.SurfaceRect> subtractSurfaceRectList(
      List<NeoCpuShadowShapeBaker.SurfaceRect> sourceRects, NeoCpuShadowShapeBaker.SurfaceRect coverRect
   ) {
      if (coverRect != null && !sourceRects.isEmpty()) {
         ArrayList<NeoCpuShadowShapeBaker.SurfaceRect> result = new ArrayList<>(sourceRects.size());

         for (NeoCpuShadowShapeBaker.SurfaceRect rect : sourceRects) {
            subtractSurfaceRect(rect, coverRect, result);
         }

         return result;
      } else {
         return sourceRects;
      }
   }

   private static void subtractSurfaceRect(
      NeoCpuShadowShapeBaker.SurfaceRect sourceRect, NeoCpuShadowShapeBaker.SurfaceRect coverRect, List<NeoCpuShadowShapeBaker.SurfaceRect> out
   ) {
      double overlapMinU = Math.max(sourceRect.minU, coverRect.minU);
      double overlapMaxU = Math.min(sourceRect.maxU, coverRect.maxU);
      double overlapMinV = Math.max(sourceRect.minV, coverRect.minV);
      double overlapMaxV = Math.min(sourceRect.maxV, coverRect.maxV);
      if (!(overlapMaxU - overlapMinU <= 1.0E-5) && !(overlapMaxV - overlapMinV <= 1.0E-5)) {
         if (overlapMinV - sourceRect.minV > 1.0E-5) {
            out.add(new NeoCpuShadowShapeBaker.SurfaceRect(sourceRect.minU, sourceRect.maxU, sourceRect.minV, overlapMinV));
         }

         if (sourceRect.maxV - overlapMaxV > 1.0E-5) {
            out.add(new NeoCpuShadowShapeBaker.SurfaceRect(sourceRect.minU, sourceRect.maxU, overlapMaxV, sourceRect.maxV));
         }

         if (overlapMinU - sourceRect.minU > 1.0E-5) {
            out.add(new NeoCpuShadowShapeBaker.SurfaceRect(sourceRect.minU, overlapMinU, overlapMinV, overlapMaxV));
         }

         if (sourceRect.maxU - overlapMaxU > 1.0E-5) {
            out.add(new NeoCpuShadowShapeBaker.SurfaceRect(overlapMaxU, sourceRect.maxU, overlapMinV, overlapMaxV));
         }
      } else {
         out.add(sourceRect);
      }
   }

   private static boolean coversFacePlane(Direction face, double planeCoordinate, AABB box) {
      return switch (face) {
         case UP -> Math.abs(box.minY - planeCoordinate) <= 1.0E-5;
         case DOWN -> Math.abs(box.maxY - planeCoordinate) <= 1.0E-5;
         case NORTH -> Math.abs(box.maxZ - planeCoordinate) <= 1.0E-5;
         case SOUTH -> Math.abs(box.minZ - planeCoordinate) <= 1.0E-5;
         case WEST -> Math.abs(box.maxX - planeCoordinate) <= 1.0E-5;
         case EAST -> Math.abs(box.minX - planeCoordinate) <= 1.0E-5;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static double facePlaneCoordinate(Direction face, AABB box) {
      return switch (face) {
         case UP -> box.maxY;
         case DOWN -> box.minY;
         case NORTH -> box.minZ;
         case SOUTH -> box.maxZ;
         case WEST -> box.minX;
         case EAST -> box.maxX;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static NeoCpuShadowShapeBaker.SurfaceRect canonicalFaceRect(Direction face, AABB box) {
      return switch (face) {
         case UP, DOWN -> surfaceRect(box.minX, box.maxX, box.minZ, box.maxZ);
         case NORTH, SOUTH -> surfaceRect(box.minX, box.maxX, box.minY, box.maxY);
         case WEST, EAST -> surfaceRect(box.minZ, box.maxZ, box.minY, box.maxY);
         default -> throw new IncompatibleClassChangeError();
      };
   }

   private static NeoCpuShadowShapeBaker.SurfaceRect surfaceRect(double minU, double maxU, double minV, double maxV) {
      return !(maxU - minU <= 1.0E-5) && !(maxV - minV <= 1.0E-5) ? new NeoCpuShadowShapeBaker.SurfaceRect(minU, maxU, minV, maxV) : null;
   }

   private static record AtlasUv(double u, double v) {
   }

   private static final class LocalTexturedQuad {
      final Vec3[] worldVertices;
      final NeoCpuShadowShapeBaker.AtlasUv[] atlasUvs;

      private LocalTexturedQuad(Vec3[] worldVertices, NeoCpuShadowShapeBaker.AtlasUv[] atlasUvs) {
         this.worldVertices = worldVertices;
         this.atlasUvs = atlasUvs;
      }

      @Nullable
      static NeoCpuShadowShapeBaker.LocalTexturedQuad from(BakedQuad quad) {
         int[] vertices = quad.getVertices();
         if (vertices.length < 32) {
            return null;
         } else {
            Vec3[] worldVertices = new Vec3[4];
            NeoCpuShadowShapeBaker.AtlasUv[] atlasUvs = new NeoCpuShadowShapeBaker.AtlasUv[4];

            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
               int baseIndex = vertexIndex * 8;
               double x = (double)Float.intBitsToFloat(vertices[baseIndex]);
               double y = (double)Float.intBitsToFloat(vertices[baseIndex + 1]);
               double z = (double)Float.intBitsToFloat(vertices[baseIndex + 2]);
               double u = (double)Float.intBitsToFloat(vertices[baseIndex + 4]);
               double v = (double)Float.intBitsToFloat(vertices[baseIndex + 5]);
               worldVertices[vertexIndex] = new Vec3(x, y, z);
               atlasUvs[vertexIndex] = new NeoCpuShadowShapeBaker.AtlasUv(u, v);
            }

            return new NeoCpuShadowShapeBaker.LocalTexturedQuad(worldVertices, atlasUvs);
         }
      }
   }

   private static record SpriteAlphaMask(NeoCpuShadowShapeBaker.SpriteRect[] rectangles, boolean fullyOpaque, boolean fullyTransparent, float averageOpacity) {
      private static final NeoCpuShadowShapeBaker.SpriteAlphaMask FULLY_OPAQUE = new NeoCpuShadowShapeBaker.SpriteAlphaMask(
         new NeoCpuShadowShapeBaker.SpriteRect[0], true, false, 1.0F
      );
      private static final NeoCpuShadowShapeBaker.SpriteAlphaMask FULLY_TRANSPARENT = new NeoCpuShadowShapeBaker.SpriteAlphaMask(
         new NeoCpuShadowShapeBaker.SpriteRect[0], false, true, 0.0F
      );

      static NeoCpuShadowShapeBaker.SpriteAlphaMask opaqueMask() {
         return FULLY_OPAQUE;
      }

      static NeoCpuShadowShapeBaker.SpriteAlphaMask transparentMask() {
         return FULLY_TRANSPARENT;
      }
   }

   private static record SpritePixelBounds(double minX, double maxX, double minY, double maxY) {
   }

   private static record SpriteRect(int minX, int maxX, int minY, int maxY) {
   }

   private static record SurfaceRect(double minU, double maxU, double minV, double maxV) {
      double width() {
         return this.maxU - this.minU;
      }

      double height() {
         return this.maxV - this.minV;
      }
   }
}
