#version 150

uniform sampler2D SceneSampler;
uniform sampler2D SceneDepth;
uniform vec2 SourceTexelSize;
uniform float LsrSharpness;

in vec2 texCoord0;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec2 safeUv(vec2 uv) {
    vec2 inset = max(SourceTexelSize * 0.55, vec2(0.00005));
    return clamp(uv, inset, vec2(1.0) - inset);
}

void main() {
    vec2 uv = safeUv(texCoord0);
    vec3 center = texture(SceneSampler, uv).rgb;
    vec3 color = center;

    if (LsrSharpness > 0.0001) {
        vec2 t = SourceTexelSize;
        vec3 left  = texture(SceneSampler, safeUv(uv - vec2(t.x, 0.0))).rgb;
        vec3 right = texture(SceneSampler, safeUv(uv + vec2(t.x, 0.0))).rgb;
        vec3 up    = texture(SceneSampler, safeUv(uv - vec2(0.0, t.y))).rgb;
        vec3 down  = texture(SceneSampler, safeUv(uv + vec2(0.0, t.y))).rgb;
        vec3 cross = (left + right + up + down) * 0.25;

        float lc = dot(center, LUMA);
        float lMin = min(lc, min(min(dot(left, LUMA), dot(right, LUMA)),
                                 min(dot(up, LUMA), dot(down, LUMA))));
        float lMax = max(lc, max(max(dot(left, LUMA), dot(right, LUMA)),
                                 max(dot(up, LUMA), dot(down, LUMA))));
        float localContrast = lMax - lMin;
        float adaptive = mix(0.35, 1.0, smoothstep(0.015, 0.18, localContrast));
        float gain = clamp(LsrSharpness, 0.0, 1.0) * adaptive * 0.70;
        vec3 sharpened = center + (center - cross) * gain;

        vec3 lo = min(center, min(min(left, right), min(up, down)));
        vec3 hi = max(center, max(max(left, right), max(up, down)));
        vec3 guard = vec3(0.015 + localContrast * 0.04);
        color = clamp(sharpened, lo - guard, hi + guard);
    }

    float depth = texture(SceneDepth, uv).r;
    float depthGradient = abs(dFdx(depth)) + abs(dFdy(depth));
    float edge = smoothstep(0.00030, 0.0055, depthGradient);

    fragColor = vec4(clamp(color, 0.0, 1.0), edge);
}
