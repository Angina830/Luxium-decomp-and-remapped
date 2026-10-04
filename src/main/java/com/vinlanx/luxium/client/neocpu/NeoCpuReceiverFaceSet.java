package com.vinlanx.luxium.client.neocpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

final class NeoCpuReceiverFaceSet {
   private final HashSet<NeoCpuReceiverFaceSet.Key> seen = new HashSet<>();
   private final ArrayList<NeoCpuFloodBoundaryFace> faces = new ArrayList<>(256);

   void add(NeoCpuShadowTypes.ReceiverSurface receiver, double distanceSq) {
      NeoCpuReceiverFaceSet.Key key = new NeoCpuReceiverFaceSet.Key(
         receiver.blockKey(),
         receiver.face().ordinal(),
         quantize(receiver.plane()),
         quantize(receiver.minU()),
         quantize(receiver.maxU()),
         quantize(receiver.minV()),
         quantize(receiver.maxV())
      );
      if (this.seen.add(key)) {
         this.faces.add(new NeoCpuFloodBoundaryFace(receiver, distanceSq));
      }
   }

   List<NeoCpuFloodBoundaryFace> faces() {
      this.faces.sort(Comparator.comparingDouble(NeoCpuFloodBoundaryFace::distanceToSourceSq));
      return this.faces;
   }

   private static long quantize(double value) {
      return Math.round(value * 1000000.0);
   }

   private static record Key(long blockKey, int faceOrdinal, long plane, long minU, long maxU, long minV, long maxV) {
   }
}
