#version 150

uniform sampler2D Sampler0;
uniform float EmissivePass;

in vec4 vertexColor;
in vec2 texCoord0;
in float viewDepth;
in float hdrScale;
out vec4 fragColor;

void main() {
    vec4 sprite = texture(Sampler0, texCoord0);
    float mask = sprite.a;
    if (mask < 0.1) discard;

    vec3 color = vertexColor.rgb * hdrScale;
    if (EmissivePass > 0.5) {
        float peak = min(max(max(color.r, color.g), color.b), 2.0);
        color *= 1.0 + 0.22 * peak;
    }
    float distanceFade = clamp(1.35 - viewDepth * 0.008, 0.65, 1.0);
    fragColor = vec4(color, vertexColor.a * mask * distanceFade);
}
