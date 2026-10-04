package com.vinlanx.luxium.client.kawase;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

public final class KawaseSourceRegistry {
   private static final KawaseSourceRegistry INSTANCE = new KawaseSourceRegistry();
   private final Map<Long, Byte> sources = new HashMap<>();
   private final Map<Long, Set<Long>> sourcesByChunk = new HashMap<>();
   @Nullable
   private ClientLevel level;
   private long version;
   private KawaseSourceRegistry.Snapshot cachedSnapshot = new KawaseSourceRegistry.Snapshot(0L, new long[0], new byte[0]);

   private KawaseSourceRegistry() {
   }

   public static KawaseSourceRegistry get() {
      return INSTANCE;
   }

   public synchronized void setLevel(@Nullable ClientLevel level) {
      if (this.level != level) {
         this.level = level;
         this.sources.clear();
         this.sourcesByChunk.clear();
         this.version++;
      }
   }

   public synchronized void addChunk(ClientLevel level, LevelChunk chunk) {
      if (this.level != level) {
         this.setLevel(level);
      }

      int chunkX = chunk.getPos().x;
      int chunkZ = chunk.getPos().z;
      this.removeChunkInternal(chunkX, chunkZ);
      long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
      Set<Long> chunkSources = new HashSet<>();
      chunk.findBlockLightSources((pos, state) -> {
         int emission = state.getLightEmission(level, pos);
         if (emission > 0) {
            long sourceKey = pos.asLong();
            this.sources.put(sourceKey, (byte)Math.min(emission, 15));
            chunkSources.add(sourceKey);
         }
      });
      if (!chunkSources.isEmpty()) {
         this.sourcesByChunk.put(chunkKey, chunkSources);
      }

      this.version++;
   }

   public synchronized void removeChunk(int chunkX, int chunkZ) {
      if (this.removeChunkInternal(chunkX, chunkZ)) {
         this.version++;
      }
   }

   public synchronized void updateBlock(ClientLevel level, BlockPos pos, BlockState newState) {
      if (this.level != level) {
         this.setLevel(level);
      }

      long sourceKey = pos.asLong();
      long chunkKey = ChunkPos.asLong(pos);
      int emission = newState.getLightEmission(level, pos);
      Byte previous = this.sources.get(sourceKey);
      if (emission > 0) {
         byte value = (byte)Math.min(emission, 15);
         if (previous != null && previous == value) {
            return;
         }

         this.sources.put(sourceKey, value);
         this.sourcesByChunk.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(sourceKey);
      } else {
         if (previous == null) {
            return;
         }

         this.sources.remove(sourceKey);
         Set<Long> chunkSources = this.sourcesByChunk.get(chunkKey);
         if (chunkSources != null) {
            chunkSources.remove(sourceKey);
            if (chunkSources.isEmpty()) {
               this.sourcesByChunk.remove(chunkKey);
            }
         }
      }

      this.version++;
   }

   public synchronized KawaseSourceRegistry.Snapshot snapshot() {
      if (this.cachedSnapshot.version() == this.version) {
         return this.cachedSnapshot;
      } else {
         long[] positions = new long[this.sources.size()];
         byte[] emissions = new byte[this.sources.size()];
         int index = 0;

         for (Entry<Long, Byte> entry : this.sources.entrySet()) {
            positions[index] = entry.getKey();
            emissions[index] = entry.getValue();
            index++;
         }

         this.cachedSnapshot = new KawaseSourceRegistry.Snapshot(this.version, positions, emissions);
         return this.cachedSnapshot;
      }
   }

   public synchronized void reconcileLoadedChunks(ClientLevel level) {
      if (this.level != level) {
         this.setLevel(level);
      } else {
         boolean changed = false;
         Iterator<Entry<Long, Set<Long>>> iterator = this.sourcesByChunk.entrySet().iterator();

         while (iterator.hasNext()) {
            Entry<Long, Set<Long>> entry = iterator.next();
            long chunkKey = entry.getKey();
            if (level.getChunkSource().getChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), ChunkStatus.FULL, false) == null) {
               for (long sourceKey : entry.getValue()) {
                  this.sources.remove(sourceKey);
               }

               iterator.remove();
               changed = true;
            }
         }

         if (changed) {
            this.version++;
         }
      }
   }

   private boolean removeChunkInternal(int chunkX, int chunkZ) {
      Set<Long> removed = this.sourcesByChunk.remove(ChunkPos.asLong(chunkX, chunkZ));
      if (removed != null && !removed.isEmpty()) {
         for (long sourceKey : removed) {
            this.sources.remove(sourceKey);
         }

         return true;
      } else {
         return false;
      }
   }

   public static record Snapshot(long version, long[] positions, byte[] emissions) {
   }
}
