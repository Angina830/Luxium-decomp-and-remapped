#version 330 core
#import <sodium:include/fog.glsl>
#import <luxium:embeddium/include/ssr.glsl>
#import <luxium:embeddium/include/sky_shadow.glsl>
#import <luxium:embeddium/include/water_surface.glsl>

in vec4 v_Color;
in vec4 v_LuxiumWaterVertexColor;
in vec2 v_TexCoord;

in float v_MaterialMipBias;
flat in uint v_LuxiumMaterialParams;
in vec3 v_LuxiumWorldPosition;
in vec3 v_LuxiumViewPosition;
#ifdef USE_FRAGMENT_DISCARD
in float v_MaterialAlphaCutoff;
#endif
#ifdef USE_FOG
in float v_FragDistance;
#endif

uniform sampler2D u_BlockTex;
uniform vec4 u_FogColor;
uniform float u_FogStart;
uniform float u_FogEnd;
layout(location = 0) out vec4 fragColor;

layout(location = 1) out vec4 luxiumSsrHitOut;

void main() {
    luxiumSsrHitOut = vec4(0.0);

    bool luxiumWaterMaterial = luxium_is_water_material(v_LuxiumMaterialParams);
    if (u_LuxiumWaterRenderMode < 0) discard;
    if ((u_LuxiumWaterRenderMode == 1 || u_LuxiumWaterRenderMode == 3) && !luxiumWaterMaterial) discard;
    if (luxiumWaterMaterial) {
        if (u_LuxiumWaterRenderMode == 2) {
            fragColor = luxium_resolve_scaled_water();
            return;
        }
        if (u_LuxiumWaterRenderMode == 3) {
            fragColor = luxium_resolve_late_water_ssr_color();
            return;
        }
        if (u_LuxiumWaterRenderMode == 4) {
            fragColor = luxium_resolve_late_water_ssr();
            if (fragColor.a <= 0.001) discard;
            return;
        }
        vec4 water = luxium_shade_water(
                v_LuxiumWorldPosition,
                v_LuxiumViewPosition,
                v_LuxiumWaterVertexColor,
                luxiumSsrHitOut);
#ifdef USE_FOG
        fragColor = _linearFog(water, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#else
        fragColor = water;
#endif
        return;
    }

    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);
#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) discard;
#endif
#ifdef USE_VANILLA_COLOR_FORMAT
    diffuseColor *= v_Color;
#else
    diffuseColor.rgb *= v_Color.rgb;
    diffuseColor.rgb *= v_Color.a;
#endif
#ifdef USE_FOG
    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#else
    fragColor = diffuseColor;
#endif
}
