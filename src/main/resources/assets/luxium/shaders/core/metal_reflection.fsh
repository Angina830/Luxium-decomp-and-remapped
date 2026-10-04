#version 150

uniform sampler2D Sampler0;
uniform vec2 TextureUvScale;
uniform float ReflectionDistance;

in vec4 vertexColor;
in vec2 vFaceUV;
in vec4 vVirtualClip;
in float vReflectionAlpha;
in float vMaterialKind;
in vec3 vPlayerRelativePosition;

out vec4 fragColor;

void main() {
    if (abs(vVirtualClip.w) <= 0.0001 || vReflectionAlpha <= 0.01) discard;
    vec2 screenUV = (vVirtualClip.xy / vVirtualClip.w) * 0.5 + 0.5;

    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    vec2 minUv = texel * 0.5;
    vec2 maxUv = max(minUv, TextureUvScale - minUv);
    vec2 finalUV = clamp(screenUV, vec2(0.0), vec2(1.0));
    finalUV = clamp(finalUV * TextureUvScale, minUv, maxUv);

    vec4 reflected = texture(Sampler0, finalUV);
        float fadeStart = ReflectionDistance * 0.75;
        float boundaryFade = 1.0 - smoothstep(fadeStart, ReflectionDistance,
            length(vPlayerRelativePosition));
        float alpha = vReflectionAlpha * boundaryFade;
        if (alpha <= 0.01) discard;
    if (vMaterialKind < 0.75) {
        vec2 edge = min(vFaceUV, vec2(1.0) - vFaceUV);
        float edgeFade = smoothstep(-0.10, 0.08, min(edge.x, edge.y));
        alpha *= 0.35 + 0.65 * edgeFade;
    }
    fragColor = vec4(reflected.rgb * vertexColor.rgb, alpha);
}
