#version 150
#moj_import <luxium_wet_surface.glsl>

uniform sampler2D TerrainDepthSampler;
uniform vec2 ScreenSize;

uniform vec3 CelestialDirection;
uniform vec3 CelestialColor;
uniform vec3 SkyAmbientColor;
uniform vec3 GroundAmbientColor;
uniform float CelestialStrength;
uniform float AmbientStrength;
uniform float EnvironmentReflectionStrength;
uniform float CelestialSpecularStrength;
uniform float SheenFloor;
uniform float RippleHighlightStrength;

in vec2 texCoord0;
out vec4 fragColor;

bool wetReadWorld(vec2 uv, out vec3 p) {
    if (uv.x <= 0.0005 || uv.x >= 0.9995 || uv.y <= 0.0005 || uv.y >= 0.9995) {
        return false;
    }
    float d = texture(TerrainDepthSampler, uv).r;
    if (d >= 0.99998) return false;
    p = luxiumWetReconstructWorld(uv, d);
    return true;
}

bool wetReconstructNormal(vec2 uv, vec2 texel, vec3 center, out vec3 normal) {
    vec3 leftP, rightP, downP, upP;
    bool hasLeft = wetReadWorld(uv - vec2(texel.x, 0.0), leftP);
    bool hasRight = wetReadWorld(uv + vec2(texel.x, 0.0), rightP);
    bool hasDown = wetReadWorld(uv - vec2(0.0, texel.y), downP);
    bool hasUp = wetReadWorld(uv + vec2(0.0, texel.y), upP);

    if ((!hasLeft && !hasRight) || (!hasDown && !hasUp)) return false;

    vec3 dx;
    if (hasLeft && hasRight) {
        vec3 dl = center - leftP;
        vec3 dr = rightP - center;
        dx = dot(dl, dl) < dot(dr, dr) ? dl : dr;
    } else {
        dx = hasRight ? (rightP - center) : (center - leftP);
    }

    vec3 dy;
    if (hasDown && hasUp) {
        vec3 dd = center - downP;
        vec3 du = upP - center;
        dy = dot(dd, dd) < dot(du, du) ? dd : du;
    } else {
        dy = hasUp ? (upP - center) : (center - downP);
    }

    vec3 n = cross(dx, dy);
    float n2 = dot(n, n);
    if (n2 <= 1.0e-12) return false;
    normal = n * inversesqrt(n2);
    return true;
}

float wetMacroDepth(vec2 xz) {
    float a = sin(xz.x * 0.43 + sin(xz.y * 0.19) * 1.7);
    float b = sin(xz.y * 0.37 - xz.x * 0.11 + 1.9);
    float broad = 0.5 + 0.25 * a + 0.25 * b;
    return mix(0.72, 1.0, luxiumWetSaturate(broad));
}

float wetHybridPow5(float value) {
    float v2 = value * value;
    return v2 * v2 * value;
}

vec3 wetHybridEnvironment(vec3 reflectionDir) {
    float skyAmount = smoothstep(-0.18, 0.82, reflectionDir.y);
    float horizonBase = 1.0 / (1.0 + abs(reflectionDir.y) * 4.6);
    float horizonBand = horizonBase * horizonBase;

    vec3 horizonColor = mix(GroundAmbientColor, SkyAmbientColor, 0.58);
    vec3 environmentColor = mix(horizonColor, SkyAmbientColor, skyAmount);
    environmentColor = mix(environmentColor, horizonColor, horizonBand * 0.18);

    vec3 keyDir = normalize(vec3(
            CelestialDirection.x,
            max(0.24, CelestialDirection.y * 0.42 + 0.32),
            CelestialDirection.z));
    vec3 fillDir = normalize(vec3(-keyDir.x, 0.48, -keyDir.z));
    float keyDot = max(dot(reflectionDir, keyDir), 0.0);
    float key2 = keyDot * keyDot;
    float keyLobe = key2 * (0.35 + keyDot * 0.65);
    float fillDot = max(dot(reflectionDir, fillDir), 0.0);
    float fill2 = fillDot * fillDot;
    float fillLobe = fill2 * fill2;
    float domeStructure = 0.84 + keyLobe * 0.20 + fillLobe * 0.08;

    vec3 celestialGlow = CelestialColor
            * keyLobe
            * max(CelestialStrength, 0.0)
            * 0.14;
    return environmentColor * max(AmbientStrength, 0.0) * domeStructure + celestialGlow;
}

float wetHybridGgxSpecular(vec3 normal, vec3 toCamera, vec3 lightDir, float roughness) {
    float nDotV = max(dot(normal, toCamera), 0.0);
    float nDotL = max(dot(normal, lightDir), 0.0);
    if (nDotV <= 0.0001 || nDotL <= 0.0001) return 0.0;

    vec3 halfVector = normalize(toCamera + lightDir);
    float nDotH = max(dot(normal, halfVector), 0.0);
    float vDotH = max(dot(toCamera, halfVector), 0.0);

    float alpha = max(roughness * roughness, 0.003);
    float alpha2 = alpha * alpha;
    float denom = nDotH * nDotH * (alpha2 - 1.0) + 1.0;
    float distribution = alpha2 / max(3.14159265 * denom * denom, 1.0e-5);

    float k = (roughness + 1.0);
    k = (k * k) * 0.125;
    float visibilityV = nDotV / max(nDotV * (1.0 - k) + k, 1.0e-5);
    float visibilityL = nDotL / max(nDotL * (1.0 - k) + k, 1.0e-5);

    float fresnel = 0.0204 + (1.0 - 0.0204) * wetHybridPow5(1.0 - vDotH);
    return distribution * visibilityV * visibilityL * fresnel * nDotL
            / max(4.0 * nDotV * nDotL, 1.0e-4);
}

