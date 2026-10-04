#version 150

uniform sampler2D DepthSampler;
uniform sampler2D CloudOcclusionSampler;
uniform float UseCloudOcclusion;
uniform vec2 SunScreenPos;
uniform vec3 LightColor;
uniform float ScreenFade;
uniform float UseMoonStyle;
uniform vec2 ScreenSize;
uniform float RayStrength;
uniform float HaloStrength;
uniform float DiscStrength;
uniform float HaloRadius;
uniform float DiscRadius;
uniform float CenterSuppression;

in vec2 texCoord0;
flat in float sunVisibility;
out vec4 fragColor;

const int SAMPLES = 40;
const float DENSITY = 0.95;
const float WEIGHT = 0.35;
const float DECAY = 0.96;
const float EXPOSURE = 0.7;

float cloudTransmission(vec2 uv) {
    if (UseCloudOcclusion < 0.5) return 1.0;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return 1.0;
    return clamp(1.0 - texture(CloudOcclusionSampler, uv).a, 0.0, 1.0);
}

float interleavedGradientNoise(vec2 uv) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);

    return fract(magic.z * fract(dot(uv, magic.xy)));
}

void main() {
    float depth = texture(DepthSampler, texCoord0).r;
    float flareMask = sunVisibility * smoothstep(0.0, 0.2, ScreenFade);
    if (ScreenFade <= 0.001 || flareMask <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }

    bool useMoonStyle = UseMoonStyle > 0.5;
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    vec2 diffBase = texCoord0 - SunScreenPos;
    diffBase.x *= aspect;
    float distToSunBase = length(diffBase);
    float discRadius = max(DiscRadius, 0.002);
    float haloRadius = max(HaloRadius, discRadius + 0.01);
    float stepDist = distToSunBase * (1.0 / float(SAMPLES)) * DENSITY;
    float dither = interleavedGradientNoise(texCoord0 * ScreenSize);
    float currentDist = distToSunBase + dither * stepDist;

    vec2 sampleCoord = texCoord0;
    vec2 deltaTexCoord = diffBase;
    deltaTexCoord.x /= aspect;
    deltaTexCoord *= (1.0 / float(SAMPLES)) * DENSITY;
    sampleCoord += deltaTexCoord * dither;

    vec3 colorAccum = vec3(0.0);
    float illuminationDecay = 1.0;
    float raySourceRadius = max(discRadius, 0.002);
    int startSample = 0;
    if (stepDist > 1.0e-5 && currentDist > raySourceRadius) {
        startSample = clamp(int((currentDist - raySourceRadius) / stepDist), 0, SAMPLES);
        sampleCoord -= deltaTexCoord * float(startSample);
        currentDist -= stepDist * float(startSample);
        illuminationDecay = pow(DECAY, float(startSample));
    }

    for (int i = startSample; i < SAMPLES; i++) {
        sampleCoord -= deltaTexCoord;
        currentDist -= stepDist;
        float isSky = step(0.99999, texture(DepthSampler, sampleCoord).r) * cloudTransmission(sampleCoord);
        if (sampleCoord.x < 0.0 || sampleCoord.x > 1.0
                || sampleCoord.y < 0.0 || sampleCoord.y > 1.0) {
            isSky = 1.0;
        }
        float sourceMask = useMoonStyle
                ? 1.0 - smoothstep(discRadius * 0.45, haloRadius, abs(currentDist))
                : 1.0 - smoothstep(raySourceRadius * 0.20, haloRadius, abs(currentDist));
        colorAccum += isSky * sourceMask * illuminationDecay * WEIGHT;
        illuminationDecay *= DECAY;
    }

    float isSkyBase = step(0.99999, depth);
    float haloInnerFade = smoothstep(discRadius * 1.25, discRadius * 4.0, distToSunBase);
    float haloBody = exp(-distToSunBase / haloRadius);
    float halo = useMoonStyle
            ? haloBody * HaloStrength * mix(0.18, 1.0, haloInnerFade) * isSkyBase
            : haloBody * HaloStrength * 1.7 * isSkyBase;
    float discCore = (1.0 - smoothstep(discRadius * 0.35, discRadius, distToSunBase))
            * DiscStrength * isSkyBase;
    vec2 borderMask = smoothstep(vec2(0.0), vec2(0.05), texCoord0)
            * (1.0 - smoothstep(vec2(0.95), vec2(1.0), texCoord0));
    float edgeFade = borderMask.x * borderMask.y;
    float centerMask = smoothstep(discRadius * 0.70, haloRadius * 0.60, distToSunBase);
    float centerMin = clamp(1.0 - 0.94 * CenterSuppression, 0.02, 1.0);
    vec3 rays = colorAccum * EXPOSURE * RayStrength
            * (useMoonStyle
            ? mix(centerMin, 1.0, centerMask)
            : mix(0.15, 1.0, smoothstep(raySourceRadius * 0.20,
                                        raySourceRadius * 1.50, distToSunBase)));
    float baseTransmission = cloudTransmission(texCoord0);
    vec3 outputColor = (rays + vec3(halo + discCore)) * LightColor * flareMask * edgeFade * baseTransmission;
    if (useMoonStyle) outputColor *= 1.15;
    fragColor = vec4(outputColor, 0.0);
}
