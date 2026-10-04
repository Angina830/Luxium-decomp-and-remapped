#version 150

uniform sampler2D SceneSampler;
uniform vec2 SourceTexelSize;
uniform float LsrSharpness;

in vec2 texCoord0;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 reconstructScene(vec2 uv) {
    vec3 center = texture(SceneSampler, uv).rgb;
    if (LsrSharpness <= 0.0001) return center;

    vec2 t = SourceTexelSize;
    vec3 left  = texture(SceneSampler, clamp(uv - vec2(t.x, 0.0), vec2(0.0), vec2(1.0))).rgb;
    vec3 right = texture(SceneSampler, clamp(uv + vec2(t.x, 0.0), vec2(0.0), vec2(1.0))).rgb;
    vec3 up    = texture(SceneSampler, clamp(uv - vec2(0.0, t.y), vec2(0.0), vec2(1.0))).rgb;
    vec3 down  = texture(SceneSampler, clamp(uv + vec2(0.0, t.y), vec2(0.0), vec2(1.0))).rgb;

    vec3 average = (left + right + up + down) * 0.25;
    float lc = dot(center, LUMA);
    float lMin = min(lc, min(min(dot(left, LUMA), dot(right, LUMA)), min(dot(up, LUMA), dot(down, LUMA))));
    float lMax = max(lc, max(max(dot(left, LUMA), dot(right, LUMA)), max(dot(up, LUMA), dot(down, LUMA))));
    float localContrast = lMax - lMin;

    float adaptive = mix(0.35, 1.0, smoothstep(0.015, 0.18, localContrast));
    float gain = clamp(LsrSharpness, 0.0, 1.0) * adaptive * 0.70;
    vec3 sharpened = center + (center - average) * gain;

    vec3 lo = min(center, min(min(left, right), min(up, down)));
    vec3 hi = max(center, max(max(left, right), max(up, down)));
    vec3 guard = vec3(0.015 + localContrast * 0.04);
    return clamp(sharpened, lo - guard, hi + guard);
}

void main() {
    vec3 color = reconstructScene(texCoord0);
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
