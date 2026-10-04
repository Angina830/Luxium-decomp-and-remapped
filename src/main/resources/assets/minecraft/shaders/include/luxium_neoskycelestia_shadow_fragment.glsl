vec2 luxium_receiver_plane_depth_gradient(vec3 projected) {
    vec3 dx = dFdx(projected);
    vec3 dy = dFdy(projected);

    float dxScale = max(abs(dx.x), abs(dx.y));
    float dyScale = max(abs(dy.x), abs(dy.y));
    if (dxScale <= 1.0e-12 || dyScale <= 1.0e-12) {
        return vec2(0.0);
    }
    dx /= dxScale;
    dy /= dyScale;

    float determinant = dx.x * dy.y - dx.y * dy.x;

    if (abs(determinant) <= 1.0e-5) {
        return vec2(0.0);
    }

    vec2 gradient = vec2(
            (dx.z * dy.y - dx.y * dy.z) / determinant,
            (dx.x * dy.z - dx.z * dy.x) / determinant);

    if (!all(lessThan(abs(gradient), vec2(1.0e6)))) {
        return vec2(0.0);
    }
    return gradient;
}

float luxium_receiver_depth_at_texel(
        vec3 projected,
        vec2 receiverGradient,
        ivec2 texel,
        float texelSize) {
    vec2 sampleUv = (vec2(texel) + vec2(0.5)) * texelSize;
    return projected.z + dot(receiverGradient, sampleUv - projected.xy);
}

float luxium_compare_texel(
        sampler2D depthMap,
        vec3 projected,
        vec2 receiverGradient,
        ivec2 texel,
        ivec2 mapSize,
        float texelSize,
        float residualBias) {
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, mapSize))) {
        return 0.0;
    }
    float receiverDepth = luxium_receiver_depth_at_texel(
            projected, receiverGradient, texel, texelSize);
    return receiverDepth - residualBias <= texelFetch(depthMap, texel, 0).r ? 1.0 : 0.0;
}

float luxium_filter_shadow(
        sampler2D depthMap,
        vec3 projected,
        vec2 receiverGradient,
        float texelSize,
        float baseBias,
        float slopeBias,
        int samples,
        float nDotL) {

    if (projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0
            || projected.z <= 0.0 || projected.z >= 1.0) {
        return 0.0;
    }

    float residualBias = baseBias;
    int mapLength = max(1, int(floor(1.0 / max(texelSize, 1.0e-8) + 0.5)));
    ivec2 mapSize = ivec2(mapLength);

    if (samples <= 1) {
        ivec2 texel = ivec2(projected.xy * float(mapLength));

        return luxium_compare_texel(
                depthMap, projected, receiverGradient, texel, mapSize,
                texelSize, residualBias);
    }

    vec2 texelPosition = projected.xy / texelSize - 0.5;
    ivec2 baseTexel = ivec2(floor(texelPosition));
    vec2 blend = fract(texelPosition);

#ifdef LUXIUM_TEXTURE_GATHER

    vec2 gatherUv = (vec2(baseTexel) + vec2(1.0)) * texelSize;
    vec4 depths = textureGather(depthMap, gatherUv);
    float r00 = luxium_receiver_depth_at_texel(projected, receiverGradient, baseTexel, texelSize);
    float r10 = luxium_receiver_depth_at_texel(projected, receiverGradient, baseTexel + ivec2(1, 0), texelSize);
    float r01 = luxium_receiver_depth_at_texel(projected, receiverGradient, baseTexel + ivec2(0, 1), texelSize);
    float r11 = luxium_receiver_depth_at_texel(projected, receiverGradient, baseTexel + ivec2(1, 1), texelSize);
    float s00 = step(r00 - residualBias, depths.w);
    float s10 = step(r10 - residualBias, depths.z);
    float s01 = step(r01 - residualBias, depths.x);
    float s11 = step(r11 - residualBias, depths.y);
#else
    float s00 = luxium_compare_texel(
            depthMap, projected, receiverGradient, baseTexel, mapSize,
            texelSize, residualBias);
    float s10 = luxium_compare_texel(
            depthMap, projected, receiverGradient, baseTexel + ivec2(1, 0), mapSize,
            texelSize, residualBias);
    float s01 = luxium_compare_texel(
            depthMap, projected, receiverGradient, baseTexel + ivec2(0, 1), mapSize,
            texelSize, residualBias);
    float s11 = luxium_compare_texel(
            depthMap, projected, receiverGradient, baseTexel + ivec2(1, 1), mapSize,
            texelSize, residualBias);
#endif

    vec2 weights = smoothstep(vec2(0.0), vec2(1.0), blend);
    return mix(mix(s00, s10, weights.x), mix(s01, s11, weights.x), weights.y);
}

