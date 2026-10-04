vec3 luxium_aquatic_wave(vec3 worldPosition, float time, float weight, float strength) {
    float phase = luxium_wave_hash(floor(worldPosition.xz * 0.16)) * LUXIUM_WAVE_TAU;
    vec2 current = normalize(vec2(0.82 + sin(time * 0.09) * 0.18, 0.46 + cos(time * 0.07) * 0.16));
    vec2 side = vec2(-current.y, current.x);
    float sway = sin(dot(worldPosition.xz, current) * 0.17 + worldPosition.y * 0.36 + time * 0.94 + phase);
    float curl = sin(dot(worldPosition.xz, side) * 0.11 - worldPosition.y * 0.21 - time * 0.52 + phase * 0.43);
    float response = weight * weight;
    vec2 lateral = (current * sway + side * curl * 0.42) * (0.067 * strength * response);
    float lift = sin(time * 0.61 + worldPosition.y * 0.33 + phase) * 0.009 * strength * response;
    return vec3(lateral.x, lift, lateral.y);
}
