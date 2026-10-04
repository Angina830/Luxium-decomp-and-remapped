#version 330 core
#define LUXIUM_WATER_ENABLED 0
#define LUXIUM_ENTITY_SHADOWS_ENABLED 0

#define LUXIUM_NGV_CUTOUT_ENABLED 0

#if defined(USE_FRAGMENT_DISCARD) && LUXIUM_NGV_CUTOUT_ENABLED == 0

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>
#import <luxium:embeddium/include/plantswave/plants_wave.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif

#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;

uniform sampler2D u_LightTex;

vec4 _sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

uvec3 _get_relative_chunk_coord(uint pos) {

    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();
    vec2 luxiumPlantWaveData = luxium_plant_vertex_data();

    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;
    position = luxium_apply_plant_wave(position, luxiumPlantWaveData.x, luxiumPlantWaveData.y);

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = _vert_tex_diffuse_coord;

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}

#else

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>
#import <luxium:embeddium/include/plantswave/plants_wave.glsl>

out vec4 v_VertexColor;
out vec3 v_FullLight;
out vec3 v_SkyOnlyLight;
out vec3 v_BlockOnlyLight;
out vec3 v_ZeroBlockOnlyLight;
out vec2 v_TexCoord;
out vec2 v_LuxiumLightCoord;
out float v_MaterialMipBias;
out vec3 v_LuxiumShadowCoord0;
out vec3 v_LuxiumShadowCoord1;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
out vec3 v_LuxiumEntityShadowCoord;
#endif
out float v_LuxiumViewDistance;
out vec3 v_LuxiumWorldPosition;
flat out uint v_LuxiumNormalCode;
#if LUXIUM_WATER_ENABLED != 0
flat out uint v_LuxiumMaterialParams;
out vec3 v_LuxiumViewPosition;
#endif
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif
#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform sampler2D u_LightTex;
uniform sampler2D u_LuxiumBlockOnlyLightTex;
uniform mat4 u_LuxiumLightFromView0;
uniform mat4 u_LuxiumLightFromView1;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
uniform mat4 u_LuxiumEntityLightFromView;
#endif
uniform int u_LuxiumShadowPass;
uniform int u_LuxiumNeoGpuDepthPrepass;
uniform int u_LuxiumSkyShadowEnabled;
uniform int u_LuxiumSkyLightEnabled;
uniform int u_LuxiumLocalShadowEnabled;
#if LUXIUM_WATER_ENABLED != 0
uniform int u_LuxiumWaterRenderMode;
#endif

vec3 luxium_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
}
uvec3 luxium_get_relative_chunk_coord(uint pos) { return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u); }

vec3 luxium_get_draw_translation(uint pos) { return luxium_get_relative_chunk_coord(pos) * vec3(16.0); }

void main() {
    _vert_init();
    vec2 luxiumPlantWaveData = luxium_plant_vertex_data();
#if LUXIUM_WATER_ENABLED != 0
    bool luxiumWaterMaterial = (_material_params & 7u) == 7u;
    if (u_LuxiumWaterRenderMode == 1 && !luxiumWaterMaterial) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
#endif
    vec3 position = _vert_position + u_RegionOffset + luxium_get_draw_translation(_draw_id);
    position = luxium_apply_plant_wave(position, luxiumPlantWaveData.x, luxiumPlantWaveData.y);
    vec4 viewPosition = u_ModelViewMatrix * vec4(position, 1.0);
    gl_Position = u_ProjectionMatrix * viewPosition;
    if (u_LuxiumShadowPass != 0 || u_LuxiumNeoGpuDepthPrepass != 0) {
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
    v_VertexColor = _vert_color;
    v_TexCoord = _vert_tex_diffuse_coord;
    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
    v_LuxiumLightCoord = vec2(_vert_tex_light_coord);
    v_FullLight = luxium_sample_lightmap(u_LightTex, _vert_tex_light_coord);
    v_SkyOnlyLight = luxium_sample_lightmap(u_LightTex, ivec2(0, _vert_tex_light_coord.y));
    bool skyActive = u_LuxiumSkyShadowEnabled != 0 || u_LuxiumSkyLightEnabled != 0;
    if (skyActive) {
        v_BlockOnlyLight = luxium_sample_lightmap(u_LuxiumBlockOnlyLightTex, _vert_tex_light_coord);
        v_ZeroBlockOnlyLight = luxium_sample_lightmap(u_LuxiumBlockOnlyLightTex, ivec2(0, _vert_tex_light_coord.y));
        v_LuxiumShadowCoord0 = (u_LuxiumLightFromView0 * viewPosition).xyz;
        v_LuxiumShadowCoord1 = (u_LuxiumLightFromView1 * viewPosition).xyz;
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
        v_LuxiumEntityShadowCoord = (u_LuxiumEntityLightFromView * viewPosition).xyz;
#endif
        v_LuxiumViewDistance = length(viewPosition.xyz);
    } else {
        v_BlockOnlyLight = v_FullLight;
        v_ZeroBlockOnlyLight = v_SkyOnlyLight;
        v_LuxiumShadowCoord0 = vec3(0.0); v_LuxiumShadowCoord1 = vec3(0.0);
#if LUXIUM_ENTITY_SHADOWS_ENABLED != 0
        v_LuxiumEntityShadowCoord = vec3(0.0);
#endif
        v_LuxiumViewDistance = 0.0;
    }
    v_LuxiumWorldPosition = position;
    v_LuxiumNormalCode = (_material_params >> 3u) & 7u;
#if LUXIUM_WATER_ENABLED != 0
    v_LuxiumMaterialParams = _material_params;
    v_LuxiumViewPosition = viewPosition.xyz;
#endif
}

#endif
