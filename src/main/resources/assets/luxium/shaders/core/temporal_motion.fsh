#version 150

uniform sampler2D HistoryDepth;
uniform mat4 ReprojectionMat;
uniform mat4 SkyReprojectionMat;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float historyDepth = texture(HistoryDepth, texCoord0).r;
    vec4 historyClip = vec4(texCoord0 * 2.0 - 1.0, historyDepth * 2.0 - 1.0, 1.0);

    vec4 currentClip;
    if (historyDepth >= 0.99995) {
        currentClip = SkyReprojectionMat * historyClip;
    } else {
        currentClip = ReprojectionMat * historyClip;
    }

    float valid = 1.0;
    vec2 destinationUv = texCoord0;

    if (currentClip.w <= 1.0e-5) {
        valid = 0.0;
    } else {
        vec3 currentNdc = currentClip.xyz / currentClip.w;
        destinationUv = currentNdc.xy * 0.5 + 0.5;
        float destinationDepth = currentNdc.z * 0.5 + 0.5;

        bool inBounds = destinationUv.x >= -0.02 && destinationUv.x <= 1.02
                     && destinationUv.y >= -0.02 && destinationUv.y <= 1.02
                     && destinationDepth >= 0.0 && destinationDepth <= 1.0;
        valid = inBounds ? 1.0 : 0.0;
    }

    float depthGradient = abs(dFdx(historyDepth)) + abs(dFdy(historyDepth));
    float depthEdge = smoothstep(0.00035, 0.0060, depthGradient);

    fragColor = vec4(destinationUv - texCoord0, valid, depthEdge);
}
