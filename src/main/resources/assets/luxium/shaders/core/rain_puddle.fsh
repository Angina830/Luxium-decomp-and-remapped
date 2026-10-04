#version 150
#moj_import <luxium_wet_surface.glsl>

uniform sampler2D PuddleDepthSampler;
uniform vec2 ScreenSize;

in vec2 puddleUv;
in vec3 worldPos;
out vec4 fragColor;

float samplePuddleDepth(vec2 uv) {
    ivec2 sizeI = textureSize(PuddleDepthSampler, 0);
    vec2 sizeF = vec2(sizeI);
    vec2 p = clamp(uv * sizeF - 0.5, vec2(0.0), sizeF - vec2(1.0));
    ivec2 p0 = ivec2(floor(p));
    vec2 f = fract(p);
    ivec2 p1 = min(p0 + ivec2(1), sizeI - ivec2(1));

    vec4 sampleA = texelFetch(PuddleDepthSampler, ivec2(p0.x, p0.y), 0);
    vec4 sampleB = texelFetch(PuddleDepthSampler, ivec2(p1.x, p0.y), 0);
    vec4 sampleC = texelFetch(PuddleDepthSampler, ivec2(p0.x, p1.y), 0);
    vec4 sampleD = texelFetch(PuddleDepthSampler, ivec2(p1.x, p1.y), 0);
    float a = sampleA.r * sampleA.a;
    float b = sampleB.r * sampleB.a;
    float c = sampleC.r * sampleC.a;
    float d = sampleD.r * sampleD.a;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    float mapDepth = samplePuddleDepth(puddleUv);
    float softness = mix(0.018, 0.105, luxiumWetSaturate(EdgeSoftness));
    float wetMask = smoothstep(0.012, 0.012 + softness, mapDepth);
    float waterMask = smoothstep(
            0.035 + softness * 0.35,
            0.075 + softness * 1.55,
            mapDepth);
    if (wetMask <= 0.002) discard;

    float depth01 = pow(luxiumWetSaturate(mapDepth), max(DepthCurve, 0.05));
    float virtualDepth = depth01 * max(MaxVirtualDepth, 0.0);
    vec2 screenUv = gl_FragCoord.xy / max(ScreenSize, vec2(1.0));
    fragColor = luxiumWetShade(
            screenUv,
            worldPos,
            depth01,
            virtualDepth,
            wetMask,
            waterMask);
}
