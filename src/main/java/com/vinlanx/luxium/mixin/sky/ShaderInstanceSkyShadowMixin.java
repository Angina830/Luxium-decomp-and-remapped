package com.vinlanx.luxium.mixin.sky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.vinlanx.luxium.Config;
import com.vinlanx.luxium.client.NeoShadowsEngine;
import com.vinlanx.luxium.client.BlockLightTest.BlockLightTest;
import com.vinlanx.luxium.client.shaders.LuxiumGpuShaderFeatures;
import com.vinlanx.luxium.client.shadows.GpuNeoShadows;
import com.vinlanx.luxium.client.shadows.GpuShadowFrameState;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestia;
import com.vinlanx.luxium.client.shadows.neoskycelestia.NeoSkyCelestiaFrameState;
import com.vinlanx.luxium.client.tfrpluslsr.TemporalFrameSystem;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanilla;
import com.vinlanx.luxium.rtx.neogpuvanilla.NeoGpuVanillaFrameState;
import java.util.Arrays;
import java.util.Set;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ShaderInstance.class})
public abstract class ShaderInstanceSkyShadowMixin {
   @Unique
   private static final Set<String> LUXIUM$RECEIVER_SHADERS = Set.of(
      "rendertype_entity_solid",
      "rendertype_entity_cutout",
      "rendertype_entity_cutout_no_cull",
      "rendertype_entity_translucent",
      "rendertype_entity_translucent_cull",
      "rendertype_armor_cutout_no_cull"
   );
   @Shadow
   @Final
   private String name;
   @Unique
   private boolean luxium$receiverShaderResolved;
   @Unique
   private boolean luxium$receiverShader;
   @Unique
   private boolean luxium$uniformsResolved;
   @Unique
   private Uniform luxium$nearMatrix;
   @Unique
   private Uniform luxium$farMatrix;
   @Unique
   private Uniform luxium$entityMatrix;
   @Unique
   private Uniform luxium$cascadeData;
   @Unique
   private Uniform luxium$biasData;
   @Unique
   private Uniform luxium$entityShadowData;
   @Unique
   private Uniform luxium$shadowEnabled;
   @Unique
   private Uniform luxium$entityShadowEnabled;
   @Unique
   private Uniform luxium$entityShadowCasterPass;
   @Unique
   private Uniform luxium$skyLightEnabled;
   @Unique
   private Uniform luxium$filterSamples;
   @Unique
   private Uniform luxium$lightDirection;
   @Unique
   private Uniform luxium$directColor;
   @Unique
   private Uniform luxium$skyAmbientColor;
   @Unique
   private Uniform luxium$groundAmbientColor;
   @Unique
   private Uniform luxium$directStrength;
   @Unique
   private Uniform luxium$ambientStrength;
   @Unique
   private Uniform luxium$vanillaBlockLightInSunShadows;
   @Unique
   private NeoSkyCelestiaFrameState luxium$lastUploadedState;
   @Unique
   private boolean luxium$lastUploadedActive;
   @Unique
   private boolean luxium$lastUploadedCasterPass;
   @Unique
   private float luxium$lastUploadedVanillaBlockLightInSunShadows = Float.NaN;
   @Unique
   private boolean luxium$localLocationsResolved;
   @Unique
   private boolean luxium$neoGpuLocationsResolved;
   @Unique
   private int luxium$localEnabledLoc;
   @Unique
   private int luxium$localReplaceLoc;
   @Unique
   private int luxium$localSoftLoc;
   @Unique
   private int luxium$localModeLoc;
   @Unique
   private int luxium$localSpreadLoc;
   @Unique
   private int luxium$localCountLoc;
   @Unique
   private int luxium$localAtlasLoc;
   @Unique
   private int luxium$localGridLoc;
   @Unique
   private int luxium$localLightsLoc;
   @Unique
   private int luxium$localRectsLoc;
   @Unique
   private int luxium$localBakedHardEnabledLoc;
   @Unique
   private int luxium$localBakedHardMinLoc;
   @Unique
   private int luxium$localBakedHardSizeLoc;
   @Unique
   private int[] luxium$localBakedHardSamplerLocs;
   @Unique
   private int luxium$neoGpuEnabledLoc;
   @Unique
   private int luxium$neoGpuSamplerLoc;
   @Unique
   private int luxium$neoGpuFineScaleLoc;
   @Unique
   private int luxium$neoGpuMinLoc;
   @Unique
   private int luxium$neoGpuSizeLoc;
   @Unique
   private int luxium$neoGpuFastEnabledLoc;
   @Unique
   private int[] luxium$neoGpuFastSamplerLocs;
   @Unique
   private long luxium$lastLocalVersion = Long.MIN_VALUE;

