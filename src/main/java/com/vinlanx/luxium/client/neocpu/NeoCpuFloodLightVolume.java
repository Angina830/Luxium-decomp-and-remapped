package com.vinlanx.luxium.client.neocpu;

import com.vinlanx.luxium.rtx.LightRtMath;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

final class NeoCpuFloodLightVolume {
   private static final int MIN_VISITED_CELLS = 16384;
   private static final int MAX_VISITED_CELLS = 262144;
   private static final Direction[] ALL_FACES = Direction.values();
   private final LongOpenHashSet visitedCells;
   private final List<NeoCpuFloodBoundaryFace> boundaryFaces;

   private NeoCpuFloodLightVolume(LongOpenHashSet visitedCells, List<NeoCpuFloodBoundaryFace> boundaryFaces) {
      this.visitedCells = visitedCells;
      this.boundaryFaces = boundaryFaces;
   }

   static NeoCpuFloodLightVolume build(NeoCpuLocalBlockAccessor accessor, long sourceKey, int emission) {
      double radius = LightRtMath.getBlockShadowRadius(emission);
      double radiusSq = radius * radius;
      int maxVisitedCells = getVisitedCellLimit(radius);
      LongOpenHashSet visited = new LongOpenHashSet();
      NeoCpuReceiverFaceSet receiverFaces = new NeoCpuReceiverFaceSet();
      ArrayDeque<Long> queue = new ArrayDeque<>();
      ArrayList<NeoCpuShadowTypes.ReceiverSurface> scratchReceivers = new ArrayList<>(8);
      visited.add(sourceKey);
      queue.add(sourceKey);
      double sourceX = (double)BlockPos.getX(sourceKey) + 0.5;
      double sourceY = (double)BlockPos.getY(sourceKey) + 0.5;
      double sourceZ = (double)BlockPos.getZ(sourceKey) + 0.5;

      while (!queue.isEmpty() && visited.size() < maxVisitedCells) {
         long cellKey = queue.removeFirst();
         int x = BlockPos.getX(cellKey);
         int y = BlockPos.getY(cellKey);
         int z = BlockPos.getZ(cellKey);

         for (Direction direction : ALL_FACES) {
            int nx = x + direction.getStepX();
            int ny = y + direction.getStepY();
            int nz = z + direction.getStepZ();
            long neighborKey = BlockPos.asLong(nx, ny, nz);
            double dx = (double)nx + 0.5 - sourceX;
            double dy = (double)ny + 0.5 - sourceY;
            double dz = (double)nz + 0.5 - sourceZ;
            if (!(dx * dx + dy * dy + dz * dz > radiusSq) && accessor.isInsideBuildHeight(ny) && accessor.isLoaded(nx, nz)) {
               NeoCpuShadowShape shape = accessor.shapeAt(neighborKey);
               boolean passable = neighborKey == sourceKey || shape.isEmpty() || !shape.blocksFlood();
               if (!shape.isEmpty()) {
                  int blockX = BlockPos.getX(neighborKey);
                  int blockY = BlockPos.getY(neighborKey);
                  int blockZ = BlockPos.getZ(neighborKey);
                  scratchReceivers.clear();
                  shape.appendReceiversForFace(neighborKey, blockX, blockY, blockZ, direction.getOpposite(), scratchReceivers);

                  for (NeoCpuShadowTypes.ReceiverSurface receiver : scratchReceivers) {
                     receiverFaces.add(receiver, receiver.distanceToSourceSq(sourceX, sourceY, sourceZ));
                  }
               }

               if (passable && visited.add(neighborKey)) {
                  queue.addLast(neighborKey);
               }
            }
         }
      }

      return new NeoCpuFloodLightVolume(visited, receiverFaces.faces());
   }

   private static int getVisitedCellLimit(double radius) {
      double sphereVolume = (Math.PI * 4.0 / 3.0) * radius * radius * radius;
      int estimatedCells = (int)Math.ceil(sphereVolume * 1.1);
      return Math.max(16384, Math.min(262144, estimatedCells));
   }

   LongOpenHashSet visitedCells() {
      return this.visitedCells;
   }

   List<NeoCpuFloodBoundaryFace> boundaryFaces() {
      return this.boundaryFaces;
   }
}
