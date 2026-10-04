#version 150

uniform sampler2D GlowSampler;
uniform sampler2D DepthSampler;
uniform mat4 InverseProjection;
uniform vec2 GlowSize;
uniform float Intensity;
uniform float DepthTolerance;
uniform int DepthOcclusion;
in vec2 texCoord0;
out vec4 fragColor;

float viewDepth(vec2 uv, float depth) {
    vec4 view = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return abs(view.z / max(abs(view.w), 1.0e-6));
}

vec3 acceptedGlow(vec2 uv, float receiverDepth) {
    vec4 glow = texture(GlowSampler, uv);
    float weight = dot(glow.rgb, vec3(0.2126, 0.7152, 0.0722));
    if (weight <= 1.0e-5 || DepthOcclusion == 0) return glow.rgb;
    float sourceDepth = glow.a / weight;
    return receiverDepth + DepthTolerance >= sourceDepth ? glow.rgb : vec3(0.0);
}

void main() {
    float receiverDepth = viewDepth(texCoord0, texture(DepthSampler, texCoord0).r);
    vec2 texel = 1.0 / max(GlowSize, vec2(1.0));
    vec3 glow = acceptedGlow(texCoord0, receiverDepth) * 4.0;
    glow += acceptedGlow(texCoord0 + vec2( texel.x, 0.0), receiverDepth);
    glow += acceptedGlow(texCoord0 + vec2(-texel.x, 0.0), receiverDepth);
    glow += acceptedGlow(texCoord0 + vec2(0.0,  texel.y), receiverDepth);
    glow += acceptedGlow(texCoord0 + vec2(0.0, -texel.y), receiverDepth);
    fragColor = vec4(glow * (Intensity / 8.0), 0.0);
}
