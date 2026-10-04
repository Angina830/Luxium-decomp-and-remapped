package com.vinlanx.luxium.client.neocpu;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class NeoCpuShadowTypes {
   static final double EPSILON = 1.0E-6;
   static final Direction[] FACES = Direction.values();

   private NeoCpuShadowTypes() {
   }

   private static double clamp(double value, double min, double max) {
      return Math.max(min, Math.min(max, value));
   }

   public static record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, long blockKey, float opacity)
      implements NeoCpuShadowTypes.Occluder {
      public double centerX() {
         return (this.minX + this.maxX) * 0.5;
      }

      public double centerY() {
         return (this.minY + this.maxY) * 0.5;
      }

      public double centerZ() {
         return (this.minZ + this.maxZ) * 0.5;
      }
   }

   public sealed interface Occluder permits NeoCpuShadowTypes.Box, NeoCpuShadowTypes.QuadOccluder {
      long blockKey();

      float opacity();
   }

   public static record PerSourceStatic(
      long sourceKey, int emission, Vec3 source, List<NeoCpuShadowTypes.ReceiverGroupEntry> groups, List<NeoCpuShadowTypes.ShadowPolygon> blockPolygons
   ) {
   }

   public static final class QuadOccluder implements NeoCpuShadowTypes.Occluder {
      private final Vec3[] vertices;
      private final AABB bounds;
      private final long blockKey;
      private final float opacity;

      public QuadOccluder(Vec3[] vertices, long blockKey, float opacity) {
         this.vertices = (Vec3[])vertices.clone();
         this.blockKey = blockKey;
         this.opacity = Mth.clamp(opacity, 0.0F, 1.0F);
         double minX = Double.POSITIVE_INFINITY;
         double minY = Double.POSITIVE_INFINITY;
         double minZ = Double.POSITIVE_INFINITY;
         double maxX = Double.NEGATIVE_INFINITY;
         double maxY = Double.NEGATIVE_INFINITY;
         double maxZ = Double.NEGATIVE_INFINITY;

         for (Vec3 vertex : this.vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
         }

         this.bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.002);
      }

      public QuadOccluder(Vec3[] ownedVertices, AABB precomputedBounds, long blockKey, float opacity) {
         this.vertices = ownedVertices;
         this.bounds = precomputedBounds;
         this.blockKey = blockKey;
         this.opacity = Mth.clamp(opacity, 0.0F, 1.0F);
      }

      public Vec3[] vertices() {
         return this.vertices;
      }

      public AABB bounds() {
         return this.bounds;
      }

      @Override
      public long blockKey() {
         return this.blockKey;
      }

      @Override
      public float opacity() {
         return this.opacity;
      }
   }

   public static record ReceiverGroupEntry(List<NeoCpuShadowTypes.ReceiverWithStrength> receivers) {
      public boolean isEmpty() {
         return this.receivers.isEmpty();
      }

      public NeoCpuShadowTypes.ReceiverSurface referenceReceiver() {
         return this.receivers.isEmpty() ? null : this.receivers.get(0).receiver();
      }
   }

   public static record ReceiverSurface(
      long blockKey,
      Direction face,
      double plane,
      double minU,
      double maxU,
      double minV,
      double maxV,
      double centerX,
      double centerY,
      double centerZ,
      double normalX,
      double normalY,
      double normalZ
   ) {
      public double width() {
         return this.maxU - this.minU;
      }

      public double height() {
         return this.maxV - this.minV;
      }

      public double distanceToSourceSq(double sourceX, double sourceY, double sourceZ) {
         double closestX;
         double closestY;
         double closestZ;
         switch (this.face) {
            case UP:
            case DOWN:
               closestX = NeoCpuShadowTypes.clamp(sourceX, this.minU, this.maxU);
               closestY = this.plane;
               closestZ = NeoCpuShadowTypes.clamp(sourceZ, this.minV, this.maxV);
               break;
            case NORTH:
            case SOUTH:
               closestX = NeoCpuShadowTypes.clamp(sourceX, this.minU, this.maxU);
               closestY = NeoCpuShadowTypes.clamp(sourceY, this.minV, this.maxV);
               closestZ = this.plane;
               break;
            case WEST:
            case EAST:
               closestX = this.plane;
               closestY = NeoCpuShadowTypes.clamp(sourceY, this.minV, this.maxV);
               closestZ = NeoCpuShadowTypes.clamp(sourceZ, this.minU, this.maxU);
               break;
            default:
               throw new IllegalStateException("Unexpected face: " + this.face);
         }

         double dx = closestX - sourceX;
         double dy = closestY - sourceY;
         double dz = closestZ - sourceZ;
         return dx * dx + dy * dy + dz * dz;
      }

      public boolean facesSource(double sourceX, double sourceY, double sourceZ) {
         double dx = sourceX - this.centerX;
         double dy = sourceY - this.centerY;
         double dz = sourceZ - this.centerZ;
         return dx * this.normalX + dy * this.normalY + dz * this.normalZ > 1.0E-6;
      }

      public double sampleX(double u, double v) {
         return switch (this.face) {
            case UP, DOWN, NORTH, SOUTH -> u;
            case WEST, EAST -> this.plane;
            default -> throw new IncompatibleClassChangeError();
         };
      }

      public double sampleY(double u, double v) {
         return switch (this.face) {
            case UP, DOWN -> this.plane;
            case NORTH, SOUTH, WEST, EAST -> v;
            default -> throw new IncompatibleClassChangeError();
         };
      }

      public double sampleZ(double u, double v) {
         return switch (this.face) {
            case UP, DOWN -> v;
            case NORTH, SOUTH -> this.plane;
            case WEST, EAST -> u;
            default -> throw new IncompatibleClassChangeError();
         };
      }

      public NeoCpuShadowTypes.Uv worldToUv(double x, double y, double z) {
         return switch (this.face) {
            case UP, DOWN -> new NeoCpuShadowTypes.Uv(x, z);
            case NORTH, SOUTH -> new NeoCpuShadowTypes.Uv(x, y);
            case WEST, EAST -> new NeoCpuShadowTypes.Uv(z, y);
            default -> throw new IncompatibleClassChangeError();
         };
      }
   }

   public static record ReceiverWithStrength(NeoCpuShadowTypes.ReceiverSurface receiver, float strength) {
   }

   public static record SectionGeometry(
      long sectionKey, long chunkKey, NeoCpuShadowTypes.ReceiverSurface[] receivers, NeoCpuShadowTypes.Occluder[][] occluderGrid
   ) {
      static final NeoCpuShadowTypes.SectionGeometry EMPTY = new NeoCpuShadowTypes.SectionGeometry(0L, 0L, new NeoCpuShadowTypes.ReceiverSurface[0], null);

      public boolean isEmpty() {
         return this.receivers.length == 0 && this.occluderGrid == null;
      }

      public NeoCpuShadowTypes.Occluder[] occludersAt(int localX, int localY, int localZ) {
         return this.occluderGrid == null ? null : this.occluderGrid[localY << 8 | localZ << 4 | localX];
      }
   }

   public static final class ShadowPolygon {
      public final double[] vertices;
      public final AABB bounds;
      public final double centerX;
      public final double centerY;
      public final double centerZ;
      public final double normalX;
      public final double normalY;
      public final double normalZ;
      public final float strength;

      public ShadowPolygon(double[] vertices, double normalX, double normalY, double normalZ, float strength) {
         this.vertices = vertices;
         this.normalX = normalX;
         this.normalY = normalY;
         this.normalZ = normalZ;
         this.strength = strength;
         double minX = Double.POSITIVE_INFINITY;
         double minY = Double.POSITIVE_INFINITY;
         double minZ = Double.POSITIVE_INFINITY;
         double maxX = Double.NEGATIVE_INFINITY;
         double maxY = Double.NEGATIVE_INFINITY;
         double maxZ = Double.NEGATIVE_INFINITY;
         double sumX = 0.0;
         double sumY = 0.0;
         double sumZ = 0.0;
         int count = 0;

         for (int i = 0; i < vertices.length; i += 3) {
            double x = vertices[i];
            double y = vertices[i + 1];
            double z = vertices[i + 2];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            sumX += x;
            sumY += y;
            sumZ += z;
            count++;
         }

         this.centerX = count == 0 ? 0.0 : sumX / (double)count;
         this.centerY = count == 0 ? 0.0 : sumY / (double)count;
         this.centerZ = count == 0 ? 0.0 : sumZ / (double)count;
         this.bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.02);
      }

      public boolean facesCamera(double cameraX, double cameraY, double cameraZ) {
         double dx = cameraX - this.centerX;
         double dy = cameraY - this.centerY;
         double dz = cameraZ - this.centerZ;
         return dx * this.normalX + dy * this.normalY + dz * this.normalZ > -0.01;
      }
   }

   public static record StaticBuildOutput(List<NeoCpuShadowTypes.PerSourceStatic> sources) {
      public static final NeoCpuShadowTypes.StaticBuildOutput EMPTY = new NeoCpuShadowTypes.StaticBuildOutput(List.of());

      public boolean isEmpty() {
         return this.sources.isEmpty();
      }
   }

   public static record Uv(double u, double v) {
      public boolean closeTo(NeoCpuShadowTypes.Uv other) {
         return Math.abs(this.u - other.u) <= 1.0E-5 && Math.abs(this.v - other.v) <= 1.0E-5;
      }
   }
}