vec3 wetApplyHybridLighting(
        vec3 baseColor,
        vec3 worldPos,
        vec3 wetNormal,
        vec2 rainSlope,
        float depth01,
        float waterMask,
        float ssrContribution,
        float fresnel) {
    vec3 toCamera = normalize(CameraPos - worldPos);
    vec3 viewToSurface = -toCamera;
    vec3 reflectionDir = normalize(reflect(viewToSurface, wetNormal));
    float nDotV = luxiumWetSaturate(dot(wetNormal, toCamera));

    vec3 result = baseColor;

    float directViewFloor = max(SheenFloor, 0.0) * mix(0.62, 1.0, nDotV);
    float fallbackFresnel = max(fresnel, directViewFloor);
    float ssrMissing = 1.0 - luxiumWetSaturate(ssrContribution * 1.15);
    float environmentWeight = luxiumWetSaturate(
            fallbackFresnel
            * max(EnvironmentReflectionStrength, 0.0)
            * waterMask
            * ssrMissing);
    environmentWeight = min(environmentWeight, 0.30);

    vec3 environmentColor = wetHybridEnvironment(reflectionDir);
    result = mix(result, environmentColor, environmentWeight);

    vec3 lightDir = normalize(CelestialDirection);
    float roughness = mix(0.19, 0.105, luxiumWetSaturate(depth01));
    roughness += (1.0 - waterMask) * 0.08;
    float directSpecular = wetHybridGgxSpecular(wetNormal, toCamera, lightDir, roughness);
    directSpecular *= max(CelestialStrength, 0.0)
            * max(CelestialSpecularStrength, 0.0)
            * waterMask;
    directSpecular = min(directSpecular, 0.42);
    result += CelestialColor * directSpecular;

    vec3 broadLight = normalize(mix(vec3(0.0, 1.0, 0.0), lightDir, 0.38));
    vec3 broadHalf = normalize(toCamera + broadLight);
    float perturbedResponse = dot(wetNormal, broadHalf);
    float flatResponse = dot(vec3(0.0, 1.0, 0.0), broadHalf);
    float signedRipple = clamp(
            (perturbedResponse - flatResponse)
            * max(RippleHighlightStrength, 0.0)
            * 1.35,
            -0.065,
            0.065);
    float rainEnergy = luxiumWetSaturate(length(rainSlope) * 1.75);
    float rainLift = rainEnergy
            * max(RippleHighlightStrength, 0.0)
            * waterMask
            * 0.018;

    result *= 1.0 + signedRipple * waterMask;
    vec3 rippleTint = mix(SkyAmbientColor, CelestialColor, 0.38);
    result += rippleTint * rainLift;

    return max(result, vec3(0.0));
}

void main() {
    vec2 uv = texCoord0;
    vec3 originalScene = texture(SceneSampler, uv).rgb;

    float terrainDepth = texture(TerrainDepthSampler, uv).r;
    if (terrainDepth >= 0.99998) {
        fragColor = vec4(originalScene, 1.0);
        return;
    }

    float sceneDepth = texture(DepthSampler, uv).r;
    if (sceneDepth >= 0.99998) {
        fragColor = vec4(originalScene, 1.0);
        return;
    }

    vec3 terrainWorld = luxiumWetReconstructWorld(uv, terrainDepth);
    vec3 sceneWorld = luxiumWetReconstructWorld(uv, sceneDepth);
    float terrainRange = length(terrainWorld - CameraPos);
    float sceneRange = length(sceneWorld - CameraPos);

    float visibilityTolerance = max(0.018, terrainRange * 0.00075);
    if (abs(sceneRange - terrainRange) > visibilityTolerance) {
        fragColor = vec4(originalScene, 1.0);
        return;
    }

    vec2 texel = 1.0 / max(ScreenSize, vec2(1.0));
    vec3 geometricNormal;
    if (!wetReconstructNormal(uv, texel, terrainWorld, geometricNormal)) {
        fragColor = vec4(originalScene, 1.0);
        return;
    }
    vec3 toCamera = normalize(CameraPos - terrainWorld);
    if (dot(geometricNormal, toCamera) < 0.0) {
        geometricNormal = -geometricNormal;
    }

    float topStart = mix(0.94, 0.70, luxiumWetSaturate(EdgeSoftness));
    float topMask = smoothstep(topStart, min(0.999, topStart + 0.16), geometricNormal.y);
    if (topMask <= 0.002) {
        fragColor = vec4(originalScene, 1.0);
        return;
    }

    float sourceDepth = wetMacroDepth(terrainWorld.xz);
    float depth01 = pow(luxiumWetSaturate(sourceDepth), max(DepthCurve, 0.05));
    float virtualDepth = depth01 * max(MaxVirtualDepth, 0.0);
    float waterMask = topMask * smoothstep(0.30, 0.58, sourceDepth);

    vec3 wetNormal;
    vec2 rainSlope;
    float ssrContribution;
    float wetFresnel;
    vec4 wet = luxiumWetShadeDetailed(
            uv,
            terrainWorld,
            depth01,
            virtualDepth,
            topMask,
            waterMask,
            wetNormal,
            rainSlope,
            ssrContribution,
            wetFresnel);

    wet.rgb = wetApplyHybridLighting(
            wet.rgb,
            terrainWorld,
            wetNormal,
            rainSlope,
            depth01,
            waterMask,
            ssrContribution,
            wetFresnel);

    fragColor = vec4(mix(originalScene, wet.rgb, wet.a), 1.0);
}
