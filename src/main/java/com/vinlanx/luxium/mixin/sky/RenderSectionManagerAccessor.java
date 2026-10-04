package com.vinlanx.luxium.mixin.sky;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(
   value = {RenderSectionManager.class},
   remap = false
)
public interface RenderSectionManagerAccessor {
   @Accessor("sectionByPosition")
   Long2ReferenceMap<RenderSection> luxium$getSectionByPosition();

   @Accessor("chunkRenderer")
   ChunkRenderer luxium$getChunkRenderer();
}
