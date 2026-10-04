#version 150

uniform sampler2D CandidateVolume;
uniform sampler2D VanillaLightVolume;
uniform sampler2D ShadowAtlas;
uniform sampler2D SourceMetadata;
uniform int SourceCount;
uniform int FineScale;
uniform int FineSlicesPerRow;

in vec2 texCoord;
out vec4 fragColor;

const int VOLUME_X = 48;
const int VOLUME_Y = 32;
const int VOLUME_Z = 48;
const float CACHE_MISS = 0.9995;
const float CUBE_UV_SCALE = 0.9656888;
const float NEAR_REJECT = 0.58;

int candidateId(vec4 packedCell, int slot) {
    int byte0 = int(floor(packedCell.g * 255.0 + 0.5));
    int byte1 = int(floor(packedCell.b * 255.0 + 0.5));
    int byte2 = int(floor(packedCell.a * 255.0 + 0.5));
    int packedCandidates = byte0 | (byte1 << 8) | (byte2 << 16);
    return (packedCandidates >> (slot * 6)) & 63;
}

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

bool clipAxis(float origin, float delta, float boxMin, float boxMax,
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
    if (!clipAxis(origin.x, delta.x, boxMin.x, boxMax.x, tMin, tMax)) return false;
    if (!clipAxis(origin.y, delta.y, boxMin.y, boxMax.y, tMin, tMax)) return false;
    if (!clipAxis(origin.z, delta.z, boxMin.z, boxMax.z, tMin, tMax)) return false;
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
    int scale = max(FineScale, 1);
    int fineXCount = VOLUME_X * scale;
    int fineYCount = VOLUME_Y * scale;
    int fineZCount = VOLUME_Z * scale;

    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int tileX = pixel.x / fineXCount;
    int tileY = pixel.y / fineYCount;
    int localFineX = pixel.x - tileX * fineXCount;
    int localFineY = pixel.y - tileY * fineYCount;
    int localFineZ = tileY * FineSlicesPerRow + tileX;

    if (localFineX < 0 || localFineY < 0 || localFineZ < 0
            || localFineX >= fineXCount || localFineY >= fineYCount || localFineZ >= fineZCount) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 receiver = (vec3(localFineX, localFineY, localFineZ) + vec3(0.5)) / float(scale);
    ivec3 blockCell = ivec3(floor(receiver));
    blockCell = clamp(blockCell, ivec3(0), ivec3(VOLUME_X - 1, VOLUME_Y - 1, VOLUME_Z - 1));
    ivec2 coarsePixel = ivec2(blockCell.z * VOLUME_X + blockCell.x, blockCell.y);

    float vanilla = texelFetch(VanillaLightVolume, coarsePixel, 0).r * 15.0;
    if (vanilla <= 0.001 || SourceCount <= 0) {
        fragColor = vec4(clamp(vanilla / 15.0, 0.0, 1.0), 0.0, 0.0, 1.0);
        return;
    }

    vec4 packedCell = texelFetch(CandidateVolume, coarsePixel, 0);
    vec3 receiverCellCenter = vec3(blockCell) + vec3(0.5);
    float maxPotential = 0.0;
    float maxVisible = 0.0;
    bool represented = false;

    for (int slot = 0; slot < 4; ++slot) {
        int encoded = candidateId(packedCell, slot);
        if (encoded <= 0) continue;
        int i = encoded - 1;
        if (i < 0 || i >= SourceCount || i >= 40) continue;
        represented = true;

        int metadataBase = i * 9;
        vec4 sourceInfo = texelFetch(SourceMetadata, ivec2(metadataBase, 0), 0);
        vec4 geometryInfo = texelFetch(SourceMetadata, ivec2(metadataBase + 1, 0), 0);
        vec3 source = sourceInfo.xyz;
        vec3 sourceCenter = geometryInfo.xyz;
        float emission = sourceInfo.w;
        int packedFlags = int(floor(geometryInfo.w + 0.5));
        bool capturePending = (packedFlags & 128) != 0;
        int blockedFaceMask = packedFlags & 63;
        bool boxEmitter = (packedFlags & 64) != 0;

        vec3 centerDelta = receiverCellCenter - sourceCenter;
        float stepDistance = abs(centerDelta.x) + abs(centerDelta.y) + abs(centerDelta.z);
        float potential = max(0.0, emission - floor(stepDistance + 0.001));
        if (potential <= 0.0) continue;
        maxPotential = max(maxPotential, potential);

        bool visible = true;
        vec3 exactCenterDelta = receiver - sourceCenter;
        if (!capturePending && boxEmitter) {
            vec3 ad = abs(exactCenterDelta);
            float major = max(max(ad.x, ad.y), ad.z);
            if (major > 0.5015 && (blockedFaceMask & dominantFaceBit(exactCenterDelta)) != 0) visible = false;
        } else if (!capturePending && immediateBlocked(source, sourceCenter, receiver, blockedFaceMask)) {
            visible = false;
        }

        vec3 delta = receiver - source;
        float receiverDistance = length(delta);
        if (!capturePending && visible && receiverDistance > NEAR_REJECT) {
            int face;
            vec2 localUv;
            encodeCube(delta, face, localUv);
            vec4 rect = texelFetch(SourceMetadata, ivec2(metadataBase + 3 + face, 0), 0);
            vec2 atlasUv = rect.xy + clamp(localUv, 0.001, 0.999) * rect.zw;
            float captured = texture(ShadowAtlas, atlasUv).r;
            float radius = 2.4 + 33.6 * clamp((emission - 1.0) / 14.0, 0.0, 1.0);
            float blockerDistance = captured * radius;

            float bias = 0.018 + receiverDistance * 0.0015;
            visible = captured >= CACHE_MISS || receiverDistance <= blockerDistance + bias;
        }

        if (visible) maxVisible = max(maxVisible, potential);
        if (maxVisible >= vanilla - 0.001) {
            fragColor = vec4(clamp(vanilla / 15.0, 0.0, 1.0), 0.0, 0.0, 1.0);
            return;
        }
    }

    float ownershipLevel = floor(vanilla + 0.5);
    float clipped = (!represented || maxPotential + 0.25 < ownershipLevel)
            ? vanilla
            : min(vanilla, maxVisible);
    fragColor = vec4(clamp(clipped / 15.0, 0.0, 1.0), 0.0, 0.0, 1.0);
}