float luxium_hardware_compare_texel(
        sampler2DShadow depthMap,
        vec3 projected,
        vec2 receiverGradient,
        ivec2 texel,
        ivec2 mapSize,
        float texelSize,
        float baseBias) {
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, mapSize))) {
        return 0.0;
    }
    float receiverDepth = luxium_receiver_depth_at_texel(
            projected, receiverGradient, texel, texelSize);
    vec2 sampleUv = (vec2(texel) + vec2(0.5)) * texelSize;
    return texture(depthMap, vec3(sampleUv, receiverDepth - baseBias));
}

float luxium_hardware_filter_shadow(
        sampler2DShadow depthMap,
        vec3 projected,
        float texelSize,
        float baseBias,
        float slopeBias,
        int samples,
        float nDotL) {
    if (projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0
            || projected.z <= 0.0 || projected.z >= 1.0) {
        return 0.0;
    }

    float effectiveBias = baseBias * (1.0 + slopeBias * (1.0 - clamp(nDotL, 0.0, 1.0)));

    if (samples <= 1) {
        return texture(depthMap, vec3(projected.xy, projected.z - effectiveBias));
    }

    vec2 receiverGradient = luxium_receiver_plane_depth_gradient(projected);
    ivec2 mapSize = textureSize(depthMap, 0);
    vec2 texelPosition = projected.xy / texelSize - 0.5;
    ivec2 baseTexel = ivec2(floor(texelPosition));
    vec2 blend = fract(texelPosition);

    float s00 = luxium_hardware_compare_texel(
            depthMap, projected, receiverGradient, baseTexel, mapSize, texelSize, effectiveBias);
    float s10 = luxium_hardware_compare_texel(
            depthMap, projected, receiverGradient, baseTexel + ivec2(1, 0), mapSize, texelSize, effectiveBias);
    float s01 = luxium_hardware_compare_texel(
            depthMap, projected, receiverGradient, baseTexel + ivec2(0, 1), mapSize, texelSize, effectiveBias);
    float s11 = luxium_hardware_compare_texel(
            depthMap, projected, receiverGradient, baseTexel + ivec2(1, 1), mapSize, texelSize, effectiveBias);

    vec2 weights = smoothstep(vec2(0.0), vec2(1.0), blend);
    return mix(mix(s00, s10, weights.x), mix(s01, s11, weights.x), weights.y);
}

float luxium_visibility(
        sampler2DShadow nearMap,
        sampler2DShadow farMap,
        vec3 nearCoord,
        vec3 farCoord,
        float distance,
        vec4 cascadeData,
        vec4 biasData,
        int samples,
        float nDotL) {

    float blendStart = cascadeData.x * biasData.w;
    if (distance < blendStart) {
        return luxium_hardware_filter_shadow(
                nearMap, nearCoord, cascadeData.z, biasData.x, biasData.z, samples, nDotL);
    }
    if (distance >= cascadeData.y) {
        return 0.0;
    }
    if (distance >= cascadeData.x) {
        float farVisibility = luxium_hardware_filter_shadow(
                farMap, farCoord, cascadeData.w, biasData.y, biasData.z, samples, nDotL);
        return mix(
                farVisibility,
                0.0,
                smoothstep(cascadeData.y * 0.95, cascadeData.y, distance));
    }

    float nearVisibility = luxium_hardware_filter_shadow(
            nearMap, nearCoord, cascadeData.z, biasData.x, biasData.z, samples, nDotL);
    float farVisibility = luxium_hardware_filter_shadow(
            farMap, farCoord, cascadeData.w, biasData.y, biasData.z, samples, nDotL);
    return mix(
            nearVisibility,
            farVisibility,
            smoothstep(blendStart, cascadeData.x, distance));
}

float luxium_entity_visibility(
        sampler2D entityMap,
        vec3 projected,
        vec4 entityData,
        int samples,
        float nDotL) {
    if (projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0
            || projected.z <= 0.0 || projected.z >= 1.0) {
        return 1.0;
    }
    vec2 receiverGradient = luxium_receiver_plane_depth_gradient(projected);
    return luxium_filter_shadow(
            entityMap, projected, receiverGradient, entityData.x,
            entityData.y, entityData.z, samples, nDotL);
}

