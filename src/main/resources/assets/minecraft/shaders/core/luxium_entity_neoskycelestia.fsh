#version 150
#ifdef GL_ARB_texture_gather
#extension GL_ARB_texture_gather : enable
#define LUXIUM_TEXTURE_GATHER
#endif
#moj_import <fog.glsl>
#moj_import <luxium_neoskycelestia_shadow_fragment.glsl>

uniform sampler2D Sampler0;
uniform sampler2DShadow LuxiumShadowMap0;
uniform sampler2DShadow LuxiumShadowMap1;

uniform sampler2D LuxiumEntityShadowMap;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec4 LuxiumCascadeData;
uniform vec4 LuxiumBiasData;
uniform vec4 LuxiumEntityShadowData;
uniform int LuxiumSkyShadowEnabled;
uniform int LuxiumSkyLightEnabled;
uniform int LuxiumEntityShadowEnabled;
uniform int LuxiumEntityShadowCasterPass;
uniform int LuxiumFilterSamples;
uniform float LuxiumAlphaCutoff;
uniform vec3 LuxiumLightDirection;
uniform vec3 LuxiumDirectColor;
uniform vec3 LuxiumSkyAmbientColor;
uniform vec3 LuxiumGroundAmbientColor;
uniform float LuxiumDirectStrength;
uniform float LuxiumAmbientStrength;
uniform float LuxiumVanillaBlockLightInSunShadows;
uniform int BtActive;
uniform int BtCount;
uniform vec4 BtEmitters[12];
uniform vec4 BtColors[12];
in float vertexDistance;
in float luxiumViewDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 skyOnlyLightMapColor;
in vec4 blockOnlyLightMapColor;
in vec4 zeroBlockOnlyLightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 luxiumShadowCoord0;
in vec3 luxiumShadowCoord1;
in vec3 luxiumEntityShadowCoord;
in vec3 luxiumNormal;
in vec3 btPosition;
in float btLevel;
out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (LuxiumAlphaCutoff >= 0.0 && color.a < LuxiumAlphaCutoff) discard;
    if (LuxiumEntityShadowCasterPass != 0) {
        fragColor = vec4(1.0);
        return;
    }
    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    vec4 skyBase = BtActive != 0 ? skyOnlyLightMapColor : lightMapColor;
    vec4 isolatedBlock = BtActive != 0 ? zeroBlockOnlyLightMapColor : blockOnlyLightMapColor;
    vec4 light = skyBase;
    if (LuxiumSkyShadowEnabled != 0 || LuxiumSkyLightEnabled != 0) {
        vec3 normal = normalize(luxiumNormal);
        float rawNdotL = dot(normal, LuxiumLightDirection);
        float nDotL = max(rawNdotL, 0.0);
        float visibility = 1.0;
        if (LuxiumSkyShadowEnabled != 0 && rawNdotL > -0.16) {
            visibility = luxium_visibility(
                    LuxiumShadowMap0, LuxiumShadowMap1,
                    luxiumShadowCoord0, luxiumShadowCoord1,
                    luxiumViewDistance, LuxiumCascadeData,
                    LuxiumBiasData, LuxiumFilterSamples, nDotL);
            if (LuxiumEntityShadowEnabled != 0) {
                visibility = min(visibility, luxium_entity_visibility(
                        LuxiumEntityShadowMap, luxiumEntityShadowCoord,
                        LuxiumEntityShadowData, LuxiumFilterSamples, nDotL));
            }
        }
        light = luxium_resolve_celestial_light(
                skyBase, isolatedBlock, zeroBlockOnlyLightMapColor, skyOnlyLightMapColor, normal, rawNdotL,
                LuxiumDirectColor, LuxiumSkyAmbientColor,
                LuxiumGroundAmbientColor, LuxiumDirectStrength,
                LuxiumAmbientStrength, LuxiumVanillaBlockLightInSunShadows, visibility,
                LuxiumSkyShadowEnabled != 0, LuxiumSkyLightEnabled != 0);
    }
    if (BtActive != 0) {
        float strength = clamp(btLevel, 0.0, 1.0);
        float carried = 0.0;
        vec3 blended = vec3(0.0);
        float weightSum = 0.0;
        float orientation = 0.0;
        for (int i = 0; i < 12; ++i) {
            if (i >= BtCount) break;
            vec3 direction = BtEmitters[i].xyz - btPosition;
            float distanceSquared = dot(direction, direction);
            if (BtEmitters[i].w < 0.0)
                carried = max(carried, -BtEmitters[i].w * max(0.0, 1.0 - distanceSquared / 100.0));
            float footprint = max(0.0, 1.0 - distanceSquared / 225.0);
            float weight = footprint * footprint * abs(BtEmitters[i].w);
            blended += BtColors[i].rgb * weight;
            orientation += (0.78 + 0.22 * max(dot(normalize(luxiumNormal),
                    direction * inversesqrt(max(distanceSquared, 0.01))), 0.0)) * weight;
            weightSum += weight;
        }
        strength = max(strength, carried);
        strength = strength * strength * (2.4 - 1.4 * strength);
        vec3 tint = weightSum > 0.001 ? blended / weightSum : vec3(1.0, 0.76, 0.48);
        light.rgb += tint * strength * (weightSum > 0.001 ? orientation / weightSum : 0.88);
    }
    color.rgb *= light.rgb;
    color.a *= light.a;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