   @Inject(
      method = {"apply"},
      at = {@At("TAIL")},
      require = 0
   )
   private void luxium$uploadBlockLightTest(CallbackInfo ci) {
      if (this.luxium$isReceiverShader()) {
         if (LuxiumGpuShaderFeatures.neoSkyCelestiaEnabled() || BlockLightTest.enabled()) {
            BlockLightTest.bind(((ShaderInstance)this).getId(), true);
         }
      }
   }

   @Unique
   private boolean luxium$isReceiverShader() {
      if (!this.luxium$receiverShaderResolved) {
         this.luxium$receiverShader = LUXIUM$RECEIVER_SHADERS.contains(this.name);
         this.luxium$receiverShaderResolved = true;
      }

      return this.luxium$receiverShader;
   }

   @Inject(
      method = {"apply"},
      at = {@At("HEAD")}
   )
   private void luxium$bindSkyShadowState(CallbackInfo ci) {
      if (this.luxium$isReceiverShader() && LuxiumGpuShaderFeatures.receiverBindingEnabled()) {
         ShaderInstance shader = (ShaderInstance)this;
         if (!this.luxium$uniformsResolved) {
            this.luxium$resolveUniforms(shader);
         }

         if (TemporalFrameSystem.isFirstPersonOverlayPass()) {
            if (this.luxium$shadowEnabled != null) {
               this.luxium$shadowEnabled.set(0);
            }

            if (this.luxium$entityShadowEnabled != null) {
               this.luxium$entityShadowEnabled.set(0);
            }

            if (this.luxium$entityShadowCasterPass != null) {
               this.luxium$entityShadowCasterPass.set(0);
            }

            if (this.luxium$skyLightEnabled != null) {
               this.luxium$skyLightEnabled.set(0);
            }

            this.luxium$lastUploadedState = null;
            this.luxium$lastUploadedActive = false;
            this.luxium$lastUploadedCasterPass = false;
            this.luxium$lastUploadedVanillaBlockLightInSunShadows = Float.NaN;
         } else {
            NeoSkyCelestia system = NeoSkyCelestia.get();
            NeoSkyCelestiaFrameState state = system.frameState();
            boolean active = system.isMainPassActive();
            boolean skyEntityCasterPass = system.isRenderingEntityShadowPass();
            boolean casterPass = skyEntityCasterPass || NeoShadowsEngine.isGpuShadowAtlasCapturePass();
            if (skyEntityCasterPass) {
               system.bindEntityShadowTarget();
            }

            float vanillaBlockLightInSunShadows = state.moonLight() ? 0.0F : ((Double)Config.CLIENT.vanillaBlockLightInSunShadows.get()).floatValue() * 0.01F;
            if (this.luxium$lastUploadedState != state
               || this.luxium$lastUploadedActive != active
               || this.luxium$lastUploadedCasterPass != casterPass
               || Float.compare(this.luxium$lastUploadedVanillaBlockLightInSunShadows, vanillaBlockLightInSunShadows) != 0) {
               this.luxium$lastUploadedState = state;
               this.luxium$lastUploadedActive = active;
               this.luxium$lastUploadedCasterPass = casterPass;
               this.luxium$lastUploadedVanillaBlockLightInSunShadows = vanillaBlockLightInSunShadows;
               boolean enabled = active && state.enabled();
               boolean skyLightEnabled = active && state.skyLightEnabled();
               shader.setSampler("LuxiumBlockOnlyLightSampler", state.blockOnlyLightTexture());
               shader.setSampler("LuxiumShadowMap0", state.nearDepthTexture());
               shader.setSampler("LuxiumShadowMap1", state.farDepthTexture());
               shader.setSampler("LuxiumEntityShadowMap", state.entityDepthTexture());
               if (this.luxium$nearMatrix != null) {
                  this.luxium$nearMatrix.set(state.nearLightFromView());
               }

               if (this.luxium$farMatrix != null) {
                  this.luxium$farMatrix.set(state.farLightFromView());
               }

               if (this.luxium$entityMatrix != null) {
                  this.luxium$entityMatrix.set(state.entityLightFromView());
               }

               if (this.luxium$cascadeData != null) {
                  this.luxium$cascadeData.set(state.nearRadius(), state.farRadius(), state.nearTexelSize(), state.farTexelSize());
               }

               if (this.luxium$biasData != null) {
                  this.luxium$biasData.set(state.baseBiasNear(), state.baseBiasFar(), state.slopeBias(), state.cascadeBlendStart());
               }

               if (this.luxium$entityShadowData != null) {
                  this.luxium$entityShadowData.set(state.entityTexelSize(), state.entityBaseBias(), state.slopeBias(), state.entityRadius());
               }

               if (this.luxium$shadowEnabled != null) {
                  this.luxium$shadowEnabled.set(enabled ? 1 : 0);
               }

               if (this.luxium$entityShadowEnabled != null) {
                  this.luxium$entityShadowEnabled.set(active && state.entityShadowEnabled() ? 1 : 0);
               }

               if (this.luxium$entityShadowCasterPass != null) {
                  this.luxium$entityShadowCasterPass.set(casterPass ? 1 : 0);
               }

               if (this.luxium$skyLightEnabled != null) {
                  this.luxium$skyLightEnabled.set(skyLightEnabled ? 1 : 0);
               }

               if (this.luxium$filterSamples != null) {
                  this.luxium$filterSamples.set(state.filterSamples());
               }

               if (this.luxium$lightDirection != null) {
                  this.luxium$lightDirection.set(state.lightDirection().x, state.lightDirection().y, state.lightDirection().z);
               }

               if (this.luxium$directColor != null) {
                  this.luxium$directColor.set(state.directColor().x, state.directColor().y, state.directColor().z);
               }

               if (this.luxium$skyAmbientColor != null) {
                  this.luxium$skyAmbientColor.set(state.skyAmbientColor().x, state.skyAmbientColor().y, state.skyAmbientColor().z);
               }

               if (this.luxium$groundAmbientColor != null) {
                  this.luxium$groundAmbientColor.set(state.groundAmbientColor().x, state.groundAmbientColor().y, state.groundAmbientColor().z);
               }

               if (this.luxium$directStrength != null) {
                  this.luxium$directStrength.set(state.directStrength() * 0.7F);
               }

               if (this.luxium$ambientStrength != null) {
                  this.luxium$ambientStrength.set(state.ambientStrength());
               }

               if (this.luxium$vanillaBlockLightInSunShadows != null) {
                  this.luxium$vanillaBlockLightInSunShadows.set(vanillaBlockLightInSunShadows);
               }
            }
         }
      }
   }

