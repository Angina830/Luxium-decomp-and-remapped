#version 150

uniform sampler2D DepthSampler;
uniform sampler2D CloudOcclusionSampler;
uniform float UseCloudOcclusion;
uniform sampler2DShadow VolumetricShadowMap0;
uniform sampler2DShadow VolumetricShadowMap1;
uniform sampler2D VolumetricEntityShadowMap;
uniform int RenderFogEffect;
uniform int RenderSkyRays;
uniform int RenderSkyVolumetricRays;
uniform int RenderLensFlare;

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

uniform float FlareIntensity;
uniform float StreakIntensity;
uniform float StreakLength;
uniform float StreakWidth;
uniform float ChromaticSpread;
uniform float GhostIntensity;
uniform float GhostSize;
uniform float FlareSpread;

uniform mat4 VolumetricNearLightFromView;
uniform mat4 VolumetricFarLightFromView;
uniform mat4 VolumetricEntityLightFromView;
uniform vec4 VolumetricCascadeData;
uniform float VolumetricCascadeBlendStart;
uniform vec3 VolumetricLightDirectionView;
uniform vec3 VolumetricLightColor;
uniform float VolumetricLightStrength;
uniform float VolumetricIntensity;
uniform float VolumetricDensity;
uniform int VolumetricSamples;
uniform float VolumetricUniformity;
uniform float VolumetricSideVisibility;
uniform float VolumetricHazeSuppression;
uniform int VolumetricEntityOcclusionEnabled;
uniform float VolumetricEntityShadowBias;
uniform float VolumetricMaxDistance;
uniform float VolumetricAnisotropy;

in vec2 texCoord0;
in vec4 rayBase;
flat in vec4 rayDepthDelta;
in vec4 viewRayBase;
flat in vec4 viewRayDepthDelta;
flat in float sunVisibility;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const int RAY_SAMPLES = 40;
const float RAY_DENSITY = 0.95;
const float RAY_WEIGHT = 0.35;
const float RAY_DECAY = 0.96;
const float RAY_EXPOSURE = 0.7;

float cloudTransmission(vec2 uv) {
    if (UseCloudOcclusion < 0.5) return 1.0;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return 1.0;
    return clamp(1.0 - texture(CloudOcclusionSampler, uv).a, 0.0, 1.0);
}
const int VOLUMETRIC_MAX_SAMPLES = 32;
const float VOLUMETRIC_START_DISTANCE = 0.75;

float interleavedGradientNoise(vec2 uv) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(magic.z * fract(dot(uv, magic.xy)));
}

float henyeyGreenstein(float mu, float g) {
    float gg = g * g;
    float denom = max(1.0 + gg - 2.0 * g * mu, 1.0e-4);
    return (1.0 - gg) / (12.5663706 * denom * sqrt(denom));
}

vec4 renderFog(float depth, float depthNdc) {
    vec4 rayHomogeneous = rayBase + rayDepthDelta * depthNdc;
    float homogeneousRayLength = length(rayHomogeneous.xyz);
    float dist = homogeneousRayLength / max(abs(rayHomogeneous.w), 1.0e-6);
    vec3 rayDir = rayHomogeneous.xyz / max(homogeneousRayLength, 1.0e-6);
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
        return vec4(celestialScattering * distanceRamp, 0.0);
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
        return vec4(0.0);
    }

    float dither = (interleavedGradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    fogColor = max(fogColor + vec3(dither), vec3(0.0));

    return vec4(fogColor * opacity, opacity);
}

float sampleVolumetricShadow(sampler2DShadow depthMap, vec3 projected, float bias) {
    if (projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0
            || projected.z <= 0.0 || projected.z >= 1.0) {
        return 0.0;
    }
    return texture(depthMap, vec3(projected.xy, projected.z - bias));
}

