package com.vinlanx.luxium.client.shadows.neoskycelestia;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class NeoSkyShadowRenderLists implements ChunkRenderListIterable {
   private static final int GEOMETRY_MASK = 1;
   private static final float SECTION_HALF = 8.0F;
   private final List<ChunkRenderList> lists;

   private NeoSkyShadowRenderLists(List<ChunkRenderList> lists) {
      this.lists = lists;
   }

   public Iterator<ChunkRenderList> iterator(boolean reverse) {
      if (!reverse) {
         return this.lists.iterator();
      } else {
         final ListIterator<ChunkRenderList> iterator = this.lists.listIterator(this.lists.size());
         return new Iterator<ChunkRenderList>() {
            @Override
            public boolean hasNext() {
               return iterator.hasPrevious();
            }

            public ChunkRenderList next() {
               return iterator.previous();
            }
         };
      }
   }

   public int regionCount() {
      return this.lists.size();
   }

   public static NeoSkyShadowRenderLists.BuildResult build(
      Long2ReferenceMap<RenderSection> sections,
      NeoSkyCascade nearCascade,
      boolean buildNear,
      NeoSkyCascade farCascade,
      boolean buildFar,
      int frameId,
      NeoSkyShadowRenderLists.Cache nearCache,
      NeoSkyShadowRenderLists.Cache farCache
   ) {
      if (!buildNear && !buildFar) {
         return new NeoSkyShadowRenderLists.BuildResult(null, Float.NaN, false, null, Float.NaN, false);
      } else {
         NeoSkyShadowRenderLists.BuildAccumulator near = buildNear ? new NeoSkyShadowRenderLists.BuildAccumulator(nearCache, frameId) : null;
         NeoSkyShadowRenderLists.BuildAccumulator far = buildFar ? new NeoSkyShadowRenderLists.BuildAccumulator(farCache, frameId) : null;
         NeoSkyShadowRenderLists.CasterVolume nearVolume = buildNear ? new NeoSkyShadowRenderLists.CasterVolume(nearCascade) : null;
         NeoSkyShadowRenderLists.CasterVolume farVolume = buildFar ? new NeoSkyShadowRenderLists.CasterVolume(farCascade) : null;
         Vector3f scratch = new Vector3f();
         ObjectIterator var13 = sections.values().iterator();

         while (var13.hasNext()) {
            RenderSection section = (RenderSection)var13.next();
            classifySection(section, near, nearVolume, far, farVolume, scratch);
         }

         return new NeoSkyShadowRenderLists.BuildResult(
            near != null ? near.finish() : null,
            near != null ? near.maxCasterAxial : Float.NaN,
            near != null && near.incomplete,
            far != null ? far.finish() : null,
            far != null ? far.maxCasterAxial : Float.NaN,
            far != null && far.incomplete
         );
      }
   }

   private static void classifySection(
      RenderSection section,
      NeoSkyShadowRenderLists.BuildAccumulator near,
      NeoSkyShadowRenderLists.CasterVolume nearVolume,
      NeoSkyShadowRenderLists.BuildAccumulator far,
      NeoSkyShadowRenderLists.CasterVolume farVolume,
      Vector3f scratch
   ) {
      if (!section.isDisposed()) {
         float nearFront = near != null ? nearVolume.intersectingCasterFront(section, scratch) : Float.NaN;
         float farFront = far != null ? farVolume.intersectingCasterFront(section, scratch) : Float.NaN;
         if (!section.isBuilt()) {
            if (Float.isFinite(nearFront)) {
               near.observeIncomplete(nearFront);
            }

            if (Float.isFinite(farFront)) {
               far.observeIncomplete(farFront);
            }
         } else if ((section.getFlags() & 1) != 0) {
            RenderRegion region = section.getRegion();
            if (region != null) {
               if (near != null && Float.isFinite(nearFront)) {
                  near.add(region, section, nearFront);
               }

               if (far != null && Float.isFinite(farFront)) {
                  far.add(region, section, farFront);
               }
            }
         }
      }
   }

   private static final class BuildAccumulator {
      private final NeoSkyShadowRenderLists.Cache cache;
      private final int frameId;
      private final Reference2ObjectLinkedOpenHashMap<RenderRegion, ChunkRenderList> active = new Reference2ObjectLinkedOpenHashMap();
      private float maxCasterAxial = Float.NEGATIVE_INFINITY;
      private boolean incomplete;

      private BuildAccumulator(NeoSkyShadowRenderLists.Cache cache, int frameId) {
         this.cache = cache;
         this.frameId = frameId;
      }

      private void observeIncomplete(float casterFront) {
         this.maxCasterAxial = Math.max(this.maxCasterAxial, casterFront);
         this.incomplete = true;
      }

      private void add(RenderRegion region, RenderSection section, float casterFront) {
         ChunkRenderList list = (ChunkRenderList)this.active.get(region);
         if (list == null) {
            list = this.cache.getOrCreate(region);
            list.reset(this.frameId);
            this.active.put(region, list);
         }

         list.add(section);
         this.maxCasterAxial = Math.max(this.maxCasterAxial, casterFront);
      }

      private NeoSkyShadowRenderLists finish() {
         this.cache.trimToActive(this.active);
         return new NeoSkyShadowRenderLists(new ArrayList<>(this.active.values()));
      }
   }

   public static record BuildResult(
      NeoSkyShadowRenderLists near, float nearCasterFront, boolean nearIncomplete, NeoSkyShadowRenderLists far, float farCasterFront, boolean farIncomplete
   ) {
   }

   public static final class Cache {
      private final Reference2ObjectOpenHashMap<RenderRegion, ChunkRenderList> lists = new Reference2ObjectOpenHashMap();

      private ChunkRenderList getOrCreate(RenderRegion region) {
         ChunkRenderList list = (ChunkRenderList)this.lists.get(region);
         if (list == null) {
            list = new ChunkRenderList(region);
            this.lists.put(region, list);
         }

         return list;
      }

      private void trimToActive(Reference2ObjectLinkedOpenHashMap<RenderRegion, ChunkRenderList> active) {
         int maximumRetained = active.size() * 3 + 64;
         if (this.lists.size() > maximumRetained) {
            Iterator<RenderRegion> iterator = this.lists.keySet().iterator();

            while (iterator.hasNext()) {
               if (!active.containsKey(iterator.next())) {
                  iterator.remove();
               }
            }
         }
      }

      public void clear() {
         this.lists.clear();
      }
   }

   private static final class CasterVolume {
      private final Vec3 center;
      private final Vec3 toLight;
      private final Matrix4f rotation;
      private final float lateralRadius;
      private final float receiverBackAxial;
      private final float sectionHalfX;
      private final float sectionHalfY;
      private final float sectionHalfAxial;

      private CasterVolume(NeoSkyCascade cascade) {
         this.center = cascade.center();
         this.toLight = cascade.toLight();
         this.rotation = cascade.lightViewRotation();
         this.lateralRadius = cascade.projectionRadius();
         this.receiverBackAxial = -cascade.radius() - cascade.receiverGuardBand();
         Vector3f worldX = new Vector3f(8.0F, 0.0F, 0.0F);
         Vector3f worldY = new Vector3f(0.0F, 8.0F, 0.0F);
         Vector3f worldZ = new Vector3f(0.0F, 0.0F, 8.0F);
         this.rotation.transformDirection(worldX);
         this.rotation.transformDirection(worldY);
         this.rotation.transformDirection(worldZ);
         this.sectionHalfX = Math.abs(worldX.x) + Math.abs(worldY.x) + Math.abs(worldZ.x);
         this.sectionHalfY = Math.abs(worldX.y) + Math.abs(worldY.y) + Math.abs(worldZ.y);
         this.sectionHalfAxial = 8.0F * ((float)Math.abs(this.toLight.x) + (float)Math.abs(this.toLight.y) + (float)Math.abs(this.toLight.z));
      }

      private float intersectingCasterFront(RenderSection section, Vector3f scratch) {
         double dx = (double)section.getCenterX() - this.center.x;
         double dy = (double)section.getCenterY() - this.center.y;
         double dz = (double)section.getCenterZ() - this.center.z;
         scratch.set((float)dx, (float)dy, (float)dz);
         this.rotation.transformDirection(scratch);
         if (!(scratch.x + this.sectionHalfX < -this.lateralRadius)
            && !(scratch.x - this.sectionHalfX > this.lateralRadius)
            && !(scratch.y + this.sectionHalfY < -this.lateralRadius)
            && !(scratch.y - this.sectionHalfY > this.lateralRadius)) {
            double axialCenter = dx * this.toLight.x + dy * this.toLight.y + dz * this.toLight.z;
            float maxAxial = (float)axialCenter + this.sectionHalfAxial;
            return maxAxial < this.receiverBackAxial ? Float.NaN : maxAxial;
         } else {
            return Float.NaN;
         }
      }
   }
}
