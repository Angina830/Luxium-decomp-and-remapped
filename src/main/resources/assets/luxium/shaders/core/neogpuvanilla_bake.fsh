#version 150

uniform sampler2D VanillaLightVolume;
uniform sampler2D ShadowAtlas;
uniform sampler2D SourceMetadata;
uniform int SourceCount;
uniform vec3 VolumeMin;
uniform vec3 VolumeSize;

in vec2 texCoord;
out vec4 fragColor;

const int VOLUME_X = 48;
const int VOLUME_Z = 48;
const int ATLAS_WIDTH = VOLUME_X * VOLUME_Z;
const float CACHE_MISS = 0.9995;
const float CUBE_UV_SCALE = 0.9656888;

void encodeCube(vec3 d, out int face, out vec2 uv) {
    vec3 ad = abs(d);
    float m = max(max(ad.x, ad.y), ad.z);
    if (m == ad.x) {
        face = d.x > 0.0 ? 0 : 1;
        uv = d.x > 0.0 ? vec2(d.z, d.y) : vec2(-d.z, d.y);
    } else if (m == ad.y) {
        face = d.y > 0.0 ? 2 : 3;
        uv = d.y > 0.0 ? vec2(-d.x, -d.z) : vec2(-d.x, d.z);
    } else {
        face = d.z > 0.0 ? 4 : 5;
        uv = d.z > 0.0 ? vec2(-d.x, d.y) : vec2(d.x, d.y);
    }
    uv = uv / max(m, 0.00001) * (0.5 * CUBE_UV_SCALE) + 0.5;
}

bool clipSegmentAxis(float origin, float delta, float boxMin, float boxMax,
                     inout float tMin, inout float tMax) {
    if (abs(delta) < 0.00001) return origin >= boxMin && origin <= boxMax;
    float invDelta = 1.0 / delta;
    float a = (boxMin - origin) * invDelta;
    float b = (boxMax - origin) * invDelta;
    if (a > b) { float swapValue = a; a = b; b = swapValue; }
    tMin = max(tMin, a);
    tMax = min(tMax, b);
    return tMax >= tMin;
}

bool segmentHitsBox(vec3 origin, vec3 target, vec3 boxMin, vec3 boxMax) {
    vec3 delta = target - origin;
    float tMin = 0.0005;
    float tMax = 0.9990;
    if (!clipSegmentAxis(origin.x, delta.x, boxMin.x, boxMax.x, tMin, tMax)) return false;
    if (!clipSegmentAxis(origin.y, delta.y, boxMin.y, boxMax.y, tMin, tMax)) return false;
    if (!clipSegmentAxis(origin.z, delta.z, boxMin.z, boxMax.z, tMin, tMax)) return false;
    return tMax >= tMin;
}