float resolveTerrainVolumetricVisibility(
        vec3 nearCoord, vec3 farCoord, float viewDistance,
        float nearRadius, float farRadius, float nearBias, float farBias,
        float blendStart, float farFadeStart) {
    if (viewDistance < blendStart) {
        return sampleVolumetricShadow(VolumetricShadowMap0, nearCoord, nearBias);
    }
    if (viewDistance >= farRadius) {
        return 0.0;
    }
    if (viewDistance >= nearRadius) {
        float farVisibility = sampleVolumetricShadow(VolumetricShadowMap1, farCoord, farBias);
        return farVisibility * (1.0 - smoothstep(farFadeStart, farRadius, viewDistance));
    }

    float nearVisibility = sampleVolumetricShadow(VolumetricShadowMap0, nearCoord, nearBias);
    float farVisibility = sampleVolumetricShadow(VolumetricShadowMap1, farCoord, farBias);
    return mix(nearVisibility, farVisibility, smoothstep(blendStart, nearRadius, viewDistance));
}

float sampleVolumetricEntityShadow(vec3 projected) {
    if (projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0
            || projected.z <= 0.0 || projected.z >= 1.0) {

        return 1.0;
    }
    float shadowDepth = texture(VolumetricEntityShadowMap, projected.xy).r;
    return step(projected.z - VolumetricEntityShadowBias, shadowDepth);
}

