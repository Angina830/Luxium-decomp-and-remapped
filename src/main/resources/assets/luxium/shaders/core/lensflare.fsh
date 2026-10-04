#version 150

uniform vec2 SunScreenPos;
uniform vec3 LightColor;
uniform float ScreenFade;

uniform float UseMoonStyle;
uniform vec2 ScreenSize;
uniform float FlareIntensity;
uniform float StreakIntensity;
uniform float StreakLength;
uniform float StreakWidth;
uniform float ChromaticSpread;
uniform float GhostIntensity;
uniform float GhostSize;
uniform float FlareSpread;

in vec2 texCoord0;
flat in float sunVisibility;
out vec4 fragColor;

vec3 renderLensFlare(vec2 uv, vec2 sunPos, float aspect, bool useMoonStyle) {
    vec2 centerUV = uv - vec2(0.5);
    centerUV.x *= aspect;
    vec2 centerSun = sunPos - vec2(0.5);
    centerSun.x *= aspect;
    vec2 uvd = centerUV * length(centerUV);
    float chromaStep = 0.05 * ChromaticSpread;
    float invGhostScale = 1.0 / max(GhostSize, 0.001);
    vec2 csSpread = FlareSpread * centerSun * invGhostScale;
    vec3 ghosts = vec3(0.0);

    vec2 base2 = uvd * invGhostScale + 0.80 * csSpread;
    vec2 step2 = chromaStep * csSpread;
    ghosts.r += max(1.0 / (1.0 + 32.0 * dot(base2, base2)), 0.0) * 0.25;
    ghosts.g += max(1.0 / (1.0 + 32.0 * dot(base2 + step2, base2 + step2)), 0.0) * 0.23;
    ghosts.b += max(1.0 / (1.0 + 32.0 * dot(base2 + step2 * 2.0, base2 + step2 * 2.0)), 0.0) * 0.21;

    vec2 uvx4 = mix(centerUV, uvd, -0.5) * invGhostScale;
    vec2 base4 = uvx4 + 0.40 * csSpread;
    vec2 step4 = chromaStep * csSpread;
    ghosts.r += max(0.01 - dot(base4, base4) * 0.46, 0.0) * 6.0;
    ghosts.g += max(0.01 - dot(base4 + step4, base4 + step4) * 0.46, 0.0) * 5.0;
    ghosts.b += max(0.01 - dot(base4 + step4 * 2.0, base4 + step4 * 2.0) * 0.46, 0.0) * 3.0;

    vec2 uvx5 = mix(centerUV, uvd, -0.4) * invGhostScale;
    vec2 base5 = uvx5 + 0.20 * csSpread;
    vec2 step5 = chromaStep * 4.0 * csSpread;
    float d5_1 = dot(base5, base5);
    float d5_2 = dot(base5 + step5, base5 + step5);
    float d5_3 = dot(base5 + step5 * 2.0, base5 + step5 * 2.0);
    ghosts.r += max(0.01 - d5_1 * d5_1 * 0.28, 0.0) * 2.0;
    ghosts.g += max(0.01 - d5_2 * d5_2 * 0.28, 0.0) * 2.0;
    ghosts.b += max(0.01 - d5_3 * d5_3 * 0.28, 0.0) * 2.0;

    vec2 uvx6 = mix(centerUV, uvd, -0.5) * invGhostScale;
    vec2 base6 = uvx6 - 0.30 * csSpread;
    vec2 step6 = chromaStep * 0.5 * csSpread;
    ghosts.r += max(0.01 - sqrt(dot(base6, base6)) * 0.18, 0.0) * 6.0;
    ghosts.g += max(0.01 - sqrt(dot(base6 - step6, base6 - step6)) * 0.18, 0.0) * 3.0;
    ghosts.b += max(0.01 - sqrt(dot(base6 - step6 * 2.0, base6 - step6 * 2.0)) * 0.18, 0.0) * 5.0;
    ghosts *= (useMoonStyle ? vec3(0.5, 0.7, 1.2) : vec3(1.4, 1.2, 1.0)) * GhostIntensity;

    float streak = 0.0;
    vec2 anamorphicUV = centerUV - centerSun;
    float streakY = abs(anamorphicUV.y);
    float streakWidthParam = 0.02 * StreakWidth;
    if (streakY < streakWidthParam) {
        float streakX = abs(anamorphicUV.x);
        float streakLenParam = 1.5 * StreakLength;
        if (streakX < streakLenParam) {
            float anamorphicFlare = (1.0 - smoothstep(0.0, streakWidthParam, streakY))
                    * (1.0 - smoothstep(0.0, streakLenParam, streakX));
            streak = anamorphicFlare * ((useMoonStyle ? 0.1 : 0.4) * StreakIntensity);
        }
    }
    return (ghosts + LightColor * streak) * ((useMoonStyle ? 0.25 : 0.7) * FlareIntensity);
}

void main() {
    float flareMask = sunVisibility * smoothstep(0.0, 0.2, ScreenFade);
    if (ScreenFade <= 0.001 || flareMask <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }
    bool useMoonStyle = UseMoonStyle > 0.5;
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    vec3 outputColor = renderLensFlare(texCoord0, SunScreenPos, aspect, useMoonStyle) * flareMask;
    if (useMoonStyle) outputColor *= 1.15;
    fragColor = vec4(outputColor, 0.0);
}
