package com.vinlanx.luxium.rtx;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class NeoFloodRtEngine {
   static final int VOLUME_RADIUS = 36;
   static final int VOLUME_SIDE = 73;
   static final int VOLUME_PLANE = 5329;
   static final int VOLUME_SIZE = 389017;
   private static final int MAX_POOLED_VISIBILITY_VOLUMES = 32;
   private static final ConcurrentLinkedQueue<byte[]> VISIBILITY_VOLUME_POOL = new ConcurrentLinkedQueue<>();
   private static final AtomicInteger VISIBILITY_VOLUME_POOL_SIZE = new AtomicInteger();
   private static final double HALF_BLOCK_DIAGONAL = 0.8660254037844386;
   private static final int MAX_DISTANCE_SQ = 3889;
   private static final int OPACITY_SOLID = 255;
   private static final float MIN_VISIBILITY = 0.007843138F;
   private static final int PROJECTION_TABLE_SIDE = 37;
   private static final int PROJECTION_TABLE_SIZE = 1369;
   private static final ThreadLocal<NeoFloodRtEngine.Scratch> SCRATCH = ThreadLocal.withInitial(NeoFloodRtEngine.Scratch::new);

   private NeoFloodRtEngine() {
   }

   static NeoFloodRtEngine.Result solve(long sourceKey, int lightLevel, ClientLevel level) {
      NeoFloodRtEngine.Scratch scratch = SCRATCH.get();
      scratch.reset();
      scratch.refreshTables(lightLevel);
      int originX = BlockPos.getX(sourceKey);
      int originY = BlockPos.getY(sourceKey);
      int originZ = BlockPos.getZ(sourceKey);
      double maxDistance = LightRtMath.getMaxDistance(lightLevel);
      int radius = Math.min(36, Math.min(FloodRtSettings.radiusCap(), (int)Math.ceil(maxDistance)));
      byte[] visibilityVolume = borrowVisibilityVolume();
      if (radius <= 0) {
         return finish(scratch, visibilityVolume, false);
      } else {
         boolean complete = fillOpacity(level, originX, originY, originZ, radius, scratch);
         propagate(scratch, visibilityVolume, radius);
         return finish(scratch, visibilityVolume, !complete);
      }
   }

   static byte[] borrowVisibilityVolume() {
      byte[] volume = VISIBILITY_VOLUME_POOL.poll();
      if (volume != null) {
         VISIBILITY_VOLUME_POOL_SIZE.decrementAndGet();
         Arrays.fill(volume, (byte)0);
         return volume;
      } else {
         return new byte[389017];
      }
   }

   static void recycleVisibilityVolume(byte[] volume) {
      if (volume != null && volume.length == 389017) {
         int size;
         do {
            size = VISIBILITY_VOLUME_POOL_SIZE.get();
            if (size >= 32) {
               return;
            }
         } while (!VISIBILITY_VOLUME_POOL_SIZE.compareAndSet(size, size + 1));

         VISIBILITY_VOLUME_POOL.offer(volume);
      }
   }

   private static boolean fillOpacity(ClientLevel level, int originX, int originY, int originZ, int radius, NeoFloodRtEngine.Scratch scratch) {
      byte[] opacity = scratch.opacity;
      Arrays.fill(opacity, (byte)0);
      int minX = originX - radius;
      int maxX = originX + radius;
      int minY = originY - radius;
      int maxY = originY + radius;
      int minZ = originZ - radius;
      int maxZ = originZ + radius;
      int minSectionX = SectionPos.blockToSectionCoord(minX);
      int maxSectionX = SectionPos.blockToSectionCoord(maxX);
      int minSectionY = SectionPos.blockToSectionCoord(minY);
      int maxSectionY = SectionPos.blockToSectionCoord(maxY);
      int minSectionZ = SectionPos.blockToSectionCoord(minZ);
      int maxSectionZ = SectionPos.blockToSectionCoord(maxZ);
      int minBuildHeight = level.getMinBuildHeight();
      boolean complete = true;

      for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
         for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
            LevelChunkSection[] sections = chunk != null ? chunk.getSections() : null;
            int chunkMinX = Math.max(minX, SectionPos.sectionToBlockCoord(sectionX));
            int chunkMaxX = Math.min(maxX, SectionPos.sectionToBlockCoord(sectionX) + 15);
            int chunkMinZ = Math.max(minZ, SectionPos.sectionToBlockCoord(sectionZ));
            int chunkMaxZ = Math.min(maxZ, SectionPos.sectionToBlockCoord(sectionZ) + 15);

            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
               int sectionBaseY = SectionPos.sectionToBlockCoord(sectionY);
               int chunkMinY = Math.max(minY, sectionBaseY);
               int chunkMaxY = Math.min(maxY, sectionBaseY + 15);
               if (chunkMinY <= chunkMaxY) {
                  LevelChunkSection section = null;
                  if (sections != null) {
                     int sectionIndex = sectionBaseY - minBuildHeight >> 4;
                     if (sectionIndex < 0 || sectionIndex >= sections.length) {
                        continue;
                     }

                     section = sections[sectionIndex];
                  }

                  if (chunk == null) {
                     complete = false;
                     fillSolidRange(opacity, originX, originY, originZ, radius, chunkMinX, chunkMaxX, chunkMinY, chunkMaxY, chunkMinZ, chunkMaxZ);
                  } else if (section != null && !section.hasOnlyAir()) {
                     boolean hasOccludersOrEmitters = section.getStates().maybeHas(state -> state.canOcclude() || state.getLightEmission() > 0);
                     if (hasOccludersOrEmitters) {
                        fillSectionRange(
                           opacity, section, originX, originY, originZ, radius, chunkMinX, chunkMaxX, chunkMinY, chunkMaxY, chunkMinZ, chunkMaxZ, scratch
                        );
                     }
                  }
               }
            }
         }
      }

      opacity[volumeIndex(0, 0, 0)] = 0;
      return complete;
   }

   private static void fillSolidRange(
      byte[] opacity, int originX, int originY, int originZ, int radius, int minX, int maxX, int minY, int maxY, int minZ, int maxZ
   ) {
      int radiusSq = radius * radius;

      for (int y = minY; y <= maxY; y++) {
         int dy = y - originY;
         int dy2 = dy * dy;
         if (dy2 <= radiusSq) {
            int baseY = (dy + 36) * 5329;

            for (int z = minZ; z <= maxZ; z++) {
               int dz = z - originZ;
               int dyz2 = dy2 + dz * dz;
               if (dyz2 <= radiusSq) {
                  int baseYZ = baseY + (dz + 36) * 73;

                  for (int x = minX; x <= maxX; x++) {
                     int dx = x - originX;
                     if (dyz2 + dx * dx <= radiusSq) {
                        opacity[baseYZ + dx + 36] = -1;
                     }
                  }
               }
            }
         }
      }
   }

   private static void fillSectionRange(
      byte[] opacity,
      LevelChunkSection section,
      int originX,
      int originY,
      int originZ,
      int radius,
      int minX,
      int maxX,
      int minY,
      int maxY,
      int minZ,
      int maxZ,
      NeoFloodRtEngine.Scratch scratch
   ) {
      int radiusSq = radius * radius;
      BlockState cachedState = scratch.cachedState;
      int cachedOpacity = scratch.cachedOpacity;

      for (int y = minY; y <= maxY; y++) {
         int dy = y - originY;
         int dy2 = dy * dy;
         if (dy2 <= radiusSq) {
            int localY = y & 15;
            int baseY = (dy + 36) * 5329;

            for (int z = minZ; z <= maxZ; z++) {
               int dz = z - originZ;
               int dyz2 = dy2 + dz * dz;
               if (dyz2 <= radiusSq) {
                  int localZ = z & 15;
                  int baseYZ = baseY + (dz + 36) * 73;

                  for (int x = minX; x <= maxX; x++) {
                     int dx = x - originX;
                     if (dyz2 + dx * dx <= radiusSq) {
                        BlockState state = section.getBlockState(x & 15, localY, localZ);
                        if (state != cachedState) {
                           cachedState = state;
                           cachedOpacity = computeOpacity(state);
                        }

                        if (cachedOpacity != 0) {
                           opacity[baseYZ + dx + 36] = (byte)cachedOpacity;
                        }
                     }
                  }
               }
            }
         }
      }

      scratch.cachedState = cachedState;
      scratch.cachedOpacity = cachedOpacity;
   }

   private static int computeOpacity(BlockState state) {
      if (state.isAir()) {
         return 0;
      } else if (state.getLightEmission() > 0) {
         return 0;
      } else {
         return state.canOcclude() ? 255 : 0;
      }
   }

   private static void propagate(NeoFloodRtEngine.Scratch scratch, byte[] visibilityVolume, int radius) {
      float[] throughput = scratch.throughput;
      byte[] opacity = scratch.opacity;
      int[] brightnessLut = scratch.brightnessLut;
      float softness = FloodRtSettings.penumbraSoftness();
      float edgeSeal = Math.min(1.0F, FloodRtSettings.cornerSeal() * 0.25F);
      int radiusSq = radius * radius;
      int centerIndex = volumeIndex(0, 0, 0);
      throughput[centerIndex] = 1.0F;
      emit(scratch, visibilityVolume, centerIndex, 0, 1.0F, brightnessLut);

      for (int octant = 0; octant < 8; octant++) {
         int signX = (octant & 1) == 0 ? 1 : -1;
         int signY = (octant & 2) == 0 ? 1 : -1;
         int signZ = (octant & 4) == 0 ? 1 : -1;

         for (int ay = 0; ay <= radius; ay++) {
            int ay2 = ay * ay;
            if (ay2 > radiusSq) {
               break;
            }

            int dy = ay * signY;
            int baseY = (dy + 36) * 5329;

            for (int az = 0; az <= radius; az++) {
               int ayz2 = ay2 + az * az;
               if (ayz2 > radiusSq) {
                  break;
               }

               int dz = az * signZ;
               int baseYZ = baseY + (dz + 36) * 73;

               for (int ax = 0; ax <= radius; ax++) {
                  int distanceSq = ayz2 + ax * ax;
                  if (distanceSq > radiusSq) {
                     break;
                  }

                  if (distanceSq != 0) {
                     int dx = ax * signX;
                     int index = baseYZ + dx + 36;
                     float incident = sampleBackProjected(scratch, ax, ay, az, signX, signY, signZ, softness, edgeSeal);
                     if (incident <= 0.007843138F) {
                        throughput[index] = 0.0F;
                     } else {
                        if (incident > 1.0F) {
                           incident = 1.0F;
                        }

                        int cellOpacity = opacity[index] & 255;
                        throughput[index] = cellOpacity >= 255 ? 0.0F : (cellOpacity == 0 ? incident : incident * (1.0F - (float)cellOpacity * 0.003921569F));
                        emit(scratch, visibilityVolume, index, distanceSq, incident, brightnessLut);
                     }
                  }
               }
            }
         }
      }
   }

   private static float sampleBackProjected(
      NeoFloodRtEngine.Scratch scratch, int ax, int ay, int az, int signX, int signY, int signZ, float softness, float edgeSeal
   ) {
      int shell = Math.max(ax, Math.max(ay, az));
      int row = shell * 37;
      int lowX = scratch.projectionLow[row + ax] & 255;
      int lowY = scratch.projectionLow[row + ay] & 255;
      int lowZ = scratch.projectionLow[row + az] & 255;
      float fracX = scratch.projectionFraction[row + ax];
      float fracY = scratch.projectionFraction[row + ay];
      float fracZ = scratch.projectionFraction[row + az];
      boolean nearHighX = (scratch.projectionNearest[row + ax] & 255) != lowX;
      boolean nearHighY = (scratch.projectionNearest[row + ay] & 255) != lowY;
      boolean nearHighZ = (scratch.projectionNearest[row + az] & 255) != lowZ;
      int stepY = signY * 5329;
      int stepZ = signZ * 73;
      int base = volumeIndex(signX * lowX, signY * lowY, signZ * lowZ);
      float[] throughput = scratch.throughput;
      float v000 = throughput[base];
      float minimum = v000;
      float maximum = v000;
      float smooth;
      float nearest;
      if (fracX == 0.0F) {
         if (fracY == 0.0F) {
            if (fracZ == 0.0F) {
               smooth = v000;
               nearest = v000;
            } else {
               float v001 = throughput[base + stepZ];
               minimum = Math.min(v000, v001);
               maximum = Math.max(v000, v001);
               smooth = lerp(v000, v001, fracZ);
               nearest = nearHighZ ? v001 : v000;
            }
         } else if (fracZ == 0.0F) {
            float v010 = throughput[base + stepY];
            minimum = Math.min(v000, v010);
            maximum = Math.max(v000, v010);
            smooth = lerp(v000, v010, fracY);
            nearest = nearHighY ? v010 : v000;
         } else {
            float v001 = throughput[base + stepZ];
            float v010 = throughput[base + stepY];
            float v011 = throughput[base + stepY + stepZ];
            minimum = Math.min(Math.min(v000, v001), Math.min(v010, v011));
            maximum = Math.max(Math.max(v000, v001), Math.max(v010, v011));
            smooth = bilerp(v000, v010, v001, v011, fracY, fracZ);
            nearest = nearHighY ? (nearHighZ ? v011 : v010) : (nearHighZ ? v001 : v000);
         }
      } else if (fracY == 0.0F) {
         if (fracZ == 0.0F) {
            float v100 = throughput[base + signX];
            minimum = Math.min(v000, v100);
            maximum = Math.max(v000, v100);
            smooth = lerp(v000, v100, fracX);
            nearest = nearHighX ? v100 : v000;
         } else {
            float v100 = throughput[base + signX];
            float v001 = throughput[base + stepZ];
            float v101 = throughput[base + signX + stepZ];
            minimum = Math.min(Math.min(v000, v100), Math.min(v001, v101));
            maximum = Math.max(Math.max(v000, v100), Math.max(v001, v101));
            smooth = bilerp(v000, v100, v001, v101, fracX, fracZ);
            nearest = nearHighX ? (nearHighZ ? v101 : v100) : (nearHighZ ? v001 : v000);
         }
      } else {
         float v100 = throughput[base + signX];
         float v010 = throughput[base + stepY];
         float v110 = throughput[base + signX + stepY];
         minimum = Math.min(Math.min(v000, v100), Math.min(v010, v110));
         maximum = Math.max(Math.max(v000, v100), Math.max(v010, v110));
         smooth = bilerp(v000, v100, v010, v110, fracX, fracY);
         nearest = nearHighX ? (nearHighY ? v110 : v100) : (nearHighY ? v010 : v000);
      }

      float filtered = smooth;
      if (edgeSeal > 0.0F && minimum <= 0.007843138F && maximum > 0.007843138F) {
         filtered = lerp(smooth, nearest, edgeSeal);
      }

      return lerp(nearest, filtered, softness);
   }

   private static float bilerp(float v00, float v10, float v01, float v11, float fx, float fy) {
      return lerp(lerp(v00, v10, fx), lerp(v01, v11, fx), fy);
   }

   private static float lerp(float a, float b, float t) {
      return a + (b - a) * t;
   }

   private static void emit(NeoFloodRtEngine.Scratch scratch, byte[] visibilityVolume, int index, int distanceSq, float incident, int[] brightnessLut) {
      int brightness = brightnessLut[distanceSq];
      if (brightness > 0) {
         int light = (int)((float)brightness * incident + 0.5F);
         if (light > 0) {
            if (light > 15) {
               light = 15;
            }

            int coverage = (int)(incident * 255.0F + 0.5F);
            if (coverage < 1) {
               coverage = 1;
            } else if (coverage > 255) {
               coverage = 255;
            }

            if (scratch.light[index] == 0) {
               scratch.touchedBits[index >>> 6] = scratch.touchedBits[index >>> 6] | 1L << (index & 63);
               scratch.touchedCount++;
            }

            if (light > (scratch.light[index] & 255)) {
               scratch.light[index] = (byte)light;
            }

            if (coverage > (visibilityVolume[index] & 255)) {
               visibilityVolume[index] = (byte)coverage;
            }
         }
      }
   }

   private static NeoFloodRtEngine.Result finish(NeoFloodRtEngine.Scratch scratch, byte[] visibilityVolume, boolean dataIncomplete) {
      int touchedCount = scratch.touchedCount;
      byte[] packedLight = new byte[194509];
      long[] touchedBits = Arrays.copyOf(scratch.touchedBits, scratch.touchedBits.length);

      for (int wordIndex = 0; wordIndex < touchedBits.length; wordIndex++) {
         for (long bits = touchedBits[wordIndex]; bits != 0L; bits &= bits - 1L) {
            int index = (wordIndex << 6) + Long.numberOfTrailingZeros(bits);
            if (index >= 389017) {
               break;
            }

            int light = scratch.light[index] & 15;
            if (light != 0) {
               int packedIndex = index >>> 1;
               int current = packedLight[packedIndex] & 255;
               packedLight[packedIndex] = (byte)((index & 1) == 0 ? current & 240 | light : current & 15 | light << 4);
            }
         }
      }

      return new NeoFloodRtEngine.Result(packedLight, visibilityVolume, touchedBits, touchedCount, dataIncomplete);
   }

   private static int volumeIndex(int dx, int dy, int dz) {
      return (dy + 36) * 5329 + (dz + 36) * 73 + dx + 36;
   }

   static final class Result {
      final byte[] packedLight;
      final byte[] visibilityVolume;
      final long[] touchedBits;
      final int solvedCellCount;
      final boolean dataIncomplete;

      Result(byte[] packedLight, byte[] visibilityVolume, long[] touchedBits, int solvedCellCount, boolean dataIncomplete) {
         this.packedLight = packedLight;
         this.visibilityVolume = visibilityVolume;
         this.touchedBits = touchedBits;
         this.solvedCellCount = solvedCellCount;
         this.dataIncomplete = dataIncomplete;
      }
   }

   private static final class Scratch {
      final float[] throughput = new float[389017];
      final byte[] opacity = new byte[389017];
      final byte[] light = new byte[389017];
      final long[] touchedBits = new long[6079];
      int touchedCount;
      final byte[] projectionLow = new byte[1369];
      final byte[] projectionNearest = new byte[1369];
      final float[] projectionFraction = new float[1369];
      final int[] brightnessLut = new int[3889];
      int projectionRevision = -1;
      int brightnessLightLevel = -1;
      BlockState cachedState;
      int cachedOpacity;

      void reset() {
         for (int wordIndex = 0; wordIndex < this.touchedBits.length; wordIndex++) {
            for (long bits = this.touchedBits[wordIndex]; bits != 0L; bits &= bits - 1L) {
               int index = (wordIndex << 6) + Long.numberOfTrailingZeros(bits);
               if (index >= 389017) {
                  break;
               }

               this.light[index] = 0;
            }
         }

         Arrays.fill(this.touchedBits, 0L);
         this.touchedCount = 0;
         this.cachedState = null;
         this.cachedOpacity = 0;
      }

      void refreshTables(int lightLevel) {
         int revision = FloodRtSettings.revision();
         if (revision != this.projectionRevision || lightLevel != this.brightnessLightLevel) {
            if (revision != this.projectionRevision) {
               float sharpness = FloodRtSettings.directionalBias();

               for (int shell = 0; shell <= 36; shell++) {
                  int row = shell * 37;

                  for (int axis = 0; axis <= 36; axis++) {
                     int tableIndex = row + axis;
                     if (shell == 0 || axis == 0) {
                        this.projectionLow[tableIndex] = 0;
                        this.projectionNearest[tableIndex] = 0;
                        this.projectionFraction[tableIndex] = 0.0F;
                     } else if (axis >= shell) {
                        this.projectionLow[tableIndex] = (byte)(shell - 1);
                        this.projectionNearest[tableIndex] = (byte)(shell - 1);
                        this.projectionFraction[tableIndex] = 0.0F;
                     } else {
                        double projected = (double)axis * (((double)shell - 1.0) / (double)shell);
                        int low = (int)Math.floor(projected);
                        double rawFraction = projected - (double)low;
                        this.projectionLow[tableIndex] = (byte)low;
                        this.projectionNearest[tableIndex] = (byte)((int)Math.floor(projected + 0.5));
                        this.projectionFraction[tableIndex] = warpFraction(rawFraction, sharpness);
                     }
                  }
               }

               this.projectionRevision = revision;
            }

            if (lightLevel != this.brightnessLightLevel) {
               for (int distanceSq = 0; distanceSq < 3889; distanceSq++) {
                  double distance = Math.sqrt((double)distanceSq);
                  double nearDistance = Math.max(0.0, distance - 0.8660254037844386);
                  this.brightnessLut[distanceSq] = LightRtMath.getFalloff(lightLevel, nearDistance);
               }

               this.brightnessLightLevel = lightLevel;
            }
         }
      }

      private static float warpFraction(double fraction, float sharpness) {
         if (fraction <= 0.0) {
            return 0.0F;
         } else if (fraction >= 1.0) {
            return 1.0F;
         } else if (Math.abs(sharpness - 1.0F) <= 1.0E-4F) {
            return (float)fraction;
         } else {
            double left = Math.pow(fraction, (double)sharpness);
            double right = Math.pow(1.0 - fraction, (double)sharpness);
            return (float)(left / (left + right));
         }
      }
   }
}
