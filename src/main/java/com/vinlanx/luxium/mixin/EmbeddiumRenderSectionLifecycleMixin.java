package com.vinlanx.luxium.mixin;

import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {RenderSection.class},
   remap = false
)
public abstract class EmbeddiumRenderSectionLifecycleMixin {
   @Shadow
   public abstract int getOriginX();

   @Shadow
   public abstract int getOriginY();

   @Shadow
   public abstract int getOriginZ();

   @Inject(
      method = {"setInfo"},
      at = {@At("TAIL")},
      remap = false,
      require = 0
   )
   private void luxium$afterEmbeddiumMeshUpload(BuiltSectionInfo info, CallbackInfo ci) {
      if (NeoGpuVanilla.isConfiguredEnabled()) {
         NeoGpuVanilla.onRenderedSectionChanged(this.getOriginX(), this.getOriginY(), this.getOriginZ());
      }
   }
}
