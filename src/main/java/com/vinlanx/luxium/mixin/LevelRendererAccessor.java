package com.vinlanx.luxium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin({LevelRenderer.class})
public interface LevelRendererAccessor {
   @Invoker("setupRender")
   void luxium$setupRender(Camera var1, Frustum var2, boolean var3, boolean var4);

   @Invoker("compileChunks")
   void luxium$compileChunks(Camera var1);

   @Invoker("renderChunkLayer")
   void luxium$renderChunkLayer(RenderType var1, PoseStack var2, double var3, double var5, double var7, Matrix4f var9);

   @Accessor("cullingFrustum")
   Frustum luxium$getCullingFrustum();
}
