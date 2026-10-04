package com.vinlanx.luxium.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class NeoShadowsProjection {
   private static final NeoShadowsTypes.PlaneRect FULL_FACE_RECT = new NeoShadowsTypes.PlaneRect(0.0, 1.0, 0.0, 1.0);
   static final double[] EMPTY_DOUBLE_ARRAY = new double[0];
   static final ThreadLocal<double[]> SCRATCH_RECEIVER_SAMPLES = ThreadLocal.withInitial(() -> new double[768]);
   private static final int[][] BOX_EDGE_VERTEX_INDICES = new int[][]{
      {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3}, {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
   };

   private NeoShadowsProjection() {
   }

   static List<NeoShadowsTypes.UvPoint> collectProjectedBoxPoints(NeoShadowsTypes.GlobalReceiverPlane plane, Vec3 source, NeoShadowsTypes.Box worldBox) {
      Vec3[] corners = worldBox.corners();
      NeoShadowsTypes.ProjectionPoint[] projectedCorners = new NeoShadowsTypes.ProjectionPoint[corners.length];
      List<NeoShadowsTypes.UvPoint> points = new ArrayList<>(corners.length + BOX_EDGE_VERTEX_INDICES.length * 2);

      for (int index = 0; index < corners.length; index++) {
         NeoShadowsTypes.ProjectionPoint projection = plane.projectPoint(source, corners[index]);
         projectedCorners[index] = projection;
         if (projection.uv != null) {
            points.add(projection.uv);
         }
      }

      for (int[] edge : BOX_EDGE_VERTEX_INDICES) {
         appendExtendedEdgePoints(plane, source, corners[edge[0]], corners[edge[1]], projectedCorners[edge[0]], projectedCorners[edge[1]], points);
      }

      return points;
   }

   static List<NeoShadowsTypes.UvPoint> collectProjectedBoxPoints(
      NeoShadowsTypes.GlobalReceiverPlane plane, Vec3 source, Vec3[] corners, List<NeoShadowsTypes.UvPoint> points
   ) {
      points.clear();
      NeoShadowsTypes.ProjectionPoint[] projectedCorners = new NeoShadowsTypes.ProjectionPoint[corners.length];

      for (int index = 0; index < corners.length; index++) {
         NeoShadowsTypes.ProjectionPoint projection = plane.projectPoint(source, corners[index]);
         projectedCorners[index] = projection;
         if (projection.uv != null) {
            points.add(projection.uv);
         }
      }

      for (int[] edge : BOX_EDGE_VERTEX_INDICES) {
         appendExtendedEdgePoints(plane, source, corners[edge[0]], corners[edge[1]], projectedCorners[edge[0]], projectedCorners[edge[1]], points);
      }

      return points;
   }

   static List<NeoShadowsTypes.UvPoint> collectProjectedBoxPoints(
      NeoShadowsTypes.GlobalReceiverPlane plane, Vec3 source, NeoShadowsTypes.Box localBox, BlockPos blockPos
   ) {
      Vec3[] corners = localBox.cornersWithOffset(blockPos.getX(), blockPos.getY(), blockPos.getZ());
      NeoShadowsTypes.ProjectionPoint[] projectedCorners = new NeoShadowsTypes.ProjectionPoint[corners.length];
      List<NeoShadowsTypes.UvPoint> points = new ArrayList<>(corners.length + BOX_EDGE_VERTEX_INDICES.length * 2);

      for (int index = 0; index < corners.length; index++) {
         NeoShadowsTypes.ProjectionPoint projection = plane.projectPoint(source, corners[index]);
         projectedCorners[index] = projection;
         if (projection.uv != null) {
            points.add(projection.uv);
         }
      }

      for (int[] edge : BOX_EDGE_VERTEX_INDICES) {
         appendExtendedEdgePoints(plane, source, corners[edge[0]], corners[edge[1]], projectedCorners[edge[0]], projectedCorners[edge[1]], points);
      }

      return points;
   }

   private static void appendExtendedEdgePoints(
      NeoShadowsTypes.GlobalReceiverPlane plane,
      Vec3 source,
      Vec3 start,
      Vec3 end,
      NeoShadowsTypes.ProjectionPoint startProjection,
      NeoShadowsTypes.ProjectionPoint endProjection,
      List<NeoShadowsTypes.UvPoint> points
   ) {
      boolean startValid = startProjection.uv != null;
      boolean endValid = endProjection.uv != null;
      if (startValid != endValid) {
         double denominatorDelta = endProjection.denominator - startProjection.denominator;
         if (!(Math.abs(denominatorDelta) <= 1.0E-6)) {
            double singularParam = -startProjection.denominator / denominatorDelta;
            if (!(singularParam < -1.0E-6) && !(singularParam > 1.000001)) {
               singularParam = Math.max(0.0, Math.min(1.0, singularParam));
               double outerOffset = startValid ? -0.001 : 0.001;
               double innerOffset = startValid ? -2.5E-4 : 2.5E-4;
               double outerParam = clampUnit(singularParam + outerOffset);
               double innerParam = clampUnit(singularParam + innerOffset);
               if (!(Math.abs(innerParam - outerParam) <= 1.0E-6)) {
                  NeoShadowsTypes.UvPoint outerUv = plane.projectFinite(source, lerp(start, end, outerParam));
                  NeoShadowsTypes.UvPoint innerUv = plane.projectFinite(source, lerp(start, end, innerParam));
                  if (outerUv != null && innerUv != null) {
                     points.add(outerUv);
                     points.add(innerUv);
                     double directionU = innerUv.u - outerUv.u;
                     double directionV = innerUv.v - outerUv.v;
                     double directionLengthSq = directionU * directionU + directionV * directionV;
                     if (directionLengthSq <= 1.0E-6) {
                        Vec3 edgeDirection = end.subtract(start);
                        directionU = edgeDirection.dot(plane.axisU);
                        directionV = edgeDirection.dot(plane.axisV);
                        directionLengthSq = directionU * directionU + directionV * directionV;
                        if (directionLengthSq <= 1.0E-6) {
                           return;
                        }
                     }

                     double directionLength = Math.sqrt(directionLengthSq);
                     points.add(new NeoShadowsTypes.UvPoint(innerUv.u + directionU / directionLength * 8.0, innerUv.v + directionV / directionLength * 8.0));
                  }
               }
            }
         }
      }
   }

   static double clampUnit(double value) {
      return Math.max(1.0E-6, Math.min(0.999999, value));
   }

   static Vec3 lerp(Vec3 start, Vec3 end, double delta) {
      return new Vec3(start.x + (end.x - start.x) * delta, start.y + (end.y - start.y) * delta, start.z + (end.z - start.z) * delta);
   }

   @Nullable
   static List<NeoShadowsTypes.UvPoint> buildProjectedShadowHull(List<NeoShadowsTypes.UvPoint> projected) {
      if (projected.size() < 3) {
         return null;
      } else {
         List<NeoShadowsTypes.UvPoint> hull = buildConvexHull(projected);
         return hull.size() >= 3 && !(polygonArea(hull) <= 1.0E-5) ? hull : null;
      }
   }

   @Nullable
   static List<NeoShadowsTypes.UvPoint> buildProjectedShadowHullBuffered(List<NeoShadowsTypes.UvPoint> projected, NeoShadowsTypes.BuildScratch scratch) {
      if (projected.size() < 3) {
         return null;
      } else {
         List<NeoShadowsTypes.UvPoint> hull = buildConvexHullBuffered(projected, scratch);
         return hull.size() >= 3 && !(polygonArea(hull) <= 1.0E-5) ? hull : null;
      }
   }

   static List<NeoShadowsTypes.UvPoint> fullBlockShadowPolygon() {
      return List.of(
         new NeoShadowsTypes.UvPoint(0.0, 0.0),
         new NeoShadowsTypes.UvPoint(1.0, 0.0),
         new NeoShadowsTypes.UvPoint(1.0, 1.0),
         new NeoShadowsTypes.UvPoint(0.0, 1.0)
      );
   }

   static long sampleRtxPosKey(BlockPos pos, Direction face, double sampleX, double sampleY, double sampleZ) {
      double outwardX = (double)face.getStepX() * 0.05;
      double outwardY = (double)face.getStepY() * 0.05;
      double outwardZ = (double)face.getStepZ() * 0.05;
      int sampleBlockX = Mth.floor(sampleX + outwardX);
      int sampleBlockY = Mth.floor(sampleY + outwardY);
      int sampleBlockZ = Mth.floor(sampleZ + outwardZ);
      long sampleKey = BlockPos.asLong(sampleBlockX, sampleBlockY, sampleBlockZ);
      return sampleKey == pos.asLong() ? BlockPos.asLong(pos.getX() + face.getStepX(), pos.getY() + face.getStepY(), pos.getZ() + face.getStepZ()) : sampleKey;
   }

   static Direction firstFaceTangent(Direction face) {
      return switch (face) {
         case UP, DOWN, NORTH, SOUTH -> Direction.EAST;
         case WEST, EAST -> Direction.SOUTH;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static Direction secondFaceTangent(Direction face) {
      return switch (face) {
         case UP, DOWN -> Direction.SOUTH;
         case NORTH, SOUTH, WEST, EAST -> Direction.UP;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static double facePointX(BlockPos pos, Direction face, double planeCoordinate, float s) {
      double minX = (double)pos.getX();
      double maxX = minX + 1.0;
      double epsilon = 0.0015F;

      return switch (face) {
         case UP, DOWN, SOUTH -> minX + (double)s;
         case NORTH -> maxX - (double)s;
         case WEST -> planeCoordinate - epsilon;
         case EAST -> planeCoordinate + epsilon;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static double facePointY(BlockPos pos, Direction face, double planeCoordinate, float t) {
      double minY = (double)pos.getY();
      double epsilon = 0.0015F;

      return switch (face) {
         case UP -> planeCoordinate + epsilon;
         case DOWN -> planeCoordinate - epsilon;
         case NORTH, SOUTH, WEST, EAST -> minY + (double)t;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static double facePointZ(BlockPos pos, Direction face, double planeCoordinate, float s, float t) {
      double minZ = (double)pos.getZ();
      double epsilon = 0.0015F;

      return switch (face) {
         case UP -> minZ + (double)t;
         case DOWN -> minZ + 1.0 - (double)t;
         case NORTH -> planeCoordinate - epsilon;
         case SOUTH -> planeCoordinate + epsilon;
         case WEST -> minZ + 1.0 - (double)s;
         case EAST -> minZ + (double)s;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static int[][] buildReceiverOffsets() {
      List<int[]> offsets = new ArrayList<>();

      for (int dy = -3; dy <= 3; dy++) {
         for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
               if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 3) {
                  offsets.add(new int[]{dx, dy, dz});
               }
            }
         }
      }

      return offsets.toArray(new int[0][]);
   }

   static List<NeoShadowsTypes.UvPoint> buildConvexHull(List<NeoShadowsTypes.UvPoint> points) {
      List<NeoShadowsTypes.UvPoint> sorted = new ArrayList<>(points);
      sorted.sort((left, right) -> {
         int cmpU = Double.compare(left.u, right.u);
         return cmpU != 0 ? cmpU : Double.compare(left.v, right.v);
      });
      List<NeoShadowsTypes.UvPoint> unique = new ArrayList<>(sorted.size());

      for (NeoShadowsTypes.UvPoint point : sorted) {
         if (unique.isEmpty() || !point.equalsApprox(unique.get(unique.size() - 1))) {
            unique.add(point);
         }
      }

      if (unique.size() < 3) {
         return unique;
      } else {
         List<NeoShadowsTypes.UvPoint> lower = new ArrayList<>();

         for (NeoShadowsTypes.UvPoint pointx : unique) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), pointx) <= 1.0E-6) {
               lower.remove(lower.size() - 1);
            }

            lower.add(pointx);
         }

         List<NeoShadowsTypes.UvPoint> upper = new ArrayList<>();

         for (int index = unique.size() - 1; index >= 0; index--) {
            NeoShadowsTypes.UvPoint pointx = unique.get(index);

            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), pointx) <= 1.0E-6) {
               upper.remove(upper.size() - 1);
            }

            upper.add(pointx);
         }

         lower.remove(lower.size() - 1);
         upper.remove(upper.size() - 1);
         lower.addAll(upper);
         return lower;
      }
   }

   static List<NeoShadowsTypes.UvPoint> buildConvexHullBuffered(List<NeoShadowsTypes.UvPoint> points, NeoShadowsTypes.BuildScratch scratch) {
      List<NeoShadowsTypes.UvPoint> sorted = scratch.hullSortBuf;
      sorted.clear();
      sorted.addAll(points);
      sorted.sort((left, right) -> {
         int cmpU = Double.compare(left.u, right.u);
         return cmpU != 0 ? cmpU : Double.compare(left.v, right.v);
      });
      List<NeoShadowsTypes.UvPoint> unique = scratch.hullUniqueBuf;
      unique.clear();

      for (NeoShadowsTypes.UvPoint point : sorted) {
         if (unique.isEmpty() || !point.equalsApprox(unique.get(unique.size() - 1))) {
            unique.add(point);
         }
      }

      if (unique.size() < 3) {
         return unique;
      } else {
         List<NeoShadowsTypes.UvPoint> lower = scratch.hullLowerBuf;
         lower.clear();

         for (NeoShadowsTypes.UvPoint pointx : unique) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), pointx) <= 1.0E-6) {
               lower.remove(lower.size() - 1);
            }

            lower.add(pointx);
         }

         List<NeoShadowsTypes.UvPoint> upper = scratch.hullUpperBuf;
         upper.clear();

         for (int index = unique.size() - 1; index >= 0; index--) {
            NeoShadowsTypes.UvPoint pointx = unique.get(index);

            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), pointx) <= 1.0E-6) {
               upper.remove(upper.size() - 1);
            }

            upper.add(pointx);
         }

         List<NeoShadowsTypes.UvPoint> output = scratch.hullOutputBuf;
         output.clear();

         for (int index = 0; index < lower.size() - 1; index++) {
            output.add(lower.get(index));
         }

         for (int index = 0; index < upper.size() - 1; index++) {
            output.add(upper.get(index));
         }

         return output;
      }
   }

   static double cross(NeoShadowsTypes.UvPoint a, NeoShadowsTypes.UvPoint b, NeoShadowsTypes.UvPoint c) {
      return (b.u - a.u) * (c.v - a.v) - (b.v - a.v) * (c.u - a.u);
   }

   static List<NeoShadowsTypes.UvPoint> clipToRectangleBuffered(
      List<NeoShadowsTypes.UvPoint> polygon, double minU, double maxU, double minV, double maxV, NeoShadowsTypes.BuildScratch scratch
   ) {
      List<NeoShadowsTypes.UvPoint> dst = scratch.clipBufA;
      dst.clear();
      clipAgainstEdgeInto(polygon, dst, p -> p.u >= minU, (a, b) -> interpolateAtU(a, b, minU));
      if (dst.isEmpty()) {
         return dst;
      } else {
         List<NeoShadowsTypes.UvPoint> var14 = scratch.clipBufB;
         var14.clear();
         clipAgainstEdgeInto(dst, var14, p -> p.u <= maxU, (a, b) -> interpolateAtU(a, b, maxU));
         if (var14.isEmpty()) {
            return var14;
         } else {
            dst = scratch.clipBufA;
            dst.clear();
            clipAgainstEdgeInto(var14, dst, p -> p.v >= minV, (a, b) -> interpolateAtV(a, b, minV));
            if (dst.isEmpty()) {
               return dst;
            } else {
               List<NeoShadowsTypes.UvPoint> var16 = scratch.clipBufB;
               var16.clear();
               clipAgainstEdgeInto(dst, var16, p -> p.v <= maxV, (a, b) -> interpolateAtV(a, b, maxV));
               return var16;
            }
         }
      }
   }

   static void clipAgainstEdgeInto(
      List<NeoShadowsTypes.UvPoint> polygon,
      List<NeoShadowsTypes.UvPoint> output,
      Predicate<NeoShadowsTypes.UvPoint> inside,
      NeoShadowsProjection.IntersectionFactory intersectionFactory
   ) {
      if (!polygon.isEmpty()) {
         NeoShadowsTypes.UvPoint previous = polygon.get(polygon.size() - 1);
         boolean previousInside = inside.test(previous);

         for (NeoShadowsTypes.UvPoint current : polygon) {
            boolean currentInside = inside.test(current);
            if (currentInside) {
               if (!previousInside) {
                  output.add(intersectionFactory.create(previous, current));
               }

               output.add(current);
            } else if (previousInside) {
               output.add(intersectionFactory.create(previous, current));
            }

            previous = current;
            previousInside = currentInside;
         }
      }
   }

   static NeoShadowsTypes.UvPoint interpolateAtU(NeoShadowsTypes.UvPoint a, NeoShadowsTypes.UvPoint b, double u) {
      double delta = b.u - a.u;
      double t = Math.abs(delta) <= 1.0E-6 ? 0.0 : (u - a.u) / delta;
      return new NeoShadowsTypes.UvPoint(u, a.v + (b.v - a.v) * t);
   }

   static NeoShadowsTypes.UvPoint interpolateAtV(NeoShadowsTypes.UvPoint a, NeoShadowsTypes.UvPoint b, double v) {
      double delta = b.v - a.v;
      double t = Math.abs(delta) <= 1.0E-6 ? 0.0 : (v - a.v) / delta;
      return new NeoShadowsTypes.UvPoint(a.u + (b.u - a.u) * t, v);
   }

   static double polygonArea(List<NeoShadowsTypes.UvPoint> polygon) {
      double area = 0.0;

      for (int i = 0; i < polygon.size(); i++) {
         NeoShadowsTypes.UvPoint a = polygon.get(i);
         NeoShadowsTypes.UvPoint b = polygon.get((i + 1) % polygon.size());
         area += a.u * b.v - b.u * a.v;
      }

      return Math.abs(area) * 0.5;
   }

   static boolean rectFullyInsideConvexPolygon(List<NeoShadowsTypes.UvPoint> polygon, NeoShadowsTypes.PlaneRect rect) {
      return polygon.size() < 3
         ? false
         : convexPolygonContainsPoint(polygon, rect.minU, rect.minV)
            && convexPolygonContainsPoint(polygon, rect.maxU, rect.minV)
            && convexPolygonContainsPoint(polygon, rect.maxU, rect.maxV)
            && convexPolygonContainsPoint(polygon, rect.minU, rect.maxV);
   }

   static boolean convexPolygonContainsPoint(List<NeoShadowsTypes.UvPoint> polygon, double u, double v) {
      double sign = 0.0;
      int index = 0;

      for (int size = polygon.size(); index < size; index++) {
         NeoShadowsTypes.UvPoint current = polygon.get(index);
         NeoShadowsTypes.UvPoint next = polygon.get((index + 1) % size);
         double cross = (next.u - current.u) * (v - current.v) - (next.v - current.v) * (u - current.u);
         if (!(Math.abs(cross) <= 1.0E-6)) {
            if (sign == 0.0) {
               sign = Math.copySign(1.0, cross);
            } else if (cross * sign < -1.0E-6) {
               return false;
            }
         }
      }

      return true;
   }

   @Nullable
   static List<NeoShadowsTypes.UvPoint> normalizeShadowPolygonToRect(
      List<NeoShadowsTypes.UvPoint> polygon, NeoShadowsTypes.PlaneRect rect, Direction face, NeoShadowsTypes.BuildScratch scratch
   ) {
      if (polygon.size() < 3) {
         return null;
      } else {
         double rectWidth = rect.maxU - rect.minU;
         double rectHeight = rect.maxV - rect.minV;
         if (!(rectWidth <= 1.0E-6) && !(rectHeight <= 1.0E-6)) {
            double rectArea = rectWidth * rectHeight;
            if (rectArea <= 1.0E-5) {
               return polygon;
            } else if (polygonArea(polygon) / rectArea >= 0.988) {
               return rectShadowPolygon(rect);
            } else {
               List<NeoShadowsTypes.UvPoint> localPolygon = scratch.polyLocalBuf;
               localPolygon.clear();

               for (NeoShadowsTypes.UvPoint point : polygon) {
                  localPolygon.add(new NeoShadowsTypes.UvPoint((point.u - rect.minU) / rectWidth, (point.v - rect.minV) / rectHeight));
               }

               List<NeoShadowsTypes.UvPoint> dilated = dilateUvPolygonInto(localPolygon, getShadowPolygonDilation(face), scratch.polyDilateBuf);
               List<NeoShadowsTypes.UvPoint> snapped = snapPolygonToEdgesInto(dilated, getShadowEdgeSnapEpsilon(face), scratch.polySnapBuf);
               List<NeoShadowsTypes.UvPoint> clippedLocal = clipToRectangleBuffered(snapped, 0.0, 1.0, 0.0, 1.0, scratch);
               if (clippedLocal.size() < 3) {
                  return polygon;
               } else {
                  List<NeoShadowsTypes.UvPoint> normalizedLocal = buildConvexHull(clippedLocal);
                  if (normalizedLocal.size() < 3) {
                     return polygon;
                  } else if (polygonArea(normalizedLocal) >= 0.988) {
                     return rectShadowPolygon(rect);
                  } else {
                     List<NeoShadowsTypes.UvPoint> normalized = new ArrayList<>(normalizedLocal.size());

                     for (NeoShadowsTypes.UvPoint point : normalizedLocal) {
                        normalized.add(new NeoShadowsTypes.UvPoint(rect.minU + point.u * rectWidth, rect.minV + point.v * rectHeight));
                     }

                     return normalized;
                  }
               }
            }
         } else {
            return polygon;
         }
      }
   }

   static List<NeoShadowsTypes.UvPoint> dilateUvPolygonInto(List<NeoShadowsTypes.UvPoint> polygon, double amount, List<NeoShadowsTypes.UvPoint> dilated) {
      if (!(amount <= 1.0E-6) && !polygon.isEmpty()) {
         double centerU = 0.0;
         double centerV = 0.0;

         for (NeoShadowsTypes.UvPoint point : polygon) {
            centerU += point.u;
            centerV += point.v;
         }

         centerU /= (double)polygon.size();
         centerV /= (double)polygon.size();
         dilated.clear();

         for (NeoShadowsTypes.UvPoint point : polygon) {
            double dirU = point.u - centerU;
            double dirV = point.v - centerV;
            double length = Math.sqrt(dirU * dirU + dirV * dirV);
            if (length <= 1.0E-6) {
               dilated.add(point);
            } else {
               dilated.add(new NeoShadowsTypes.UvPoint(point.u + dirU / length * amount, point.v + dirV / length * amount));
            }
         }

         return dilated;
      } else {
         return polygon;
      }
   }

   static List<NeoShadowsTypes.UvPoint> snapPolygonToEdgesInto(List<NeoShadowsTypes.UvPoint> polygon, double epsilon, List<NeoShadowsTypes.UvPoint> snapped) {
      if (!polygon.isEmpty() && !(epsilon <= 1.0E-6)) {
         snapped.clear();

         for (NeoShadowsTypes.UvPoint point : polygon) {
            snapped.add(new NeoShadowsTypes.UvPoint(snapUvCoordinate(point.u, epsilon), snapUvCoordinate(point.v, epsilon)));
         }

         return snapped;
      } else {
         return polygon;
      }
   }

   static double snapUvCoordinate(double value, double epsilon) {
      if (value <= epsilon) {
         return 0.0;
      } else {
         return value >= 1.0 - epsilon ? 1.0 : value;
      }
   }

   static double getShadowPolygonDilation(Direction face) {
      return isHorizontalReceiverFace(face) ? 0.024 : 0.016;
   }

   static double getShadowEdgeSnapEpsilon(Direction face) {
      return isHorizontalReceiverFace(face) ? 0.03 : 0.022;
   }

   static List<NeoShadowsTypes.UvPoint> rectShadowPolygon(NeoShadowsTypes.PlaneRect rect) {
      return List.of(
         new NeoShadowsTypes.UvPoint(rect.minU, rect.minV),
         new NeoShadowsTypes.UvPoint(rect.maxU, rect.minV),
         new NeoShadowsTypes.UvPoint(rect.maxU, rect.maxV),
         new NeoShadowsTypes.UvPoint(rect.minU, rect.maxV)
      );
   }

   static int planeCoordinateForFace(BlockPos pos, Direction face) {
      return switch (face) {
         case UP -> pos.getY() + 1;
         case DOWN -> pos.getY();
         case NORTH -> pos.getZ();
         case SOUTH -> pos.getZ() + 1;
         case WEST -> pos.getX();
         case EAST -> pos.getX() + 1;
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static boolean isMergeableReceiverBounds(NeoShadowsTypes.PlaneRect rect) {
      return rect != null
         && Math.abs(rect.minU) <= 1.0E-6
         && Math.abs(rect.minV) <= 1.0E-6
         && Math.abs(rect.maxU - 1.0) <= 1.0E-6
         && Math.abs(rect.maxV - 1.0) <= 1.0E-6;
   }

   static boolean isMergeablePlaneCoordinate(double planeCoordinate) {
      return Math.abs(planeCoordinate - Math.rint(planeCoordinate)) <= 1.0E-6;
   }

   static boolean sameReceiverStrength(float left, float right) {
      return Math.abs(left - right) <= 0.01F;
   }

   static long receiverCellKey(int cellU, int cellV) {
      return (long)cellU << 32 ^ (long)cellV & 4294967295L;
   }

   static int unpackReceiverCellU(long cellKey) {
      return (int)(cellKey >> 32);
   }

   static int unpackReceiverCellV(long cellKey) {
      return (int)cellKey;
   }

   static boolean isProbeInsideRect(NeoShadowsTypes.UvPoint probe, NeoShadowsTypes.PlaneRect rect) {
      return probe.u >= rect.minU - 1.0E-6 && probe.u <= rect.maxU + 1.0E-6 && probe.v >= rect.minV - 1.0E-6 && probe.v <= rect.maxV + 1.0E-6;
   }

   static NeoShadowsTypes.UvPoint[] buildOcclusionFaceProbes(Direction face) {
      return NeoShadowsOcclusionPipeline.buildOcclusionFaceProbes(face);
   }

   static NeoShadowsTypes.PlaneRect fullFaceRect() {
      return FULL_FACE_RECT;
   }

   static boolean isHorizontalReceiverFace(Direction face) {
      return face == Direction.UP || face == Direction.DOWN;
   }

   static int blockPosToPlaneCellU(Direction face, BlockPos pos) {
      return switch (face) {
         case UP, DOWN, NORTH, SOUTH -> pos.getX();
         case WEST, EAST -> pos.getZ();
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static int blockPosToPlaneCellV(Direction face, BlockPos pos) {
      return switch (face) {
         case UP, DOWN -> pos.getZ();
         case NORTH, SOUTH, WEST, EAST -> pos.getY();
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static BlockPos planeCellToBlockPos(NeoShadowsTypes.ReceiverPlaneKey key, int cellU, int cellV) {
      return switch (key.face) {
         case UP -> new BlockPos(cellU, Mth.floor(key.planeCoordinate) - 1, cellV);
         case DOWN -> new BlockPos(cellU, Mth.floor(key.planeCoordinate), cellV);
         case NORTH -> new BlockPos(cellU, cellV, Mth.floor(key.planeCoordinate));
         case SOUTH -> new BlockPos(cellU, cellV, Mth.floor(key.planeCoordinate) - 1);
         case WEST -> new BlockPos(Mth.floor(key.planeCoordinate), cellV, cellU);
         case EAST -> new BlockPos(Mth.floor(key.planeCoordinate) - 1, cellV, cellU);
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static NeoShadowsTypes.PlaneRect faceContributionPlaneRect(Direction face, BlockPos pos, NeoShadowsTypes.PlaneRect localRect) {
      int cellU = blockPosToPlaneCellU(face, pos);
      int cellV = blockPosToPlaneCellV(face, pos);
      return new NeoShadowsTypes.PlaneRect(
         (double)cellU + localRect.minU, (double)cellU + localRect.maxU, (double)cellV + localRect.minV, (double)cellV + localRect.maxV
      );
   }

   static long sectionRectCellToBlockKey(int sectionBaseX, int sectionBaseY, int sectionBaseZ, Direction face, int localU, int localV, int planeCoordinate) {
      return switch (face) {
         case UP -> BlockPos.asLong(sectionBaseX + localU, planeCoordinate - 1, sectionBaseZ + localV);
         case DOWN -> BlockPos.asLong(sectionBaseX + localU, planeCoordinate, sectionBaseZ + localV);
         case NORTH -> BlockPos.asLong(sectionBaseX + localU, sectionBaseY + localV, planeCoordinate);
         case SOUTH -> BlockPos.asLong(sectionBaseX + localU, sectionBaseY + localV, planeCoordinate - 1);
         case WEST -> BlockPos.asLong(planeCoordinate, sectionBaseY + localV, sectionBaseZ + localU);
         case EAST -> BlockPos.asLong(planeCoordinate - 1, sectionBaseY + localV, sectionBaseZ + localU);
         default -> throw new IncompatibleClassChangeError();
      };
   }

   static long[] scratchKeys(int needed) {
      long[] current = NeoShadowsEngine.CANDIDATE_KEYS_TL.get();
      if (current.length >= needed) {
         return current;
      } else {
         int size = Integer.highestOneBit(Math.max(needed, 16) - 1) << 1;
         long[] grown = new long[size];
         NeoShadowsEngine.CANDIDATE_KEYS_TL.set(grown);
         return grown;
      }
   }

   static double[] scratchDistances(int needed) {
      double[] current = NeoShadowsEngine.CANDIDATE_DISTANCES_TL.get();
      if (current.length >= needed) {
         return current;
      } else {
         int size = Integer.highestOneBit(Math.max(needed, 16) - 1) << 1;
         double[] grown = new double[size];
         NeoShadowsEngine.CANDIDATE_DISTANCES_TL.set(grown);
         return grown;
      }
   }

   static byte[] scratchMasks(int needed) {
      byte[] current = NeoShadowsEngine.CANDIDATE_MASKS_TL.get();
      if (current.length >= needed) {
         return current;
      } else {
         int size = Integer.highestOneBit(Math.max(needed, 16) - 1) << 1;
         byte[] grown = new byte[size];
         NeoShadowsEngine.CANDIDATE_MASKS_TL.set(grown);
         return grown;
      }
   }

   @FunctionalInterface
   interface IntersectionFactory {
      NeoShadowsTypes.UvPoint create(NeoShadowsTypes.UvPoint var1, NeoShadowsTypes.UvPoint var2);
   }
}
