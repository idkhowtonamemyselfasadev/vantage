#version 330

// Hand Aura, step 1: where the first-person hands were drawn. The depth buffer was cleared just before the hands, so any
// pixel with depth below 1 is hand or held item. White where that is, transparent black elsewhere.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(InSampler, texCoord).r;
    float hand = depth < 0.99999 ? 1.0 : 0.0;
    fragColor = vec4(hand, hand, hand, hand);
}
