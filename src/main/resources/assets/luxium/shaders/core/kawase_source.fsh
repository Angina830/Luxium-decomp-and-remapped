#version 150

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D SourceAtlas;
uniform mat4 InverseViewProjection;
uniform mat4 InverseProjection;
uniform vec3 VolumeMinRelative;
uniform float VolumeSize;
uniform float SourceAtlasSize;
uniform float SourceTilesPerRow;
uniform float Threshold;

in vec2 texCoord0;
out vec4 fragColor;

float viewDepth(vec2 uv, float depth) {
    vec4 view = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return abs(view.z / max(abs(view.w), 1.0e-6));
}

float sampleSource(vec3 cell) {
    float tileX = mod(cell.z, SourceTilesPerRow);
    float tileY = floor(cell.z / SourceTilesPerRow);
    vec2 atlasCell = vec2(tileX * VolumeSize + cell.x, tileY * VolumeSize + cell.y);
    return texture(SourceAtlas, (atlasCell + vec2(0.5)) / SourceAtlasSize).r;
}

void main() {
    float depth = texture(DepthSampler, texCoord0).r;
    if (depth >= 0.999999) {
        fragColor = vec4(0.0);
        return;
    }

    vec4 relative = InverseViewProjection * vec4(texCoord0 * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 cameraRelative = relative.xyz / max(abs(relative.w), 1.0e-6);
    vec3 baseCell = floor(cameraRelative - VolumeMinRelative);
    float emission = 0.0;
    for (int z = -1; z <= 1; ++z) {
        for (int y = -1; y <= 1; ++y) {
            for (int x = -1; x <= 1; ++x) {
                if (abs(x) + abs(y) + abs(z) > 1) continue;
                vec3 cell = baseCell + vec3(x, y, z);
                vec3 sourceCenter = VolumeMinRelative + cell + vec3(0.5);
                if (all(lessThanEqual(abs(cameraRelative - sourceCenter), vec3(0.515)))) {
                    if (all(greaterThanEqual(cell, vec3(0.0))) && all(lessThan(cell, vec3(VolumeSize)))) {
                        emission = max(emission, sampleSource(cell));
                    }
                }
            }
        }
    }

    if (emission <= Threshold) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 source = texture(SceneSampler, texCoord0).rgb * emission;
    float weight = dot(source, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4(source, viewDepth(texCoord0, depth) * weight);
}
