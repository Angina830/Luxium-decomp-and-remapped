#version 150
#moj_import <light.glsl>
#moj_import <fog.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
out vec2 btUv;
out vec4 btTint;
out vec4 btOverlay;
out vec3 btSky;
out float btLevel;
out vec3 btPosition;
out vec3 btNormal;
out float btDistance;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    btUv = UV0;
    btTint = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    btOverlay = texelFetch(Sampler1, UV1, 0);
    btSky = texelFetch(Sampler2, ivec2(0, UV2.y / 16), 0).rgb;
    btLevel = clamp(float(UV2.x) / 240.0, 0.0, 1.0);
    btPosition = IViewRotMat * view.xyz;
    btNormal = normalize(IViewRotMat * mat3(ModelViewMat) * Normal);
    btDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
}
