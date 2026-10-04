#version 150

#moj_import <fog.glsl>

in vec3  Position;
in vec4  Color;
in vec2  UV0;

in ivec2 UV1;
in ivec2 UV2;
in vec4  Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 ColorModulator;
uniform int  FogShape;

uniform float Time;
uniform float WindDirX;
uniform float WindDirZ;
uniform float CameraX;
uniform float CameraZ;

out float vertexDistance;
out vec4  vertexColor;
out vec2  texCoord0;
out vec2  texCoord2;
out vec4  normal;
out float sheen;

#define BLADE_HEIGHT     0.5
#define BLADE_HALF_WIDTH 0.021875
#define TWO_PI           6.2831853
#define HALF_PI          1.5707963
#define MAX_BEND         0.263
#define SHEEN_BEND_MIN   0.07
#define SHEEN_BEND_MAX   0.20
#define LEAN_MAX         0.115

const float K1          = 0.483322;
const float OMEGA1      = 1.111641;
const float AMP1        = 0.085;
const float K2          = 1.047198;
const float OMEGA2      = 3.560472;
const float AMP2        = 0.030;
const float K_GENV      = 0.285599;
const float OM_GENV     = 1.427997;
const float K_GCAR      = 0.869980;
const float OM_GCAR     = 4.175904;
const float AMP_GUST    = 0.13;
const float MICRO_OMEGA = 5.463267;
const float AMP_MICRO   = 0.018;
const vec2 PHASE_DIR    = vec2(0.796084, 0.605186);

void main() {

    float phi1     = float(UV1.x) * (TWO_PI / 32767.0);
    float sideSign = (UV1.y == 0) ? 1.0 : -1.0;
    float t        = 1.0 - UV0.y;
    float bendFactor = t * t;

    float bx = Position.x;
    float bz = Position.z;

    float leanAngle = phi1 * 1.732 + 0.61;
    float leanAmt = (0.35 + 0.65 * sin(phi1 * 2.417 + 1.73)) * LEAN_MAX;
    float leanX = cos(leanAngle) * leanAmt;
    float leanZ = sin(leanAngle) * leanAmt;

    vec2 toCamera = vec2(CameraX - bx, CameraZ - bz);
    float dist2d = max(length(toCamera), 0.001);
    float perpX = -toCamera.y / dist2d;
    float perpZ =  toCamera.x / dist2d;

    float billX = perpX * sideSign * BLADE_HALF_WIDTH;
    float billZ = perpZ * sideSign * BLADE_HALF_WIDTH;

    float dot1 = bx * PHASE_DIR.x + bz * PHASE_DIR.y;
    float dot2 = bx * (-PHASE_DIR.y) + bz * PHASE_DIR.x;

    float phi2 = phi1 * 1.5  + 1.1;
    float phiG = phi1 * 0.625;
    float phiM = phi1 * 3.25;

    float bend1 = sin(K1 * dot1 - OMEGA1 * Time + phi1) * AMP1;
    float bend2 = sin(K2 * dot2 - OMEGA2 * Time + phi2) * AMP2;

    float gustEnv = sin(K_GENV * dot1 - OM_GENV * Time + phiG);
    gustEnv = max(0.0, gustEnv);
    gustEnv = gustEnv * gustEnv * gustEnv;
    float bend3 = sin(K_GCAR * dot1 - OM_GCAR * Time + phi1 + 0.7) * AMP_GUST * gustEnv;

    float bend4 = sin(MICRO_OMEGA * Time + phiM) * AMP_MICRO;

    float totalBend = bend1 + bend2 + bend3 + bend4;

    float theta    = clamp(totalBend / MAX_BEND, -1.0, 1.0) * HALF_PI;
    float sinTheta = sin(theta);
    float cosTheta = cos(theta);

    float xDisplace = BLADE_HEIGHT * sinTheta * bendFactor;
    float yDisplace = BLADE_HEIGHT * (cosTheta - 1.0) * bendFactor;

    float bendAbs  = abs(totalBend);
    float sheenRaw = clamp((bendAbs - SHEEN_BEND_MIN) / (SHEEN_BEND_MAX - SHEEN_BEND_MIN),
                            0.0, 1.0);
    sheen = sheenRaw * sheenRaw;

    float staticLean = bendFactor * (0.35 + 0.65 * t);
    float worldX = bx + billX + xDisplace * WindDirX + leanX * staticLean;
    float worldY = Position.y + yDisplace;
    float worldZ = bz + billZ + xDisplace * WindDirZ + leanZ * staticLean;

    vec4 mvPos  = ModelViewMat * vec4(worldX, worldY, worldZ, 1.0);
    gl_Position = ProjMat * mvPos;

    if (FogShape == 0) {
        vertexDistance = length(mvPos.xyz);
    } else {
        vertexDistance = max(length(mvPos.xz), abs(mvPos.y));
    }
    float heightFade = 0.2 + 0.8 * (t * t);
    vertexColor    = Color * ColorModulator * vec4(vec3(heightFade), 1.0);
    texCoord0      = UV0;
    texCoord2      = vec2(UV2) / 65536.0;
    normal         = ModelViewMat * Normal;
}
