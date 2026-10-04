uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D MicroNormalSampler;

uniform mat4 ViewProj;
uniform mat4 InvViewProj;
uniform vec3 CameraPos;
uniform float Time;

uniform float MaxVirtualDepth;
uniform float DepthCurve;
uniform float WetDarkening;
uniform float SurfaceOpacity;
uniform float EdgeSoftness;
uniform float WaveStrength;
uniform float WaveScale;
uniform float WaveSpeed;
uniform float RippleStrength;
uniform float RefractionStrength;

uniform int SsrEnabled;
uniform int SsrSteps;
uniform int SsrRefinementSteps;
uniform float SsrMaxDistance;
uniform float SsrThickness;
uniform float SsrEdgeFade;
uniform float SsrStrength;

float luxiumWetSaturate(float v) {
    return clamp(v, 0.0, 1.0);
}

float luxiumWetHash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 luxiumWetDecodeSlope(vec2 encodedSlope) {
    return encodedSlope * 2.0 - 1.0;
}

vec2 luxiumWetMicroSlope(vec2 xz) {
    float scale = max(WaveScale, 0.001);
    float t = Time * WaveSpeed;
    vec2 uvA = xz * (0.065 * scale) + vec2(0.021, -0.015) * t;
    vec4 sampleA = texture(MicroNormalSampler, uvA);

    mat2 rot = mat2(0.8192, -0.5736, 0.5736, 0.8192);
    vec2 uvB = rot * xz * (0.119 * scale)
            + vec2(-0.013, 0.018) * t
            + vec2(0.31, 0.17);
    vec4 sampleB = texture(MicroNormalSampler, uvB);

    vec2 slope = luxiumWetDecodeSlope(sampleA.rg) * 0.56;
    slope += luxiumWetDecodeSlope(sampleA.ba) * 0.28;
    slope += luxiumWetDecodeSlope(sampleB.rg) * 0.20;
    return slope * WaveStrength;
}

vec2 luxiumWetRainRippleSlope(vec2 xz) {
    if (RippleStrength <= 0.001) return vec2(0.0);

    float density = 1.35;
    vec2 gridPos = xz * density;
    vec2 baseCell = floor(gridPos) - vec2(0.5);
    vec2 slope = vec2(0.0);

    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            vec2 cell = baseCell + vec2(float(x), float(y));
            float seed = luxiumWetHash12(cell);
            vec2 jitter = vec2(
                    luxiumWetHash12(cell + 13.17),
                    luxiumWetHash12(cell + 71.93));
            vec2 center = (cell + jitter) / density;
            vec2 delta = xz - center;
            float dist = length(delta);

            float age = fract(Time * (0.55 + 0.35 * seed) + seed * 7.0);
            float radius = age * 0.48;
            float ringDistance = abs(dist - radius);
            float envelope = exp(-ringDistance * 46.0)
                    * (1.0 - age) * (1.0 - age);
            float carrier = sin((dist - radius) * 86.0);
            vec2 dir = delta / max(dist, 0.015);
            slope += dir * (envelope * carrier);
        }
    }
    return slope * (0.28 * RippleStrength);
}

vec3 luxiumWetReconstructWorld(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 cameraRelative = InvViewProj * clip;
    cameraRelative /= max(abs(cameraRelative.w), 1.0e-6);
    return CameraPos + cameraRelative.xyz;
}

bool luxiumWetProjectWorld(vec3 p, out vec2 uv) {
    vec4 clip = ViewProj * vec4(p - CameraPos, 1.0);
    if (clip.w <= 0.0001) return false;
    vec3 ndc = clip.xyz / clip.w;
    uv = ndc.xy * 0.5 + 0.5;
    return ndc.z >= -1.05 && ndc.z <= 1.05;
}

float luxiumWetEdgeConfidence(vec2 uv) {
    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    return smoothstep(0.0, max(SsrEdgeFade, 0.001), edge);
}

