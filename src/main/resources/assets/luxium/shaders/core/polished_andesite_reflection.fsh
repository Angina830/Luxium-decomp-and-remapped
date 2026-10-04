#version 150

uniform sampler2D Sampler0;
uniform vec2 TextureUvScale;
uniform float ReflectionDistance;

in vec4 vertexColor;
in vec2 vFaceUV;
in vec4 vVirtualClip;
in float vReflectionAlpha;
in vec3 vPlayerRelativePosition;

out vec4 fragColor;

void main() {
    if (abs(vVirtualClip.w) <= 0.0001 || vReflectionAlpha <= 0.01) discard;
    vec2 screenUV = (vVirtualClip.xy / vVirtualClip.w) * 0.5 + 0.5;

    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    vec2 minUv = texel * 0.5;
    vec2 maxUv = max(minUv, TextureUvScale - minUv);
    vec2 baseUv = clamp(screenUV, vec2(0.0), vec2(1.0));
    baseUv = clamp(baseUv * TextureUvScale, minUv, maxUv);

    vec3 accum = vec3(0.0);
    float alphaAccum = 0.0;
    float totalWeight = 0.0;
    float sigma = 3.0;
    float radius = 6.0;

    for (int y = -3; y <= 3; y++) {
        for (int x = -3; x <= 3; x++) {
            vec2 o = vec2(float(x), float(y));
            float dist2 = dot(o, o);
            float w = exp(-dist2 / (2.0 * sigma * sigma));
            vec2 sampleUv = clamp(baseUv + (o * radius) * texel, minUv, maxUv);
            vec4 reflected = texture(Sampler0, sampleUv);
            accum += reflected.rgb * w;
            alphaAccum += reflected.a * w;
            totalWeight += w;
        }
    }

    vec3 blurred = accum / max(totalWeight, 1e-5);
    float fadeStart = ReflectionDistance * 0.75;
    float boundaryFade = 1.0 - smoothstep(fadeStart, ReflectionDistance,
            length(vPlayerRelativePosition));
    float alpha = vReflectionAlpha * boundaryFade;
    if (alpha <= 0.01) discard;
    fragColor = vec4(blurred * vertexColor.rgb, alpha);
}
