#version 150

uniform sampler2D DepthSampler;
uniform mat4 InverseProj;
uniform float LightRadius;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float zDepth = texture(DepthSampler, texCoord0).r;

    if (zDepth >= 1.0) {
        fragColor = vec4(1.0);
        return;
    }

    vec4 ndc = vec4(texCoord0 * 2.0 - 1.0, zDepth * 2.0 - 1.0, 1.0);
    vec4 view = InverseProj * ndc;
    view /= view.w;

    float dist = length(view.xyz) / LightRadius;

    fragColor = vec4(dist, dist, dist, 1.0);
}
