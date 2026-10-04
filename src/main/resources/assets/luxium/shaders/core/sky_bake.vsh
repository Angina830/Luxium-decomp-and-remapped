#version 150

in vec3 Position;
uniform int CubeFace;
out vec3 viewRay;

void main() {
    gl_Position = vec4(Position, 1.0);

    vec2 st = Position.xy;
    if (CubeFace == 0)      viewRay = vec3( 1.0, -st.y, -st.x);
    else if (CubeFace == 1) viewRay = vec3(-1.0, -st.y,  st.x);
    else if (CubeFace == 2) viewRay = vec3( st.x,  1.0,  st.y);
    else if (CubeFace == 3) viewRay = vec3( st.x, -1.0, -st.y);
    else if (CubeFace == 4) viewRay = vec3( st.x, -st.y,  1.0);
    else                    viewRay = vec3(-st.x, -st.y, -1.0);
}
