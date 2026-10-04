#version 150

uniform sampler2D HistoryColor;
uniform sampler2D MotionSampler;

uniform vec2 SourceTexelSize;
uniform float ReprojectionStrength;
uniform int SyntheticFrame;
uniform int HistoryHasEdgeMetadata;

in vec2 texCoord0;
out vec4 fragColor;

vec2 clampSourceUv(vec2 uv) {
    vec2 inset = max(SourceTexelSize * 0.55, vec2(0.00005));
    return clamp(uv, inset, vec2(1.0) - inset);
}

vec4 nearestHistory(vec2 uv) {
    ivec2 size = textureSize(HistoryColor, 0);
    ivec2 pixel = ivec2(clamp(uv, vec2(0.0), vec2(0.999999)) * vec2(size));
    pixel = clamp(pixel, ivec2(0), size - ivec2(1));
    return texelFetch(HistoryColor, pixel, 0);
}

vec3 fillInvalidEdge(vec2 sourceUv, vec2 safeUv) {
    vec2 inward = vec2(0.0);
    if (sourceUv.x < 0.0) inward.x = 1.0;
    else if (sourceUv.x > 1.0) inward.x = -1.0;
    if (sourceUv.y < 0.0) inward.y = 1.0;
    else if (sourceUv.y > 1.0) inward.y = -1.0;

    if (dot(inward, inward) < 0.5) {
        vec2 centerDir = vec2(0.5) - safeUv;
        float len = length(centerDir);
        inward = len > 1.0e-5 ? centerDir / len : vec2(0.0, 1.0);
    } else {
        inward = normalize(inward);
    }

    vec3 base = nearestHistory(safeUv).rgb;
    vec3 inner2 = texture(HistoryColor,
        clampSourceUv(safeUv + inward * SourceTexelSize * 2.0)).rgb;
    vec3 inner5 = texture(HistoryColor,
        clampSourceUv(safeUv + inward * SourceTexelSize * 5.0)).rgb;
    return base * 0.62 + inner2 * 0.25 + inner5 * 0.13;
}

void main() {
    vec2 sourceUv = texCoord0;
    float valid = 1.0;
    float motionEdge = 0.0;

    if (SyntheticFrame != 0) {
        for (int i = 0; i < 2; ++i) {
            bool inBounds = sourceUv.x >= 0.0 && sourceUv.x <= 1.0
                         && sourceUv.y >= 0.0 && sourceUv.y <= 1.0;
            if (!inBounds) {
                valid = 0.0;
                break;
            }

            vec4 motion = texture(MotionSampler, sourceUv);
            valid *= step(0.5, motion.z);
            motionEdge = max(motionEdge, motion.w);
            sourceUv = texCoord0 - motion.xy * clamp(ReprojectionStrength, 0.0, 1.0);
        }
    }

    bool outside = sourceUv.x < 0.0 || sourceUv.x > 1.0
                || sourceUv.y < 0.0 || sourceUv.y > 1.0;
    if (outside) valid = 0.0;

    vec2 safeUv = clampSourceUv(sourceUv);

    vec4 history = texture(HistoryColor, safeUv);
    float preparedEdge = HistoryHasEdgeMetadata != 0 ? history.a : 0.0;
    float geometryEdge = max(motionEdge, preparedEdge);

    vec3 color;
    if (valid < 0.5 || outside) {
        color = fillInvalidEdge(sourceUv, safeUv);
    } else if (geometryEdge > 0.34) {
        color = nearestHistory(safeUv).rgb;
    } else {
        color = history.rgb;
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
