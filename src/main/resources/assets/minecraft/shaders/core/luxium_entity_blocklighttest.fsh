#version 150
#moj_import <fog.glsl>
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float LuxiumAlphaCutoff;
uniform int BtCount;
uniform vec4 BtEmitters[12];
uniform vec4 BtColors[12];
in vec2 btUv;
in vec4 btTint;
in vec4 btOverlay;
in vec3 btSky;
in float btLevel;
in vec3 btPosition;
in vec3 btNormal;
in float btDistance;
out vec4 fragColor;

const float BT_LOCAL_NEAR_REJECT = 0.6;
const float BT_LOCAL_MAX_RADIUS = 36.0;

float bt_luxium_radius(float emission) {
    return max(1.0, BT_LOCAL_MAX_RADIUS * clamp(emission, 0.0, 1.0));
}

float bt_luxium_source_intensity(float emission) {
    return 1.18 * pow(clamp(emission, 0.0, 1.0), 0.78);
}

float bt_luxium_attenuation(float distanceToLight, float radius, out float radial) {
    radial = 1.0 - distanceToLight / max(radius, 0.0001);
    if (radial <= 0.0) return 0.0;
    return max((exp(-3.0 * (1.0 - radial)) - 0.0497871) / 0.9502129, 0.0);
}

float bt_luxium_diffuse(vec3 normal, vec3 toLight, float distanceToLight) {
    if (distanceToLight <= BT_LOCAL_NEAR_REJECT) return 1.0;
    vec3 n = normal * inversesqrt(max(dot(normal, normal), 0.00000001));
    vec3 l = toLight / max(distanceToLight, 0.00001);
    float nDotL = max(dot(n, l), 0.0);
    return clamp((nDotL + 0.16) / 1.16, 0.0, 1.0);
}

float bt_luxium_vanilla_curve(float level) {
    float amount = clamp(level, 0.0, 1.0);
    return amount * amount * (2.4 - 1.4 * amount);
}

float bt_luxium_spread_visibility(float blockLightGuide, float attenuation, float radial) {
    float guideBase = max(attenuation, 0.0);
    float guideCurve = mix(guideBase, sqrt(guideBase), 0.4841);
    float expectedGuide = max(guideCurve * 0.72, 0.04);
    float relativeGuide = clamp(blockLightGuide / expectedGuide, 0.0, 2.0);
    float floodVisibility = smoothstep(0.035, 0.48, relativeGuide);
    floodVisibility *= smoothstep(0.0, 0.08, radial);
    return floodVisibility;
}

vec3 bt_luxium_illumination(float level, vec3 receiver, vec3 normal) {
    float blockLightGuide = bt_luxium_vanilla_curve(level);
    vec3 vanillaApprox = vec3(1.0, 0.76, 0.48) * blockLightGuide * 0.88;
    vec3 localLight = vec3(0.0);
    float replacementWeight = 0.0;

    for (int i = 0; i < 12; ++i) {
        if (i >= BtCount) break;

        vec3 toLight = BtEmitters[i].xyz - receiver;
        float distSq = dot(toLight, toLight);
        if (distSq <= 0.00000001) continue;

        float emission = abs(BtEmitters[i].w);
        if (emission <= 0.00001) continue;

        float radius = bt_luxium_radius(emission);
        if (distSq > radius * radius) continue;

        float dist = sqrt(distSq);
        float radial;
        float attenuation = bt_luxium_attenuation(dist, radius, radial);
        if (attenuation <= 0.0) continue;

        float diffuse = bt_luxium_diffuse(normal, toLight, dist);
        bool carried = BtEmitters[i].w < 0.0;
        float visibility = carried
                ? 1.0
                : bt_luxium_spread_visibility(blockLightGuide, attenuation, radial);

        float sourceIntensity = bt_luxium_source_intensity(emission);
        float weight = attenuation * diffuse * visibility;
        if (weight > 0.0001) {
            localLight += BtColors[i].rgb * sourceIntensity * weight;
        }

        if (!carried) {
            replacementWeight = max(replacementWeight, smoothstep(0.0, 0.16, radial));
        }
    }

    return min(mix(vanillaApprox, vec3(0.0), replacementWeight) + localLight, vec3(1.6));
}

void main() {
    vec4 surface = texture(Sampler0, btUv);
    if (LuxiumAlphaCutoff >= 0.0 && surface.a < LuxiumAlphaCutoff) discard;
    surface *= btTint * ColorModulator;
    surface.rgb = mix(btOverlay.rgb, surface.rgb, btOverlay.a);

    vec3 glow = bt_luxium_illumination(btLevel, btPosition, btNormal);
    surface.rgb *= min(btSky + glow, vec3(1.6));
    fragColor = linear_fog(surface, btDistance, FogStart, FogEnd, FogColor);
}