bool immediateBlocked(vec3 emitter, vec3 sourceCenter, vec3 receiver, int blockedFaceMask) {
    if (blockedFaceMask == 0) return false;
    const vec3 halfBlock = vec3(0.5);
    if ((blockedFaceMask & 1) != 0) {
        vec3 c = sourceCenter + vec3(1.0, 0.0, 0.0);
        if (segmentHitsBox(emitter, receiver, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 2) != 0) {
        vec3 c = sourceCenter + vec3(-1.0, 0.0, 0.0);
        if (segmentHitsBox(emitter, receiver, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 4) != 0) {
        vec3 c = sourceCenter + vec3(0.0, 1.0, 0.0);
        if (segmentHitsBox(emitter, receiver, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 8) != 0) {
        vec3 c = sourceCenter + vec3(0.0, -1.0, 0.0);
        if (segmentHitsBox(emitter, receiver, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 16) != 0) {
        vec3 c = sourceCenter + vec3(0.0, 0.0, 1.0);
        if (segmentHitsBox(emitter, receiver, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 32) != 0) {
        vec3 c = sourceCenter + vec3(0.0, 0.0, -1.0);
        if (segmentHitsBox(emitter, receiver, c - halfBlock, c + halfBlock)) return true;
    }
    return false;
}

int dominantFaceBit(vec3 centerToReceiver) {
    vec3 ad = abs(centerToReceiver);
    float major = max(max(ad.x, ad.y), ad.z);
    if (major == ad.x) return centerToReceiver.x >= 0.0 ? 1 : 2;
    if (major == ad.y) return centerToReceiver.y >= 0.0 ? 4 : 8;
    return centerToReceiver.z >= 0.0 ? 16 : 32;
}

void main() {
    ivec2 atlasPixel = ivec2(gl_FragCoord.xy);
    if (atlasPixel.x < 0 || atlasPixel.x >= ATLAS_WIDTH
            || atlasPixel.y < 0 || atlasPixel.y >= int(VolumeSize.y)) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    int localZ = atlasPixel.x / VOLUME_X;
    int localX = atlasPixel.x - localZ * VOLUME_X;
    int localY = atlasPixel.y;
    float vanilla = texelFetch(VanillaLightVolume, atlasPixel, 0).r * 15.0;
    if (SourceCount <= 0) {
        fragColor = vec4(vanilla / 15.0, 0.0, 0.0, 0.0);
        return;
    }

    vec3 receiver = vec3(localX, localY, localZ) + vec3(0.5);
    float maxPotential = 0.0;
    float maxVisible = 0.0;

    float candidatePotential0 = -1.0;
    float candidatePotential1 = -1.0;
    float candidatePotential2 = -1.0;
    float candidatePotential3 = -1.0;
    int candidate0 = 0;
    int candidate1 = 0;
    int candidate2 = 0;
    int candidate3 = 0;

    for (int i = 0; i < 40; ++i) {
        if (i >= SourceCount) break;
        int metadataBase = i * 9;
        vec4 sourceInfo = texelFetch(SourceMetadata, ivec2(metadataBase, 0), 0);
        vec4 geometryInfo = texelFetch(SourceMetadata, ivec2(metadataBase + 1, 0), 0);
        vec3 source = sourceInfo.xyz;
        vec3 sourceCenter = geometryInfo.xyz;
        float emission = sourceInfo.w;
        int packedFlags = int(geometryInfo.w + 0.5);
        bool capturePending = (packedFlags & 128) != 0;
        int blockedFaceMask = packedFlags & 63;
        bool boxEmitter = (packedFlags & 64) != 0;

        vec3 centerDelta = receiver - sourceCenter;
        float vanillaStepDistance = abs(centerDelta.x) + abs(centerDelta.y) + abs(centerDelta.z);
        float potential = max(0.0, emission - floor(vanillaStepDistance + 0.001));
        if (potential <= 0.0) continue;
        maxPotential = max(maxPotential, potential);

        int encodedCandidate = i + 1;
        if (potential > candidatePotential0) {
            candidatePotential3 = candidatePotential2; candidate3 = candidate2;
            candidatePotential2 = candidatePotential1; candidate2 = candidate1;
            candidatePotential1 = candidatePotential0; candidate1 = candidate0;
            candidatePotential0 = potential; candidate0 = encodedCandidate;
        } else if (potential > candidatePotential1) {
            candidatePotential3 = candidatePotential2; candidate3 = candidate2;
            candidatePotential2 = candidatePotential1; candidate2 = candidate1;
            candidatePotential1 = potential; candidate1 = encodedCandidate;
        } else if (potential > candidatePotential2) {
            candidatePotential3 = candidatePotential2; candidate3 = candidate2;
            candidatePotential2 = potential; candidate2 = encodedCandidate;
        } else if (potential > candidatePotential3) {
            candidatePotential3 = potential; candidate3 = encodedCandidate;
        }

        bool visible = true;
        vec3 delta = receiver - source;
        float distanceToSource = length(delta);
        if (!capturePending && boxEmitter) {
            vec3 ad = abs(centerDelta);
            float major = max(max(ad.x, ad.y), ad.z);
            if (major > 0.5015 && (blockedFaceMask & dominantFaceBit(centerDelta)) != 0) {
                visible = false;
            }
        } else if (!capturePending && immediateBlocked(source, sourceCenter, receiver, blockedFaceMask)) {
            visible = false;
        }

        if (!capturePending && visible && distanceToSource > 0.0001) {
            int face;
            vec2 localUv;
            encodeCube(delta, face, localUv);
            vec4 rect = texelFetch(SourceMetadata, ivec2(metadataBase + 3 + face, 0), 0);
            vec2 atlasUv = rect.xy + clamp(localUv, 0.001, 0.999) * rect.zw;
            float captured = texture(ShadowAtlas, atlasUv).r;
            float radius = 2.4 + 33.6 * clamp((emission - 1.0) / 14.0, 0.0, 1.0);
            float blockerDistance = captured * radius;
            float bias = 0.045 + distanceToSource * 0.0025;
            visible = captured >= CACHE_MISS || distanceToSource <= blockerDistance + bias;
        }
        if (visible) maxVisible = max(maxVisible, potential);
    }

    float clipped = vanilla;
    if (maxPotential + 0.25 >= vanilla) clipped = min(vanilla, maxVisible);

    int packedCandidates = (candidate0 & 63)
            | ((candidate1 & 63) << 6)
            | ((candidate2 & 63) << 12)
            | ((candidate3 & 63) << 18);
    int byte0 = packedCandidates & 255;
    int byte1 = (packedCandidates >> 8) & 255;
    int byte2 = (packedCandidates >> 16) & 255;

    fragColor = vec4(
            clamp(clipped / 15.0, 0.0, 1.0),
            float(byte0) / 255.0,
            float(byte1) / 255.0,
            float(byte2) / 255.0);
}
