#ifndef LUXIUM_WATER_SURFACE_GLSL
#define LUXIUM_WATER_SURFACE_GLSL

uniform sampler2D u_LuxiumWaterSceneColor;
uniform sampler2D u_LuxiumWaterSceneDepth;
uniform sampler2D u_LuxiumWaterResolvedColor;
uniform sampler2D u_LuxiumWaterMicroNormal;
uniform sampler2D u_LuxiumSsrHitTexture;
uniform mat4 u_ProjectionMatrix;
uniform mat4 u_ModelViewMatrix;
uniform vec4 u_LuxiumWaterFrame;
uniform vec4 u_LuxiumWaterWave;
uniform vec4 u_LuxiumWaterOptics;
uniform vec4 u_LuxiumWaterSurface;
uniform vec3 u_LuxiumWaterCameraPos;
uniform vec3 u_LuxiumWaterLightDir;
uniform vec3 u_LuxiumWaterLightColor;
uniform sampler2DShadow u_LuxiumWaterShadowMap0;
uniform sampler2DShadow u_LuxiumWaterShadowMap1;
uniform mat4 u_LuxiumWaterLightFromView0;
uniform mat4 u_LuxiumWaterLightFromView1;
uniform vec4 u_LuxiumWaterCascadeData;
uniform vec4 u_LuxiumWaterBiasData;
uniform int u_LuxiumWaterSkyShadowEnabled;
uniform int u_LuxiumWaterFilterSamples;
uniform int u_LuxiumWaterDepthAware;
uniform int u_LuxiumWaterRenderMode;
uniform int u_LuxiumWaterMicroReady;

uniform int u_LuxiumSsrEnabled;
uniform vec4 u_LuxiumSsrTrace;
uniform vec4 u_LuxiumSsrResolve;

bool luxium_is_water_material(uint materialParams) {

    return (materialParams & 7u) == 7u;
}

