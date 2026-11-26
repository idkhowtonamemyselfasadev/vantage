#version 330

// Vantage Shader ESP: the entity outline target holds every marked entity as a flat silhouette in its ESP colour.
// Pixels inside a silhouette become a translucent fill; pixels outside look around within Width texels for the
// nearest silhouette pixel and take its colour, fading with the distance so the edge glows and softens outward.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform OutlineData {
    int Width;      // outline thickness in texels, 1..6
    float Fill;     // alpha of the inside of a silhouette
    int Shape;      // 0 outline only, 1 fill only, 2 both
    float Glow;     // how solid the outline is: alpha = (1 - d/(w+1)) * (1 + Glow), clamped
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / InSize;
    vec4 here = texture(InSampler, texCoord);
    if (here.a > 0.0) {
        if (Shape == 0) discard;
        fragColor = vec4(here.rgb, Fill);
        return;
    }
    if (Shape == 1) discard;
    float w2 = float(Width * Width);
    float best = w2 + 1.0;
    vec3 colour = vec3(0.0);
    for (int x = -Width; x <= Width; x++) {
        for (int y = -Width; y <= Width; y++) {
            vec4 s = texture(InSampler, texCoord + texel * vec2(float(x), float(y)));
            if (s.a > 0.0) {
                float d = float(x * x + y * y);
                if (d < best) {
                    best = d;
                    colour = s.rgb;
                }
            }
        }
    }
    if (best > w2) discard;
    // solid for most of the width, fading over the last pixels; Glow pushes the solid part outward
    float t = 1.0 - sqrt(best) / (float(Width) + 1.0);
    float a = clamp(t * (1.0 + Glow), 0.0, 1.0);
    fragColor = vec4(colour, a);
}
