#version 330

// Vantage Colour Grading: exposure, warmth, vibrance (dull colours gain the most), contrast, then a soft vignette.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GradeData {
    float Vibrance;    // 0..1
    float Contrast;    // 1 = unchanged
    float Exposure;    // 1 = unchanged
    float Warmth;      // -1..1
    float Vignette;    // 0..1
    float Pad1;
    float Pad2;
    float Pad3;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 c = texture(InSampler, texCoord).rgb * Exposure;
    c = vec3(c.r * (1.0 + 0.08 * Warmth), c.g * (1.0 + 0.02 * Warmth), c.b * (1.0 - 0.08 * Warmth));
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float saturation = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    c = mix(vec3(luma), c, 1.0 + Vibrance * (1.0 - saturation));
    c = (c - 0.5) * Contrast + 0.5;
    float d = length((texCoord - 0.5) * vec2(1.25, 1.0)) * 1.35;
    c *= 1.0 - Vignette * smoothstep(0.35, 0.95, d);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
