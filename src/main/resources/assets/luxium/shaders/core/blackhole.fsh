#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform mat4 ViewProj;
uniform mat4 InverseViewProj;
uniform vec3 CameraPos;
uniform vec3 SphereCenter;
uniform float SphereRadius;
uniform float Intensity;
uniform vec2 InSize;
uniform vec2 OutSize;

in vec2 texCoord;

out vec4 fragColor;

const float PI = 3.14159265359;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float value = 0.0; float amplitude = 0.5; float frequency = 1.0;
    for(int i = 0; i < 5; i++) { value += amplitude * noise(p * frequency); frequency *= 2.0; amplitude *= 0.5; }
    return value;
}
float turbulence(vec2 p) {
    float t = 0.0; float scale = 1.0;
    for(int i = 0; i < 4; i++) { t += abs(noise(p * scale)) / scale; scale *= 2.0; }
    return t;
}
vec4 getDiskTexture(vec2 uv, float distNorm, float radialDist) {
    float angle = uv.x * 2.0 * PI; float rad = uv.y;
    float rotation = angle + rad * 3.0; vec2 p = vec2(cos(rotation), sin(rotation)) * rad * 10.0;
    float baseNoise = fbm(p); float detail = turbulence(p * 2.0) * 0.5;
    float swirls = sin(angle * 8.0 + rad * 12.0 + baseNoise * 2.0) * 0.5 + 0.5;
    float pattern = baseNoise * 0.6 + detail * 0.3 + swirls * 0.1;
    float temp = 1.0 - smoothstep(0.0, 0.6, rad); temp = pow(temp, 0.7);
    float brightness = pattern * 0.4 + 0.6; brightness *= (1.0 + sin(angle * 15.0 + rad * 20.0) * 0.15);
    vec3 col;
    if (temp > 0.85) col = mix(vec3(1.0, 0.95, 0.8), vec3(1.2, 1.2, 1.3), (temp - 0.85) / 0.15);
    else if (temp > 0.6) col = mix(vec3(1.0, 0.8, 0.3), vec3(1.0, 0.95, 0.8), (temp - 0.6) / 0.25);
    else if (temp > 0.3) col = mix(vec3(1.0, 0.4, 0.1), vec3(1.0, 0.8, 0.3), (temp - 0.3) / 0.3);
    else col = mix(vec3(0.6, 0.1, 0.0), vec3(1.0, 0.4, 0.1), temp / 0.3);
    col *= brightness;
    float innerGlow = exp(-rad * 8.0) * 2.0; col += vec3(1.0, 0.9, 0.7) * innerGlow;
    float edgeFade = smoothstep(1.0, 0.7, rad);

    return vec4(col * edgeFade, edgeFade);
}