   @Inject(
      method = {"apply"},
      at = {@At("TAIL")}
   )
   private void luxium$bindLocalShadowState(CallbackInfo ci) {
      if (LUXIUM$RECEIVER_SHADERS.contains(this.name)) {
         ShaderInstance shader = (ShaderInstance)this;
         int program = shader.getId();
         if (program > 0 && GL20.glGetProgrami(program, 35714) != 0) {
            if (LuxiumGpuShaderFeatures.neoGpuVanillaReceiverEnabled()) {
               if (!this.luxium$neoGpuLocationsResolved) {
                  if (this.luxium$neoGpuFastSamplerLocs == null) {
                     this.luxium$neoGpuFastSamplerLocs = new int[6];
                     Arrays.fill(this.luxium$neoGpuFastSamplerLocs, -1);
                  }

                  this.luxium$neoGpuEnabledLoc = GL20.glGetUniformLocation(program, "LuxiumNeoGpuVanillaEnabled");
                  this.luxium$neoGpuSamplerLoc = GL20.glGetUniformLocation(program, "LuxiumNeoGpuVanillaVolume");
                  this.luxium$neoGpuFastEnabledLoc = GL20.glGetUniformLocation(program, "LuxiumNeoGpuFastShadowsEnabled");
                  this.luxium$neoGpuFastSamplerLocs[0] = GL20.glGetUniformLocation(program, "LuxiumNeoGpuFastLightPX");
                  this.luxium$neoGpuFastSamplerLocs[1] = GL20.glGetUniformLocation(program, "LuxiumNeoGpuFastLightNX");
                  this.luxium$neoGpuFastSamplerLocs[2] = GL20.glGetUniformLocation(program, "LuxiumNeoGpuFastLightPY");
                  this.luxium$neoGpuFastSamplerLocs[3] = GL20.glGetUniformLocation(program, "LuxiumNeoGpuFastLightNY");
                  this.luxium$neoGpuFastSamplerLocs[4] = GL20.glGetUniformLocation(program, "LuxiumNeoGpuFastLightPZ");
                  this.luxium$neoGpuFastSamplerLocs[5] = GL20.glGetUniformLocation(program, "LuxiumNeoGpuFastLightNZ");
                  this.luxium$neoGpuFineScaleLoc = GL20.glGetUniformLocation(program, "LuxiumNeoGpuVanillaFineScale");
                  this.luxium$neoGpuMinLoc = GL20.glGetUniformLocation(program, "LuxiumNeoGpuVanillaMin");
                  this.luxium$neoGpuSizeLoc = GL20.glGetUniformLocation(program, "LuxiumNeoGpuVanillaSize");
                  this.luxium$neoGpuLocationsResolved = true;
               }

               if (TemporalFrameSystem.isFirstPersonOverlayPass()) {
                  if (this.luxium$neoGpuEnabledLoc >= 0) {
                     GL20.glUniform1i(this.luxium$neoGpuEnabledLoc, 0);
                  }

                  if (this.luxium$neoGpuFastEnabledLoc >= 0) {
                     GL20.glUniform1i(this.luxium$neoGpuFastEnabledLoc, 0);
                  }

                  if (this.luxium$neoGpuFineScaleLoc >= 0) {
                     GL20.glUniform1i(this.luxium$neoGpuFineScaleLoc, 1);
                  }
               } else {
                  NeoGpuVanillaFrameState neoGpu = NeoGpuVanilla.frameState();
                  boolean neoGpuActive = NeoGpuVanilla.isMainPassActive() && neoGpu.enabled() && !NeoShadowsEngine.isAnyShadowCapturePass();
                  boolean neoGpuFastActive = neoGpuActive && neoGpu.fastShadowsEnabled();
                  if (this.luxium$neoGpuEnabledLoc >= 0) {
                     GL20.glUniform1i(this.luxium$neoGpuEnabledLoc, neoGpuActive ? 1 : 0);
                  }

                  if (this.luxium$neoGpuFastEnabledLoc >= 0) {
                     GL20.glUniform1i(this.luxium$neoGpuFastEnabledLoc, neoGpuFastActive ? 1 : 0);
                  }

                  if (this.luxium$neoGpuFineScaleLoc >= 0) {
                     GL20.glUniform1i(this.luxium$neoGpuFineScaleLoc, neoGpuActive ? neoGpu.fineScale() : 1);
                  }

                  if (neoGpuActive) {
                     luxium$bindTexture(9, neoGpu.volumeTexture());
                     if (this.luxium$neoGpuSamplerLoc >= 0) {
                        GL20.glUniform1i(this.luxium$neoGpuSamplerLoc, 9);
                     }

                     if (this.luxium$neoGpuMinLoc >= 0) {
                        GL20.glUniform3f(this.luxium$neoGpuMinLoc, neoGpu.minX(), neoGpu.minY(), neoGpu.minZ());
                     }

                     if (this.luxium$neoGpuSizeLoc >= 0) {
                        GL20.glUniform3f(this.luxium$neoGpuSizeLoc, neoGpu.sizeX(), neoGpu.sizeY(), neoGpu.sizeZ());
                     }

                     if (neoGpuFastActive) {
                        int[] textures = new int[]{
                           neoGpu.fastLightPX(), neoGpu.fastLightNX(), neoGpu.fastLightPY(), neoGpu.fastLightNY(), neoGpu.fastLightPZ(), neoGpu.fastLightNZ()
                        };

                        for (int i = 0; i < 6; i++) {
                           luxium$bindTexture3D(10 + i, textures[i]);
                           if (this.luxium$neoGpuFastSamplerLocs[i] >= 0) {
                              GL20.glUniform1i(this.luxium$neoGpuFastSamplerLocs[i], 10 + i);
                           }
                        }
                     }
                  }
               }

               GlStateManager._activeTexture(33984);
            }

            if (LuxiumGpuShaderFeatures.localReceiverEnabled()) {
               if (!this.luxium$localLocationsResolved) {
                  if (this.luxium$localBakedHardSamplerLocs == null) {
                     this.luxium$localBakedHardSamplerLocs = new int[6];
                     Arrays.fill(this.luxium$localBakedHardSamplerLocs, -1);
                  }

                  this.luxium$localEnabledLoc = GL20.glGetUniformLocation(program, "LuxiumLocalShadowEnabled");
                  this.luxium$localReplaceLoc = GL20.glGetUniformLocation(program, "LuxiumLocalReplaceBlockLight");
                  this.luxium$localSoftLoc = GL20.glGetUniformLocation(program, "LuxiumLocalSoftShadows");
                  this.luxium$localModeLoc = GL20.glGetUniformLocation(program, "LuxiumLocalShadowMode");
                  this.luxium$localSpreadLoc = GL20.glGetUniformLocation(program, "LuxiumLocalSpreadOcclusion");
                  this.luxium$localCountLoc = GL20.glGetUniformLocation(program, "LuxiumLocalLightCount");
                  this.luxium$localAtlasLoc = GL20.glGetUniformLocation(program, "LuxiumLocalShadowAtlas");
                  this.luxium$localGridLoc = GL20.glGetUniformLocation(program, "LuxiumLocalLightGrid");
                  this.luxium$localLightsLoc = luxium$uniformLocation(program, "LuxiumLocalLights");
                  this.luxium$localRectsLoc = luxium$uniformLocation(program, "LuxiumLocalAtlasRect");
                  this.luxium$localBakedHardEnabledLoc = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardEnabled");
                  this.luxium$localBakedHardSamplerLocs[0] = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardPX");
                  this.luxium$localBakedHardSamplerLocs[1] = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardNX");
                  this.luxium$localBakedHardSamplerLocs[2] = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardPY");
                  this.luxium$localBakedHardSamplerLocs[3] = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardNY");
                  this.luxium$localBakedHardSamplerLocs[4] = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardPZ");
                  this.luxium$localBakedHardSamplerLocs[5] = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardNZ");
                  this.luxium$localBakedHardMinLoc = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardMin");
                  this.luxium$localBakedHardSizeLoc = GL20.glGetUniformLocation(program, "LuxiumLocalBakedHardSize");
                  this.luxium$localLocationsResolved = true;
               }

               if (TemporalFrameSystem.isFirstPersonOverlayPass()) {
                  if (this.luxium$localEnabledLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localEnabledLoc, 0);
                  }

                  if (this.luxium$localReplaceLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localReplaceLoc, 0);
                  }

                  if (this.luxium$localCountLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localCountLoc, 0);
                  }

                  if (this.luxium$localSoftLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localSoftLoc, 0);
                  }

                  if (this.luxium$localModeLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localModeLoc, 0);
                  }

