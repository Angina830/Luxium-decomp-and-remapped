#version 330 core
#import <sodium:include/fog.glsl>
#import <luxium:embeddium/include/blocklighttest.glsl>

in vec4 btColor;
in vec2 btUv;
in vec3 btSky;
in float btLevel;
in vec3 btPosition;
flat in uint btFace;
in float btBias;
#ifdef USE_FRAGMENT_DISCARD
in float btCutoff;
#endif
#ifdef USE_FOG
in float btFog;
#endif
uniform sampler2D u_BlockTex;
uniform vec4 u_FogColor;
uniform float u_FogStart;
uniform float u_FogEnd;
out vec4 fragColor;

vec3 bt_normal(uint face, vec3 position) {
    if (face == 1u) return vec3(0.0, -1.0, 0.0);
    if (face == 2u) return vec3(0.0, 1.0, 0.0);
    if (face == 3u) return vec3(0.0, 0.0, -1.0);
    if (face == 4u) return vec3(0.0, 0.0, 1.0);
    if (face == 5u) return vec3(-1.0, 0.0, 0.0);
    if (face == 6u) return vec3(1.0, 0.0, 0.0);
    return normalize(cross(dFdx(position), dFdy(position)));
}

void main() {
    vec4 surface = texture(u_BlockTex, btUv, btBias);
#ifdef USE_FRAGMENT_DISCARD
    if (surface.a < btCutoff) discard;
#endif
#ifdef USE_VANILLA_COLOR_FORMAT
    surface *= btColor;
#else
    surface.rgb *= btColor.rgb * btColor.a;
#endif
    vec3 light = btSky + bt_illumination(btLevel, btPosition, bt_normal(btFace, btPosition));
    surface.rgb *= min(light, vec3(1.6));
#ifdef USE_FOG
    fragColor = _linearFog(surface, btFog, u_FogColor, u_FogStart, u_FogEnd);
#else
    fragColor = surface;
#endif
}
