#version 150

uniform sampler2D DepthSampler;
uniform int FogEnabled;
uniform int CelestialScatteringEnabled;
uniform vec3 FogSunDir;
uniform float FogDensityAtOrigin;
uniform float FogExtinction;
uniform float FogScatteringBrightness;
uniform float FogMaxBrightness;
uniform float FogNearDensityBoost;
uniform float FogNearBoostRange;
uniform int FogDynamicCelestialColor;
uniform float FogCelestialColorBlend;
uniform vec3 FogCelestialColor;
uniform float FogStartDistance;
uniform float FogNearFade;
uniform float FogMaxOpacity;
uniform float FogSkyTint;
uniform float FogScatteringStrength;
uniform float FogDistanceCurve;
uniform float FogHeightFalloff;
uniform vec3 FogAmbientColor;
uniform vec3 FogSkyColor;
uniform vec3 FogSunColor;
uniform vec3 FogMoonColor;
uniform vec4 FogMieParams;
uniform float FogSunVisibility;
uniform float FogMoonVisibility;

in vec2 texCoord0;
in vec4 rayBase;
flat in vec4 rayDepthDelta;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float interleavedGradientNoise(vec2 uv) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(magic.z * fract(dot(uv, magic.xy)));
}

float henyeyGreenstein(float mu, float g) {
    float gg = g * g;
    float denom = max(1.0 + gg - 2.0 * g * mu, 1.0e-4);
    return (1.0 - gg) / (12.5663706 * denom * sqrt(denom));
}

void main() {
    float depth = texture(DepthSampler, texCoord0).r;
    float depthNdc = depth * 2.0 - 1.0;
    vec4 rayHomogeneous = rayBase + rayDepthDelta * depthNdc;
    vec3 rayVec = rayHomogeneous.xyz / max(abs(rayHomogeneous.w), 1.0e-6);
    float dist = length(rayVec);
    vec3 rayDir = rayVec / max(dist, 1.0e-6);
    bool isSkyPixel = depth > 0.99999;
    if (isSkyPixel) dist = 3000.0;

    float startDistance = min(FogStartDistance, dist);
    float travelDistance = max(dist - startDistance, 0.0);
    float hDir = rayDir.y;
    float densityAtStart = FogDensityAtOrigin * exp(-FogHeightFalloff * hDir * startDistance);
    float opticalDepth;
    if (abs(hDir) > 0.001) {
        float relativeEndDensity = exp(-FogHeightFalloff * hDir * travelDistance);
        opticalDepth = densityAtStart * (1.0 - relativeEndDensity) / (FogHeightFalloff * hDir);
    } else {
        opticalDepth = densityAtStart * travelDistance;
    }
    float densityMul = FogExtinction * FogExtinction;
    float nearBoost = FogNearDensityBoost
            * (1.0 - exp(-travelDistance / max(FogNearBoostRange, 0.01)));
    opticalDepth = clamp(opticalDepth * densityMul + nearBoost, 0.0, 32.0);
    float distanceRamp = smoothstep(0.0, max(FogNearFade, 0.01), travelDistance);
    vec3 celestialScattering = vec3(0.0);

    if (CelestialScatteringEnabled != 0 && travelDistance > 0.0) {
        float scatterDistanceFade = smoothstep(24.0, 150.0, travelDistance);
        if (FogSunVisibility > 0.001) {
            float mu = dot(rayDir, FogSunDir);
            float mie = henyeyGreenstein(mu, FogMieParams.x) * 0.72
                    + henyeyGreenstein(mu, FogMieParams.y) * 0.28;
            celestialScattering += FogSunColor
                    * (min(mie, 3.0) * 0.080 * scatterDistanceFade * FogScatteringStrength);
        }
        if (FogMoonVisibility > 0.001) {
            float moonMu = dot(rayDir, -FogSunDir);
            float moonMie = henyeyGreenstein(moonMu, FogMieParams.z) * 0.78
                    + henyeyGreenstein(moonMu, FogMieParams.w) * 0.22;
            celestialScattering += FogMoonColor
                    * (min(moonMie, 2.5) * 0.060 * scatterDistanceFade * FogScatteringStrength);
        }
    }

    if (FogEnabled == 0) {
        fragColor = vec4(celestialScattering * distanceRamp, 0.0);
        return;
    }

    float skyAmount = smoothstep(-0.20, 0.68, rayDir.y);
    float neutralLuma = dot(FogAmbientColor, LUMA);
    vec3 neutralFog = vec3(neutralLuma);
    vec3 fogColor = mix(
            mix(neutralFog, FogAmbientColor, FogSkyTint),
            mix(neutralFog, FogSkyColor, FogSkyTint), skyAmount);
    if (FogDynamicCelestialColor != 0) {
        float celestialLuma = max(dot(FogCelestialColor, LUMA), 0.001);
        vec3 normalizedCelestial = FogCelestialColor / celestialLuma
                * max(dot(fogColor, LUMA), 0.001);
        fogColor = mix(fogColor, normalizedCelestial, FogCelestialColorBlend);
    }

    float sunFacing = smoothstep(0.05, 0.92, dot(rayDir, FogSunDir)) * FogSunVisibility;
    fogColor *= mix(vec3(1.0), vec3(1.04, 1.00, 0.94), sunFacing * 0.20);
    fogColor += celestialScattering * 0.70;
    fogColor *= FogScatteringBrightness;
    float fogLuminance = max(dot(fogColor, LUMA), 0.001);
    fogColor *= min(1.0, FogMaxBrightness / fogLuminance);

    float opacity = 1.0 - exp(-opticalDepth);
    opacity = pow(clamp(opacity, 0.0, 1.0), max(FogDistanceCurve, 0.01));
    opacity = min(opacity * distanceRamp, FogMaxOpacity);
    if (isSkyPixel) opacity *= 0.72;
    if (opacity <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    float dither = (interleavedGradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    fogColor = max(fogColor + vec3(dither), vec3(0.0));
    fragColor = vec4(fogColor * opacity, opacity);
}
