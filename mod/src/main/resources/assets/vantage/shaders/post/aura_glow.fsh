#version 330

// Hand Aura, step 3: the glow itself, added onto the frame. Halo = the blurred mask with a soft knee, outside the hand only.
// Rim = outside pixels that touch the hand in one of eight directions. Sheen = a diagonal band that drifts across the hand.
// Everything breathes slowly.

uniform sampler2D InSampler;    // sharp mask, full size
uniform sampler2D GlowSampler;  // blurred mask, half size

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform AuraData {
    vec4 Colour;
    float Aura;
    float Rim;
    float Sheen;
    float Time;     // seconds
    float Breathe;  // 0 or 1
    float Debug;    // 1 = show the mask (red) and the blur (green) instead of the glow
    float Pad2;
    float Pad3;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float mask = texture(InSampler, texCoord).r;
    float glow = texture(GlowSampler, texCoord).r;
    float halo = (1.0 - exp(-3.4 * glow * 4.0)) * Aura * (1.0 - mask);
    vec2 texel = 2.5 / InSize;
    float near = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        near += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * texel).r;
    }
    float rim = clamp(near * 0.7, 0.0, 1.0) * (1.0 - mask) * Rim * 1.6;
    float phase = fract(Time / 4.4);
    float band = exp(-pow(((texCoord.x + texCoord.y) * 0.5 - phase) * 10.0, 2.0));
    float sheen = band * mask * Sheen * 1.8;
    float breathe = Breathe > 0.5 ? 0.6 + 0.4 * (0.5 + 0.5 * sin(Time * 6.2831853 / 3.2)) : 1.0;
    if (Debug > 0.5) { fragColor = vec4(mask, glow, 0.0, 1.0); return; }
    // alpha 1 with the colour already scaled: the blend adds rgb as is, so the strength is not squared by src alpha
    float strength = (halo + rim + sheen) * breathe;
    fragColor = vec4(Colour.rgb * strength, 1.0);
}
