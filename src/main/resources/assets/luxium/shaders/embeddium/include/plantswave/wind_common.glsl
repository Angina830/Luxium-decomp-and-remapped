const float LUXIUM_WAVE_TAU = 6.28318530718;

float luxium_wave_hash(vec2 cell) {
    vec2 q = fract(cell * vec2(0.1031, 0.11369));
    q += dot(q, q.yx + 33.33);
    return fract((q.x + q.y) * q.x);
}

float luxium_wave_noise(vec2 position) {
    vec2 cell = floor(position);
    vec2 local = fract(position);
    local = local * local * (3.0 - 2.0 * local);
    float a = luxium_wave_hash(cell);
    float b = luxium_wave_hash(cell + vec2(1.0, 0.0));
    float c = luxium_wave_hash(cell + vec2(0.0, 1.0));
    float d = luxium_wave_hash(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}

vec2 luxium_wave_direction(float time) {
    float angle = 0.78 + sin(time * 0.17) * 0.31 + sin(time * 0.047 + 1.9) * 0.18;
    return vec2(cos(angle), sin(angle));
}

float luxium_wave_gust(vec3 worldPosition, float time, float gustStrength) {
    float phase = luxium_wave_noise(worldPosition.xz * 0.071) * LUXIUM_WAVE_TAU;
    float broad = sin(time * 0.63 + phase + dot(worldPosition.xz, vec2(0.018, -0.013)));
    float pulse = smoothstep(-0.15, 0.92, broad);
    return mix(0.72, 1.0 + gustStrength * 0.58, pulse);
}

float luxium_wave_distance_fade(vec3 cameraRelativePosition, float maxDistance) {
    float start = maxDistance * 0.72;
    return 1.0 - smoothstep(start, maxDistance, length(cameraRelativePosition));
}
