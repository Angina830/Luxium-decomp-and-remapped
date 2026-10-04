#version 150

uniform sampler2D SceneSampler;
uniform vec2 ScreenSize;
uniform float BlurEnabled;
uniform float DimmingEnabled;

uniform vec4 PanelTint;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / ScreenSize;
    vec3 scene = texture(SceneSampler, texCoord0).rgb;

    vec3 blur = scene * 0.20;
    blur += texture(SceneSampler, texCoord0 + texel * vec2(4.0, 0.0)).rgb * 0.10;
    blur += texture(SceneSampler, texCoord0 - texel * vec2(4.0, 0.0)).rgb * 0.10;
    blur += texture(SceneSampler, texCoord0 + texel * vec2(0.0, 4.0)).rgb * 0.10;
    blur += texture(SceneSampler, texCoord0 - texel * vec2(0.0, 4.0)).rgb * 0.10;
    blur += texture(SceneSampler, texCoord0 + texel * vec2(3.0, 3.0)).rgb * 0.10;
    blur += texture(SceneSampler, texCoord0 + texel * vec2(-3.0, 3.0)).rgb * 0.10;
    blur += texture(SceneSampler, texCoord0 + texel * vec2(3.0, -3.0)).rgb * 0.10;
    blur += texture(SceneSampler, texCoord0 - texel * vec2(3.0, 3.0)).rgb * 0.10;

    scene = mix(scene, blur, clamp(BlurEnabled, 0.0, 1.0));
    scene = mix(scene, PanelTint.rgb, PanelTint.a * clamp(DimmingEnabled, 0.0, 1.0));

    fragColor = vec4(scene, 1.0);
}
