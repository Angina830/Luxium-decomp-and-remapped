const float LUXIUM_LOCAL_NEAR_REJECT = 0.6;
const float LUXIUM_LOCAL_CACHE_MISS = 0.9995;
const int LUXIUM_LOCAL_TILE_SIZE = 16;
const int LUXIUM_LOCAL_GRID_TEXELS = 8;

const vec2 LUXIUM_LOCAL_ATLAS_TILE_SCALE = vec2(255.0 / 4096.0);

const float LUXIUM_LOCAL_CUBE_UV_SCALE = 0.9656888;

const vec2 luxiumLocalPoisson[12] = vec2[](
    vec2(-0.1711703, 0.01196155), vec2(-0.0664082, -0.150654),
    vec2(0.1254117, -0.06100821), vec2(0.007551044, 0.134552),
    vec2(-0.07008105, 0.3168273), vec2(-0.3541221, -0.05193427),
    vec2(0.08779836, -0.3807213), vec2(0.3396556, 0.1264426),
    vec2(0.3064434, -0.2384234), vec2(-0.2567431, -0.2755259),
    vec2(-0.1656846, 0.4190472), vec2(0.3884483, 0.3123845)
);

const vec2 luxiumLocalSearch[8] = vec2[](
    vec2(-0.7071, -0.7071), vec2(0.7071, -0.7071),
    vec2(-0.7071, 0.7071), vec2(0.7071, 0.7071),
    vec2(0.0, -1.0), vec2(0.0, 1.0), vec2(-1.0, 0.0), vec2(1.0, 0.0)
);

