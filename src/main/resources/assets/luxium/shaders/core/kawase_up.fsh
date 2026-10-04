#version 150

uniform sampler2D LowSampler;
uniform sampler2D HighSampler;
uniform vec2 LowTexelSize;
uniform float Radius;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec2 h = LowTexelSize * Radius * 0.5;
    vec4 low = texture(LowSampler, texCoord0 + vec2(-2.0 * h.x, 0.0));
    low += texture(LowSampler, texCoord0 + vec2(-h.x, h.y)) * 2.0;
    low += texture(LowSampler, texCoord0 + vec2(0.0, 2.0 * h.y));
    low += texture(LowSampler, texCoord0 + vec2(h.x, h.y)) * 2.0;
    low += texture(LowSampler, texCoord0 + vec2(2.0 * h.x, 0.0));
    low += texture(LowSampler, texCoord0 + vec2(h.x, -h.y)) * 2.0;
    low += texture(LowSampler, texCoord0 + vec2(0.0, -2.0 * h.y));
    low += texture(LowSampler, texCoord0 + vec2(-h.x, -h.y)) * 2.0;
    fragColor = texture(HighSampler, texCoord0) + low / 12.0;
}
