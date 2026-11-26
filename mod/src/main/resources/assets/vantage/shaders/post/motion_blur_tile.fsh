#version 330

// Vantage Motion Blur, tile pass: the longest velocity in each T x T tile of the screen (McGuire 2012 "tile max").
// Output: one texel per tile, the velocity in screen pixels packed 16 bits per component into RGBA8. The next pass widens it
// to the neighbouring tiles, so a still background pixel can find a fast foreground that will smear over it.
// The velocity is the same reprojection as motion_blur.fsh (keep the two in step).

uniform sampler2D DepthSampler;  // world depth, DEPTH32 0..1 (1 = sky); texelFetch only

layout(std140) uniform SamplerInfo {
    vec2 OutSize;   // tile texture size
    vec2 InSize;    // full screen size (the depth texture)
};

layout(std140) uniform BlurData {
    mat4 Reproject; // current NDC (homogeneous) -> previous clip space
    vec4 Params;    // x shutter, y max blur in screen pixels, z taps, w debug
    vec4 Stage;     // x 3 = tile pass, y tile size in pixels, z sample stride inside a tile
    vec4 ViewZ;     // row 2 of inverse(P) (unused here)
    vec4 ViewW;     // row 3 of inverse(P) (unused here)
};

out vec4 fragColor;

vec2 velocity(vec2 uv, float d, vec2 size) {
    vec4 prev = Reproject * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    if (!(prev.w > 1e-6)) return vec2(0.0);
    vec2 v = (uv - (prev.xy / prev.w * 0.5 + 0.5)) * size * Params.x;
    float l = length(v);
    if (!(l < 1e30)) return vec2(0.0);
    if (l > Params.y) v *= Params.y / l;
    return v;
}

vec4 encodeVel(vec2 v) {          // [-max, max] px -> 0..65534 per component, 0 px = 32767 exactly; +0.25 survives round or truncate
    vec2 u = floor(clamp(v / max(Params.y, 1e-3), -1.0, 1.0) * 32767.0 + 32767.5);
    vec2 hi = floor(u / 256.0);
    vec2 lo = u - hi * 256.0;
    return (vec4(hi.x, lo.x, hi.y, lo.y) + 0.25) / 255.0;
}

void main() {
    int T = max(1, int(Stage.y + 0.5));
    int stride = max(1, int(Stage.z + 0.5));
    ivec2 full = ivec2(InSize + 0.5);
    ivec2 base = ivec2(gl_FragCoord.xy) * T;
    vec2 best = vec2(0.0);
    float bestL = -1.0;
    for (int y = 0; y < 32; y++) {                      // fixed bounds + break: T / stride is at most 16
        int py = y * stride + stride / 2;
        if (py >= T) break;
        int ty = base.y + py;
        if (ty >= full.y) break;
        for (int x = 0; x < 32; x++) {
            int px = x * stride + stride / 2;
            if (px >= T) break;
            int tx = base.x + px;
            if (tx >= full.x) break;
            float d = texelFetch(DepthSampler, ivec2(tx, ty), 0).r;
            vec2 v = velocity((vec2(tx, ty) + 0.5) / InSize, d, InSize);
            float l = dot(v, v);
            if (l > bestL) { bestL = l; best = v; }
        }
    }
    fragColor = encodeVel(best);
}
