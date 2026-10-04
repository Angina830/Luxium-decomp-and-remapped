#version 150

in vec3 viewRay;

uniform vec3 SunDir;
uniform int CachePass;
uniform int CubeFace;
uniform int CacheFaceSize;

out vec4 fragColor;

const float PI  = 3.14159265358979323846;
const float TAU = 6.28318530717958647692;

const float CLOUD_COVERAGE       = 0.56;
const float CLOUD_OPACITY        = 0.94;
const float CLOUD_SCALE          = 0.92;
const float CIRRUS_AMOUNT        = 0.36;
const float STAR_INTENSITY       = 1.10;
const float MILKY_WAY_INTENSITY  = 0.34;
const float SUN_INTENSITY        = 11.0;
const float MOON_INTENSITY       = 1.15;

float saturate(float x) { return clamp(x, 0.0, 1.0); }
vec2  saturate(vec2 x)  { return clamp(x, 0.0, 1.0); }
vec3  saturate(vec3 x)  { return clamp(x, 0.0, 1.0); }

float sqr(float x) { return x * x; }

vec3 safeNormalize(vec3 v, vec3 fallback) {
    float l2 = dot(v, v);
    return (l2 > 1e-10) ? v * inversesqrt(l2) : fallback;
}

vec2 safeNormalize2(vec2 v, vec2 fallback) {
    float l2 = dot(v, v);
    return (l2 > 1e-10) ? v * inversesqrt(l2) : fallback;
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 tonemapACES(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return saturate((x * (a * x + b)) / (x * (c * x + d) + e));
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 hash33(vec3 p3) {
    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yxx) * p3.zyx);
}

float valueNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    float a = hash12(i + vec2(0.0, 0.0));
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float valueNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);

    float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));

    float nx00 = mix(n000, n100, u.x);
    float nx10 = mix(n010, n110, u.x);
    float nx01 = mix(n001, n101, u.x);
    float nx11 = mix(n011, n111, u.x);
    float nxy0 = mix(nx00, nx10, u.y);
    float nxy1 = mix(nx01, nx11, u.y);
    return mix(nxy0, nxy1, u.z);
}

float fbm2(vec2 p) {
    float f = 0.0;
    float a = 0.52;
    mat2 r = mat2(0.80, -0.60,
                  0.60,  0.80);

    for (int i = 0; i < 6; ++i) {
        f += a * valueNoise2(p);
        p = r * p * 2.03 + vec2(13.17, -7.41);
        a *= 0.50;
    }
    return f;
}

float fbm2Fast(vec2 p) {
    float f = 0.0;
    float a = 0.56;
    mat2 r = mat2(0.8660254, -0.5,
                  0.5,        0.8660254);

    for (int i = 0; i < 4; ++i) {
        f += a * valueNoise2(p);
        p = r * p * 2.07 + vec2(-5.73, 9.17);
        a *= 0.48;
    }
    return f;
}

float fbm3(vec3 p) {
    float f = 0.0;
    float a = 0.53;

    for (int i = 0; i < 5; ++i) {
        f += a * valueNoise3(p);
        p = p * 2.01 + vec3(5.13, -8.71, 3.77);
        p = p.yzx;
        a *= 0.49;
    }
    return f;
}

struct DayPhase {
    float daylight;
    float deepNight;
    float twilight;
    float golden;
    float highSun;
};

DayPhase getDayPhase(float sunY) {
    DayPhase p;

    p.daylight  = smoothstep(-0.115, 0.035, sunY);
    p.deepNight = 1.0 - smoothstep(-0.29, -0.105, sunY);

    p.twilight = exp(-sqr((sunY + 0.025) / 0.145));
    p.twilight *= 1.0 - 0.30 * smoothstep(0.11, 0.35, sunY);

    p.golden = exp(-sqr((sunY - 0.015) / 0.105));
    p.highSun = smoothstep(0.10, 0.58, sunY);
    return p;
}

