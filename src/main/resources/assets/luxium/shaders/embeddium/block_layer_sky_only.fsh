#version 330 core
#define LUXIUM_WATER_ENABLED 0
#define LUXIUM_ENTITY_SHADOWS_ENABLED 0
#ifdef GL_ARB_texture_gather
#extension GL_ARB_texture_gather : enable
#define LUXIUM_TEXTURE_GATHER
#endif
#import <sodium:include/fog.glsl>
#import <luxium:embeddium/include/sky_shadow.glsl>

in vec4 v_LuxiumShadowColorAlpha;
in vec3 v_LuxiumLightDelta;
in vec2 v_TexCoord;
in float v_MaterialMipBias;
in vec3 v_LuxiumShadowCoord0;
in vec3 v_LuxiumShadowCoord1;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
in vec3 v_LuxiumEntityShadowCoord;
#endif
in float v_LuxiumViewDistance;
flat in float v_LuxiumRawNdotL;
flat in uint v_LuxiumNormalCode;
#ifdef USE_FRAGMENT_DISCARD
in float v_MaterialAlphaCutoff;
#endif
#ifdef USE_FOG
in float v_FragDistance;
#endif

uniform sampler2D u_BlockTex;
uniform sampler2DShadow u_LuxiumShadowMap0;
uniform sampler2DShadow u_LuxiumShadowMap1;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
uniform sampler2D u_LuxiumEntityShadowMap;
#endif
uniform vec4 u_LuxiumCascadeData;
uniform vec4 u_LuxiumBiasData;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
uniform vec4 u_LuxiumEntityShadowData;
#endif
uniform int u_LuxiumSkyShadowEnabled;
uniform int u_LuxiumSkyLightEnabled;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
uniform int u_LuxiumEntityShadowEnabled;
#endif
uniform int u_LuxiumFilterSamples;
uniform int u_LuxiumShadowPass;
uniform vec4 u_FogColor;
uniform float u_FogStart;
uniform float u_FogEnd;
out vec4 fragColor;

void main() {
    if (u_LuxiumShadowPass != 0) {
#ifdef USE_FRAGMENT_DISCARD
        if (texture(u_BlockTex, v_TexCoord, v_MaterialMipBias).a < v_MaterialAlphaCutoff) discard;
#endif
        return;
    }

    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);
#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) discard;
#endif

    float visibility = 1.0;
    bool shadowCanAffectLighting = any(notEqual(v_LuxiumLightDelta, vec3(0.0)));
    if (u_LuxiumSkyShadowEnabled != 0 && shadowCanAffectLighting) {
        visibility = luxium_resolve_sky_visibility(
                u_LuxiumShadowMap0,
                u_LuxiumShadowMap1,
                v_LuxiumShadowCoord0,
            v_LuxiumShadowCoord1,
                v_LuxiumViewDistance,
                u_LuxiumCascadeData,
                u_LuxiumBiasData,
                u_LuxiumFilterSamples,
            max(v_LuxiumRawNdotL, 0.0));
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
        if (u_LuxiumEntityShadowEnabled != 0) {
            visibility = min(visibility, luxium_resolve_entity_visibility(
                    u_LuxiumEntityShadowMap,
                    v_LuxiumEntityShadowCoord,
                    u_LuxiumEntityShadowData,
                    u_LuxiumFilterSamples,
                    max(v_LuxiumRawNdotL, 0.0)));
        }
#endif
    }

    diffuseColor.rgb *= v_LuxiumShadowColorAlpha.rgb + v_LuxiumLightDelta * visibility;
    diffuseColor.a *= v_LuxiumShadowColorAlpha.a;
#ifdef USE_FOG
    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#else
    fragColor = diffuseColor;
#endif
}
