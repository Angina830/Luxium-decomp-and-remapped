#version 150

uniform sampler2D InputSampler;
uniform vec2 InputTexelSize;
uniform float Radius;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec2 offset = InputTexelSize * Radius;
    vec4 color = texture(InputSampler, texCoord0) * 4.0;
    color += texture(InputSampler, texCoord0 + vec2(-offset.x, -offset.y));
    color += texture(InputSampler, texCoord0 + vec2( offset.x, -offset.y));
    color += texture(InputSampler, texCoord0 + vec2(-offset.x,  offset.y));
    color += texture(InputSampler, texCoord0 + vec2( offset.x,  offset.y));
    fragColor = color * 0.125;
}