vec4 luxium_resolve_scaled_water() {
    vec2 frameSize = max(u_LuxiumWaterFrame.xy, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / frameSize;
    vec2 texel = 1.0 / max(vec2(textureSize(u_LuxiumWaterResolvedColor, 0)), vec2(1.0));
    uv = clamp(uv, texel * 0.5, vec2(1.0) - texel * 0.5);
    vec4 water = texture(u_LuxiumWaterResolvedColor, uv);

    if (water.a > 0.02) water.rgb /= water.a;
    water.a = 1.0;
    return water;
}

vec4 luxium_resolve_late_water_ssr() {
    vec2 frameSize = max(u_LuxiumWaterFrame.xy, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / frameSize;
    vec2 texel = 1.0 / max(vec2(textureSize(u_LuxiumWaterResolvedColor, 0)), vec2(1.0));
    uv = clamp(uv, texel * 0.5, vec2(1.0) - texel * 0.5);
    vec4 reflection = texture(u_LuxiumWaterResolvedColor, uv);
    if (reflection.a <= 0.001) return vec4(0.0);
    reflection.rgb /= max(reflection.a, 1.0e-4);
    reflection.a = clamp(reflection.a, 0.0, 0.94);
    return reflection;
}

float luxium_water_linear_depth(float depth) {

    float zNdc = depth * 2.0 - 1.0;
    float denom = zNdc + u_ProjectionMatrix[2][2];
    if (abs(denom) < 1.0e-5) return 1.0e6;
    return abs(u_ProjectionMatrix[3][2] / denom);
}

float luxium_water_fresnel_curve(float x, float power) {
    x = clamp(x, 0.0, 1.0);
    power = clamp(power, 1.0, 10.0);
    float x2 = x * x;
    float x4 = x2 * x2;
    float x8 = x4 * x4;
    if (power <= 2.0) return mix(x, x2, power - 1.0);
    if (power <= 4.0) return mix(x2, x4, (power - 2.0) * 0.5);
    if (power <= 8.0) return mix(x4, x8, (power - 4.0) * 0.25);
    float x10 = x8 * x2;

    return mix(x8, x10, (power - 8.0) * 0.5);
}

float luxium_water_fast_exp_neg(float x) {
    float b = max(1.0 - min(max(x, 0.0), 15.95) * 0.0625, 0.0);
    b *= b; b *= b; b *= b; b *= b;
    return b;
}

vec3 luxium_water_fast_exp_neg(vec3 x) {
    vec3 b = max(vec3(1.0) - min(max(x, vec3(0.0)), vec3(15.95)) * 0.0625, vec3(0.0));
    b *= b; b *= b; b *= b; b *= b;
    return b;
}

vec2 luxium_water_decode_slope(vec2 packedSlope) {
    return packedSlope * 2.0 - 1.0;
}

vec3 luxium_water_micro_normal(vec3 worldPos, vec3 geometricNormal, vec3 dpdx, vec3 dpdy) {
    if (u_LuxiumWaterWave.x <= 0.0001) return geometricNormal;

    float topness = smoothstep(0.18, 0.82, abs(geometricNormal.y));
    if (topness <= 0.001) return geometricNormal;

    float scale = max(u_LuxiumWaterWave.y, 0.001);
    float time = u_LuxiumWaterFrame.z * u_LuxiumWaterWave.z;
    float uvScale = 0.032 * scale;
    vec2 baseUv = worldPos.xz * uvScale;

    float footprint = max(length(dpdx.xz * uvScale), length(dpdy.xz * uvScale));
    float mediumVisible = 1.0 - smoothstep(0.022, 0.105, footprint);
    float detailVisible = 1.0 - smoothstep(0.010, 0.052, footprint);

    vec2 gradient = vec2(0.0);
    if (u_LuxiumWaterMicroReady != 0) {

        if (mediumVisible > 0.001) {
            vec2 driftA = vec2(0.0125, -0.0085) * time;
            vec4 packedA = texture(u_LuxiumWaterMicroNormal, baseUv + driftA);
            vec2 rippleLarge = luxium_water_decode_slope(packedA.rg);
            vec2 rippleMedium = luxium_water_decode_slope(packedA.ba);

            const mat2 rot = mat2(0.8121, -0.5835, 0.5835, 0.8121);
            vec2 transformedUv = rot * baseUv * 2.31 + vec2(-0.010, 0.014) * time + vec2(0.37, 0.11);

            gradient += rippleLarge * (0.52 * mediumVisible);
            gradient += rippleMedium * (0.285 * mediumVisible);

            float detailStrength = clamp(u_LuxiumWaterWave.w, 0.0, 1.0);
            if (detailStrength > 0.0001 && detailVisible > 0.001) {
                vec4 packedB = texture(u_LuxiumWaterMicroNormal, transformedUv);
                vec2 rippleSmall = luxium_water_decode_slope(packedB.rg);
                gradient += rippleSmall * (0.235 * detailStrength * detailVisible);
            }
        }
    } else {

        const vec2 d0 = vec2(0.9138, 0.4061);
        const vec2 d1 = vec2(-0.5260, 0.8505);
        gradient += d0 * cos(dot(worldPos.xz, d0) * (0.82 * scale) + time * 0.72) * 0.21;
        gradient += d1 * cos(dot(worldPos.xz, d1) * (1.37 * scale) - time * 0.57) * 0.14;
    }

    float broadScale = 0.090 * sqrt(scale);
    const vec2 b0 = vec2(0.9659, 0.2588);
    const vec2 b1 = vec2(-0.3584, 0.9336);
    float broad0 = cos(dot(worldPos.xz, b0) * broadScale + time * 0.18);
    float broad1 = cos(dot(worldPos.xz, b1) * (broadScale * 1.47) - time * 0.13 + 1.7);
    gradient += b0 * broad0 * 0.042 + b1 * broad1 * 0.028;

    gradient *= 0.48 * u_LuxiumWaterWave.x;

    float upSign = geometricNormal.y >= 0.0 ? 1.0 : -1.0;
    vec3 rippleNormal = normalize(vec3(-gradient.x, upSign, -gradient.y));
    return normalize(mix(geometricNormal, rippleNormal, topness));
}

float luxium_water_pow5(float x) {
    float x2 = x * x;
    return x2 * x2 * x;
}

float luxium_water_celestial_visibility(vec3 viewPos, vec3 geometricNormal, vec3 lightDir) {
    if (u_LuxiumWaterSkyShadowEnabled == 0) return 1.0;

    vec4 viewPosition = vec4(viewPos, 1.0);
    vec3 nearCoord = (u_LuxiumWaterLightFromView0 * viewPosition).xyz;
    vec3 farCoord = (u_LuxiumWaterLightFromView1 * viewPosition).xyz;
    float viewDistance = length(viewPos);
    float nDotL = max(dot(geometricNormal, lightDir), 0.0);

    return luxium_resolve_sky_visibility(
            u_LuxiumWaterShadowMap0,
            u_LuxiumWaterShadowMap1,
            nearCoord,
            farCoord,
            viewDistance,
            u_LuxiumWaterCascadeData,
            u_LuxiumWaterBiasData,
            u_LuxiumWaterFilterSamples,
            nDotL);
}

float luxium_water_celestial_glint(vec3 normal, vec3 viewDir, vec3 lightDir,
        float sharpness, float surfaceDepth) {
    float nDotV = max(dot(normal, viewDir), 0.001);
    float nDotL = max(dot(normal, lightDir), 0.0);
    if (nDotL <= 0.0001) return 0.0;

    vec3 halfDir = normalize(viewDir + lightDir);
    float nDotH = max(dot(normal, halfDir), 0.0);
    float vDotH = max(dot(viewDir, halfDir), 0.0);

    float baseRoughness = sqrt(2.0 / (max(sharpness, 1.0) + 2.0));
    float pixelRoughness = min(surfaceDepth * 0.00055, 0.085);
    float roughness = clamp(baseRoughness * 1.18 + pixelRoughness, 0.045, 0.34);
    float alpha = roughness * roughness;
    float alpha2 = alpha * alpha;

    float denom = nDotH * nDotH * (alpha2 - 1.0) + 1.0;
    float distribution = alpha2 / max(3.14159265 * denom * denom, 1.0e-5);

    float k = (roughness + 1.0);
    k = k * k * 0.125;
    float gV = nDotV / max(nDotV * (1.0 - k) + k, 1.0e-4);
    float gL = nDotL / max(nDotL * (1.0 - k) + k, 1.0e-4);

    float fresnel = 0.0204 + (1.0 - 0.0204) * luxium_water_pow5(1.0 - vDotH);
    float specular = distribution * gV * gL * fresnel
            / max(4.0 * nDotV * nDotL, 0.02);

    return (specular / (1.0 + specular * 0.34)) * 1.65;
}

vec4 luxium_shade_water(vec3 renderPos, vec3 viewPos, vec4 vertexColor, out vec4 ssrHitRecord) {

    ssrHitRecord = vec4(0.0, 0.0, 0.0, 1.0);
    vec3 worldPos = renderPos + u_LuxiumWaterCameraPos;
    bool sceneReady = u_LuxiumWaterFrame.w > 0.5;

    vec2 passSize = max(u_LuxiumWaterFrame.xy, vec2(1.0));
    vec2 passPixel = 1.0 / passSize;
    vec2 uv = gl_FragCoord.xy * passPixel;

    vec3 dpdx = dFdx(renderPos);
    vec3 dpdy = dFdy(renderPos);

    float baseSceneDepthRaw = 1.0;
    if (sceneReady) {
        baseSceneDepthRaw = texture(u_LuxiumWaterSceneDepth, uv).r;

        if (baseSceneDepthRaw + 1.0e-6 < gl_FragCoord.z) discard;
    }

    vec3 geometricNormal = normalize(cross(dpdx, dpdy));
    if (!gl_FrontFacing) geometricNormal = -geometricNormal;
    vec3 normal = luxium_water_micro_normal(worldPos, geometricNormal, dpdx, dpdy);

    vec2 scenePixel = sceneReady
            ? 1.0 / max(vec2(textureSize(u_LuxiumWaterSceneColor, 0)), vec2(1.0))
            : passPixel;

    float surfaceDepth = max(abs(viewPos.z), 0.01);
    float distanceFade = 1.0 / (1.0 + surfaceDepth * 0.018);
    float topness = smoothstep(0.10, 0.85, abs(geometricNormal.y));

    vec3 perturbation = normal - geometricNormal;
    float invDx = inversesqrt(max(dot(dpdx, dpdx), 1.0e-8));
    float invDy = inversesqrt(max(dot(dpdy, dpdy), 1.0e-8));
    vec2 projectedSlope = vec2(dot(perturbation, dpdx) * invDx,
                               dot(perturbation, dpdy) * invDy);
    vec2 refractOffset = projectedSlope
            * (0.018 * u_LuxiumWaterOptics.x)
            * distanceFade
            * mix(0.20, 1.0, topness);

    vec2 refractedUv = clamp(uv + refractOffset,
            scenePixel * 1.5, vec2(1.0) - scenePixel * 1.5);
    float refractedDepth = 1.0e6;
    if (sceneReady) {
        refractedDepth = luxium_water_linear_depth(texture(u_LuxiumWaterSceneDepth, refractedUv).r);
        if (u_LuxiumWaterDepthAware != 0 && refractedDepth + 0.08 < surfaceDepth) {
            refractedUv = uv;
            refractedDepth = luxium_water_linear_depth(baseSceneDepthRaw);
        }
    }

    float visibilityDepth = max(u_LuxiumWaterOptics.z, 0.5);
    float thickness = clamp(refractedDepth - surfaceDepth, 0.0, visibilityDepth * 2.5);
    float depthOptical = thickness / visibilityDepth * 2.1;
    float depth01 = 1.0 - luxium_water_fast_exp_neg(depthOptical);

    vec3 refractedScene = sceneReady
            ? texture(u_LuxiumWaterSceneColor, refractedUv).rgb
            : vec3(0.18, 0.33, 0.39);

    vec3 biome = clamp(vertexColor.rgb * 1.35 + vec3(0.015, 0.025, 0.03), 0.0, 1.0);
    vec3 shallowColor = mix(vec3(0.055, 0.29, 0.33), biome, 0.26);
    vec3 deepColor = mix(vec3(0.012, 0.075, 0.13), biome * vec3(0.28, 0.48, 0.58), 0.18);
    vec3 scatterColor = mix(shallowColor, deepColor, depth01);

    vec3 sigma = vec3(0.34, 0.115, 0.052) * max(u_LuxiumWaterOptics.y, 0.0);
    vec3 transmittance = luxium_water_fast_exp_neg(sigma * thickness);
    vec3 color = refractedScene * transmittance
               + scatterColor * (vec3(1.0) - transmittance);

    vec3 viewDirWorld = normalize(-renderPos);
    float nDotV = clamp(dot(normal, viewDirWorld), 0.0, 1.0);

    if (u_LuxiumSsrEnabled != 0 && sceneReady) {
        float physicalFresnel = 0.0204 + (1.0 - 0.0204)
                * luxium_water_pow5(1.0 - nDotV);
        float artisticFresnel = 0.76 + clamp(u_LuxiumWaterSurface.x, 0.0, 1.5) * 3.0;
        float reflectionPotential = u_LuxiumSsrResolve.x * physicalFresnel * artisticFresnel;

        if (reflectionPotential > 0.028) {
            vec3 normalView = normalize(mat3(u_ModelViewMatrix) * normal);
            vec3 incidentView = normalize(viewPos);
            if (dot(normalView, -incidentView) < 0.0) normalView = -normalView;
            vec3 reflectedView = reflect(incidentView, normalView);

            LuxiumSsrHit ssr = luxium_ssr_trace_hit(
                    u_LuxiumWaterSceneDepth,
                    u_ProjectionMatrix,
                    viewPos + normalView * 0.025,
                    reflectedView,
                    int(u_LuxiumSsrTrace.x + 0.5),
                    int(u_LuxiumSsrTrace.y + 0.5),
                    u_LuxiumSsrTrace.z,
                    u_LuxiumSsrTrace.w,
                    u_LuxiumSsrResolve.y,
                    gl_FragCoord.xy);

            float reflectionWeight = clamp(ssr.confidence * reflectionPotential, 0.0, 0.94);
            if (reflectionWeight > 0.001) {

                ssrHitRecord = vec4(ssr.uv, reflectionWeight, 1.0);
            }
        }
    }

    float tintFresnel = luxium_water_fresnel_curve(1.0 - nDotV, u_LuxiumWaterSurface.y)
                      * u_LuxiumWaterSurface.x;
    float surfaceMix = clamp(u_LuxiumWaterOptics.w + tintFresnel * 0.38, 0.0, 0.82);
    vec3 surfaceTint = mix(shallowColor * 1.10, vec3(0.38, 0.55, 0.62), tintFresnel * 0.45);
    color = mix(color, surfaceTint, surfaceMix);

    vec3 celestialLightDir = normalize(u_LuxiumWaterLightDir);
    float glint = luxium_water_celestial_glint(normal, viewDirWorld,
            celestialLightDir, u_LuxiumWaterSurface.w, surfaceDepth);
    glint *= u_LuxiumWaterSurface.z
            * smoothstep(-0.02, 0.08, u_LuxiumWaterLightDir.y);
    if (glint > 1.0e-5 && u_LuxiumWaterSkyShadowEnabled != 0) {
        glint *= luxium_water_celestial_visibility(
                viewPos, geometricNormal, celestialLightDir);
    }
    color += u_LuxiumWaterLightColor * glint;

    return vec4(max(color, vec3(0.0)), 1.0);
}

vec4 luxium_shade_water(vec3 renderPos, vec3 viewPos, vec4 vertexColor) {
    vec4 ignoredHitRecord;
    return luxium_shade_water(renderPos, viewPos, vertexColor, ignoredHitRecord);
}

vec4 luxium_resolve_late_water_ssr_color() {
    if (u_LuxiumSsrEnabled == 0 || u_LuxiumWaterFrame.w < 0.5) return vec4(0.0);

    vec2 passSize = max(u_LuxiumWaterFrame.xy, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / passSize;
    vec2 hitTexel = 1.0 / max(vec2(textureSize(u_LuxiumSsrHitTexture, 0)), vec2(1.0));
    uv = clamp(uv, hitTexel * 0.5, vec2(1.0) - hitTexel * 0.5);

    vec4 record = texture(u_LuxiumSsrHitTexture, uv);
    float reflectionWeight = clamp(record.b, 0.0, 0.94);
    if (reflectionWeight <= 0.001) return vec4(0.0);

    vec2 hitUv = clamp(record.rg, vec2(0.0), vec2(1.0));
    vec3 reflectedColor = texture(u_LuxiumWaterSceneColor, hitUv).rgb;

    return vec4(max(reflectedColor, vec3(0.0)), reflectionWeight);
}

#endif
