#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform mat4 ViewProj;
uniform mat4 InverseViewProj;
uniform vec3 CameraPos;
uniform vec3 SphereCenter;
uniform float SphereRadius;
uniform float Intensity;
uniform float Time;
uniform vec2 InSize;
uniform vec2 OutSize;

in vec2 texCoord;
out vec4 fragColor;

#define DISTORTION_STRENGTH 0.25
#define EDGE_SHARPNESS 4.0
#define CHROMATIC_ABERRATION 0.025
#define EDGE_DARKENING 0.4

#define JITTER_SCALE 35.0
#define JITTER_SPEED 20.0
#define JITTER_STRENGTH 0.15

float hash(vec3 p) {
    p = fract(p * 0.3183099 + .1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i + vec3(0,0,0)), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float f = 0.0;
    float amp = 0.5;
    for(int i = 0; i < 3; i++) {
        f += amp * noise(p);
        p *= 2.0;
        amp *= 0.5;
    }
    return f;
}

void main() {
    vec4 baseColor = texture(DiffuseSampler, texCoord);

    if (Intensity <= 0.001 || SphereRadius <= 0.001) {
        fragColor = baseColor;
        return;
    }

    vec4 ndcFar = vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec4 relFar = InverseViewProj * ndcFar;
    vec3 rayDir = normalize(relFar.xyz / relFar.w);

    float sceneDepth = texture(DepthSampler, texCoord).r;
    bool isSky = sceneDepth > 0.99999;
    vec4 sceneClip = vec4(texCoord * 2.0 - 1.0, sceneDepth * 2.0 - 1.0, 1.0);
    vec4 sceneRel = InverseViewProj * sceneClip;
    float sceneDist = length(sceneRel.xyz / sceneRel.w);

    vec3 oc = CameraPos - SphereCenter;
    float b = dot(oc, rayDir);
    float c = dot(oc, oc) - SphereRadius * SphereRadius;
    float h = b * b - c;

    if (h <= 0.0) {
        fragColor = baseColor;
        return;
    }

    float sqrtH = sqrt(h);
    float t1 = -b - sqrtH;
    float t2 = -b + sqrtH;

    float hitDist = t1;
    bool isInside = false;

    if (t1 < 0.0) {
        hitDist = t2;
        isInside = true;
        if (t2 < 0.0) {
            fragColor = baseColor;
            return;
        }
    }

    float depthDiff = isSky ? 1000.0 : (sceneDist - hitDist);
    if (depthDiff <= 0.0) {
        fragColor = baseColor;
        return;
    }
    float depthFade = smoothstep(0.0, SphereRadius * 0.2, depthDiff);

    vec3 hitPos = CameraPos + rayDir * hitDist;
    vec3 normal = normalize(hitPos - SphereCenter);
    if (isInside) {
        normal = -normal;
    }

    float facing = abs(dot(-rayDir, normal));
    float edgeFactor = 1.0 - facing;

    float compressionRing = pow(edgeFactor, EDGE_SHARPNESS);

    vec3 noisePos = hitPos * JITTER_SCALE - vec3(0.0, Time * JITTER_SPEED, 0.0);

    float jitterX = (fbm(noisePos) - 0.5) * 2.0;
    float jitterY = (fbm(noisePos + vec3(15.2, -22.4, 31.8)) - 0.5) * 2.0;
    vec2 turbulence = vec2(jitterX, jitterY) * JITTER_STRENGTH;

    vec4 viewSpaceNormal = ViewProj * vec4(normal, 0.0);
    vec2 screenNormal = normalize(viewSpaceNormal.xy + vec2(0.0001));

    vec2 distortionVector = (screenNormal * compressionRing) + (turbulence * compressionRing);
    vec2 finalUVOffset = distortionVector * DISTORTION_STRENGTH * Intensity * depthFade;

    vec2 uv = texCoord;
    vec2 uvR = uv + finalUVOffset * (1.0 + CHROMATIC_ABERRATION);
    vec2 uvG = uv + finalUVOffset * 1.0;
    vec2 uvB = uv + finalUVOffset * (1.0 - CHROMATIC_ABERRATION);

    uvR = clamp(uvR, 0.0, 1.0);
    uvG = clamp(uvG, 0.0, 1.0);
    uvB = clamp(uvB, 0.0, 1.0);

    float colR = texture(DiffuseSampler, uvR).r;
    float colG = texture(DiffuseSampler, uvG).g;
    float colB = texture(DiffuseSampler, uvB).b;
    vec3 finalColor = vec3(colR, colG, colB);

    float darkenRing = compressionRing * EDGE_DARKENING * Intensity;
    finalColor *= (1.0 - darkenRing);

    finalColor += finalColor * abs(jitterX) * compressionRing * 0.2 * Intensity;

    fragColor = vec4(finalColor, 1.0);
}
