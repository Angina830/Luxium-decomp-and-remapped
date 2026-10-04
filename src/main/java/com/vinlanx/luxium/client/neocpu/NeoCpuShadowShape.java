package com.vinlanx.luxium.client.neocpu;

import java.util.ArrayList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class NeoCpuShadowShape {
   private static final NeoCpuShadowTypes.Box[] EMPTY_BOXES = new NeoCpuShadowTypes.Box[0];
   private static final NeoCpuShadowShape.LocalQuadTemplate[] EMPTY_QUADS = new NeoCpuShadowShape.LocalQuadTemplate[0];
   private static final NeoCpuShadowShape.ReceiverTemplate[] EMPTY_RECEIVERS = new NeoCpuShadowShape.ReceiverTemplate[0];
   static final NeoCpuShadowShape EMPTY = new NeoCpuShadowShape(EMPTY_BOXES, EMPTY_QUADS, EMPTY_RECEIVERS, false);
   private final NeoCpuShadowTypes.Box[] localBoxes;
   private final NeoCpuShadowShape.LocalQuadTemplate[] localQuads;
   private final NeoCpuShadowShape.ReceiverTemplate[] receivers;
   private final boolean floodBlocking;

   NeoCpuShadowShape(
      NeoCpuShadowTypes.Box[] localBoxes,
      NeoCpuShadowShape.LocalQuadTemplate[] localQuads,
      NeoCpuShadowShape.ReceiverTemplate[] receivers,
      boolean floodBlocking
   ) {
      this.localBoxes = localBoxes;
      this.localQuads = localQuads;
      this.receivers = receivers;
      this.floodBlocking = floodBlocking;
   }

   boolean isEmpty() {
      return this.localBoxes.length == 0 && this.localQuads.length == 0 && this.receivers.length == 0;
   }

   boolean hasOccluders() {
      return this.localBoxes.length > 0 || this.localQuads.length > 0;
   }

   boolean blocksFlood() {
      return this.floodBlocking;
   }

   NeoCpuShadowShape.ReceiverTemplate[] receivers() {
      return this.receivers;
   }

   void appendReceiversForFace(long blockKey, int blockX, int blockY, int blockZ, Direction face, ArrayList<NeoCpuShadowTypes.ReceiverSurface> out) {
      for (NeoCpuShadowShape.ReceiverTemplate receiver : this.receivers) {
         if (receiver.face == face) {
            out.add(receiver.instantiate(blockKey, blockX, blockY, blockZ));
         }
      }
   }

   NeoCpuShadowTypes.Occluder[] instantiateOccluders(long blockKey, int blockX, int blockY, int blockZ) {
      if (!this.hasOccluders()) {
         return null;
      } else if (this.localQuads.length > 0) {
         NeoCpuShadowTypes.Occluder[] result = new NeoCpuShadowTypes.Occluder[this.localQuads.length];

         for (int i = 0; i < this.localQuads.length; i++) {
            result[i] = this.localQuads[i].instantiate(blockKey, blockX, blockY, blockZ);
         }

         return result;
      } else {
         NeoCpuShadowTypes.Occluder[] result = new NeoCpuShadowTypes.Occluder[this.localBoxes.length];

         for (int i = 0; i < this.localBoxes.length; i++) {
            NeoCpuShadowTypes.Box box = this.localBoxes[i];
            result[i] = new NeoCpuShadowTypes.Box(
               (double)blockX + box.minX(),
               (double)blockY + box.minY(),
               (double)blockZ + box.minZ(),
               (double)blockX + box.maxX(),
               (double)blockY + box.maxY(),
               (double)blockZ + box.maxZ(),
               blockKey,
               box.opacity()
            );
         }

         return result;
      }
   }

   static record LocalQuadTemplate(Vec3[] vertices, float opacity) {
      NeoCpuShadowTypes.QuadOccluder instantiate(long blockKey, int blockX, int blockY, int blockZ) {
         Vec3[] worldVertices = new Vec3[this.vertices.length];
         double minX = Double.POSITIVE_INFINITY;
         double minY = Double.POSITIVE_INFINITY;
         double minZ = Double.POSITIVE_INFINITY;
         double maxX = Double.NEGATIVE_INFINITY;
         double maxY = Double.NEGATIVE_INFINITY;
         double maxZ = Double.NEGATIVE_INFINITY;

         for (int i = 0; i < this.vertices.length; i++) {
            Vec3 vertex = this.vertices[i];
            double wx = vertex.x + (double)blockX;
            double wy = vertex.y + (double)blockY;
            double wz = vertex.z + (double)blockZ;
            worldVertices[i] = new Vec3(wx, wy, wz);
            if (wx < minX) {
               minX = wx;
            }

            if (wx > maxX) {
               maxX = wx;
            }

            if (wy < minY) {
               minY = wy;
            }

            if (wy > maxY) {
               maxY = wy;
            }

            if (wz < minZ) {
               minZ = wz;
            }

            if (wz > maxZ) {
               maxZ = wz;
            }
         }

         AABB bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.002);
         return new NeoCpuShadowTypes.QuadOccluder(worldVertices, bounds, blockKey, this.opacity);
      }
   }

   static record ReceiverTemplate(Direction face, double plane, double minU, double maxU, double minV, double maxV) {
      NeoCpuShadowTypes.ReceiverSurface instantiate(long blockKey, int blockX, int blockY, int blockZ) {
         double worldPlane = switch (this.face) {
            case UP, DOWN -> (double)blockY + this.plane;
            case NORTH, SOUTH -> (double)blockZ + this.plane;
            case WEST, EAST -> (double)blockX + this.plane;
            default -> throw new IncompatibleClassChangeError();
         };

         double worldMinU = switch (this.face) {
            case UP, DOWN, NORTH, SOUTH -> (double)blockX + this.minU;
            case WEST, EAST -> (double)blockZ + this.minU;
            default -> throw new IncompatibleClassChangeError();
         };

         double worldMaxU = switch (this.face) {
            case UP, DOWN, NORTH, SOUTH -> (double)blockX + this.maxU;
            case WEST, EAST -> (double)blockZ + this.maxU;
            default -> throw new IncompatibleClassChangeError();
         };

         double worldMinV = switch (this.face) {
            case UP, DOWN -> (double)blockZ + this.minV;
            case NORTH, SOUTH, WEST, EAST -> (double)blockY + this.minV;
            default -> throw new IncompatibleClassChangeError();
         };

         double worldMaxV = switch (this.face) {
            case UP, DOWN -> (double)blockZ + this.maxV;
            case NORTH, SOUTH, WEST, EAST -> (double)blockY + this.maxV;
            default -> throw new IncompatibleClassChangeError();
         };
         double centerU = (worldMinU + worldMaxU) * 0.5;
         double centerV = (worldMinV + worldMaxV) * 0.5;

         double centerX = switch (this.face) {
            case UP, DOWN, NORTH, SOUTH -> centerU;
            case WEST, EAST -> worldPlane;
            default -> throw new IncompatibleClassChangeError();
         };

         double centerY = switch (this.face) {
            case UP, DOWN -> worldPlane;
            case NORTH, SOUTH, WEST, EAST -> centerV;
            default -> throw new IncompatibleClassChangeError();
         };

         double centerZ = switch (this.face) {
            case UP, DOWN -> centerV;
            case NORTH, SOUTH -> worldPlane;
            case WEST, EAST -> centerU;
            default -> throw new IncompatibleClassChangeError();
         };
         return new NeoCpuShadowTypes.ReceiverSurface(
            blockKey,
            this.face,
            worldPlane,
            worldMinU,
            worldMaxU,
            worldMinV,
            worldMaxV,
            centerX,
            centerY,
            centerZ,
            (double)this.face.getStepX(),
            (double)this.face.getStepY(),
            (double)this.face.getStepZ()
         );
      }
   }
}