vec3 renderSkyVolumetricRays(float depth, float depthNdc) {

    if (VolumetricIntensity <= 0.0001 || VolumetricDensity <= 0.0001
            || VolumetricLightStrength <= 0.0001) {
        return vec3(0.0);
    }

    vec4 sceneHomogeneous = viewRayBase + viewRayDepthDelta * depthNdc;
    float homogeneousRayLength = length(sceneHomogeneous.xyz);
    if (homogeneousRayLength <= 1.0e-5) {
        return vec3(0.0);
    }
    float sceneDistance = homogeneousRayLength / max(abs(sceneHomogeneous.w), 1.0e-6);
    vec3 rayDir = sceneHomogeneous.xyz / homogeneousRayLength;

    float nearRadius = max(VolumetricCascadeData.x, 1.0);
    float farRadius = max(VolumetricCascadeData.y, nearRadius + 1.0);
    float nearBias = max(VolumetricCascadeData.z, 1.0e-6);
    float farBias = max(VolumetricCascadeData.w, 1.0e-6);
    float blendStart = nearRadius * clamp(VolumetricCascadeBlendStart, 0.0, 1.0);
    float farFadeStart = farRadius * 0.95;
    float farCoverage = farRadius * 0.985;

    float marchEnd = min(VolumetricMaxDistance, farCoverage);
    if (depth < 0.99999) {
        marchEnd = min(marchEnd, sceneDistance);
    }
    if (marchEnd <= VOLUMETRIC_START_DISTANCE + 0.001) {
        return vec3(0.0);
    }

    float travel = marchEnd - VOLUMETRIC_START_DISTANCE;

    int sampleCount = clamp(VolumetricSamples, 8, VOLUMETRIC_MAX_SAMPLES);
    float stepLength = travel / float(sampleCount);
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
    float sigma = VolumetricDensity * 0.010;
    float stepTransmittance = exp(-sigma * stepLength);
    float stepScatter = 1.0 - stepTransmittance;
    float scatterWeight = stepScatter;
    float illuminated = 0.0;
    float illuminatedSecondMoment = 0.0;

    float sampleDistance = VOLUMETRIC_START_DISTANCE + jitter * stepLength;
    vec3 firstViewPosition = rayDir * sampleDistance;
    vec3 viewStep = rayDir * stepLength;

    vec3 nearCoord = (VolumetricNearLightFromView * vec4(firstViewPosition, 1.0)).xyz;
    vec3 nearCoordStep = (VolumetricNearLightFromView * vec4(viewStep, 0.0)).xyz;

    bool needsFarCascade = marchEnd > blendStart;
    vec3 farCoord = vec3(0.0);
    vec3 farCoordStep = vec3(0.0);
    if (needsFarCascade) {
        farCoord = (VolumetricFarLightFromView * vec4(firstViewPosition, 1.0)).xyz;
        farCoordStep = (VolumetricFarLightFromView * vec4(viewStep, 0.0)).xyz;
    }

    bool entityOcclusion = VolumetricEntityOcclusionEnabled != 0;
    vec3 entityCoord = vec3(0.0);
    vec3 entityCoordStep = vec3(0.0);
    if (entityOcclusion) {
        entityCoord = (VolumetricEntityLightFromView * vec4(firstViewPosition, 1.0)).xyz;
        entityCoordStep = (VolumetricEntityLightFromView * vec4(viewStep, 0.0)).xyz;
    }

    for (int i = 0; i < VOLUMETRIC_MAX_SAMPLES; i++) {
        if (i >= sampleCount) {
            break;
        }
        float visibility = resolveTerrainVolumetricVisibility(
                nearCoord, farCoord, sampleDistance,
                nearRadius, farRadius, nearBias, farBias, blendStart, farFadeStart);
        if (entityOcclusion && visibility > 0.0) {
            visibility = min(visibility, sampleVolumetricEntityShadow(entityCoord));
        }

        illuminatedSecondMoment += visibility * visibility * scatterWeight;

        illuminated += visibility * scatterWeight;
        scatterWeight *= stepTransmittance;

        nearCoord += nearCoordStep;
        if (needsFarCascade) {
            farCoord += farCoordStep;
        }
        if (entityOcclusion) {
            entityCoord += entityCoordStep;
        }
        sampleDistance += stepLength;
    }

    float transmittance = scatterWeight / max(stepScatter, 1.0e-8);

    float physicalIlluminated = illuminated;
    float accumulatedScatter = max(1.0 - transmittance, 1.0e-8);
    float visibleFraction = clamp(physicalIlluminated / accumulatedScatter, 0.0, 1.0);

    float uniformity = clamp(VolumetricUniformity, 0.0, 1.0);
    if (uniformity > 0.0001) {
        float referenceEnd = min(VolumetricMaxDistance, farCoverage);
        float referenceTravel = max(referenceEnd - VOLUMETRIC_START_DISTANCE, 0.0);
        float referenceScatter = 1.0 - exp(-sigma * referenceTravel);
        float uniformIlluminated = visibleFraction * referenceScatter;
        illuminated = mix(illuminated, uniformIlluminated, uniformity);
    }

    float mu = clamp(dot(rayDir, VolumetricLightDirectionView), -1.0, 1.0);
    float directionalPhase = 0.18 + min(henyeyGreenstein(mu, VolumetricAnisotropy) * 1.60, 2.80);

    float sideVisibility = clamp(VolumetricSideVisibility, 0.0, 1.0);
    float phase = directionalPhase
            + max(1.0 - directionalPhase, 0.0) * sideVisibility;

    float scattering = illuminated * phase;
    float hazeSuppression = clamp(VolumetricHazeSuppression, 0.0, 1.0);
    if (hazeSuppression > 0.0001) {

        float visibilitySecondMoment = illuminatedSecondMoment / accumulatedScatter;
        float visibilityVariance = max(
                visibilitySecondMoment - visibleFraction * visibleFraction, 0.0);
        float rayStructure = smoothstep(0.06, 0.20, visibilityVariance);
        float openIllumination = smoothstep(0.55, 0.95, visibleFraction);
        float broadHazeMask = openIllumination * (1.0 - rayStructure);

        float physicalScattering = physicalIlluminated * directionalPhase;
        float artisticLift = max(scattering - physicalScattering, 0.0);
        float artisticKeep = 1.0 - broadHazeMask * hazeSuppression * 0.92;
        scattering = physicalScattering + artisticLift * artisticKeep;
    }

    float strength = scattering * VolumetricIntensity
            * VolumetricLightStrength * 0.55;
    return VolumetricLightColor * strength;
}

