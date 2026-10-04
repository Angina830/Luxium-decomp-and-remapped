#version 150

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 InverseProjMat;
uniform mat4 InverseViewMat;
uniform sampler2D DepthSampler;
uniform sampler2D CloudOcclusionSampler;
uniform float UseCloudOcclusion;
uniform vec2 SunScreenPos;
uniform vec2 ScreenSize;
uniform int RenderCelestialVisibility;

out vec2 texCoord0;
out vec4 rayBase;
flat out vec4 rayDepthDelta;
out vec4 viewRayBase;
flat out vec4 viewRayDepthDelta;
flat out float sunVisibility;

float cloudTransmission(vec2 uv) {
    if (UseCloudOcclusion < 0.5) return 1.0;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return 1.0;
    return clamp(1.0 - textureLod(CloudOcclusionSampler, uv, 0.0).a, 0.0, 1.0);
}

float celestialVisibilitySample(vec2 uv) {
    return step(0.99999, textureLod(DepthSampler, uv, 0.0).r) * cloudTransmission(uv);
}

float getSunVisibility(vec2 sunPos, float aspect) {
    if (sunPos.x < -0.1 || sunPos.x > 1.1 || sunPos.y < -0.1 || sunPos.y > 1.1) return 0.0;

    vec2 rScale = vec2(1.0 / aspect, 1.0) * 0.05;
    float c = celestialVisibilitySample(sunPos);
    float u = celestialVisibilitySample(sunPos + vec2(0.0, 1.0) * rScale);
    float d = celestialVisibilitySample(sunPos + vec2(0.0, -1.0) * rScale);
    float l = celestialVisibilitySample(sunPos + vec2(-1.0, 0.0) * rScale);
    float r = celestialVisibilitySample(sunPos + vec2(1.0, 0.0) * rScale);
    float ul = celestialVisibilitySample(sunPos + vec2(-0.7, 0.7) * rScale);
    float ur = celestialVisibilitySample(sunPos + vec2(0.7, 0.7) * rScale);
    float dl = celestialVisibilitySample(sunPos + vec2(-0.7, -0.7) * rScale);
    float dr = celestialVisibilitySample(sunPos + vec2(0.7, -0.7) * rScale);
    float sum9 = c + u + d + l + r + ul + ur + dl + dr;
    if (sum9 > 8.9) return 1.0;
    if (sum9 < 0.1) return 0.0;

    float visibility = 0.0;
    const int SAMPLES_VIS = 64;
    for (int i = 0; i < SAMPLES_VIS; i++) {
        float radius = sqrt(float(i) + 0.5) / 8.0;
        float theta = float(i) * 2.39996323;
        vec2 offset = vec2(cos(theta), sin(theta)) * radius;
        visibility += celestialVisibilitySample(sunPos + offset * rScale);
    }

    return smoothstep(0.0, 1.0, visibility / float(SAMPLES_VIS));
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;

    vec2 ndcXY = UV0 * 2.0 - 1.0;
    vec4 viewBase = InverseProjMat * vec4(ndcXY, 0.0, 1.0);
    vec4 viewDepthDelta = InverseProjMat * vec4(0.0, 0.0, 1.0, 0.0);
    viewRayBase = viewBase;
    viewRayDepthDelta = viewDepthDelta;
    rayBase = vec4((InverseViewMat * vec4(viewBase.xyz, 0.0)).xyz, viewBase.w);
    rayDepthDelta = vec4(
        (InverseViewMat * vec4(viewDepthDelta.xyz, 0.0)).xyz,
        viewDepthDelta.w
    );

    if (RenderCelestialVisibility != 0) {
        float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
        sunVisibility = getSunVisibility(SunScreenPos, aspect);
    } else {
        sunVisibility = 0.0;
    }
}