vec3 sunLightColor(float sunY) {
    float warm = 1.0 - smoothstep(0.03, 0.38, sunY);
    vec3 lowSun  = vec3(1.000, 0.315, 0.075);
    vec3 midSun  = vec3(1.000, 0.690, 0.355);
    vec3 highSun = vec3(1.000, 0.965, 0.885);

    vec3 c = mix(midSun, highSun, smoothstep(0.05, 0.55, sunY));
    c = mix(c, lowSun, warm * (1.0 - smoothstep(-0.05, 0.08, sunY)));
    return c;
}

vec3 atmosphere(vec3 rd, vec3 sunDir, DayPhase phase) {
    float y = rd.y;
    float upperY = max(y, 0.0);
    float zenith = pow(saturate(upperY), 0.36);
    float horizonBand = exp(-abs(y) * 7.5);

    vec2 sunXZ = safeNormalize2(sunDir.xz, vec2(0.0, 1.0));
    vec2 rayXZ = safeNormalize2(rd.xz, sunXZ);
    float sunAz = saturate(dot(rayXZ, sunXZ) * 0.5 + 0.5);
    sunAz = pow(sunAz, 3.2);

    vec3 dayHorizon = vec3(0.475, 0.705, 0.930);
    vec3 dayZenith  = vec3(0.025, 0.165, 0.505);
    vec3 nightHorizon = vec3(0.014, 0.023, 0.047);
    vec3 nightZenith  = vec3(0.0017, 0.0037, 0.0125);

    vec3 dayBase = mix(dayHorizon, dayZenith, zenith);
    vec3 nightBase = mix(nightHorizon, nightZenith, pow(zenith, 0.72));

    float dayMix = max(phase.daylight, phase.twilight * 0.42);
    vec3 col = mix(nightBase, dayBase, saturate(dayMix));

    float mu = clamp(dot(rd, sunDir), -1.0, 1.0);
    float rayleighPhase = 0.75 * (1.0 + mu * mu);

    const float g = 0.76;
    float mieDen = max(1.0 + g * g - 2.0 * g * mu, 0.0025);
    float miePhase = (1.0 - g * g) / pow(mieDen, 1.5);

    float airMass = 1.0 / max(0.16 + max(y, 0.0), 0.16);
    float lowAltitude = saturate((airMass - 0.85) / 4.0);

    vec3 rayleighTint = vec3(0.070, 0.170, 0.390);
    col += rayleighTint * rayleighPhase * phase.daylight * (0.045 + 0.095 * lowAltitude);

    vec3 sCol = sunLightColor(sunDir.y);
    float solarHaze = miePhase * horizonBand * (0.035 + 0.085 * lowAltitude);
    col += sCol * solarHaze * phase.daylight;

    float warmForward = horizonBand * phase.twilight * (0.20 + 0.80 * sunAz);
    vec3 amber = vec3(1.23, 0.245, 0.047);
    vec3 rose  = vec3(0.92, 0.120, 0.155);
    vec3 peach = vec3(1.18, 0.510, 0.215);

    float roseMask = horizonBand * phase.twilight * (1.0 - sunAz) * 0.60;
    col += amber * warmForward * 0.46;
    col += peach * warmForward * 0.16;
    col += rose  * roseMask * 0.17;

    float antiSun = pow(saturate(1.0 - sunAz), 2.0);
    float venusBelt = phase.twilight * horizonBand * antiSun;
    col += vec3(0.24, 0.075, 0.19) * venusBelt * 0.17;

    float airglow = phase.deepNight * exp(-sqr((abs(y) - 0.015) * 11.0));
    col += vec3(0.007, 0.020, 0.029) * airglow;

    float below = 1.0 - smoothstep(-0.24, 0.02, y);
    vec3 lowerDay   = vec3(0.105, 0.165, 0.225);
    vec3 lowerNight = vec3(0.0025, 0.0040, 0.0070);
    vec3 lower = mix(lowerNight, lowerDay, phase.daylight);
    col = mix(col, lower, below * 0.88);

    return max(col, vec3(0.0));
}

