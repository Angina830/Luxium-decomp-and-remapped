#version 150

uniform sampler2D ShadowAtlas;
uniform sampler2D SourceMetadata;
uniform int SourceCount;
uniform int SliceZ;
uniform int DirectionIndex;
uniform float FastStrength;
uniform float DiffuseWrap;

in vec2 texCoord;
out vec4 fragColor;

const int VOLUME_X = 48;
const int VOLUME_Y = 32;
const int VOLUME_Z = 48;
const float CACHE_MISS = 0.9995;
const float CUBE_UV_SCALE = 0.9656888;
const float NEAR_REJECT = 0.60;

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
    if (a > b) { float s = a; a = b; b = s; }
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

bool immediateBlocked(vec3 emitter, vec3 sourceCenter, vec3 receiver, int mask) {
    if (mask == 0) return false;
    const vec3 halfBlock = vec3(0.5);
    if ((mask & 1) != 0) { vec3 c = sourceCenter + vec3(1,0,0); if (segmentHitsBox(emitter, receiver, c-halfBlock, c+halfBlock)) return true; }
    if ((mask & 2) != 0) { vec3 c = sourceCenter + vec3(-1,0,0); if (segmentHitsBox(emitter, receiver, c-halfBlock, c+halfBlock)) return true; }
    if ((mask & 4) != 0) { vec3 c = sourceCenter + vec3(0,1,0); if (segmentHitsBox(emitter, receiver, c-halfBlock, c+halfBlock)) return true; }
    if ((mask & 8) != 0) { vec3 c = sourceCenter + vec3(0,-1,0); if (segmentHitsBox(emitter, receiver, c-halfBlock, c+halfBlock)) return true; }
    if ((mask & 16) != 0) { vec3 c = sourceCenter + vec3(0,0,1); if (segmentHitsBox(emitter, receiver, c-halfBlock, c+halfBlock)) return true; }
    if ((mask & 32) != 0) { vec3 c = sourceCenter + vec3(0,0,-1); if (segmentHitsBox(emitter, receiver, c-halfBlock, c+halfBlock)) return true; }
    return false;
}

int dominantFaceBit(vec3 d) {
    vec3 ad = abs(d);
    float major = max(max(ad.x, ad.y), ad.z);
    if (major == ad.x) return d.x >= 0.0 ? 1 : 2;
    if (major == ad.y) return d.y >= 0.0 ? 4 : 8;
    return d.z >= 0.0 ? 16 : 32;
}

vec3 outputNormal(int direction) {
    if (direction == 0) return vec3( 1.0, 0.0, 0.0);
    if (direction == 1) return vec3(-1.0, 0.0, 0.0);
    if (direction == 2) return vec3(0.0,  1.0, 0.0);
    if (direction == 3) return vec3(0.0, -1.0, 0.0);
    if (direction == 4) return vec3(0.0, 0.0,  1.0);
    return vec3(0.0, 0.0, -1.0);
}

