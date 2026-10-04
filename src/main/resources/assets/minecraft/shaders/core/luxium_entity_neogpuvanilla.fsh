#version 150
#ifdef GL_ARB_texture_gather
#extension GL_ARB_texture_gather : enable
#define LUXIUM_TEXTURE_GATHER
#endif
#moj_import <fog.glsl>
#moj_import <luxium_sky_shadow_fragment.glsl>
#moj_import <luxium_neogpuvanilla_fragment.glsl>

uniform sampler2D Sampler0; uniform sampler2D Sampler2; uniform sampler2D LuxiumBlockOnlyLightSampler;
uniform sampler2DShadow LuxiumShadowMap0; uniform sampler2DShadow LuxiumShadowMap1;
uniform sampler2D LuxiumEntityShadowMap;

uniform vec4 ColorModulator; uniform float FogStart; uniform float FogEnd; uniform vec4 FogColor;
uniform vec4 LuxiumCascadeData; uniform vec4 LuxiumBiasData; uniform vec4 LuxiumEntityShadowData;
uniform int LuxiumSkyShadowEnabled; uniform int LuxiumSkyLightEnabled; uniform int LuxiumEntityShadowEnabled;

uniform int LuxiumEntityShadowCasterPass; uniform int LuxiumFilterSamples; uniform float LuxiumAlphaCutoff;
uniform vec3 LuxiumLightDirection; uniform vec3 LuxiumDirectColor; uniform vec3 LuxiumSkyAmbientColor;
uniform vec3 LuxiumGroundAmbientColor; uniform float LuxiumDirectStrength; uniform float LuxiumAmbientStrength;
uniform float LuxiumVanillaBlockLightInSunShadows;
uniform sampler2D LuxiumNeoGpuVanillaVolume;
uniform sampler3D LuxiumNeoGpuFastLightPX; uniform sampler3D LuxiumNeoGpuFastLightNX;
uniform sampler3D LuxiumNeoGpuFastLightPY; uniform sampler3D LuxiumNeoGpuFastLightNY;
uniform sampler3D LuxiumNeoGpuFastLightPZ; uniform sampler3D LuxiumNeoGpuFastLightNZ;
uniform int LuxiumNeoGpuFastShadowsEnabled;
uniform int LuxiumNeoGpuVanillaEnabled;
uniform int LuxiumNeoGpuVanillaFineScale;
uniform vec3 LuxiumNeoGpuVanillaMin;
uniform vec3 LuxiumNeoGpuVanillaSize;
in float vertexDistance; in float luxiumViewDistance; in vec4 vertexColor; in vec4 lightMapColor;
in vec4 skyOnlyLightMapColor; in vec4 blockOnlyLightMapColor; in vec4 zeroBlockOnlyLightMapColor;
in vec4 overlayColor; in vec2 texCoord0; in vec2 luxiumLightCoord; in vec3 luxiumShadowCoord0; in vec3 luxiumShadowCoord1;
in vec3 luxiumEntityShadowCoord; in vec3 luxiumNormal; in vec3 luxiumWorldPosition; out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (LuxiumAlphaCutoff >= 0.0 && color.a < LuxiumAlphaCutoff) discard;
    if (LuxiumEntityShadowCasterPass != 0) { fragColor = vec4(1.0); return; }
    color *= vertexColor * ColorModulator; color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    vec3 normal = normalize(luxiumNormal);
    vec3 localNormal = gl_FrontFacing ? normal : -normal;
    vec4 clippedLightMapColor = lightMapColor;
    vec4 clippedBlockOnlyLightMapColor = blockOnlyLightMapColor;
    float originalBlockLevel = clamp(luxiumLightCoord.x / 16.0, 0.0, 15.0);
    float clippedBlockLevel = luxium_ngv_clipped_level(
            LuxiumNeoGpuVanillaVolume, LuxiumNeoGpuVanillaEnabled,
            LuxiumNeoGpuVanillaMin, LuxiumNeoGpuVanillaSize,
            luxiumWorldPosition, localNormal, LuxiumNeoGpuVanillaFineScale,
            originalBlockLevel);
    if (clippedBlockLevel + 0.001 < originalBlockLevel) {
        float skyLevel = clamp(luxiumLightCoord.y / 16.0, 0.0, 15.0);
        clippedLightMapColor = luxium_ngv_sample_lightmap(
                Sampler2, clippedBlockLevel, skyLevel);
        clippedBlockOnlyLightMapColor = (LuxiumSkyShadowEnabled != 0 || LuxiumSkyLightEnabled != 0)
                ? luxium_ngv_sample_lightmap(
                        LuxiumBlockOnlyLightSampler, clippedBlockLevel, skyLevel)
                : clippedLightMapColor;
    }
    vec4 fullLight = clippedLightMapColor; vec4 noBlockLight = skyOnlyLightMapColor;
    if (LuxiumSkyShadowEnabled != 0 || LuxiumSkyLightEnabled != 0) {
        float rawNdotL = dot(normal, LuxiumLightDirection); float nDotL = max(rawNdotL, 0.0); float visibility = 1.0;
        if (LuxiumSkyShadowEnabled != 0 && rawNdotL > -0.16) {
            visibility = luxium_visibility(LuxiumShadowMap0, LuxiumShadowMap1, luxiumShadowCoord0,
                    luxiumShadowCoord1, luxiumViewDistance, LuxiumCascadeData, LuxiumBiasData, LuxiumFilterSamples, nDotL);
            if (LuxiumEntityShadowEnabled != 0) visibility = min(visibility, luxium_entity_visibility(
                    LuxiumEntityShadowMap, luxiumEntityShadowCoord, LuxiumEntityShadowData, LuxiumFilterSamples, nDotL));
        }
        if (LuxiumSkyLightEnabled != 0) {
            LuxiumCelestialTerms celestial = luxium_prepare_celestial_light(
                    zeroBlockOnlyLightMapColor, skyOnlyLightMapColor, normal, rawNdotL,
                    LuxiumDirectColor, LuxiumSkyAmbientColor, LuxiumGroundAmbientColor,
                    LuxiumDirectStrength, LuxiumAmbientStrength, visibility,
                    LuxiumSkyShadowEnabled != 0);
            fullLight = luxium_apply_prepared_celestial_light(
                    clippedLightMapColor, clippedBlockOnlyLightMapColor, zeroBlockOnlyLightMapColor,
                    skyOnlyLightMapColor, celestial, LuxiumVanillaBlockLightInSunShadows);
            noBlockLight = vec4(
                    zeroBlockOnlyLightMapColor.rgb + celestial.light,
                    skyOnlyLightMapColor.a);
        }
    }
    vec4 fastLocal = luxium_ngv_fast_lighting(
            LuxiumNeoGpuFastLightPX, LuxiumNeoGpuFastLightNX,
            LuxiumNeoGpuFastLightPY, LuxiumNeoGpuFastLightNY,
            LuxiumNeoGpuFastLightPZ, LuxiumNeoGpuFastLightNZ,
            LuxiumNeoGpuFastShadowsEnabled, LuxiumNeoGpuVanillaMin,
            LuxiumNeoGpuVanillaSize, luxiumWorldPosition, localNormal);
    vec3 resolvedLight = min(mix(fullLight.rgb, noBlockLight.rgb, fastLocal.a) + fastLocal.rgb, vec3(1.6));
    color.rgb *= resolvedLight;
    color.a *= fullLight.a;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