vec3 sunContribution(vec3 rd, vec3 sunDir, DayPhase phase) {
    float mu = clamp(dot(rd, sunDir), -1.0, 1.0);
    float a = sqrt(max(0.0, 2.0 * (1.0 - mu)));

    float aboveHorizon = smoothstep(-0.030, 0.012, sunDir.y);
    vec3 sCol = sunLightColor(sunDir.y);

    float disk = 1.0 - smoothstep(0.0041, 0.0054, a);
    float corona = exp(-a * 24.0) * 0.28 + exp(-a * 88.0) * 0.52;
    float broadGlow = exp(-a * 7.0) * 0.055;

    float intensity = aboveHorizon * (0.55 + 0.45 * phase.daylight);
    return sCol * intensity * (disk * SUN_INTENSITY + corona + broadGlow);
}

vec3 moonContribution(vec3 rd, vec3 sunDir, DayPhase phase) {
    vec3 moonDir = -sunDir;
    float moonAbove = smoothstep(-0.01, 0.045, moonDir.y);
    float night = saturate(phase.deepNight + (1.0 - phase.daylight) * 0.35);

    float mu = clamp(dot(rd, moonDir), -1.0, 1.0);
    float a = sqrt(max(0.0, 2.0 * (1.0 - mu)));

    float disk = 1.0 - smoothstep(0.0040, 0.0056, a);
    float halo = exp(-a * 34.0) * 0.10 + exp(-a * 8.0) * 0.018;

    vec3 moonCol = vec3(0.70, 0.82, 1.00);
    return moonCol * moonAbove * night * (disk * MOON_INTENSITY + halo);
}

float starCell(vec3 rd, float scale, float density, float radius) {
    vec3 p = rd * scale;
    vec3 id = floor(p);
    vec3 f = fract(p);

    float alive = step(density, hash13(id + vec3(17.0, 41.0, 73.0)));
    vec3 sp = 0.14 + 0.72 * hash33(id + vec3(11.0, 29.0, 53.0));
    float d = length(f - sp);

    float core = 1.0 - smoothstep(radius * 0.32, radius, d);
    float halo = 1.0 - smoothstep(radius * 0.60, radius * 2.2, d);
    float brightness = 0.45 + 1.90 * pow(hash13(id + vec3(101.0, 7.0, 19.0)), 5.0);

    return alive * brightness * (core + 0.20 * halo);
}

vec3 starField(vec3 rd, DayPhase phase) {
    float night = phase.deepNight;
    float horizonFade = smoothstep(-0.03, 0.15, rd.y);

    float s1 = starCell(rd, 170.0, 0.955, 0.115);
    float s2 = starCell(rd.yzx, 315.0, 0.976, 0.105);
    float stars = (s1 + 0.72 * s2) * night * horizonFade;

    float t = hash13(floor(rd * 190.0) + vec3(3.0, 17.0, 31.0));
    vec3 warm = vec3(1.00, 0.78, 0.58);
    vec3 cool = vec3(0.62, 0.79, 1.00);
    vec3 starCol = mix(warm, cool, smoothstep(0.26, 0.78, t));

    return starCol * stars * STAR_INTENSITY;
}

