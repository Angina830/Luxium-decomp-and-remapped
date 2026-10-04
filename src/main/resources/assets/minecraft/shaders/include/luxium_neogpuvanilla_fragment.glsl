const ivec3 LUXIUM_NGV_VOLUME_BLOCKS = ivec3(48, 32, 48);

float luxium_ngv_level(sampler2D volumeTexture, int enabled,
        vec3 volumeMin, vec3 volumeSize, vec3 worldPosition,
        vec3 normal, int fineScale) {
    if (enabled == 0) return -1.0;
    int scale = max(fineScale, 1);

    vec3 receiver = worldPosition + normal * (0.55 / float(scale) + 0.002);
    ivec3 cell = ivec3(floor((receiver - volumeMin) * float(scale)));
    ivec3 size = LUXIUM_NGV_VOLUME_BLOCKS * scale;
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, size))) return -1.0;

    int slicesPerRow = max(1, textureSize(volumeTexture, 0).x / max(size.x, 1));
    int tileY = cell.z / slicesPerRow;
    int tileX = cell.z - tileY * slicesPerRow;
    ivec2 volumeTexel = ivec2(tileX * size.x + cell.x,
                              tileY * size.y + cell.y);

    return floor(texelFetch(volumeTexture, volumeTexel, 0).r * 15.0 + 0.5);
}

vec4 luxium_ngv_sample_lightmap(sampler2D lightmap, float blockLevel, float skyLevel) {
    vec2 level = clamp(vec2(blockLevel, skyLevel), vec2(0.0), vec2(15.0));
    return texture(lightmap, (level + vec2(0.5)) * (1.0 / 16.0));
}

float luxium_ngv_clipped_level(sampler2D volumeTexture,
        int enabled, vec3 volumeMin, vec3 volumeSize,
        vec3 worldPosition, vec3 normal, int fineScale,
        float originalBlockLevel) {
    if (enabled == 0 || originalBlockLevel <= 0.001) return originalBlockLevel;
    float baked = luxium_ngv_level(volumeTexture, enabled, volumeMin, volumeSize,
            worldPosition, normal, fineScale);
    if (baked < 0.0) return originalBlockLevel;
    return min(originalBlockLevel, baked);
}

vec4 luxium_ngv_fast_lighting(
        sampler3D lightPX, sampler3D lightNX,
        sampler3D lightPY, sampler3D lightNY,
        sampler3D lightPZ, sampler3D lightNZ,
        int enabled, vec3 volumeMin, vec3 volumeSize,
        vec3 worldPosition, vec3 normal) {
    if (enabled == 0) return vec4(0.0);
    vec3 receiver = worldPosition + normal * 0.05;
    vec3 local = receiver - volumeMin;
    if (any(lessThan(local, vec3(0.0))) || any(greaterThanEqual(local, volumeSize))) {
        return vec4(0.0);
    }
    vec3 uvw = clamp(local / volumeSize,
            vec3(0.5) / volumeSize,
            vec3(1.0) - vec3(0.5) / volumeSize);

    vec3 w = abs(normal);
    float sumW = max(w.x + w.y + w.z, 0.0001);
    vec3 directLight = vec3(0.0);
    float ownership = 0.0;

    if (w.x > 0.0001) {
        vec4 sampleX = normal.x >= 0.0 ? texture(lightPX, uvw) : texture(lightNX, uvw);
        directLight += sampleX.rgb * w.x;
        ownership = max(ownership, sampleX.a);
    }
    if (w.y > 0.0001) {
        vec4 sampleY = normal.y >= 0.0 ? texture(lightPY, uvw) : texture(lightNY, uvw);
        directLight += sampleY.rgb * w.y;
        ownership = max(ownership, sampleY.a);
    }
    if (w.z > 0.0001) {
        vec4 sampleZ = normal.z >= 0.0 ? texture(lightPZ, uvw) : texture(lightNZ, uvw);
        directLight += sampleZ.rgb * w.z;
        ownership = max(ownership, sampleZ.a);
    }

    directLight /= sumW;

    return vec4(min(directLight, vec3(1.6)), clamp(ownership, 0.0, 1.0));
}