void main() {
    if (Intensity <= 0.001) {
        fragColor = texture(DiffuseSampler, texCoord);
        return;
    }

    vec4 ndcFar = vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec4 relFar = InverseViewProj * ndcFar;
    vec3 rayDir = normalize(relFar.xyz / relFar.w);

    float sceneDepth = texture(DepthSampler, texCoord).r;
    bool isSky = sceneDepth > 0.99999;

    vec4 sceneClip = vec4(texCoord * 2.0 - 1.0, sceneDepth * 2.0 - 1.0, 1.0);
    vec4 sceneRel = InverseViewProj * sceneClip;
    float sceneDist = length(sceneRel.xyz / sceneRel.w);

    float Rs = SphereRadius;
    float M = Rs * 0.5;
    vec3 bh = vec3(0.0);
    vec3 eye = CameraPos - SphereCenter;

    float r_inner = Rs * 1.5;
    float r_outer = Rs * 5.0;
    vec3 disc_n = vec3(0.0, 1.0, 0.0);
    vec3 disc_s = vec3(1.0, 0.0, 0.0);

    vec3 h2e = eye - bh;
    float l2_h2e = dot(h2e, h2e);
    float rm = length(cross(rayDir, h2e));
    float t_cp = sqrt(max(0.0, l2_h2e - rm * rm));

    float frontInfluenceDist = t_cp - (Rs * 10.0);

    vec2 distortedUV = texCoord;
    if (rm > Rs) {
         vec3 cp = eye + rayDir * t_cp;
         vec3 toCenter = normalize(-cp);
         float deflection = (4.0 * M) / rm;

         vec3 bentRay = normalize(rayDir + toCenter * deflection);

         vec3 relSamplePos = rayDir * t_cp + bentRay * max(100.0, Rs * 8.0);
         vec4 clipSpace = ViewProj * vec4(relSamplePos, 1.0);
         vec2 newUV = (clipSpace.xy / max(clipSpace.w, 1e-5)) * 0.5 + 0.5;

         float influence = smoothstep(0.0, 10.0 * Rs, rm);
         if (isSky || sceneDist > frontInfluenceDist) {
             distortedUV = mix(texCoord, newUV, 0.8 * Intensity * (1.0 - influence));
         }
    }

    vec4 bgColor = texture(DiffuseSampler, distortedUV);

    float warpDepth = texture(DepthSampler, distortedUV).r;
    if (warpDepth < 0.99999) {
        vec4 wClip = vec4(distortedUV * 2.0 - 1.0, warpDepth * 2.0 - 1.0, 1.0);
        vec4 wRel = InverseViewProj * wClip;
        float warpDist = length(wRel.xyz / wRel.w);
        if (warpDist < frontInfluenceDist) {
            bgColor = texture(DiffuseSampler, texCoord);
        }
    }

    if (rm < Rs) {
        float horizonDist = t_cp - sqrt(Rs*Rs - rm*rm);
        if (!isSky && sceneDist < horizonDist) {
            fragColor = texture(DiffuseSampler, texCoord);
            return;
        }
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec4 diskColorAccum = vec4(0.0);
    float alpha_ray = 4.0 * M / rm;
    float tan_a_2 = tan(alpha_ray * 0.5);

    vec3 cp = eye + rayDir * t_cp;
    vec3 coord_origin = cp + rayDir * (rm * tan_a_2);

    vec3 x_axis = normalize(bh - coord_origin);
    vec3 y_axis = normalize(rayDir + tan_a_2 * normalize(bh - cp));
    vec3 z_axis = cross(x_axis, y_axis);

    float c = length(bh - coord_origin);
    float k = tan_a_2;
    vec3 iline_r = normalize(cross(z_axis, disc_n));

    float x1 = -1.0, x2 = -1.0, y1, y2;
    float k2 = k*k; float b_val2 = c*c / (1.0 + k2); float a2 = k2 * b_val2;
    float denom = dot(x_axis, iline_r);

    if (abs(denom) > 0.0001) {
        float slope = dot(y_axis, iline_r) / denom;
        float k2_slope = slope * slope;
        float A = a2 * k2_slope - b_val2; float B = -2.0 * a2 * k2_slope * c;
        float C = a2 * (k2_slope * c * c + b_val2); float delta = B*B - 4.0*A*C;
        if (delta >= 0.0) {
            float sqrtDelta = sqrt(delta);
            x1 = (-B - sqrtDelta) / (2.0 * A); x2 = (-B + sqrtDelta) / (2.0 * A);
            y1 = slope * (x1 - c); y2 = slope * (x2 - c);
        }
    }

    float yeye = dot(eye - coord_origin, y_axis);

    vec3 p1 = coord_origin + x1 * x_axis + y1 * y_axis;
    float d1 = length(p1 - bh);
    if (x1 >= 0.0 && y1 >= yeye && d1 >= r_inner && d1 <= r_outer) {
        float distToP1 = length(p1 - eye);
        if (isSky || sceneDist > distToP1) {
            float u = atan(dot(p1, disc_s), dot(p1, cross(disc_s, disc_n))) / (2.0 * PI);
            float v = (d1 - r_inner) / (r_outer - r_inner);
            vec4 col = getDiskTexture(vec2(u, v), d1, d1);
            float innerFade = smoothstep(r_inner, r_inner + Rs*0.5, d1);
            float outerFade = smoothstep(r_outer, r_outer - Rs*0.8, d1);
            diskColorAccum += col * (innerFade * outerFade) * Intensity * 1.5;
        }
    }

    vec3 p2 = coord_origin + x2 * x_axis + y2 * y_axis;
    float d2 = length(p2 - bh);
    if (x2 >= 0.0 && y2 >= yeye && d2 >= r_inner && d2 <= r_outer) {
        float distToP2 = length(p2 - eye);
        if (isSky || sceneDist > distToP2) {
            float u = atan(dot(p2, disc_s), dot(p2, cross(disc_s, disc_n))) / (2.0 * PI);
            float v = (d2 - r_inner) / (r_outer - r_inner);
            vec4 col = getDiskTexture(vec2(u, v), d2, d2);
            float innerFade = smoothstep(r_inner, r_inner + Rs*0.5, d2);
            float outerFade = smoothstep(r_outer, r_outer - Rs*0.8, d2);
            diskColorAccum += col * (innerFade * outerFade) * Intensity * 1.2;
        }
    }

    float distToCenter = rm / Rs;
    float diskProximity = smoothstep(r_inner, r_outer, distToCenter * Rs);
    float diskGlow = exp(-abs(distToCenter - 2.5) * 0.8) * Intensity * diskProximity;
    vec3 glowColor = vec3(1.0, 0.8, 0.5) * diskGlow * 0.4;

    vec3 finalColor = bgColor.rgb;
    finalColor += diskColorAccum.rgb;

    float diskBrightness = dot(diskColorAccum.rgb, vec3(0.299, 0.587, 0.114));
    if (diskBrightness > 0.01) {
        vec3 bloomAccum = vec3(0.0);
        float bloomWeight = 0.0;
        float bloomRadius = 0.08 * Intensity;
        for (int i = 0; i < 16; i++) {
            float angle = float(i) * 6.28318 / 16.0;
            vec2 offset = vec2(cos(angle), sin(angle)) * bloomRadius;
            float weight = exp(-length(offset) * 8.0);
            bloomAccum += diskColorAccum.rgb * weight;
            bloomWeight += weight;
        }
        if (bloomWeight > 0.0) finalColor += (bloomAccum / bloomWeight) * diskBrightness * 0.8;
    }

    finalColor += glowColor;
    if (diskBrightness > 0.5) {
        float strongGlow = (diskBrightness - 0.5) * 2.0;
        vec3 glowSpread = diskColorAccum.rgb * strongGlow * 0.6;
        for (int i = 0; i < 8; i++) {
            float angle = float(i) * 0.785398;
            vec2 offset = vec2(cos(angle), sin(angle)) * 0.05;
            finalColor += glowSpread * exp(-length(offset) * 12.0) * 0.15;
        }
    }

    finalColor = finalColor / (1.0 + finalColor * 0.12);
    finalColor *= 0.75 + 0.25 * smoothstep(0.8, 0.4, length(texCoord - vec2(0.5)));

    fragColor = vec4(finalColor, 1.0);
}
