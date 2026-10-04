#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 texCoord0;
out float viewDepth;
out float hdrScale;

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;
    vertexColor = Color;
    texCoord0 = UV0;
    viewDepth = max(-viewPosition.z, 0.35);
    hdrScale = max(float(UV2.y) / 1024.0, 1.0);
}
