#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 VirtualVPMat;
uniform vec4 ColorModulator;
uniform vec3 CameraOffset;
uniform vec3 PlayerOffset;
uniform vec3 FaceNormal;
uniform float DistanceScale;
uniform float ReflectionDistance;
uniform float NestedPass;

out vec4 vertexColor;
out vec2 vFaceUV;
out vec4 vVirtualClip;
out float vReflectionAlpha;
out float vMaterialKind;
out vec3 vPlayerRelativePosition;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = vec4(ColorModulator.rgb, 1.0);
    vFaceUV = UV0;
    vVirtualClip = VirtualVPMat * vec4(Position, 1.0);
    float baseAlpha = Color.r;
    float maxDistance = max(Color.g * 64.0, 0.001);
    float mask = Color.b;
    vec3 toCamera = -(CameraOffset + Position);
    float rawDistance = length(toCamera);
    float distanceToCamera = rawDistance * DistanceScale;
    float fade = clamp(1.0 - distanceToCamera / maxDistance, 0.0, 1.0);
    float directAlpha = baseAlpha * fade;
    vMaterialKind = Color.a;
    if (Color.a < 0.75 && rawDistance > 0.0001) {
        float fresnelLike = 1.0 - abs(dot(normalize(FaceNormal), normalize(toCamera)));
        float closeRangeFade = 0.45 + 0.55 * smoothstep(1.25, 3.75, rawDistance);
        directAlpha *= (0.86 + 0.14 * fresnelLike) * closeRangeFade;
    }
    float nestedAlpha = min(1.0, baseAlpha * 1.3);
    vReflectionAlpha = mix(directAlpha, nestedAlpha, NestedPass) * mask;
    vPlayerRelativePosition = PlayerOffset + Position;
}
