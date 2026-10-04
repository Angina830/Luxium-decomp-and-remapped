#import <luxium:embeddium/include/plantswave/wind_common.glsl>
#import <luxium:embeddium/include/plantswave/grass_wave.glsl>
#import <luxium:embeddium/include/plantswave/leaf_wave.glsl>
#import <luxium:embeddium/include/plantswave/aquatic_wave.glsl>

uniform int u_LuxiumPlantWaveEnabled;
uniform float u_LuxiumPlantWaveTime;
uniform vec3 u_LuxiumPlantWaveCamera;
uniform vec4 u_LuxiumPlantWaveSettings0;
uniform vec4 u_LuxiumPlantWaveSettings1;

vec2 luxium_plant_vertex_data() {
    uint waveType = (_material_params >> 6u) & 3u;
    if (waveType == 0u) {
        return vec2(0.0);
    }
    int plantData = int(round(_vert_color.a * 255.0));
    int weight = plantData & 15;
    int phase = (plantData >> 4) & 15;
    _vert_color.a = 1.0;
    return vec2(float(weight) / 15.0, float(phase) / 16.0);
}

vec3 luxium_apply_plant_wave(vec3 cameraRelativePosition, float vertexWeight, float vertexPhase) {
    if (u_LuxiumPlantWaveEnabled == 0) {
        return cameraRelativePosition;
    }

    uint waveType = (_material_params >> 6u) & 3u;
    if (waveType == 0u) {
        return cameraRelativePosition;
    }

    float fade = luxium_wave_distance_fade(cameraRelativePosition, u_LuxiumPlantWaveSettings0.w);
    if (fade <= 0.0) {
        return cameraRelativePosition;
    }

    vec3 worldPosition = cameraRelativePosition + u_LuxiumPlantWaveCamera;
    float time = u_LuxiumPlantWaveTime * u_LuxiumPlantWaveSettings0.y;
    float globalStrength = u_LuxiumPlantWaveSettings0.x * fade;
    vec3 offset;

    if (waveType == 1u) {
        offset = luxium_grass_wave(
                worldPosition,
                time,
                vertexWeight,
                vertexPhase,
                globalStrength * u_LuxiumPlantWaveSettings1.x,
                u_LuxiumPlantWaveSettings0.z,
                u_LuxiumPlantWaveSettings1.w);
    } else if (waveType == 2u) {
        float gust = luxium_wave_gust(worldPosition, time, u_LuxiumPlantWaveSettings0.z);
        offset = luxium_leaf_wave(
                worldPosition,
                time,
                globalStrength * u_LuxiumPlantWaveSettings1.y,
                gust);
    } else {
        offset = luxium_aquatic_wave(
                worldPosition,
                time,
                vertexWeight,
                globalStrength * u_LuxiumPlantWaveSettings1.z);
    }

    return cameraRelativePosition + offset;
}
