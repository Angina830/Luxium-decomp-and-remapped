#version 150
#ifdef GL_ARB_texture_gather
#extension GL_ARB_texture_gather : enable
#define LUXIUM_TEXTURE_GATHER
#endif
#moj_import <fog.glsl>
#moj_import <luxium_sky_shadow_fragment.glsl>

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
    vec4 light = lightMapColor;
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
                lightMapColor, blockOnlyLightMapColor, zeroBlockOnlyLightMapColor, skyOnlyLightMapColor, normal, rawNdotL,
                LuxiumDirectColor, LuxiumSkyAmbientColor,
                LuxiumGroundAmbientColor, LuxiumDirectStrength,
                LuxiumAmbientStrength, LuxiumVanillaBlockLightInSunShadows, visibility,
                LuxiumSkyShadowEnabled != 0, LuxiumSkyLightEnabled != 0);
    }
    color.rgb *= light.rgb;
    color.a *= light.a;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
