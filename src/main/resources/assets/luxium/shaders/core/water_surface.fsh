#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 water = texture(DiffuseSampler, texCoord);
    if (water.a <= 0.001) discard;
    gl_FragDepth = texture(DepthSampler, texCoord).r;
    fragColor = water;
}
