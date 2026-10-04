#version 150

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 InverseProjMat;
uniform mat4 InverseViewMat;

out vec2 texCoord0;
out vec4 rayBase;
flat out vec4 rayDepthDelta;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;

    vec2 ndcXY = UV0 * 2.0 - 1.0;
    vec4 viewBase = InverseProjMat * vec4(ndcXY, 0.0, 1.0);
    vec4 viewDepthDelta = InverseProjMat * vec4(0.0, 0.0, 1.0, 0.0);
    rayBase = vec4((InverseViewMat * vec4(viewBase.xyz, 0.0)).xyz, viewBase.w);
    rayDepthDelta = vec4(
        (InverseViewMat * vec4(viewDepthDelta.xyz, 0.0)).xyz,
        viewDepthDelta.w
    );
}