vec3 renderSkyRays(float depth) {
    float flareMask = sunVisibility * smoothstep(0.0, 0.2, ScreenFade);
    if (ScreenFade <= 0.001 || flareMask <= 0.0) {
        return vec3(0.0);
    }

    bool useMoonStyle = UseMoonStyle > 0.5;
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    vec2 diffBase = texCoord0 - SunScreenPos;
    diffBase.x *= aspect;
    float distToSunBase = length(diffBase);
    float discRadius = max(DiscRadius, 0.002);
    float haloRadius = max(HaloRadius, discRadius + 0.01);
    float stepDist = distToSunBase * (1.0 / float(RAY_SAMPLES)) * RAY_DENSITY;
    float dither = interleavedGradientNoise(texCoord0 * ScreenSize);
    float currentDist = distToSunBase + dither * stepDist;

    vec2 sampleCoord = texCoord0;
    vec2 deltaTexCoord = diffBase;
    deltaTexCoord.x /= aspect;
    deltaTexCoord *= (1.0 / float(RAY_SAMPLES)) * RAY_DENSITY;
    sampleCoord += deltaTexCoord * dither;

    vec3 colorAccum = vec3(0.0);
    float illuminationDecay = 1.0;
    float raySourceRadius = max(discRadius, 0.002);
    int startSample = 0;
    if (stepDist > 1.0e-5 && currentDist > raySourceRadius) {
        startSample = clamp(int((currentDist - raySourceRadius) / stepDist), 0, RAY_SAMPLES);
        sampleCoord -= deltaTexCoord * float(startSample);
        currentDist -= stepDist * float(startSample);
        illuminationDecay = pow(RAY_DECAY, float(startSample));
    }

    for (int i = startSample; i < RAY_SAMPLES; i++) {
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
        colorAccum += isSky * sourceMask * illuminationDecay * RAY_WEIGHT;
        illuminationDecay *= RAY_DECAY;
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
    vec3 rays = colorAccum * RAY_EXPOSURE * RayStrength
            * (useMoonStyle
            ? mix(centerMin, 1.0, centerMask)
            : mix(0.15, 1.0, smoothstep(raySourceRadius * 0.20,
                                        raySourceRadius * 1.50, distToSunBase)));
    float baseTransmission = cloudTransmission(texCoord0);
    vec3 outputColor = (rays + vec3(halo + discCore)) * LightColor * flareMask * edgeFade * baseTransmission;
    if (useMoonStyle) outputColor *= 1.15;
    return outputColor;
}

vec3 renderLensFlare(vec2 uv, vec2 sunPos, float aspect, bool useMoonStyle) {
    vec2 centerUV = uv - vec2(0.5);
    centerUV.x *= aspect;
    vec2 centerSun = sunPos - vec2(0.5);
    centerSun.x *= aspect;
    vec2 uvd = centerUV * length(centerUV);
    float chromaStep = 0.05 * ChromaticSpread;
    float invGhostScale = 1.0 / max(GhostSize, 0.001);
    vec2 csSpread = FlareSpread * centerSun * invGhostScale;
    vec3 ghosts = vec3(0.0);

    vec2 base2 = uvd * invGhostScale + 0.80 * csSpread;
    vec2 step2 = chromaStep * csSpread;
    ghosts.r += max(1.0 / (1.0 + 32.0 * dot(base2, base2)), 0.0) * 0.25;
    ghosts.g += max(1.0 / (1.0 + 32.0 * dot(base2 + step2, base2 + step2)), 0.0) * 0.23;
    ghosts.b += max(1.0 / (1.0 + 32.0 * dot(base2 + step2 * 2.0, base2 + step2 * 2.0)), 0.0) * 0.21;

    vec2 uvx4 = mix(centerUV, uvd, -0.5) * invGhostScale;
    vec2 base4 = uvx4 + 0.40 * csSpread;
    vec2 step4 = chromaStep * csSpread;
    ghosts.r += max(0.01 - dot(base4, base4) * 0.46, 0.0) * 6.0;
    ghosts.g += max(0.01 - dot(base4 + step4, base4 + step4) * 0.46, 0.0) * 5.0;
    ghosts.b += max(0.01 - dot(base4 + step4 * 2.0, base4 + step4 * 2.0) * 0.46, 0.0) * 3.0;

    vec2 uvx5 = mix(centerUV, uvd, -0.4) * invGhostScale;
    vec2 base5 = uvx5 + 0.20 * csSpread;
    vec2 step5 = chromaStep * 4.0 * csSpread;
    float d5_1 = dot(base5, base5);
    float d5_2 = dot(base5 + step5, base5 + step5);
    float d5_3 = dot(base5 + step5 * 2.0, base5 + step5 * 2.0);
    ghosts.r += max(0.01 - d5_1 * d5_1 * 0.28, 0.0) * 2.0;
    ghosts.g += max(0.01 - d5_2 * d5_2 * 0.28, 0.0) * 2.0;
    ghosts.b += max(0.01 - d5_3 * d5_3 * 0.28, 0.0) * 2.0;

    vec2 uvx6 = mix(centerUV, uvd, -0.5) * invGhostScale;
    vec2 base6 = uvx6 - 0.30 * csSpread;
    vec2 step6 = chromaStep * 0.5 * csSpread;
    ghosts.r += max(0.01 - sqrt(dot(base6, base6)) * 0.18, 0.0) * 6.0;
    ghosts.g += max(0.01 - sqrt(dot(base6 - step6, base6 - step6)) * 0.18, 0.0) * 3.0;
    ghosts.b += max(0.01 - sqrt(dot(base6 - step6 * 2.0, base6 - step6 * 2.0)) * 0.18, 0.0) * 5.0;
    ghosts *= (useMoonStyle ? vec3(0.5, 0.7, 1.2) : vec3(1.4, 1.2, 1.0)) * GhostIntensity;

    float streak = 0.0;
    vec2 anamorphicUV = centerUV - centerSun;
    float streakY = abs(anamorphicUV.y);
    float streakWidthParam = 0.02 * StreakWidth;
    if (streakY < streakWidthParam) {
        float streakX = abs(anamorphicUV.x);
        float streakLenParam = 1.5 * StreakLength;
        if (streakX < streakLenParam) {
            float anamorphicFlare = (1.0 - smoothstep(0.0, streakWidthParam, streakY))
                    * (1.0 - smoothstep(0.0, streakLenParam, streakX));
            streak = anamorphicFlare * ((useMoonStyle ? 0.1 : 0.4) * StreakIntensity);
        }
    }
    return (ghosts + LightColor * streak) * ((useMoonStyle ? 0.25 : 0.7) * FlareIntensity);
}

vec3 renderLensFlareEffect() {
    float flareMask = sunVisibility * smoothstep(0.0, 0.2, ScreenFade);
    if (ScreenFade <= 0.001 || flareMask <= 0.0) {

        return vec3(0.0);
    }
    bool useMoonStyle = UseMoonStyle > 0.5;
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    vec3 outputColor = renderLensFlare(texCoord0, SunScreenPos, aspect, useMoonStyle) * flareMask;
    if (useMoonStyle) outputColor *= 1.15;
    return outputColor;
}

void main() {

    float depth = texture(DepthSampler, texCoord0).r;
    float depthNdc = depth * 2.0 - 1.0;
    vec4 fogOut = RenderFogEffect != 0 ? renderFog(depth, depthNdc) : vec4(0.0);
    vec3 additive = vec3(0.0);
    if (RenderSkyRays != 0) additive += renderSkyRays(depth);
    if (RenderSkyVolumetricRays != 0) additive += renderSkyVolumetricRays(depth, depthNdc);
    if (RenderLensFlare != 0) additive += renderLensFlareEffect();
    fragColor = vec4(fogOut.rgb + additive, fogOut.a);
}
