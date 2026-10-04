uniform int u_BtCount;
uniform vec4 u_BtEmitters[12];
uniform vec4 u_BtColors[12];

vec3 bt_face_normal(uint face) {
    if (face == 1u) return vec3(0.0, -1.0, 0.0);
    if (face == 2u) return vec3(0.0, 1.0, 0.0);
    if (face == 3u) return vec3(0.0, 0.0, -1.0);
    if (face == 4u) return vec3(0.0, 0.0, 1.0);
    if (face == 5u) return vec3(-1.0, 0.0, 0.0);
    if (face == 6u) return vec3(1.0, 0.0, 0.0);
    return vec3(0.0, 1.0, 0.0);
}

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

vec3 bt_illumination(float level, vec3 receiver, vec3 normal) {
    float blockLightGuide = bt_luxium_vanilla_curve(level);

    vec3 vanillaApprox = vec3(1.0, 0.76, 0.48) * blockLightGuide * 0.88;

    vec3 localLight = vec3(0.0);
    float replacementWeight = 0.0;

    for (int i = 0; i < 12; ++i) {
        if (i >= u_BtCount) break;

        vec3 toLight = u_BtEmitters[i].xyz - receiver;
        float distSq = dot(toLight, toLight);
        if (distSq <= 0.00000001) continue;

        float emission = abs(u_BtEmitters[i].w);
        if (emission <= 0.00001) continue;

        float radius = bt_luxium_radius(emission);
        if (distSq > radius * radius) continue;

        float dist = sqrt(distSq);
        float radial;
        float attenuation = bt_luxium_attenuation(dist, radius, radial);
        if (attenuation <= 0.0) continue;

        float diffuse = bt_luxium_diffuse(normal, toLight, dist);

        bool carried = u_BtEmitters[i].w < 0.0;
        float visibility = carried
                ? 1.0
                : bt_luxium_spread_visibility(blockLightGuide, attenuation, radial);

        float sourceIntensity = bt_luxium_source_intensity(emission);
        float weight = attenuation * diffuse * visibility;
        if (weight > 0.0001) {
            localLight += u_BtColors[i].rgb * sourceIntensity * weight;
        }

        if (!carried) {
            replacementWeight = max(replacementWeight, smoothstep(0.0, 0.16, radial));
        }
    }

    return min(mix(vanillaApprox, vec3(0.0), replacementWeight) + localLight, vec3(1.6));
}