float luxium_luminance(vec3 color) {
    return dot(max(color, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
}

vec3 luxium_compete_vanilla_block_light(
        vec3 blockOnlyLight,
        vec3 zeroBlockOnlyLight,
        vec3 skyOnlyLight,
        vec3 celestialLight,
        float sunShadowBlockVisibility,
        float sunShadowAmount) {

    vec3 blockContribution = max(blockOnlyLight - zeroBlockOnlyLight, vec3(0.0));

    vec3 skyReference = max(skyOnlyLight - zeroBlockOnlyLight, vec3(0.0));

    float blockIrradiance = luxium_luminance(blockContribution);
    float adaptationIrradiance = max(
            luxium_luminance(skyReference),
            luxium_luminance(celestialLight));

    float dominantVisibleBlockIrradiance = max(blockIrradiance - adaptationIrradiance, 0.0);
    float dominantVisibility = blockIrradiance > 1.0e-5
            ? dominantVisibleBlockIrradiance / blockIrradiance
            : 0.0;

    float daylightPresence = smoothstep(0.10, 0.32, luxium_luminance(skyReference));
    float setting = clamp(sunShadowBlockVisibility, 0.0, 1.0);
    float requestedShadowVisibility = setting
            * daylightPresence
            * clamp(sunShadowAmount, 0.0, 1.0);

    float sourceCoreCompression = 1.0 / (
            1.0 + blockIrradiance * (1.0 - setting));
    float shadowVisibility = requestedShadowVisibility * sourceCoreCompression;

    float blockVisibility = max(dominantVisibility, shadowVisibility);
    return zeroBlockOnlyLight + celestialLight + blockContribution * blockVisibility;
}

struct LuxiumCelestialTerms {
    vec3 light;
    float sunShadowAmount;
};

LuxiumCelestialTerms luxium_prepare_celestial_light(
        vec4 zeroBlockOnly,
        vec4 skyOnly,
        vec3 normal,
        float rawNdotL,
        vec3 directColor,
        vec3 skyAmbientColor,
        vec3 groundAmbientColor,
        float directStrength,
        float ambientStrength,
        float visibility,
        bool occlusionEnabled) {
    vec3 vanillaSky = max(skyOnly.rgb - zeroBlockOnly.rgb, vec3(0.0));
    float skyAccess = smoothstep(0.015, 0.30,
            max(vanillaSky.r, max(vanillaSky.g, vanillaSky.b)));
    vec3 hemisphere = mix(
            groundAmbientColor,
            skyAmbientColor,
            clamp(normal.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 ambientSky = hemisphere * ambientStrength * skyAccess;
    float directAccess = occlusionEnabled
            ? clamp(visibility, 0.0, 1.0) * skyAccess
            : skyAccess;
    float geometricSunFacing = step(0.005, rawNdotL);
    float wrappedNdotL = clamp((rawNdotL + 0.16) / 1.16, 0.0, 1.0) * geometricSunFacing;
    vec3 direct = directColor * wrappedNdotL * directStrength * directAccess;

    LuxiumCelestialTerms terms;
    terms.light = ambientSky + direct;
    terms.sunShadowAmount = occlusionEnabled
            ? 1.0 - clamp(visibility, 0.0, 1.0) * geometricSunFacing
            : 0.0;
    return terms;
}

vec4 luxium_apply_prepared_celestial_light(
        vec4 fullLight,
        vec4 blockOnly,
        vec4 zeroBlockOnly,
        vec4 skyOnly,
        LuxiumCelestialTerms terms,
        float sunShadowBlockVisibility) {
    return vec4(luxium_compete_vanilla_block_light(
            blockOnly.rgb, zeroBlockOnly.rgb, skyOnly.rgb, terms.light,
            sunShadowBlockVisibility, terms.sunShadowAmount), fullLight.a);
}

vec4 luxium_resolve_celestial_light(
        vec4 fullLight,
        vec4 blockOnly,
        vec4 zeroBlockOnly,
        vec4 skyOnly,
        vec3 normal,
        float rawNdotL,
        vec3 directColor,
        vec3 skyAmbientColor,
        vec3 groundAmbientColor,
        float directStrength,
        float ambientStrength,
        float sunShadowBlockVisibility,
        float visibility,
        bool occlusionEnabled,
        bool lightEnabled) {
    if (!lightEnabled) {
        return fullLight;
    }
    LuxiumCelestialTerms terms = luxium_prepare_celestial_light(
            zeroBlockOnly, skyOnly, normal, rawNdotL, directColor,
            skyAmbientColor, groundAmbientColor, directStrength, ambientStrength,
            visibility, occlusionEnabled);

    return luxium_apply_prepared_celestial_light(
            fullLight, blockOnly, zeroBlockOnly, skyOnly, terms,
            sunShadowBlockVisibility);
}
