package com.vinlanx.luxium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(
   targets = {"org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderMeshingTask"}
)
public interface EmbeddiumChunkBuilderMeshingTaskAccessor {
   @Accessor("render")
   Object luxium$getRender();
}
