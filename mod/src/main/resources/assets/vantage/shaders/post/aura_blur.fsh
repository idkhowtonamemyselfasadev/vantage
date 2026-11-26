#version 330

// Hand Aura, step 2: a 13-tap Gaussian along one direction; run once across and once down to spread the hand mask into a glow.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlurData {
    vec2 Dir;      // (1,0) or (0,1)
    float Reach;   // texel step between taps
    float Pad;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    const float w[7] = float[](0.1964825, 0.1746813, 0.1210987, 0.0654780, 0.0276306, 0.0090793, 0.0023300);
    vec2 step = Dir * Reach / InSize;
    float sum = texture(InSampler, texCoord).r * w[0];
    for (int i = 1; i < 7; i++) {
        sum += texture(InSampler, texCoord + step * float(i)).r * w[i];
        sum += texture(InSampler, texCoord - step * float(i)).r * w[i];
    }
    fragColor = vec4(sum, sum, sum, sum);
}