vec4 luxiumWetTraceSsr(vec3 origin, vec3 direction) {
    if (SsrEnabled == 0 || SsrSteps <= 0 || SsrMaxDistance <= 0.01) {
        return vec4(0.0);
    }

    float stepLength = SsrMaxDistance / float(max(SsrSteps, 1));
    float previousT = 0.10;
    float previousGap = -1.0;
    bool previousValid = false;

    for (int i = 0; i < 32; ++i) {
        if (i >= SsrSteps) break;
        float t = 0.10 + stepLength * float(i + 1);
        vec3 sampleWorld = origin + direction * t;
        vec2 uv;
        if (!luxiumWetProjectWorld(sampleWorld, uv)) break;
        if (uv.x <= 0.001 || uv.x >= 0.999 || uv.y <= 0.001 || uv.y >= 0.999) break;

        float sceneDepth = texture(DepthSampler, uv).r;
        if (sceneDepth >= 0.99998) {
            previousT = t;
            previousValid = false;
            continue;
        }

        vec3 sceneWorld = luxiumWetReconstructWorld(uv, sceneDepth);
        float rayRange = length(sampleWorld - CameraPos);
        float sceneRange = length(sceneWorld - CameraPos);
        float gap = rayRange - sceneRange;

        if (previousValid && previousGap < 0.0 && gap >= 0.0) {
            float crossing = luxiumWetSaturate(
                    (-previousGap) / max(gap - previousGap, 1.0e-5));
            float lo = previousT;
            float hi = t;
            float hitT = mix(previousT, t, crossing);
            vec3 hitWorld = origin + direction * hitT;
            vec2 hitUv;
            if (!luxiumWetProjectWorld(hitWorld, hitUv)) break;
            float hitDepth = texture(DepthSampler, hitUv).r;
            if (hitDepth >= 0.99998) {
                previousT = t;
                previousGap = gap;
                previousValid = true;
                continue;
            }
            vec3 hitScene = luxiumWetReconstructWorld(hitUv, hitDepth);
            float hitGap = length(hitWorld - CameraPos) - length(hitScene - CameraPos);

            if (hitGap >= 0.0) hi = hitT; else lo = hitT;
            for (int r = 0; r < 3; ++r) {
                if (r >= SsrRefinementSteps) break;
                float mid = (lo + hi) * 0.5;
                vec3 midWorld = origin + direction * mid;
                vec2 midUv;
                if (!luxiumWetProjectWorld(midWorld, midUv)) break;
                float midDepth = texture(DepthSampler, midUv).r;
                if (midDepth >= 0.99998) {
                    lo = mid;
                    continue;
                }
                vec3 midScene = luxiumWetReconstructWorld(midUv, midDepth);
                float midGap = length(midWorld - CameraPos) - length(midScene - CameraPos);
                hitUv = midUv;
                hitGap = midGap;
                hitT = mid;
                if (midGap >= 0.0) hi = mid; else lo = mid;
            }

            float thicknessConfidence = 1.0 - smoothstep(
                    max(SsrThickness, 0.001),
                    max(SsrThickness, 0.001) * 2.25,
                    abs(hitGap));
            float distanceConfidence = 1.0
                    - luxiumWetSaturate(hitT / max(SsrMaxDistance, 0.001));
            float confidence = luxiumWetEdgeConfidence(hitUv)
                    * thicknessConfidence
                    * mix(0.35, 1.0, distanceConfidence);
            if (confidence > 0.001) {
                return vec4(hitUv, confidence, 1.0);
            }
        }

        previousT = t;
        previousGap = gap;
        previousValid = true;
    }

    return vec4(0.0);
}

float luxiumWetFresnel(vec3 normal, vec3 surfaceToCamera) {
    float nDotV = luxiumWetSaturate(dot(normal, surfaceToCamera));
    return 0.0204 + (1.0 - 0.0204) * pow(1.0 - nDotV, 5.0);
}

