#version 330

// Vantage Motion Blur: camera motion blur from depth reprojection, two passes that act as one long smooth streak.
// - Every pixel is rebuilt from the world depth and moved to where it sat on screen last frame. That uses the previous camera
//   rotation and position with THIS frame's projection, so view bob, hurt tilt and FOV/zoom changes cancel out.
// - The screen distance between the two is the pixel's velocity (scaled by the shutter, capped at the max blur).
// - Gathering only along a pixel's own velocity cannot smear a moving foreground out over a still background (the silhouette
//   would stay razor sharp). So each pixel also knows the longest velocity around it (tile max + neighbour max passes) and
//   gathers along that when its own motion is much shorter, as a reconstruction filter (McGuire 2012, layered):
//     front layer: taps nearer the camera whose own streak reaches this pixel cover it, by g / |v| per tap;
//     own layer:   this pixel plus taps at its depth, the same way;
//     back layer:  taps behind it show through where its own streak leaves the pixel uncovered.
//   Composited front to back, a fast foreground fades out symmetrically across its silhouette while the still background
//   under it stays sharp, and a uniformly moving surface is a plain box blur.
// - Stage 1 (frame -> scratch): that filter with n taps over the streak, one "gap" g = streak / n apart. Its alpha says how
//   much of the result is not the pixel itself.
//   Stage 2 (scratch -> frame): the same streak rebuilt, m taps over ONE gap of stage 1's result, so n * m evenly spaced taps
//   smooth out stage 1's staircase. It only touches pixels stage 1 actually blurred (or that sit next to one).
// - Only this frame is read: no history, no ghosting, no trail lagging behind.
// - Neighbourhoods moving under half a pixel: stage 1 writes them as they are, stage 2 discards them, so the frame keeps them
//   byte-exact and a still camera looks exactly like vanilla.

uniform sampler2D InSampler;     // stage 1: the frame; stage 2: stage 1's result (alpha = blur amount). LINEAR, taps land between texels
uniform sampler2D DepthSampler;  // world depth, DEPTH32 0..1 (1 = nothing drawn, sky); texelFetch only
uniform sampler2D TileSampler;   // neighbour max velocity per tile (motion_blur_near.fsh), 16 bits per component in RGBA8; texelFetch only

layout(std140) uniform SamplerInfo {
    vec2 OutSize;   // target size in pixels
    vec2 InSize;    // input size (equals OutSize)
};

layout(std140) uniform BlurData {
    mat4 Reproject; // P * Rprev * T(camNow - camPrev) * inverse(P * Rnow): current NDC (homogeneous) -> previous clip space
    vec4 Params;    // x shutter = Strength * 60 Hz frame-time scale, y max blur in target pixels, z taps (4..16), w 1 = velocity debug view
    vec4 Stage;     // x 1 = stage 1 (frame -> scratch), 2 = stage 2 (scratch -> frame); y tile size in pixels
    vec4 ViewZ;     // row 2 of inverse(P): view-space z = dot(ViewZ, ndc)
    vec4 ViewW;     // row 3 of inverse(P): view-space w = dot(ViewW, ndc)
};

in vec2 texCoord;

out vec4 fragColor;

// Window depth -> OpenGL NDC: JOML perspective() without zZeroToOne and no clip control in 1.21.11, so z = depth*2-1.
// screenquad texCoord (0,0) is NDC (-1,-1); colour and depth share that orientation, so no flip anywhere.
vec2 velocity(vec2 uv, float d) {
    vec4 prev = Reproject * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    if (!(prev.w > 1e-6)) return vec2(0.0);                         // behind the previous camera: no direction, sharp
    vec2 v = (uv - (prev.xy / prev.w * 0.5 + 0.5)) * OutSize * Params.x;
    float l = length(v);
    if (!(l < 1e30)) return vec2(0.0);                              // NaN / inf: sharp
    if (l > Params.y) v *= Params.y / l;                            // max blur: a flick never turns the screen to soup
    return v;
}

// distance in front of the camera (blocks); only compared as a ratio
float viewDist(vec2 uv, float d) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    float w = dot(ViewW, ndc);
    float z = -dot(ViewZ, ndc) / (abs(w) > 1e-12 ? w : 1e-12);
    if (!(z > 1e-3)) z = 1e-3;
    if (!(z < 1e7)) z = 1e7;
    return z;
}

vec2 decodeVel(vec4 c) {
    vec4 b = floor(c * 255.0 + 0.5);
    return (vec2(b.x * 256.0 + b.y, b.z * 256.0 + b.w) - 32767.0) / 32767.0 * Params.y;
}

