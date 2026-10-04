#version 150

uniform sampler2D DepthSampler;
uniform sampler2D LightVolumeSampler;
uniform mat4 InverseProj;
uniform vec3 CameraPos;
uniform vec3 CamForward;
uniform vec3 CamUp;
uniform vec3 CamRight;
uniform vec3 VolumeMin;
uniform vec3 VolumeSize;
uniform vec3 VolumeDim;
uniform vec3 RayColor;
uniform float GameTime;
uniform float EffectStrength;
uniform vec2 ScreenSize;

in vec2 texCoord0;
out vec4 fragColor;

float sampleLutVolume(vec3 uvw) {
    float sizeX  = VolumeDim.x;
    float sizeY  = VolumeDim.y;
    float sizeZ  = VolumeDim.z;
    float atlasW = sizeX * sizeZ;

    float vx = uvw.x * (sizeX - 1.0);
    float vy = uvw.y * (sizeY - 1.0);
    float vz = uvw.z * (sizeZ - 1.0);

    float slice0 = floor(vz);
    float slice1 = min(slice0 + 1.0, sizeZ - 1.0);
    float frac   = vz - slice0;

    float v  = (vy + 0.5) / sizeY;
    float u0 = (vx + slice0 * sizeX + 0.5) / atlasW;
    float u1 = (vx + slice1 * sizeX + 0.5) / atlasW;

    return mix(
        texture(LightVolumeSampler, vec2(u0, v)).r,
        texture(LightVolumeSampler, vec2(u1, v)).r,
        frac
    );
}

vec3 reconstructView(vec2 uv, float depth) {
    vec4 ndc  = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = InverseProj * ndc;
    return view.xyz / max(view.w, 1e-6);
}

float interleavedGradientNoise(vec2 uv) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);

    return fract(magic.z * fract(dot(uv, magic.xy)));
}

void main() {
    float depth     = texture(DepthSampler, texCoord0).r;
    vec3  rayView   = reconstructView(texCoord0, depth);
    float rayLength = length(rayView);

    bool  isSky       = depth > 0.99999;
    float maxDistance = isSky ? 36.0 : min(rayLength, 36.0);

    if (maxDistance < 0.1 || rayLength < 1e-4) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 dir = normalize(
        CamRight * rayView.x +
        CamUp * rayView.y -
        CamForward * rayView.z
    );

    vec3  invDir = 1.0 / dir;
    vec3  tBot   = (VolumeMin            - CameraPos) * invDir;
    vec3  tTop   = (VolumeMin + VolumeSize - CameraPos) * invDir;
    vec3  tMin3  = min(tBot, tTop);
    vec3  tMax3  = max(tBot, tTop);
    float tEnter = max(max(tMin3.x, tMin3.y), max(tMin3.z, 0.0));
    float tExit  = min(min(tMax3.x, tMax3.y), min(tMax3.z, maxDistance));

    if (tEnter >= tExit) {
        fragColor = vec4(0.0);
        return;
    }

    const int STEPS = 24;
    float marchDist  = tExit - tEnter;
    float stepLength = marchDist / float(STEPS);

    float ditherOffset = interleavedGradientNoise(texCoord0 * ScreenSize);

    vec3 invVolumeSize = 1.0 / VolumeSize;
    vec3 uvwStep       = dir * stepLength * invVolumeSize;
    vec3 uvwPos        = (CameraPos + dir * (tEnter + ditherOffset * stepLength) - VolumeMin) * invVolumeSize;

    float accumulation = 0.0;

    for (int i = 0; i < STEPS; i++) {
        float lightLevel = sampleLutVolume(uvwPos);
        accumulation    += lightLevel * lightLevel;
        uvwPos          += uvwStep;
    }

    accumulation *= stepLength;

    float animated = 0.95 + 0.05 * sin(GameTime * 0.05 + texCoord0.x * 10.0);
    float energy   = accumulation * EffectStrength * 0.16 * animated;

    fragColor = vec4(RayColor * energy, 0.0);
}
