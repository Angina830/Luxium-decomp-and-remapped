#version 150

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec2 Center;
uniform float ScaleAmount;

out vec2 texCoord0;

void main() {
    vec2 scaled = Center + (Position.xy - Center) * ScaleAmount;
    gl_Position = ProjMat * ModelViewMat * vec4(scaled, Position.z, 1.0);
    texCoord0 = UV0;
}