void main() {
    bool first = Stage.x < 1.5;
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 c0 = texelFetch(InSampler, texel, 0);
    vec3 centre = c0.rgb;
    float depth = texelFetch(DepthSampler, texel, 0).r;
    vec2 vc = velocity(texCoord, depth);
    float lc = length(vc);
    int T = max(1, int(Stage.y + 0.5));
    vec2 vn = decodeVel(texelFetch(TileSampler, min(texel / T, textureSize(TileSampler, 0) - 1), 0));
    float ln = length(vn);
    if (lc > ln) { vn = vc; ln = lc; }                              // never shorter than the pixel's own motion

    if (Params.w > 0.5) {                                           // -Dvantage.blurDebug=true: red/green = own velocity, blue = neighbourhood max
        fragColor = first ? vec4(0.5 + 0.5 * vc / Params.y, 0.5 + 0.5 * ln / Params.y, 1.0) : vec4(centre, 1.0);
        return;
    }
    if (!(ln >= 0.5)) {                                             // nothing around moves
        if (first) { fragColor = vec4(centre, 0.0); return; }       // stage 2 reads its neighbours from here: write it as it is
        discard;                                                    // the frame keeps it untouched, byte-exact
    }

    // The streak to gather along: the pixel's own velocity when it moves about as fast as its neighbourhood (smooth surfaces,
    // any turn: no tile seams), the neighbourhood max when it is much slower (a still background next to a fast foreground).
    vec2 va = dot(vc, vn) < 0.0 ? -vc : vc;                         // a blur has no sign: face the same way before mixing
    vec2 vs = mix(vn, va, smoothstep(0.4, 0.8, lc / ln));
    float L = length(vs);
    if (L < 0.25) { vs = vn; L = ln; }
    int n = int(clamp(min(Params.z, ceil(L)), 2.0, 16.0));          // a 3 px streak needs 3 taps, not 16
    n = min(n + (n & 1), 16);                                       // even: taps sit in pairs around the pixel, never on it
    float g = L / float(n);                                         // gap between stage 1 taps, in pixels
    vec2 dirUv = vs / (L * OutSize);                                // one pixel along the streak, in uv

    if (first) {
        float zc = viewDist(texCoord, depth);
        float s0 = min(1.0, 1.0 / max(lc, 1e-4));                   // the pixel's own point covers it for 1 / |v| of the shutter
        vec3 F = vec3(0.0), O = centre * s0, B = vec3(0.0);
        float Af = 0.0, Ao = s0, Wb = 0.0;
        float rc = 0.5 * lc;                                        // how far the pixel's own streak reaches
        for (int i = 0; i < 16; i++) {                              // fixed bound + break: safe on every GL 3.3 driver
            if (i >= n) break;
            float t = (float(i) + 0.5) / float(n) - 0.5;            // evenly spread over -0.5 .. +0.5: centred on the pixel, never trailing
            vec2 uv = texCoord + dirUv * (t * L);
            if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) continue;   // off-screen taps dropped: no smeared border
            float dist = abs(t) * L;
            ivec2 tp = clamp(ivec2(uv * OutSize), ivec2(0), ivec2(OutSize) - 1);
            vec2 tuv = (vec2(tp) + 0.5) / OutSize;
            float dy = texelFetch(DepthSampler, tp, 0).r;
            float ly = length(velocity(tuv, dy));
            float q = log(zc / viewDist(tuv, dy));                  // > 0: the tap is nearer the camera than this pixel
            float fy = clamp((q - 0.02) * 25.0, 0.0, 1.0);          // front from 2 % nearer, fully at 6 %
            float by = clamp((-q - 0.02) * 25.0, 0.0, 1.0);         // behind, the same way
            vec3 col = texture(InSampler, uv).rgb;
            // the tap's own streak reaches this pixel (soft over one gap, so a reach crossing a tap never pops) and spreads
            // over |v| pixels: one tap stands for g pixels of it
            float c = g * clamp((0.5 * ly - dist) / g + 0.5, 0.0, 1.0) / max(ly, 1.0);
            F += col * (fy * c); Af += fy * c;
            float sy = 1.0 - fy - by;
            O += col * (sy * c); Ao += sy * c;
            float wb = by * clamp((rc - dist) / g + 0.5, 0.0, 1.0); // behind: seen through this pixel's own blur
            B += col * wb; Wb += wb;
        }
        float af = min(Af, 1.0);
        float ao = min(Ao, 1.0) * (1.0 - af);
        float ab = max(1.0 - af - ao, 0.0);
        vec3 ownC = O / Ao;
        bool back = Wb > 1e-4;
        vec3 res = (Af > 1e-6 ? F * (af / Af) : vec3(0.0)) + ownC * ao + (back ? B / Wb : ownC) * ab;
        float keep = s0 / Ao * (ao + (back ? 0.0 : ab));            // share of this pixel's own colour in res
        float ease = smoothstep(0.5, 1.5, ln);                      // eases in over the first pixel of motion so nothing pops
        fragColor = vec4(mix(centre, res, ease), clamp((1.0 - keep) * ease, 0.0, 1.0));
        return;
    }

    // stage 2: m taps over one gap of stage 1, only where stage 1 blurred something (alpha) at or next to this pixel
    int m = int(clamp(min(Params.z, ceil(g)), 1.0, 16.0));          // about one tap per pixel of the gap
    vec3 sum = vec3(0.0);
    float weight = 0.0, amax = c0.a;
    for (int i = 0; i < 16; i++) {
        if (i >= m) break;
        float t = (float(i) + 0.5) / float(m) - 0.5;
        vec2 uv = texCoord + dirUv * (t * g);
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) continue;
        vec4 s = texture(InSampler, uv);
        sum += s.rgb;
        weight += 1.0;
        amax = max(amax, s.a);
    }
    float k = clamp(amax * float(n), 0.0, 1.0);                     // a pixel stage 1 left alone (alpha 0 all round) stays byte-exact
    if (!(k > 0.0) || weight <= 0.0) discard;
    fragColor = vec4(mix(centre, sum / weight, k), 1.0);
}
// Java side: BlurData = 128 bytes (mat4 64 + 4 x vec4 16) via u.putMat4f(reproject).putVec4(shutter, maxPx, taps, debug)
// .putVec4(stage, tile, extra, 0).putVec4(viewZ).putVec4(viewW). Std140Builder.putMat4f writes column-major like a GLSL mat4,
// so Reproject * v equals the JOML transform.
