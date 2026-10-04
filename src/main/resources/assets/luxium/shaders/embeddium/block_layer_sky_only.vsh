#version 330 core
#define LUXIUM_WATER_ENABLED 0
#define LUXIUM_ENTITY_SHADOWS_ENABLED 0

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>
#import <luxium:embeddium/include/plantswave/plants_wave.glsl>

out vec4 v_LuxiumShadowColorAlpha;
out vec3 v_LuxiumLightDelta;
out vec2 v_TexCoord;
out float v_MaterialMipBias;
out vec3 v_LuxiumShadowCoord0;
out vec3 v_LuxiumShadowCoord1;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
out vec3 v_LuxiumEntityShadowCoord;
#endif
out float v_LuxiumViewDistance;
flat out float v_LuxiumRawNdotL;
flat out uint v_LuxiumNormalCode;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif
#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform sampler2D u_LightTex;
uniform sampler2D u_LuxiumCelestialLut;
uniform mat4 u_LuxiumLightFromView0;
uniform mat4 u_LuxiumLightFromView1;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
uniform mat4 u_LuxiumEntityLightFromView;
#endif
uniform int u_LuxiumShadowPass;
uniform int u_LuxiumSkyShadowEnabled;
uniform int u_LuxiumSkyLightEnabled;

vec3 luxium_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
}

vec4 luxium_sample_celestial_lut(ivec2 lightCoord, uint normalCode, float litSide) {
    vec2 lightUv = clamp(
            vec2(lightCoord) / 256.0,
            vec2(0.5 / 16.0),
            vec2(15.5 / 16.0));
    float code = float(min(normalCode, 6u));
    vec2 uv = vec2(
            lightUv.x * 0.5 + litSide * 0.5,
            (code + lightUv.y) / 7.0);
    return texture(u_LuxiumCelestialLut, uv);
}

uvec3 luxium_get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 luxium_get_draw_translation(uint pos) {
    return luxium_get_relative_chunk_coord(pos) * vec3(16.0);
}


void main() {
    _vert_init();
    vec2 luxiumPlantWaveData = luxium_plant_vertex_data();
    vec3 position = _vert_position + u_RegionOffset + luxium_get_draw_translation(_draw_id);
    position = luxium_apply_plant_wave(position, luxiumPlantWaveData.x, luxiumPlantWaveData.y);
    vec4 viewPosition = u_ModelViewMatrix * vec4(position, 1.0);
    gl_Position = u_ProjectionMatrix * viewPosition;

    if (u_LuxiumShadowPass != 0) {
#ifdef USE_FRAGMENT_DISCARD
        v_TexCoord = _vert_tex_diffuse_coord;
        v_MaterialMipBias = _material_mip_bias(_material_params);
        v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
        return;
    }

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif
    v_TexCoord = _vert_tex_diffuse_coord;
    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif

    uint normalCode = (_material_params >> 3u) & 7u;
    bool skyActive = u_LuxiumSkyShadowEnabled != 0 || u_LuxiumSkyLightEnabled != 0;
    vec3 shadowLight;
    vec3 litLight;
    float rawNdotL;
    if (skyActive) {
        vec4 shadowSample = luxium_sample_celestial_lut(_vert_tex_light_coord, normalCode, 0.0);
        vec4 litSample = luxium_sample_celestial_lut(_vert_tex_light_coord, normalCode, 1.0);
        shadowLight = shadowSample.rgb;
        litLight = litSample.rgb;
        rawNdotL = litSample.a;
    } else {
        vec3 fullLight = luxium_sample_lightmap(u_LightTex, _vert_tex_light_coord);
        shadowLight = fullLight;
        litLight = fullLight;
        rawNdotL = -1.0;
    }

#ifdef USE_VANILLA_COLOR_FORMAT
    vec3 vertexFactor = _vert_color.rgb;
    float alphaFactor = _vert_color.a;
#else
    vec3 vertexFactor = _vert_color.rgb * _vert_color.a;
    float alphaFactor = 1.0;
#endif

    v_LuxiumShadowColorAlpha = vec4(vertexFactor * shadowLight, alphaFactor);
    v_LuxiumLightDelta = vertexFactor * (litLight - shadowLight);
    v_LuxiumNormalCode = normalCode;
    v_LuxiumRawNdotL = rawNdotL;

    if (skyActive) {
        v_LuxiumShadowCoord0 = (u_LuxiumLightFromView0 * viewPosition).xyz;
        v_LuxiumShadowCoord1 = (u_LuxiumLightFromView1 * viewPosition).xyz;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
        v_LuxiumEntityShadowCoord = (u_LuxiumEntityLightFromView * viewPosition).xyz;
#endif
        v_LuxiumViewDistance = length(viewPosition.xyz);
    } else {
        v_LuxiumShadowCoord0 = vec3(0.0);
        v_LuxiumShadowCoord1 = vec3(0.0);
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
        v_LuxiumEntityShadowCoord = vec3(0.0);
#endif
        v_LuxiumViewDistance = 0.0;
    }
}
