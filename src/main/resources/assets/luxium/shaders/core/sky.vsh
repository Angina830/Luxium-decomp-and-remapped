#version 150

in vec3 Position;

uniform mat4 ProjInverseMat;
uniform mat4 ModelViewInverseMat;

out vec3 viewRay;

void main() {
    gl_Position = vec4(Position, 1.0);

    vec4 clipPos = vec4(Position.xy, 1.0, 1.0);
    vec4 viewPos = ProjInverseMat * clipPos;
    viewPos /= viewPos.w;
    viewRay = (ModelViewInverseMat * vec4(viewPos.xyz, 0.0)).xyz;
}
