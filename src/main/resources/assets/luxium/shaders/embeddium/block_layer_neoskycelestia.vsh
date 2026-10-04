#version 330 core
#define LUXIUM_WATER_ENABLED 0

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>
#import <luxium:embeddium/include/plantswave/plants_wave.glsl>
#ifdef LUXIUM_BLOCKLIGHT_TEST
#import <luxium:embeddium/include/blocklighttest.glsl>
#endif

out vec4 v_NeoShadowColorAlpha;
out vec3 v_NeoLightDelta;
out vec2 v_TexCoord;
out float v_MaterialMipBias;
flat out float v_NeoRawNdotL;
out vec3 v_NeoShadowCoord0;
out vec3 v_NeoShadowCoord1;
out vec3 v_NeoEntityShadowCoord;
out float v_NeoViewDistance;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif
#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform vec3 u_RegionOffset;
uniform sampler2D u_LightTex;
uniform sampler2D u_LuxiumCelestialLut;
uniform int u_FogShape;
uniform int u_LuxiumNeoSkyInlineActive;
uniform mat4 u_LuxiumLightFromWorldRelative0;
uniform mat4 u_LuxiumLightFromWorldRelative1;
uniform mat4 u_LuxiumEntityLightFromWorldRelative;

uvec3 neo_get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 neo_get_draw_translation(uint pos) {
    return neo_get_relative_chunk_coord(pos) * vec3(16.0);
}

vec3 neo_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
}

vec4 neo_sample_lut_endpoint(ivec2 lightCoord, uint normalCode, float litSide) {
    vec2 lightUv = clamp(vec2(lightCoord) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    float code = float(min(normalCode, 6u));
    vec2 uv = vec2(lightUv.x * 0.5 + litSide * 0.5, (code + lightUv.y) / 7.0);
    return texture(u_LuxiumCelestialLut, uv);
}

void main() {
    _vert_init();
    vec2 luxiumPlantWaveData = luxium_plant_vertex_data();
    vec3 position = _vert_position + u_RegionOffset + neo_get_draw_translation(_draw_id);
    position = luxium_apply_plant_wave(position, luxiumPlantWaveData.x, luxiumPlantWaveData.y);
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    uint normalCode = (_material_params >> 3u) & 7u;
    ivec2 skyCoord = _vert_tex_light_coord;
#ifdef LUXIUM_BLOCKLIGHT_TEST
    skyCoord.x = 0;
#endif
    vec3 shadowLight;
    vec3 litLight;
    float rawNdotL;
    if (u_LuxiumNeoSkyInlineActive != 0) {
        vec4 shadowSample = neo_sample_lut_endpoint(skyCoord, normalCode, 0.0);
        vec4 litSample = neo_sample_lut_endpoint(skyCoord, normalCode, 1.0);
        shadowLight = shadowSample.rgb;
        litLight = litSample.rgb;
        rawNdotL = litSample.a;
    } else {
        shadowLight = neo_sample_lightmap(u_LightTex, skyCoord);
        litLight = shadowLight;
        rawNdotL = -1.0;
    }
#ifdef LUXIUM_BLOCKLIGHT_TEST
    vec3 localGlow = bt_illumination(clamp(float(_vert_tex_light_coord.x) / 240.0, 0.0, 1.0),
            position, bt_face_normal(normalCode));
    shadowLight += localGlow;
    litLight += localGlow;
#endif

#ifdef USE_VANILLA_COLOR_FORMAT
    vec3 vertexFactor = _vert_color.rgb;
    float alphaFactor = _vert_color.a;
#else
    vec3 vertexFactor = _vert_color.rgb * _vert_color.a;
    float alphaFactor = 1.0;
#endif
    v_NeoShadowColorAlpha = vec4(vertexFactor * shadowLight, alphaFactor);
    v_NeoLightDelta = vertexFactor * (litLight - shadowLight);
    v_NeoRawNdotL = rawNdotL;

    v_NeoShadowCoord0 = (u_LuxiumLightFromWorldRelative0 * vec4(position, 1.0)).xyz;
    v_NeoShadowCoord1 = (u_LuxiumLightFromWorldRelative1 * vec4(position, 1.0)).xyz;
    v_NeoEntityShadowCoord = (u_LuxiumEntityLightFromWorldRelative * vec4(position, 1.0)).xyz;
    v_NeoViewDistance = length(position);

    v_TexCoord = _vert_tex_diffuse_coord;
    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif
}
