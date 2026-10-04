#version 150

in vec3 viewRay;
out vec4 fragColor;

uniform samplerCube SkyCache;

void main() {
    fragColor = texture(SkyCache, viewRay);
}
