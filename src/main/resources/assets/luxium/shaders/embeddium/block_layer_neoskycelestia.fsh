#version 330 core
#ifdef GL_ARB_texture_gather
#extension GL_ARB_texture_gather : enable
#define LUXIUM_TEXTURE_GATHER
#endif

#import <sodium:include/fog.glsl>
#import <luxium:embeddium/include/neoskycelestia_forward_shadow.glsl>

in vec4 v_NeoShadowColorAlpha;
in vec3 v_NeoLightDelta;
in vec2 v_TexCoord;
in float v_MaterialMipBias;
flat in float v_NeoRawNdotL;
in vec3 v_NeoShadowCoord0;
in vec3 v_NeoShadowCoord1;
in vec3 v_NeoEntityShadowCoord;
in float v_NeoViewDistance;
#ifdef USE_FRAGMENT_DISCARD
in float v_MaterialAlphaCutoff;
#endif
#ifdef USE_FOG
in float v_FragDistance;
#endif

uniform sampler2D u_BlockTex;
uniform sampler2DShadow u_LuxiumShadowMap0;
uniform sampler2DShadow u_LuxiumShadowMap1;
uniform sampler2D u_LuxiumEntityShadowMap;

uniform int u_LuxiumNeoSkyInlineActive;
uniform int u_LuxiumSkyShadowEnabled;
uniform int u_LuxiumSkyLightEnabled;
uniform int u_LuxiumEntityShadowEnabled;
uniform int u_LuxiumFilterSamples;

uniform vec4 u_LuxiumCascadeData;
uniform vec4 u_LuxiumBiasData;
uniform vec4 u_LuxiumEntityShadowData;

uniform vec4 u_FogColor;
uniform float u_FogStart;
uniform float u_FogEnd;

layout(location = 0) out vec4 fragColor;

float neo_resolve_sky_visibility() {
    return luxium_resolve_sky_visibility(
            u_LuxiumShadowMap0,
            u_LuxiumShadowMap1,
            v_NeoShadowCoord0,
            v_NeoShadowCoord1,
            v_NeoViewDistance,
            u_LuxiumCascadeData,
            u_LuxiumBiasData,
            u_LuxiumFilterSamples,
            max(v_NeoRawNdotL, 0.0));
}

float neo_resolve_entity_visibility(float rawNdotL) {
    return luxium_resolve_entity_visibility(
            u_LuxiumEntityShadowMap,
            v_NeoEntityShadowCoord,
            u_LuxiumEntityShadowData,
            u_LuxiumFilterSamples,
            max(rawNdotL, 0.0));
}

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);
#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) discard;
#endif

    if (u_LuxiumNeoSkyInlineActive == 0) {
        diffuseColor.rgb *= v_NeoShadowColorAlpha.rgb;
        diffuseColor.a *= v_NeoShadowColorAlpha.a;
#ifdef USE_FOG
        fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#else
        fragColor = diffuseColor;
#endif
        return;
    }

    float rawNdotL = v_NeoRawNdotL;
    bool shadowCanAffectLighting = rawNdotL >= 0.005;
    float skyVisibility = 1.0;

    if (u_LuxiumSkyShadowEnabled != 0 && shadowCanAffectLighting) {
        skyVisibility = neo_resolve_sky_visibility();
    }

    float finalVisibility = skyVisibility;
    if (u_LuxiumEntityShadowEnabled != 0 && shadowCanAffectLighting) {
        finalVisibility = min(finalVisibility, neo_resolve_entity_visibility(rawNdotL));
    }

    float lightingVisibility = u_LuxiumSkyLightEnabled != 0 ? finalVisibility : 1.0;
    diffuseColor.rgb *= v_NeoShadowColorAlpha.rgb + v_NeoLightDelta * lightingVisibility;
    diffuseColor.a *= v_NeoShadowColorAlpha.a;

#ifdef USE_FOG
    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#else
    fragColor = diffuseColor;
#endif
}
