package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.rtx.LuxiumLayerLightStorageExtension;
import com.vinlanx.luxium.rtx.LuxiumLightEngineExtension;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LightEngine.QueueEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin({LightEngine.class})
public abstract class LightEngineMixin implements LuxiumLightEngineExtension {
   @Shadow
   @Final
   private LongOpenHashSet blockNodesToCheck;
   @Shadow
   @Final
   private LongArrayFIFOQueue decreaseQueue;
   @Shadow
   @Final
   private LongArrayFIFOQueue increaseQueue;
   @Shadow
   @Final
   protected LayerLightSectionStorage<?> storage;

   @Shadow
   protected abstract void checkNode(long var1);

   @Shadow
   protected abstract void propagateIncrease(long var1, long var3, int var5);

   @Shadow
   protected abstract void propagateDecrease(long var1, long var3);

   @Invoker("clearChunkCache")
   protected abstract void luxium$invokeClearChunkCache();

   @Override
   public int luxium$runLightUpdatesBudgeted(int maxOperations) {
      int budget = Math.max(1, maxOperations);
      int processed = 0;

      for (LongIterator nodes = this.blockNodesToCheck.iterator(); processed < budget && nodes.hasNext(); processed++) {
         long posKey = nodes.nextLong();
         nodes.remove();
         this.checkNode(posKey);
      }

      if (this.blockNodesToCheck.isEmpty()) {
         this.blockNodesToCheck.trim(512);
      }

      while (processed < budget && !this.decreaseQueue.isEmpty()) {
         long posKey = this.decreaseQueue.dequeueLong();
         long entry = this.decreaseQueue.dequeueLong();
         this.propagateDecrease(posKey, entry);
         processed++;
      }

      LuxiumLayerLightStorageExtension storageAccess;
      for (storageAccess = (LuxiumLayerLightStorageExtension)this.storage;
         processed < budget && this.decreaseQueue.isEmpty() && !this.increaseQueue.isEmpty();
         processed++
      ) {
         long posKey = this.increaseQueue.dequeueLong();
         long entry = this.increaseQueue.dequeueLong();
         int stored = storageAccess.luxium$getStoredLevel(posKey);
         int fromLevel = QueueEntry.getFromLevel(entry);
         if (QueueEntry.isIncreaseFromEmission(entry) && stored < fromLevel) {
            storageAccess.luxium$setStoredLevel(posKey, fromLevel);
            stored = fromLevel;
         }

         if (stored == fromLevel) {
            this.propagateIncrease(posKey, entry, stored);
         }
      }

      this.luxium$invokeClearChunkCache();
      if (this.blockNodesToCheck.isEmpty() && this.decreaseQueue.isEmpty() && this.increaseQueue.isEmpty()) {
         storageAccess.luxium$markNewInconsistencies((LightEngine<?, ?>)this);
      }

      storageAccess.luxium$swapSectionMap();
      return processed;
   }
}
