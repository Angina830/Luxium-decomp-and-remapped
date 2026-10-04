#version 150

in vec3 Position;
in vec2 UV0;

uniform mat4 ViewProj;
uniform vec3 CameraPos;

out vec2 puddleUv;
out vec3 worldPos;

void main() {
    puddleUv = UV0;
    worldPos = Position + CameraPos;
    gl_Position = ViewProj * vec4(Position, 1.0);
}
