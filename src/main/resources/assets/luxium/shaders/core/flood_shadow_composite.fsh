#version 150

uniform sampler2D SceneSampler;
uniform sampler2D ShadowlessSceneSampler;
uniform sampler2D ShadowMaskSampler;
uniform sampler2D DepthSampler;
uniform sampler2D ShadowlessDepthSampler;

vec4 sampleDepthAwareShadowless(vec2 uv, vec4 sceneColor, float sceneDepth, float depthDerivative, out float confidence) {
    ivec2 lowSize = textureSize(ShadowlessSceneSampler, 0);
    vec2 lowSizeF = vec2(lowSize);
    vec2 samplePos = uv * lowSizeF - vec2(0.5);
    ivec2 base = ivec2(floor(samplePos));
    vec2 fraction = fract(samplePos);

    ivec2 fullSize = textureSize(DepthSampler, 0);
    float footprint = max(float(fullSize.x) / lowSizeF.x, float(fullSize.y) / lowSizeF.y);
    float depthTolerance = clamp(depthDerivative * (2.0 + footprint), 0.00001, 0.002);

    const vec3 colorEpsilon = vec3(1.0 / 255.0);

    if (all(equal(lowSize, fullSize))) {
        vec4 shadowless = texture(ShadowlessSceneSampler, uv);
        float depthDelta = abs(texture(ShadowlessDepthSampler, uv).r - sceneDepth);
        confidence = 1.0 - smoothstep(depthTolerance * 2.0, depthTolerance * 8.0, depthDelta);
        vec3 attenuation = clamp(
                (shadowless.rgb + colorEpsilon) / (sceneColor.rgb + colorEpsilon),
                vec3(0.0),
                vec3(1.0));
        return vec4(sceneColor.rgb * attenuation, sceneColor.a);
    }

    vec4 shadowlessSum = vec4(0.0);
    vec3 referenceSum = vec3(0.0);
    float weightSum = 0.0;
    float minDepthDelta = 1.0;

    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            ivec2 sampleCoord = clamp(base + ivec2(x, y), ivec2(0), lowSize - ivec2(1));
            float sampleDepth = texelFetch(ShadowlessDepthSampler, sampleCoord, 0).r;
            float depthDelta = abs(sampleDepth - sceneDepth);
            minDepthDelta = min(minDepthDelta, depthDelta);

            float spatialX = x == 0 ? 1.0 - fraction.x : fraction.x;
            float spatialY = y == 0 ? 1.0 - fraction.y : fraction.y;
            float normalizedDelta = depthDelta / depthTolerance;
            float squaredDelta = normalizedDelta * normalizedDelta;
            float depthWeight = 1.0 / (1.0 + squaredDelta * squaredDelta);
            float weight = max(spatialX * spatialY, 0.0001) * depthWeight;

            vec2 sampleUv = (vec2(sampleCoord) + vec2(0.5)) / lowSizeF;
            shadowlessSum += texelFetch(ShadowlessSceneSampler, sampleCoord, 0) * weight;
            referenceSum += texture(SceneSampler, sampleUv).rgb * weight;
            weightSum += weight;
        }
    }

    confidence = 1.0 - smoothstep(depthTolerance * 2.0, depthTolerance * 8.0, minDepthDelta);
    if (weightSum <= 0.00001 || confidence <= 0.0) {
        confidence = 0.0;
        return vec4(0.0);
    }

    vec4 shadowlessAverage = shadowlessSum / weightSum;
    vec3 referenceAverage = referenceSum / weightSum;
    vec3 attenuation = clamp(
            (shadowlessAverage.rgb + colorEpsilon) / (referenceAverage + colorEpsilon),
            vec3(0.0),
            vec3(1.0));

    return vec4(sceneColor.rgb * attenuation, sceneColor.a);
}

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float sceneDepth = texture(DepthSampler, texCoord0).r;
    float depthDerivative = fwidth(sceneDepth);
    float rawMask = clamp(texture(ShadowMaskSampler, texCoord0).r, 0.0, 1.0);
    if (rawMask <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    vec4 scene = texture(SceneSampler, texCoord0);
    float confidence;
    vec4 shadowless = sampleDepthAwareShadowless(texCoord0, scene, sceneDepth, depthDerivative, confidence);
    float visible = pow(rawMask, 1.15) * confidence;
    fragColor = vec4(shadowless.rgb, visible);
}
