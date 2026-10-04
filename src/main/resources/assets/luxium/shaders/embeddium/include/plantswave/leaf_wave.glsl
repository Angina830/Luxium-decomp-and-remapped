vec3 luxium_leaf_wave(vec3 worldPosition, float time, float strength, float gust) {
    vec2 phasePosition = worldPosition.xz * 0.18 + vec2(worldPosition.y * 0.07, -worldPosition.y * 0.05);
    float phase = luxium_wave_noise(phasePosition) * LUXIUM_WAVE_TAU;
    vec2 direction = luxium_wave_direction(time * 0.55 + phase * 0.04);
    vec2 crossDirection = vec2(-direction.y, direction.x);
    float along = dot(worldPosition.xz, direction);
    float across = dot(worldPosition.xz, crossDirection);
    float primary = sin(along * 0.16 + time * 0.82 + phase);
    float secondary = sin(across * 0.13 - time * 0.54 + phase * 0.67);
    float lift = sin(worldPosition.y * 0.19 + along * 0.08 + time * 1.13 + phase * 0.31);
    float amplitude = 0.034 * strength * mix(0.90, gust, 0.48);
    return vec3(primary + secondary * 0.25, lift * 0.16, secondary + primary * 0.18) * amplitude;
}