vec4 luxiumWetShadeDetailed(
        vec2 screenUv,
        vec3 worldPos,
        float depth01,
        float virtualDepth,
        float wetMask,
        float waterMask,
        out vec3 wetNormal,
        out vec2 rainSlopeOut,
        out float ssrContributionOut,
        out float fresnelOut) {
    vec2 slope = luxiumWetMicroSlope(worldPos.xz);
    vec2 rainSlope = luxiumWetRainRippleSlope(worldPos.xz);
    slope += rainSlope;
    slope *= mix(0.18, 1.0, waterMask);
    vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));

    wetNormal = normal;
    rainSlopeOut = rainSlope;
    ssrContributionOut = 0.0;

    vec2 refractOffset = normal.xz
            * (0.050 * RefractionStrength)
            * virtualDepth
            * waterMask;
    vec2 refractUv = clamp(screenUv + refractOffset, vec2(0.001), vec2(0.999));
    float refractDepth = texture(DepthSampler, refractUv).r;
    if (refractDepth < 0.99998) {
        vec3 refractWorld = luxiumWetReconstructWorld(refractUv, refractDepth);
        float surfaceRange = length(worldPos - CameraPos);
        float refractRange = length(refractWorld - CameraPos);
        if (refractRange + max(0.04, virtualDepth * 1.5) < surfaceRange) {
            refractUv = screenUv;
        }
    }

    vec3 originalScene = texture(SceneSampler, screenUv).rgb;
    vec3 refractedScene = texture(SceneSampler, refractUv).rgb;

    float darkening = luxiumWetSaturate(WetDarkening) * mix(0.45, 1.0, depth01);
    vec3 wetColor = mix(originalScene, refractedScene, waterMask * 0.82);
    wetColor *= 1.0 - darkening * wetMask;
    wetColor *= 1.0 - luxiumWetSaturate(virtualDepth * 6.0) * 0.12;

    vec3 viewToSurface = normalize(worldPos - CameraPos);
    vec3 surfaceToCamera = -viewToSurface;
    float fresnel = luxiumWetFresnel(normal, surfaceToCamera);
    fresnelOut = fresnel;

    vec3 finalColor = wetColor;
    float reflectionPotential = fresnel * SsrStrength * waterMask;
    if (SsrEnabled != 0 && waterMask > 0.02 && reflectionPotential > 0.012) {
        vec3 rayDir = normalize(reflect(viewToSurface, normal));
        vec4 hit = rayDir.y > -0.05
                ? luxiumWetTraceSsr(worldPos + normal * 0.018, rayDir)
                : vec4(0.0);
        if (hit.w > 0.5) {
            vec3 reflected = texture(SceneSampler, hit.xy).rgb;
            float reflectionWeight = luxiumWetSaturate(
                    hit.z * fresnel * SsrStrength * waterMask * 1.35);
            finalColor = mix(finalColor, reflected, reflectionWeight);
            ssrContributionOut = reflectionWeight;
        }
    }

    float ringSheen = luxiumWetSaturate(length(rainSlope) * 0.55);
    float sheen = fresnel * waterMask * (0.035 + 0.055 * depth01);
    sheen += ringSheen * waterMask * 0.018;
    finalColor += vec3(sheen);

    float coverage = wetMask * mix(0.30, luxiumWetSaturate(SurfaceOpacity), waterMask);
    return vec4(finalColor, luxiumWetSaturate(coverage));
}

vec4 luxiumWetShade(
        vec2 screenUv,
        vec3 worldPos,
        float depth01,
        float virtualDepth,
        float wetMask,
        float waterMask) {
    vec3 unusedNormal;
    vec2 unusedRainSlope;
    float unusedSsrContribution;
    float unusedFresnel;
    return luxiumWetShadeDetailed(
            screenUv,
            worldPos,
            depth01,
            virtualDepth,
            wetMask,
            waterMask,
            unusedNormal,
            unusedRainSlope,
            unusedSsrContribution,
            unusedFresnel);
}
