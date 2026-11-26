#version 330

// Vantage Motion Blur, neighbour pass: the longest tile velocity within Stage.z tiles around each tile (McGuire 2012
// "neighbor max"). The radius covers half the max blur, the farthest a foreground streak can reach over its background.

uniform sampler2D TileSampler;   // tile max velocities (motion_blur_tile.fsh), 16 bits per component in RGBA8; texelFetch only

layout(std140) uniform SamplerInfo {
    vec2 OutSize;   // tile texture size
    vec2 InSize;    // tile texture size
};

layout(std140) uniform BlurData {
    mat4 Reproject;
    vec4 Params;    // y max blur in screen pixels (the packing scale)
    vec4 Stage;     // x 4 = neighbour pass, y tile size, z radius in tiles (1..8)
    vec4 ViewZ;
    vec4 ViewW;
};

out vec4 fragColor;

vec2 decodeVel(vec4 c) {
    vec4 b = floor(c * 255.0 + 0.5);
    return (vec2(b.x * 256.0 + b.y, b.z * 256.0 + b.w) - 32767.0) / 32767.0 * Params.y;
}

vec4 encodeVel(vec2 v) {
    vec2 u = floor(clamp(v / max(Params.y, 1e-3), -1.0, 1.0) * 32767.0 + 32767.5);
    vec2 hi = floor(u / 256.0);
    vec2 lo = u - hi * 256.0;
    return (vec4(hi.x, lo.x, hi.y, lo.y) + 0.25) / 255.0;
}

void main() {
    int r = clamp(int(Stage.z + 0.5), 1, 8);
    ivec2 size = ivec2(InSize + 0.5);
    ivec2 c = ivec2(gl_FragCoord.xy);
    vec2 best = vec2(0.0);
    float bestL = -1.0;
    for (int dy = -8; dy <= 8; dy++) {
        if (dy < -r || dy > r) continue;
        int y = c.y + dy;
        if (y < 0 || y >= size.y) continue;
        for (int dx = -8; dx <= 8; dx++) {
            if (dx < -r || dx > r) continue;
            int x = c.x + dx;
            if (x < 0 || x >= size.x) continue;
            vec2 v = decodeVel(texelFetch(TileSampler, ivec2(x, y), 0));
            float l = dot(v, v);
            if (l > bestL) { bestL = l; best = v; }
        }
    }
    fragColor = encodeVel(best);
}
