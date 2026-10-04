#ifndef LUXIUM_SCREEN_SPACE_REFLECTIONS_GLSL
#define LUXIUM_SCREEN_SPACE_REFLECTIONS_GLSL

struct LuxiumSsrHit {
    float confidence;
    vec2 uv;
    float distance;
};

float luxium_ssr_hash(vec2 p) {

    float h = fract(dot(p, vec2(0.7548777, 0.5698403)));
    return fract(h * (31.416 + h * 17.903));
}

float luxium_ssr_view_depth(float rawDepth, mat4 projection) {
    float zNdc = rawDepth * 2.0 - 1.0;
    float denom = zNdc + projection[2][2];
    if (abs(denom) < 1.0e-6) return 1.0e6;
    return abs(projection[3][2] / denom);
}

bool luxium_ssr_projected_sample(vec4 clip, out vec2 uv, out float rawDepth) {
    if (clip.w <= 1.0e-5) {
        uv = vec2(-1.0);
        rawDepth = 1.0;
        return false;
    }

    float invW = 1.0 / clip.w;
    vec3 ndc = clip.xyz * invW;
    uv = ndc.xy * 0.5 + 0.5;
    rawDepth = ndc.z * 0.5 + 0.5;
    return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0
            && rawDepth > 0.0 && rawDepth < 1.0;
}

float luxium_ssr_edge_confidence(vec2 uv, float edgeFade) {
    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    return smoothstep(0.0, max(edgeFade, 1.0e-4), edge);
}

LuxiumSsrHit luxium_ssr_trace_hit(
        sampler2D sceneDepth,
        mat4 projection,
        vec3 originView,
        vec3 directionView,
        int coarseSteps,
        int refinementSteps,
        float maxDistance,
        float thickness,
        float edgeFade,
        vec2 pixelCoord) {

    LuxiumSsrHit miss;
    miss.confidence = 0.0;
    miss.uv = vec2(0.0);
    miss.distance = 0.0;

    if (coarseSteps <= 0 || maxDistance <= 0.001) return miss;

    vec3 dir = normalize(directionView);

    if (dir.z > 0.25) return miss;

    float startBias = max(0.07, thickness * 0.45);
    vec3 startPos = originView + dir * startBias;

    float traceDistance = maxDistance;
    if (dir.z > 1.0e-5) {
        float toCameraPlane = (-0.05 - startPos.z) / dir.z;
        traceDistance = min(traceDistance, max(toCameraPlane, 0.0));
    }
    if (traceDistance <= 0.001) return miss;

    vec3 endPos = startPos + dir * traceDistance;
    vec4 startClip = projection * vec4(startPos, 1.0);
    vec4 endClip = projection * vec4(endPos, 1.0);
    if (startClip.w <= 1.0e-5 || endClip.w <= 1.0e-5) return miss;

    float jitter = luxium_ssr_hash(pixelCoord);
    float previousS = 0.0;
    float previousRawDelta = -1.0e-4;
    bool previousHadDepth = false;

    const int LUXIUM_SSR_MAX_COARSE_STEPS = 20;
    for (int i = 0; i < LUXIUM_SSR_MAX_COARSE_STEPS; ++i) {
        if (i >= coarseSteps) break;

        float u = (float(i) + 0.35 + jitter * 0.30) / float(max(coarseSteps, 1));
        u = clamp(u, 0.0, 1.0);
        float s = u * (0.30 + 0.70 * u);

        vec4 clip = mix(startClip, endClip, s);
        vec2 uv;
        float rayRawDepth;
        if (!luxium_ssr_projected_sample(clip, uv, rayRawDepth)) break;

        float sceneRawDepth = texture(sceneDepth, uv).r;
        if (sceneRawDepth >= 0.999999) {
            previousS = s;
            previousRawDelta = -1.0e-4;
            previousHadDepth = false;
            continue;
        }

        float rawDelta = rayRawDepth - sceneRawDepth;
        bool crossed = rawDelta >= 0.0 && (!previousHadDepth || previousRawDelta < 0.0);

        if (crossed) {
            float lo = previousS;
            float hi = s;
            float hitS = s;
            vec2 hitUv = uv;
            float hitSceneRaw = sceneRawDepth;

            const int LUXIUM_SSR_MAX_REFINEMENT_STEPS = 3;
            for (int r = 0; r < LUXIUM_SSR_MAX_REFINEMENT_STEPS; ++r) {
                if (r >= refinementSteps) break;

                float mid = 0.5 * (lo + hi);
                vec4 midClip = mix(startClip, endClip, mid);
                vec2 midUv;
                float midRayRaw;
                if (!luxium_ssr_projected_sample(midClip, midUv, midRayRaw)) break;

                float midSceneRaw = texture(sceneDepth, midUv).r;
                if (midSceneRaw >= 0.999999) {
                    lo = mid;
                    continue;
                }

                if (midRayRaw - midSceneRaw >= 0.0) {
                    hi = mid;
                    hitS = mid;
                    hitUv = midUv;
                    hitSceneRaw = midSceneRaw;
                } else {
                    lo = mid;
                }
            }

            float hitDistance = startBias + hitS * traceDistance;
            float rayViewDepth = abs(startPos.z + dir.z * (hitS * traceDistance));
            float sceneViewDepth = luxium_ssr_view_depth(hitSceneRaw, projection);
            float finalDelta = max(rayViewDepth - sceneViewDepth, 0.0);
            float finalAllowed = thickness * (1.0 + hitDistance * 0.018);

            if (finalDelta <= finalAllowed) {
                float edgeConfidence = luxium_ssr_edge_confidence(hitUv, edgeFade);
                float distanceConfidence = 1.0 - smoothstep(
                        maxDistance * 0.68, maxDistance, hitDistance);
                float depthConfidence = 1.0 - smoothstep(
                        finalAllowed * 0.35, finalAllowed, finalDelta);

                LuxiumSsrHit hit;
                hit.confidence = clamp(edgeConfidence * distanceConfidence
                        * mix(0.55, 1.0, depthConfidence), 0.0, 1.0);
                hit.uv = hitUv;
                hit.distance = hitDistance;
                return hit;
            }
        }

        previousS = s;
        previousRawDelta = rawDelta;
        previousHadDepth = true;
    }

    return miss;
}

#endif
