#version 150

uniform sampler2D Sampler0;
uniform float HoverAmount;
uniform float GlowTime;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);

    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));

    float hover = smoothstep(0.0, 1.0, HoverAmount);
    float pulse = 0.94 + 0.06 * sin(GlowTime * 5.0);

    vec4 glowSample = vec4(0.0);
    float glowWeight = 0.0;

    vec2 nearOffset = texel * 2.0;

    vec4 s1 = texture(Sampler0, texCoord0 + vec2( nearOffset.x, 0.0));
    vec4 s2 = texture(Sampler0, texCoord0 + vec2(-nearOffset.x, 0.0));
    vec4 s3 = texture(Sampler0, texCoord0 + vec2(0.0,  nearOffset.y));
    vec4 s4 = texture(Sampler0, texCoord0 + vec2(0.0, -nearOffset.y));

    vec4 s5 = texture(Sampler0, texCoord0 + vec2( nearOffset.x,  nearOffset.y));
    vec4 s6 = texture(Sampler0, texCoord0 + vec2(-nearOffset.x,  nearOffset.y));
    vec4 s7 = texture(Sampler0, texCoord0 + vec2( nearOffset.x, -nearOffset.y));
    vec4 s8 = texture(Sampler0, texCoord0 + vec2(-nearOffset.x, -nearOffset.y));

    glowSample.rgb += s1.rgb * s1.a;
    glowSample.rgb += s2.rgb * s2.a;
    glowSample.rgb += s3.rgb * s3.a;
    glowSample.rgb += s4.rgb * s4.a;
    glowSample.rgb += s5.rgb * s5.a;
    glowSample.rgb += s6.rgb * s6.a;
    glowSample.rgb += s7.rgb * s7.a;
    glowSample.rgb += s8.rgb * s8.a;

    glowSample.a += s1.a + s2.a + s3.a + s4.a;
    glowSample.a += s5.a + s6.a + s7.a + s8.a;

    glowWeight += 8.0;

    vec2 farOffset = texel * 5.0;

    vec4 f1 = texture(Sampler0, texCoord0 + vec2( farOffset.x, 0.0));
    vec4 f2 = texture(Sampler0, texCoord0 + vec2(-farOffset.x, 0.0));
    vec4 f3 = texture(Sampler0, texCoord0 + vec2(0.0,  farOffset.y));
    vec4 f4 = texture(Sampler0, texCoord0 + vec2(0.0, -farOffset.y));

    glowSample.rgb += f1.rgb * f1.a * 0.5;
    glowSample.rgb += f2.rgb * f2.a * 0.5;
    glowSample.rgb += f3.rgb * f3.a * 0.5;
    glowSample.rgb += f4.rgb * f4.a * 0.5;

    glowSample.a += (f1.a + f2.a + f3.a + f4.a) * 0.5;

    glowWeight += 2.0;

    float sampledAlpha = glowSample.a / glowWeight;
    vec3 sampledColor = glowSample.rgb / max(glowSample.a, 0.001);

    sampledColor *= 1.25;

    float halo = max(sampledAlpha - tex.a * 0.5, 0.0);
    halo = smoothstep(0.0, 0.5, halo);
    halo *= hover * pulse;

    vec3 highlighted = tex.rgb * mix(1.0, 1.11, hover);
    highlighted += tex.rgb * hover * tex.a * 0.07;

    vec3 finalColor = highlighted + sampledColor * halo * 0.575;
    float finalAlpha = max(tex.a, halo * 0.75);

    if (finalAlpha < 0.01) {
        discard;
    }

    fragColor = vec4(finalColor, finalAlpha);
}
