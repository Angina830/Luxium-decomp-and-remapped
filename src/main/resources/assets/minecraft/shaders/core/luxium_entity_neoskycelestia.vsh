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
uniform sampler2D LuxiumBlockOnlyLightSampler;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 LuxiumLightFromView0;
uniform mat4 LuxiumLightFromView1;
uniform mat4 LuxiumEntityLightFromView;
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform int LuxiumSkyShadowEnabled;
uniform int LuxiumSkyLightEnabled;
uniform int LuxiumEntityShadowEnabled;
uniform int LuxiumEntityShadowCasterPass;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
out float vertexDistance;
out float luxiumViewDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 skyOnlyLightMapColor;
out vec4 blockOnlyLightMapColor;
out vec4 zeroBlockOnlyLightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec3 luxiumShadowCoord0;
out vec3 luxiumShadowCoord1;
out vec3 luxiumEntityShadowCoord;
out vec3 luxiumNormal;
out vec3 btPosition;
out float btLevel;

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;
    texCoord0 = UV0;
    if (LuxiumEntityShadowCasterPass != 0) return;
    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    skyOnlyLightMapColor = texelFetch(Sampler2, ivec2(0, UV2.y / 16), 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    luxiumNormal = normalize(IViewRotMat * mat3(ModelViewMat) * Normal);
    btPosition = IViewRotMat * viewPosition.xyz;
    btLevel = clamp(float(UV2.x) / 240.0, 0.0, 1.0);
    if (LuxiumSkyShadowEnabled != 0 || LuxiumSkyLightEnabled != 0) {
        luxiumViewDistance = length(viewPosition.xyz);
        blockOnlyLightMapColor = texelFetch(LuxiumBlockOnlyLightSampler, UV2 / 16, 0);
        zeroBlockOnlyLightMapColor = texelFetch(
                LuxiumBlockOnlyLightSampler, ivec2(0, UV2.y / 16), 0);
        luxiumShadowCoord0 = (LuxiumLightFromView0 * viewPosition).xyz;
        luxiumShadowCoord1 = (LuxiumLightFromView1 * viewPosition).xyz;
        if (LuxiumEntityShadowEnabled != 0) {
            luxiumEntityShadowCoord = (LuxiumEntityLightFromView * viewPosition).xyz;
        } else {
            luxiumEntityShadowCoord = vec3(0.0);
        }
    } else {
        luxiumViewDistance = 0.0;
        blockOnlyLightMapColor = lightMapColor;
        zeroBlockOnlyLightMapColor = texelFetch(Sampler2, ivec2(0, UV2.y / 16), 0);
        luxiumShadowCoord0 = vec3(0.0);
        luxiumShadowCoord1 = vec3(0.0);
        luxiumEntityShadowCoord = vec3(0.0);
    }
}