vec3 milkyWay(vec3 rd, DayPhase phase) {
    vec3 galN = safeNormalize(vec3(0.34, 0.78, -0.525), vec3(0.0, 1.0, 0.0));
    float lat = abs(dot(rd, galN));

    float broad = exp(-sqr(lat * 4.6));
    float core  = exp(-sqr(lat * 10.5));

    float dustA = fbm3(rd * 5.2 + vec3(8.0, -3.0, 11.0));
    float dustB = fbm3(rd.zxy * 13.0 + vec3(-4.0, 9.0, 2.0));
    float dust = saturate(0.58 * dustA + 0.42 * dustB);

    float mottling = smoothstep(0.38, 0.84, dust);
    float darkLane = smoothstep(0.50, 0.78, fbm3(rd * 9.0 + vec3(31.0, -12.0, 6.0)));

    float band = broad * (0.35 + 0.65 * mottling);
    band += core * 0.32;
    band *= (1.0 - 0.48 * darkLane * core);

    float horizonFade = smoothstep(0.01, 0.22, rd.y);
    float night = phase.deepNight * horizonFade;

    vec3 milkyCol = vec3(0.25, 0.31, 0.47);
    milkyCol += vec3(0.09, 0.045, 0.085) * mottling;

    return milkyCol * band * night * MILKY_WAY_INTENSITY;
}

vec2 cloudCoordinates(vec3 rd) {
    float h = max(rd.y, 0.0);
    float denom = 0.24 + 0.90 * h;
    vec2 p = rd.xz / denom;

    mat2 r = mat2(0.913545, -0.406737,
                  0.406737,  0.913545);
    return r * p * CLOUD_SCALE;
}

float cloudMacro(vec2 p) {
    vec2 warp = vec2(
        fbm2Fast(p * 0.42 + vec2(17.2, -4.8)),
        fbm2Fast(p * 0.42 + vec2(-9.1, 13.7))
    );
    warp = (warp - 0.5) * 1.35;

    float n0 = fbm2(p * 0.72 + warp * 0.74);
    float n1 = fbm2Fast(p * 1.86 - warp * 0.26 + vec2(6.3, -12.1));
    return n0 * 0.78 + n1 * 0.22;
}

float cloudDensity(vec2 p) {
    float macro = cloudMacro(p);
    float detail = fbm2Fast(p * 3.35 + vec2(-2.7, 8.4));
    float shape = macro * 0.86 + detail * 0.14;

    float threshold = CLOUD_COVERAGE;
    return smoothstep(threshold - 0.085, threshold + 0.095, shape);
}

float cirrusDensity(vec2 p) {
    mat2 shear = mat2(1.65, 0.42,
                     -0.12, 0.48);
    vec2 q = shear * p * 0.72;

    float warp = fbm2Fast(q * 0.46 + vec2(5.2, 19.4));
    q.x += (warp - 0.5) * 2.7;

    float n = fbm2Fast(q * 1.35);
    float streak = smoothstep(0.58, 0.76, n);
    return streak * CIRRUS_AMOUNT;
}