float luxium_local_random(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void luxium_local_encode_cube(vec3 d, out int face, out vec2 uv) {
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
    uv = uv / max(m, 0.00001) * (0.5 * LUXIUM_LOCAL_CUBE_UV_SCALE) + 0.5;
}

bool luxium_local_clip_segment_axis(float origin, float delta, float boxMin, float boxMax,
                                    inout float tMin, inout float tMax) {
    if (abs(delta) < 0.00001) {
        return origin >= boxMin && origin <= boxMax;
    }
    float invDelta = 1.0 / delta;
    float a = (boxMin - origin) * invDelta;
    float b = (boxMax - origin) * invDelta;
    if (a > b) { float swapValue = a; a = b; b = swapValue; }
    tMin = max(tMin, a);
    tMax = min(tMax, b);
    return tMax >= tMin;
}

bool luxium_local_segment_hits_box(vec3 origin, vec3 target, vec3 boxMin, vec3 boxMax) {
    vec3 delta = target - origin;
    float tMin = 0.0005;
    float tMax = 0.9990;
    if (!luxium_local_clip_segment_axis(origin.x, delta.x, boxMin.x, boxMax.x, tMin, tMax)) return false;
    if (!luxium_local_clip_segment_axis(origin.y, delta.y, boxMin.y, boxMax.y, tMin, tMax)) return false;
    if (!luxium_local_clip_segment_axis(origin.z, delta.z, boxMin.z, boxMax.z, tMin, tMax)) return false;
    return tMax >= tMin;
}

bool luxium_local_immediate_blocked(vec3 emitterPos, vec3 sourceCenter, vec3 target,
                                    int blockedFaceMask) {
    if (blockedFaceMask == 0) return false;

    const vec3 halfBlock = vec3(0.5);
    if ((blockedFaceMask & 1) != 0) {
        vec3 c = sourceCenter + vec3(1.0, 0.0, 0.0);
        if (luxium_local_segment_hits_box(emitterPos, target, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 2) != 0) {
        vec3 c = sourceCenter + vec3(-1.0, 0.0, 0.0);
        if (luxium_local_segment_hits_box(emitterPos, target, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 4) != 0) {
        vec3 c = sourceCenter + vec3(0.0, 1.0, 0.0);
        if (luxium_local_segment_hits_box(emitterPos, target, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 8) != 0) {
        vec3 c = sourceCenter + vec3(0.0, -1.0, 0.0);
        if (luxium_local_segment_hits_box(emitterPos, target, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 16) != 0) {
        vec3 c = sourceCenter + vec3(0.0, 0.0, 1.0);
        if (luxium_local_segment_hits_box(emitterPos, target, c - halfBlock, c + halfBlock)) return true;
    }
    if ((blockedFaceMask & 32) != 0) {
        vec3 c = sourceCenter + vec3(0.0, 0.0, -1.0);
        if (luxium_local_segment_hits_box(emitterPos, target, c - halfBlock, c + halfBlock)) return true;
    }
    return false;
}

float luxium_local_hard_compare(sampler2D atlas, vec4 rect, vec2 localUv,
                                float receiverDistance, float radius, float bias) {
    ivec2 atlasSize = textureSize(atlas, 0);
    vec2 atlasSizeF = vec2(atlasSize);
    ivec2 tileOrigin = ivec2(floor(rect.xy * atlasSizeF));
    ivec2 tileSpan = max(ivec2(round(rect.zw * atlasSizeF)), ivec2(1));

    vec2 texelPos = clamp(localUv, 0.0, 1.0) * vec2(tileSpan);
    ivec2 p0 = ivec2(floor(texelPos));
    ivec2 p1 = min(p0 + ivec2(1), tileSpan);
    vec2 f = fract(texelPos);

    float d00 = texelFetch(atlas, tileOrigin + ivec2(p0.x, p0.y), 0).r;
    float d10 = texelFetch(atlas, tileOrigin + ivec2(p1.x, p0.y), 0).r;
    float d01 = texelFetch(atlas, tileOrigin + ivec2(p0.x, p1.y), 0).r;
    float d11 = texelFetch(atlas, tileOrigin + ivec2(p1.x, p1.y), 0).r;

    float c00 = (d00 < LUXIUM_LOCAL_CACHE_MISS && receiverDistance > d00 * radius + bias) ? 1.0 : 0.0;
    float c10 = (d10 < LUXIUM_LOCAL_CACHE_MISS && receiverDistance > d10 * radius + bias) ? 1.0 : 0.0;
    float c01 = (d01 < LUXIUM_LOCAL_CACHE_MISS && receiverDistance > d01 * radius + bias) ? 1.0 : 0.0;
    float c11 = (d11 < LUXIUM_LOCAL_CACHE_MISS && receiverDistance > d11 * radius + bias) ? 1.0 : 0.0;
    return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}

float luxium_local_shadow_for_light(int i, vec3 worldPos, vec3 normal,
                                    vec3 lightPos, vec3 sourceCenter,
                                    int blockedFaceMask, bool boxEmitter,
                                    float radius, float rawDist, vec3 direction) {
    float nDotL = clamp(dot(normal, direction), 0.001, 1.0);
    float normalBias = min(0.04 + 0.12 * (1.0 - nDotL), max(rawDist, 0.05) * 0.8);
    vec3 biasPos = worldPos + normal * normalBias;

    if (!boxEmitter && luxium_local_immediate_blocked(lightPos, sourceCenter, biasPos, blockedFaceMask)) return 1.0;
    if (rawDist <= LUXIUM_LOCAL_NEAR_REJECT) return 0.0;

    float receiverDistance = length(lightPos - biasPos);
    int face; vec2 localUv;
    luxium_local_encode_cube(biasPos - lightPos, face, localUv);
    vec4 rectData = u_LuxiumLocalAtlasRect[i * 6 + face];
    vec4 rect = vec4(rectData.xy, LUXIUM_LOCAL_ATLAS_TILE_SCALE);
    vec2 baseUv = rect.xy + localUv * rect.zw;
    float shadowBias = 0.03 + 0.06 * (1.0 - nDotL);

    if (u_LuxiumLocalSoftShadows == 0) {
        return luxium_local_hard_compare(u_LuxiumLocalShadowAtlas, rect, localUv,
                receiverDistance, radius, shadowBias);
    }

    float blockerSum = 0.0;
    int blockerCount = 0;
    float center = texture(u_LuxiumLocalShadowAtlas, baseUv).r;
    if (center < LUXIUM_LOCAL_CACHE_MISS && receiverDistance > center * radius + shadowBias) {
        blockerSum += center * radius;
        blockerCount++;
    }

    float randomValue = luxium_local_random(gl_FragCoord.xy + vec2(float(i) * 3.17));
    float angle = randomValue * 6.2831853;
    mat2 searchRotation = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
    for (int k = 0; k < 8; ++k) {
        vec2 uv = rect.xy + clamp(localUv + searchRotation * luxiumLocalSearch[k] * 0.025,
                0.001, 0.999) * rect.zw;
        float dNorm = texture(u_LuxiumLocalShadowAtlas, uv).r;
        if (dNorm < LUXIUM_LOCAL_CACHE_MISS && receiverDistance > dNorm * radius + shadowBias) {
            blockerSum += dNorm * radius;
            blockerCount++;
        }
    }

    if (blockerCount <= 0) return 0.0;
    float avgBlocker = blockerSum / float(blockerCount);
    float filterRadius = clamp((receiverDistance - avgBlocker) / receiverDistance, 0.0, 1.0) * 0.06;
    float pcfAngle = randomValue * 3.14159 + 1.57079;
    mat2 pcfRotation = mat2(cos(pcfAngle), -sin(pcfAngle), sin(pcfAngle), cos(pcfAngle));
    float occluded = 0.0;
    for (int k = 0; k < 12; ++k) {
        vec2 uv = rect.xy + clamp(localUv + pcfRotation * luxiumLocalPoisson[k] * filterRadius,
                0.001, 0.999) * rect.zw;
        float dNorm = texture(u_LuxiumLocalShadowAtlas, uv).r;
        if (dNorm < LUXIUM_LOCAL_CACHE_MISS && receiverDistance > dNorm * radius + shadowBias) {
            occluded += 1.0;
        }
    }
    return occluded / 12.0;
}

float luxium_local_spread_visibility(float blockLightGuide, float attenuation, float radial) {
    float guideBase = max(attenuation, 0.0);
    float guideCurve = mix(guideBase, sqrt(guideBase), 0.4841);
    float expectedGuide = max(guideCurve * 0.72, 0.04);
    float relativeGuide = clamp(blockLightGuide / expectedGuide, 0.0, 2.0);
    float floodVisibility = smoothstep(0.035, 0.48, relativeGuide);
    floodVisibility *= smoothstep(0.0, 0.08, radial);

    return mix(1.0, floodVisibility, clamp(u_LuxiumLocalSpreadOcclusion, 0.0, 1.0));
}

vec3 luxium_local_baked_axis_uvw(vec3 local, vec3 volumeSize, vec3 texSize,
        int axis, float signValue) {
    vec3 sampleLocal = local;
    if (axis == 0) sampleLocal.x -= 0.5 * signValue;
    else if (axis == 1) sampleLocal.y -= 0.5 * signValue;
    else sampleLocal.z -= 0.5 * signValue;

    vec3 uvw = sampleLocal / volumeSize;
    return clamp(uvw, vec3(0.5) / texSize, vec3(1.0) - vec3(0.5) / texSize);
}

bool luxium_local_baked_hard(vec3 worldPos, vec3 normal, out vec4 result) {
    if (u_LuxiumLocalBakedHardEnabled == 0) return false;

    vec3 local = worldPos - u_LuxiumLocalBakedHardMin;
    const float boundsEpsilon = 0.001;
    if (any(lessThan(local, vec3(-boundsEpsilon)))
            || any(greaterThan(local, u_LuxiumLocalBakedHardSize + vec3(boundsEpsilon)))) {
        return false;
    }
    local = clamp(local, vec3(0.0), u_LuxiumLocalBakedHardSize);

    vec3 w = abs(normal);
    float sumW = max(w.x + w.y + w.z, 0.0001);
    vec3 directLight = vec3(0.0);
    float ownership = 0.0;
    if (w.x > 0.0001) {
        vec4 v = normal.x >= 0.0
                ? texture(u_LuxiumLocalBakedHardPX, luxium_local_baked_axis_uvw(local, u_LuxiumLocalBakedHardSize, vec3(textureSize(u_LuxiumLocalBakedHardPX, 0)), 0, 1.0))
                : texture(u_LuxiumLocalBakedHardNX, luxium_local_baked_axis_uvw(local, u_LuxiumLocalBakedHardSize, vec3(textureSize(u_LuxiumLocalBakedHardNX, 0)), 0, -1.0));
        directLight += v.rgb * w.x; ownership = max(ownership, v.a);
    }
    if (w.y > 0.0001) {
        vec4 v = normal.y >= 0.0
                ? texture(u_LuxiumLocalBakedHardPY, luxium_local_baked_axis_uvw(local, u_LuxiumLocalBakedHardSize, vec3(textureSize(u_LuxiumLocalBakedHardPY, 0)), 1, 1.0))
                : texture(u_LuxiumLocalBakedHardNY, luxium_local_baked_axis_uvw(local, u_LuxiumLocalBakedHardSize, vec3(textureSize(u_LuxiumLocalBakedHardNY, 0)), 1, -1.0));
        directLight += v.rgb * w.y; ownership = max(ownership, v.a);
    }
    if (w.z > 0.0001) {
        vec4 v = normal.z >= 0.0
                ? texture(u_LuxiumLocalBakedHardPZ, luxium_local_baked_axis_uvw(local, u_LuxiumLocalBakedHardSize, vec3(textureSize(u_LuxiumLocalBakedHardPZ, 0)), 2, 1.0))
                : texture(u_LuxiumLocalBakedHardNZ, luxium_local_baked_axis_uvw(local, u_LuxiumLocalBakedHardSize, vec3(textureSize(u_LuxiumLocalBakedHardNZ, 0)), 2, -1.0));
        directLight += v.rgb * w.z; ownership = max(ownership, v.a);
    }
    result = vec4(min(directLight / sumW, vec3(1.6)), clamp(ownership, 0.0, 1.0));
    return true;
}

vec4 luxium_resolve_local_lighting(vec3 worldPos, vec3 normal, float blockLightGuide) {
    if (u_LuxiumLocalShadowEnabled == 0) return vec4(0.0);
    vec4 bakedHard;
    if (u_LuxiumLocalBakedHardEnabled != 0) {

        if (luxium_local_baked_hard(worldPos, normal, bakedHard)) return bakedHard;
        return vec4(0.0);
    }
    if (u_LuxiumLocalLightCount <= 0) return vec4(0.0);

    vec3 localLight = vec3(0.0);
    float replacementWeight = 0.0;
    bool preserveEmissiveSurface = false;
    bool spreadMode = u_LuxiumLocalShadowMode == 0;
    ivec2 tile = ivec2(gl_FragCoord.xy) / LUXIUM_LOCAL_TILE_SIZE;
    vec4 packedIndices = vec4(0.0);

    for (int slot = 0; slot < 32; ++slot) {
        if (slot % 4 == 0) {
            packedIndices = texelFetch(u_LuxiumLocalLightGrid,
                    ivec2(tile.x * LUXIUM_LOCAL_GRID_TEXELS + slot / 4, tile.y), 0);
        }
        int encoded = int(packedIndices[slot % 4] * 255.0 + 0.5);
        if (encoded == 0) break;
        int i = encoded - 1;
        if (i >= u_LuxiumLocalLightCount) continue;

        vec3 lightPos = u_LuxiumLocalLights[i].xyz;
        float radius = u_LuxiumLocalLights[i].w;
        bool boxEmitter = u_LuxiumLocalAtlasRect[i * 6 + 4].z > 0.5;
        vec3 sourceCenter;
        int blockedFaceMask;
        if (boxEmitter) {

            sourceCenter = lightPos;
            blockedFaceMask = int(u_LuxiumLocalAtlasRect[i * 6 + 3].w + 0.5);
        } else {
            vec3 centerOffset = vec3(
                    u_LuxiumLocalAtlasRect[i * 6 + 2].z,
                    u_LuxiumLocalAtlasRect[i * 6 + 2].w,
                    u_LuxiumLocalAtlasRect[i * 6 + 3].z);
            sourceCenter = lightPos + centerOffset;
            blockedFaceMask = int(u_LuxiumLocalAtlasRect[i * 6 + 3].w + 0.5);
        }

        vec3 toLight;
        vec3 centerToReceiver = vec3(0.0);
        float emitterOutward = 1.0;
        int faceBit = 0;

        if (boxEmitter) {

            centerToReceiver = worldPos - sourceCenter;
            vec3 ad = abs(centerToReceiver);
            float majorAxis = max(max(ad.x, ad.y), ad.z);
            if (majorAxis <= 0.5015) {
                preserveEmissiveSurface = true;
                break;
            }

            emitterOutward = majorAxis - 0.5;
            if (majorAxis == ad.x) {
                bool positive = centerToReceiver.x >= 0.0;
                toLight = vec3((positive ? 0.5 : -0.5) - centerToReceiver.x,
                        -centerToReceiver.y, -centerToReceiver.z);
                faceBit = positive ? 1 : 2;
            } else if (majorAxis == ad.y) {
                bool positive = centerToReceiver.y >= 0.0;
                toLight = vec3(-centerToReceiver.x,
                        (positive ? 0.5 : -0.5) - centerToReceiver.y,
                        -centerToReceiver.z);
                faceBit = positive ? 4 : 8;
            } else {
                bool positive = centerToReceiver.z >= 0.0;
                toLight = vec3(-centerToReceiver.x, -centerToReceiver.y,
                        (positive ? 0.5 : -0.5) - centerToReceiver.z);
                faceBit = positive ? 16 : 32;
            }
        } else {
            toLight = lightPos - worldPos;
        }

        float distSq = dot(toLight, toLight);
        float radiusSq = radius * radius;
        if (distSq > radiusSq || distSq <= 0.00000001) continue;
        float invDist = inversesqrt(distSq);
        float dist = distSq * invDist;
        float radial = 1.0 - dist / radius;
        if (radial <= 0.0) continue;

        vec4 source = vec4(
                u_LuxiumLocalAtlasRect[i * 6].zw,
                u_LuxiumLocalAtlasRect[i * 6 + 1].z,
                u_LuxiumLocalAtlasRect[i * 6 + 1].w);
        float lodWeight = source.a;
        if (lodWeight <= 0.0) continue;

        replacementWeight = max(replacementWeight,
                smoothstep(0.0, 0.16, radial) * lodWeight);

        float nDotL = max(dot(normal, toLight) * invDist, 0.0);
        float diffuse;
        if (boxEmitter) {
            if ((blockedFaceMask & faceBit) != 0 || nDotL <= 0.0) continue;
            diffuse = nDotL * emitterOutward * invDist;
        } else {
            diffuse = dist <= LUXIUM_LOCAL_NEAR_REJECT
                    ? 1.0
                    : clamp((nDotL + 0.16) / 1.16, 0.0, 1.0);
        }
        float attenuation = max((exp(-3.0 * (1.0 - radial)) - 0.0497871) / 0.9502129, 0.0);

        float visibility;
        if (spreadMode) {
            visibility = (!boxEmitter && dist <= LUXIUM_LOCAL_NEAR_REJECT)
                    ? 1.0
                    : luxium_local_spread_visibility(blockLightGuide, attenuation, radial);
        } else {

            float shadowDist;
            vec3 shadowDirection;
            vec3 shadowOrigin;
            if (boxEmitter) {
                float centerDistSq = dot(centerToReceiver, centerToReceiver);
                float invCenterDist = inversesqrt(max(centerDistSq, 0.00000001));
                shadowDist = centerDistSq * invCenterDist;
                shadowDirection = -centerToReceiver * invCenterDist;
                shadowOrigin = sourceCenter;
            } else {
                shadowDist = dist;
                shadowDirection = toLight * invDist;
                shadowOrigin = lightPos;
            }
            float shadow = luxium_local_shadow_for_light(i, worldPos, normal,
                    shadowOrigin, sourceCenter, blockedFaceMask, boxEmitter,
                    radius, shadowDist, shadowDirection);
            visibility = 1.0 - shadow;
        }

        float weight = attenuation * diffuse * visibility * lodWeight;
        if (weight > 0.0001) localLight += source.rgb * weight;
    }

    if (preserveEmissiveSurface) return vec4(0.0);

    return vec4(min(localLight, vec3(1.6)), replacementWeight);
}