float axisDiffuse(vec3 n, vec3 toLight, float invDist, float dist,
                  bool boxEmitter, float faceCosine, int blockedFaceMask, int faceBit) {
    float nDotL = max(dot(n, toLight) * invDist, 0.0);
    if (boxEmitter) {
        if ((blockedFaceMask & faceBit) != 0 || nDotL <= 0.0) return 0.0;
        return nDotL * faceCosine * invDist;
    }
    if (dist <= NEAR_REJECT) return 1.0;
    float wrap = clamp(DiffuseWrap, 0.0, 0.50);

    return clamp((nDotL + wrap) / (1.0 + wrap), 0.0, 1.0);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (pixel.x < 0 || pixel.y < 0 || pixel.x >= VOLUME_X || pixel.y >= VOLUME_Y
            || SliceZ < 0 || SliceZ >= VOLUME_Z || DirectionIndex < 0 || DirectionIndex > 5) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 receiver = vec3(float(pixel.x) + 0.5, float(pixel.y) + 0.5, float(SliceZ) + 0.5);
    vec3 normal = outputNormal(DirectionIndex);
    vec3 localLight = vec3(0.0);
    float ownership = 0.0;

    for (int i = 0; i < 40; ++i) {
        if (i >= SourceCount) break;
        int base = i * 9;
        vec4 sourceInfo = texelFetch(SourceMetadata, ivec2(base, 0), 0);
        vec4 geometryInfo = texelFetch(SourceMetadata, ivec2(base + 1, 0), 0);
        vec3 sourceColor = texelFetch(SourceMetadata, ivec2(base + 2, 0), 0).rgb * FastStrength;
        vec3 source = sourceInfo.xyz;
        vec3 sourceCenter = geometryInfo.xyz;
        float emission = sourceInfo.w;
        int flags = int(floor(geometryInfo.w + 0.5));
        bool capturePending = (flags & 128) != 0;
        int blockedFaceMask = flags & 63;
        bool boxEmitter = (flags & 64) != 0;

        if (capturePending || emission <= 0.0) continue;

        vec3 centerToReceiver = receiver - sourceCenter;
        vec3 toLight;
        float faceCosine = 1.0;
        int faceBit = 0;
        if (boxEmitter) {
            vec3 ad = abs(centerToReceiver);
            float major = max(max(ad.x, ad.y), ad.z);

            if (major <= 0.5015) {
                fragColor = vec4(0.0);
                return;
            }
            faceBit = dominantFaceBit(centerToReceiver);
            if (major == ad.x) {
                bool positive = centerToReceiver.x >= 0.0;
                toLight = vec3((positive ? 0.5 : -0.5) - centerToReceiver.x,
                        -centerToReceiver.y, -centerToReceiver.z);
            } else if (major == ad.y) {
                bool positive = centerToReceiver.y >= 0.0;
                toLight = vec3(-centerToReceiver.x,
                        (positive ? 0.5 : -0.5) - centerToReceiver.y,
                        -centerToReceiver.z);
            } else {
                bool positive = centerToReceiver.z >= 0.0;
                toLight = vec3(-centerToReceiver.x, -centerToReceiver.y,
                        (positive ? 0.5 : -0.5) - centerToReceiver.z);
            }
            faceCosine = major - 0.5;
        } else {
            toLight = source - receiver;
        }

        float radius = 2.4 + 33.6 * clamp((emission - 1.0) / 14.0, 0.0, 1.0);
        float distSq = dot(toLight, toLight);
        if (distSq <= 0.00000001 || distSq > radius * radius) continue;
        float invDist = inversesqrt(distSq);
        float dist = distSq * invDist;
        float radial = 1.0 - dist / radius;
        if (radial <= 0.0) continue;

        ownership = max(ownership, smoothstep(0.0, 0.16, radial));

        bool visible = true;
        vec3 delta = receiver - source;
        float receiverDistance = length(delta);
        if (boxEmitter) {
            if ((blockedFaceMask & faceBit) != 0) visible = false;
        } else if (immediateBlocked(source, sourceCenter, receiver, blockedFaceMask)) {
            visible = false;
        }
        if (visible && receiverDistance > NEAR_REJECT) {
            int face;
            vec2 localUv;
            encodeCube(delta, face, localUv);
            vec4 rect = texelFetch(SourceMetadata, ivec2(base + 3 + face, 0), 0);
            vec2 atlasUv = rect.xy + clamp(localUv, 0.001, 0.999) * rect.zw;
            float captured = texture(ShadowAtlas, atlasUv).r;
            float blockerDistance = captured * radius;
            float bias = 0.018 + receiverDistance * 0.0015;
            visible = captured >= CACHE_MISS || receiverDistance <= blockerDistance + bias;
        }
        if (!visible) continue;

        float attenuation = max((exp(-3.0 * (1.0 - radial)) - 0.0497871) / 0.9502129, 0.0);
        if (attenuation <= 0.0001) continue;

        float diffuse = axisDiffuse(normal, toLight, invDist, dist,
                boxEmitter, faceCosine, blockedFaceMask, faceBit);
        localLight += sourceColor * attenuation * diffuse;
    }

    fragColor = vec4(min(localLight, vec3(1.6)), ownership);
}
