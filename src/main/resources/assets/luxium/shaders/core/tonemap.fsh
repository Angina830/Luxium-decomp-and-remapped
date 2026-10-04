#version 150

uniform sampler2D SceneSampler;
uniform sampler2D EffectsSampler;
uniform int CompositeEffects;
uniform float TonemapExposure;
uniform float TonemapContrast;
uniform float TonemapHighlightCompression;
uniform float TonemapShadowDepth;
uniform float TonemapSaturation;
uniform float TonemapVibrance;
uniform float TonemapGamma;
uniform float TonemapStrength;
uniform int LsrEnabled;
uniform vec2 SceneTexelSize;
uniform float LsrSharpness;

in vec2 texCoord0;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 sampleSpatialScene(vec2 uv) {
    vec3 center = texture(SceneSampler, uv).rgb;
    if (LsrEnabled == 0 || LsrSharpness <= 0.0001) return center;

    vec2 t = SceneTexelSize;
    vec3 left  = texture(SceneSampler, clamp(uv - vec2(t.x, 0.0), vec2(0.0), vec2(1.0))).rgb;
    vec3 right = texture(SceneSampler, clamp(uv + vec2(t.x, 0.0), vec2(0.0), vec2(1.0))).rgb;
    vec3 up    = texture(SceneSampler, clamp(uv - vec2(0.0, t.y), vec2(0.0), vec2(1.0))).rgb;
    vec3 down  = texture(SceneSampler, clamp(uv + vec2(0.0, t.y), vec2(0.0), vec2(1.0))).rgb;

    vec3 average = (left + right + up + down) * 0.25;
    float lc = dot(center, LUMA);
    float lMin = min(lc, min(min(dot(left, LUMA), dot(right, LUMA)), min(dot(up, LUMA), dot(down, LUMA))));
    float lMax = max(lc, max(max(dot(left, LUMA), dot(right, LUMA)), max(dot(up, LUMA), dot(down, LUMA))));
    float localContrast = lMax - lMin;
    float adaptive = mix(0.35, 1.0, smoothstep(0.015, 0.18, localContrast));
    float gain = clamp(LsrSharpness, 0.0, 1.0) * adaptive * 0.70;
    vec3 sharpened = center + (center - average) * gain;

    vec3 lo = min(center, min(min(left, right), min(up, down)));
    vec3 hi = max(center, max(max(left, right), max(up, down)));
    vec3 guard = vec3(0.015 + localContrast * 0.04);
    return clamp(sharpened, lo - guard, hi + guard);
}

vec3 applyFilmicTonemap(vec3 inputColor) {
    vec3 original = clamp(inputColor, 0.0, 1.0);
    if (TonemapStrength <= 0.0001) return original;

    float gammaValue = max(TonemapGamma, 1.0);
    vec3 linearColor = pow(max(inputColor, vec3(0.0)), vec3(gammaValue));
    linearColor *= exp2(TonemapExposure);
    float luminance = max(dot(linearColor, LUMA), 1.0e-6);
    float toeMask = 1.0 - smoothstep(0.025, 0.34, luminance);
    linearColor *= 1.0 - toeMask * TonemapShadowDepth * 0.30;
    luminance = max(dot(linearColor, LUMA), 1.0e-6);

    float whitePoint = mix(1.75, 7.5, TonemapHighlightCompression);
    float mappedLuma = luminance * (1.0 + luminance / (whitePoint * whitePoint));
    mappedLuma /= 1.0 + luminance;
    mappedLuma = clamp(mappedLuma, 0.0, 1.0);
    float sCurve = mappedLuma * mappedLuma * (3.0 - 2.0 * mappedLuma);
    mappedLuma = mix(mappedLuma, sCurve, TonemapContrast);

    vec3 mapped = linearColor * (mappedLuma / luminance);
    float mappedLum = dot(mapped, LUMA);
    mapped = mix(vec3(mappedLum), mapped, TonemapSaturation);
    float maxChannel = max(mapped.r, max(mapped.g, mapped.b));
    float minChannel = min(mapped.r, min(mapped.g, mapped.b));
    float chroma = (maxChannel - minChannel) / max(maxChannel, 1.0e-4);
    float vibranceGain = 1.0 + TonemapVibrance
            * (1.0 - clamp(chroma, 0.0, 1.0)) * 0.75;
    mapped = mix(vec3(dot(mapped, LUMA)), mapped, vibranceGain);
    vec3 encoded = pow(clamp(mapped, 0.0, 1.0), vec3(1.0 / gammaValue));
    return mix(original, encoded, TonemapStrength);
}

void main() {
    vec3 scene = sampleSpatialScene(texCoord0);
    if (CompositeEffects != 0) {
        vec4 effects = texture(EffectsSampler, texCoord0);
        scene = scene * (1.0 - effects.a) + effects.rgb;
    }
    vec3 outputColor = applyFilmicTonemap(scene);
    fragColor = vec4(clamp(outputColor, 0.0, 1.0), 1.0);
}