                  if (this.luxium$localSpreadLoc >= 0) {
                     GL20.glUniform1f(this.luxium$localSpreadLoc, 0.0F);
                  }

                  if (this.luxium$localBakedHardEnabledLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localBakedHardEnabledLoc, 0);
                  }

                  GlStateManager._activeTexture(33984);
               } else {
                  GpuShadowFrameState state = GpuNeoShadows.frameState();
                  boolean active = GpuNeoShadows.isMainPassActive() && state.enabled() && !NeoShadowsEngine.isAnyShadowCapturePass();
                  boolean bakedHardActive = active && GpuNeoShadows.isBakedHardReceiverReady();
                  if (this.luxium$localEnabledLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localEnabledLoc, active ? 1 : 0);
                  }

                  if (this.luxium$localReplaceLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localReplaceLoc, active && state.replaceBlockLight() ? 1 : 0);
                  }

                  if (this.luxium$localCountLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localCountLoc, active && !bakedHardActive ? state.lightCount() : 0);
                  }

                  if (this.luxium$localSoftLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localSoftLoc, !bakedHardActive && state.softShadows() ? 1 : 0);
                  }

                  if (this.luxium$localModeLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localModeLoc, state.shadowMode());
                  }

                  if (this.luxium$localSpreadLoc >= 0) {
                     GL20.glUniform1f(this.luxium$localSpreadLoc, state.spreadOcclusionStrength());
                  }

                  if (this.luxium$localBakedHardEnabledLoc >= 0) {
                     GL20.glUniform1i(this.luxium$localBakedHardEnabledLoc, bakedHardActive ? 1 : 0);
                  }

                  if (active) {
                     if (!bakedHardActive) {
                        luxium$bindTexture(7, state.atlasTexture());
                        luxium$bindTexture(8, state.lightGridTexture());
                        if (this.luxium$localAtlasLoc >= 0) {
                           GL20.glUniform1i(this.luxium$localAtlasLoc, 7);
                        }

                        if (this.luxium$localGridLoc >= 0) {
                           GL20.glUniform1i(this.luxium$localGridLoc, 8);
                        }
                     }

                     if (bakedHardActive) {
                        for (int ix = 0; ix < 6; ix++) {
                           int textureUnit = 9 + ix;
                           luxium$bindTexture3D(textureUnit, GpuNeoShadows.bakedHardTexture(ix));
                           if (this.luxium$localBakedHardSamplerLocs[ix] >= 0) {
                              GL20.glUniform1i(this.luxium$localBakedHardSamplerLocs[ix], textureUnit);
                           }
                        }

                        if (this.luxium$localBakedHardMinLoc >= 0) {
                           GL20.glUniform3f(
                              this.luxium$localBakedHardMinLoc, GpuNeoShadows.bakedHardMinX(), GpuNeoShadows.bakedHardMinY(), GpuNeoShadows.bakedHardMinZ()
                           );
                        }

                        if (this.luxium$localBakedHardSizeLoc >= 0) {
                           GL20.glUniform3f(
                              this.luxium$localBakedHardSizeLoc, GpuNeoShadows.bakedHardSizeX(), GpuNeoShadows.bakedHardSizeY(), GpuNeoShadows.bakedHardSizeZ()
                           );
                        }
                     }

                     if (!bakedHardActive && this.luxium$lastLocalVersion != state.version()) {
                        if (this.luxium$localLightsLoc >= 0) {
                           GL20.glUniform4fv(this.luxium$localLightsLoc, state.lights());
                        }

                        if (this.luxium$localRectsLoc >= 0) {
                           GL20.glUniform4fv(this.luxium$localRectsLoc, state.atlasRects());
                        }

                        this.luxium$lastLocalVersion = state.version();
                     }

                     GlStateManager._activeTexture(33984);
                  }
               }
            }
         }
      }
   }

   @Unique
   private static int luxium$uniformLocation(int program, String name) {
      int location = GL20.glGetUniformLocation(program, name + "[0]");
      return location >= 0 ? location : GL20.glGetUniformLocation(program, name);
   }

   @Unique
   private static void luxium$bindTexture(int unit, int textureId) {
      GlStateManager._activeTexture(33984 + unit);
      GlStateManager._bindTexture(Math.max(textureId, 0));
   }

   @Unique
   private static void luxium$bindTexture3D(int unit, int textureId) {
      GL13.glActiveTexture(33984 + unit);
      GL11.glBindTexture(32879, Math.max(textureId, 0));
      GL13.glActiveTexture(33984);
   }

   @Unique
   private void luxium$resolveUniforms(ShaderInstance shader) {
      this.luxium$nearMatrix = shader.getUniform("LuxiumLightFromView0");
      this.luxium$farMatrix = shader.getUniform("LuxiumLightFromView1");
      this.luxium$entityMatrix = shader.getUniform("LuxiumEntityLightFromView");
      this.luxium$cascadeData = shader.getUniform("LuxiumCascadeData");
      this.luxium$biasData = shader.getUniform("LuxiumBiasData");
      this.luxium$entityShadowData = shader.getUniform("LuxiumEntityShadowData");
      this.luxium$shadowEnabled = shader.getUniform("LuxiumSkyShadowEnabled");
      this.luxium$entityShadowEnabled = shader.getUniform("LuxiumEntityShadowEnabled");
      this.luxium$entityShadowCasterPass = shader.getUniform("LuxiumEntityShadowCasterPass");
      this.luxium$skyLightEnabled = shader.getUniform("LuxiumSkyLightEnabled");
      this.luxium$filterSamples = shader.getUniform("LuxiumFilterSamples");
      this.luxium$lightDirection = shader.getUniform("LuxiumLightDirection");
      this.luxium$directColor = shader.getUniform("LuxiumDirectColor");
      this.luxium$skyAmbientColor = shader.getUniform("LuxiumSkyAmbientColor");
      this.luxium$groundAmbientColor = shader.getUniform("LuxiumGroundAmbientColor");
      this.luxium$directStrength = shader.getUniform("LuxiumDirectStrength");
      this.luxium$ambientStrength = shader.getUniform("LuxiumAmbientStrength");
      this.luxium$vanillaBlockLightInSunShadows = shader.getUniform("LuxiumVanillaBlockLightInSunShadows");
      this.luxium$uniformsResolved = true;
   }
}
