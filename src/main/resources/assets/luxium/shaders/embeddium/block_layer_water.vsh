#version 330 core
#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>

out vec4 v_Color;
out vec4 v_LuxiumWaterVertexColor;
out vec2 v_TexCoord;
out float v_MaterialMipBias;
flat out uint v_LuxiumMaterialParams;
out vec3 v_LuxiumWorldPosition;
out vec3 v_LuxiumViewPosition;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif
#ifdef USE_FOG
out float v_FragDistance;
#endif

uniform int u_FogShape;
uniform vec3 u_RegionOffset;
uniform sampler2D u_LightTex;
uniform int u_LuxiumWaterRenderMode;

vec4 luxium_water_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

uvec3 luxium_water_relative_chunk(uint pos) { return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u); }
vec3 luxium_water_draw_translation(uint pos) { return luxium_water_relative_chunk(pos) * vec3(16.0); }

void main() {
    _vert_init();
    bool luxiumWaterMaterial = (_material_params & 7u) == 7u;
    if (u_LuxiumWaterRenderMode < 0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    if ((u_LuxiumWaterRenderMode == 1 || u_LuxiumWaterRenderMode == 3) && !luxiumWaterMaterial) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    vec3 position = _vert_position + u_RegionOffset + luxium_water_draw_translation(_draw_id);
    vec4 viewPosition = u_ModelViewMatrix * vec4(position, 1.0);
    gl_Position = u_ProjectionMatrix * viewPosition;
#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif
    v_LuxiumWaterVertexColor = _vert_color;

    v_Color = (u_LuxiumWaterRenderMode == 3 || u_LuxiumWaterRenderMode == 4)
            ? _vert_color
            : _vert_color * luxium_water_sample_lightmap(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = _vert_tex_diffuse_coord;
    v_MaterialMipBias = _material_mip_bias(_material_params);
    v_LuxiumMaterialParams = _material_params;
    v_LuxiumWorldPosition = position;
    v_LuxiumViewPosition = viewPosition.xyz;
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
