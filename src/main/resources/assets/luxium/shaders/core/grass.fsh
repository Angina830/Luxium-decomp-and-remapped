#version 150

#moj_import <fog.glsl>

in float vertexDistance;
in vec4  vertexColor;
in vec2  texCoord0;

in vec2  texCoord2;
in vec4  normal;
in float sheen;

out vec4 fragColor;

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
uniform float     FogStart;
uniform float     FogEnd;
uniform vec4      FogColor;

#define SHEEN_MAX_BONUS  0.11
#define BASE_AO_STRENGTH  0.28
#define BASE_AO_START     0.16
#define BASE_AO_END       0.94

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
    if (texColor.a < 0.1) discard;

    vec4 light = texture(Sampler2, texCoord2);
    light.rgb += sheen * SHEEN_MAX_BONUS;

    vec4 col = texColor * vertexColor * light;
    float baseOcclusion = smoothstep(BASE_AO_START, BASE_AO_END, texCoord0.y);
    baseOcclusion *= baseOcclusion;
    col.rgb *= 1.0 - baseOcclusion * BASE_AO_STRENGTH;

    fragColor = linear_fog(col, vertexDistance, FogStart, FogEnd, FogColor);
}