vec4 renderClouds(vec3 rd, vec3 sunDir, DayPhase phase, vec3 skyBehind) {
    float elevationMask = smoothstep(-0.025, 0.075, rd.y);
    if (elevationMask <= 0.0001) {
        return vec4(skyBehind, 0.0);
    }

    vec2 p = cloudCoordinates(rd);
    float d0 = cloudDensity(p);
    float cirrus = cirrusDensity(p + vec2(3.7, -1.9));

    float density = saturate(d0 + cirrus * (1.0 - d0) * 0.72);
    density *= elevationMask;

    if (density <= 0.001) {
        return vec4(skyBehind, 0.0);
    }

    vec2 sunXZ = safeNormalize2(sunDir.xz, vec2(0.0, 1.0));
    float sunElev = saturate(sunDir.y * 0.5 + 0.5);
    float stepLen = mix(0.075, 0.028, sunElev);

    float towardSun = cloudDensity(p + sunXZ * stepLen);
    float awaySun   = cloudDensity(p - sunXZ * stepLen * 0.72);
    float directional = saturate(0.50 + (d0 - towardSun) * 1.65 + (awaySun - d0) * 0.45);

    float edge = saturate(1.0 - abs(d0 * 2.0 - 1.0));
    edge = pow(edge, 2.2);

    float mu = saturate(dot(rd, sunDir));
    float forward = pow(mu, mix(7.0, 22.0, saturate(sunDir.y + 0.15)));

    vec3 sunCol = sunLightColor(sunDir.y);

    vec3 dayShadow = vec3(0.34, 0.43, 0.54);
    vec3 dayLight  = vec3(1.04, 1.055, 1.06);
    vec3 nightCloud = vec3(0.018, 0.026, 0.044);

    vec3 warmShadow = vec3(0.36, 0.105, 0.095);
    vec3 warmLight  = vec3(1.19, 0.475, 0.175);

    float warm = phase.golden * (0.55 + 0.45 * phase.twilight);
    vec3 shadowCol = mix(dayShadow, warmShadow, warm * 0.78);
    vec3 lightCol  = mix(dayLight, warmLight, warm * 0.88);

    float sunLighting = phase.daylight * smoothstep(-0.06, 0.035, sunDir.y);
    vec3 litCloud = mix(shadowCol, lightCol, 0.28 + 0.72 * directional);
    litCloud *= 0.68 + 0.48 * sunLighting;

    vec3 moonDir = -sunDir;
    float moonForward = pow(saturate(dot(rd, moonDir)), 12.0);
    float moonAbove = smoothstep(-0.02, 0.08, moonDir.y) * phase.deepNight;
    vec3 moonLit = nightCloud + vec3(0.055, 0.075, 0.115) * moonForward * moonAbove;

    vec3 cloudCol = mix(moonLit, litCloud, phase.daylight);

    cloudCol += vec3(0.31, 0.055, 0.035) * phase.twilight * (1.0 - phase.daylight) * 0.48;

    float rim = edge * forward * sunLighting;
    cloudCol += sunCol * rim * (0.95 + 1.45 * warm);

    cloudCol *= mix(1.06, 0.76, pow(density, 1.7) * (1.0 - directional * 0.55));

    cloudCol += vec3(0.13, 0.15, 0.18) * cirrus * phase.daylight * 0.32;

    float alpha = saturate(density * CLOUD_OPACITY);
    alpha *= 0.92 + 0.08 * smoothstep(0.0, 0.35, rd.y);

    return vec4(max(cloudCol, vec3(0.0)), alpha);
}

vec3 ditherSky(vec3 c) {
    float n = hash12(gl_FragCoord.xy + vec2(float(CubeFace) * 17.0, float(CachePass) * 43.0));
    float cacheScale = (CachePass != 0) ? 1.0 : 0.72;
    float faceSize = max(float(CacheFaceSize), 64.0);
    float amp = cacheScale * mix(1.0 / 255.0, 0.45 / 255.0, saturate((faceSize - 256.0) / 1792.0));
    return c + (n - 0.5) * amp;
}

void main() {
    vec3 rd = safeNormalize(viewRay, vec3(0.0, 1.0, 0.0));
    vec3 sunDir = safeNormalize(SunDir, vec3(0.0, 1.0, 0.0));

    DayPhase phase = getDayPhase(sunDir.y);

    vec3 col = atmosphere(rd, sunDir, phase);

    col += milkyWay(rd, phase);
    col += starField(rd, phase);

    col += moonContribution(rd, sunDir, phase);
    col += sunContribution(rd, sunDir, phase);

    vec4 clouds = renderClouds(rd, sunDir, phase, col);
    col = mix(col, clouds.rgb, clouds.a);

    float sunMu = clamp(dot(rd, sunDir), -1.0, 1.0);
    float sunA = sqrt(max(0.0, 2.0 * (1.0 - sunMu)));
    float thinCloud = (1.0 - clouds.a) + clouds.a * 0.12;
    col += sunLightColor(sunDir.y) * exp(-sunA * 12.0) * 0.035 * phase.daylight * thinCloud;

    col = ditherSky(max(col, vec3(0.0)));

    col = tonemapACES(col);
    col = pow(max(col, vec3(0.0)), vec3(1.0 / 2.2));

    fragColor = vec4(saturate(col), 1.0);
}
