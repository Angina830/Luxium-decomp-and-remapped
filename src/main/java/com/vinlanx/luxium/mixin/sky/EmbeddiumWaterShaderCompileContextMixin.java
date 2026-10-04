package com.vinlanx.luxium.mixin.sky;

import com.vinlanx.luxium.client.water.EmbeddiumWaterShaderCompileContext;
import com.vinlanx.luxium.client.water.WaterTerrainPass;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
   value = {ShaderChunkRenderer.class},
   remap = false
)
public abstract class EmbeddiumWaterShaderCompileContextMixin {
   @Inject(
      method = {"createShader"},
      at = {@At("HEAD")}
   )
   private void luxium$beginWaterShaderCompile(String path, ChunkShaderOptions options, CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> cir) {
      EmbeddiumWaterShaderCompileContext.begin(WaterTerrainPass.is(options.pass()), options.pass().isReverseOrder());
   }

   @Inject(
      method = {"createShader"},
      at = {@At("RETURN")}
   )
   private void luxium$endWaterShaderCompile(String path, ChunkShaderOptions options, CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> cir) {
      EmbeddiumWaterShaderCompileContext.end();
   }
}
