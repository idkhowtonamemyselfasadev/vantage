package dev.vantage.module.cosmetic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vantage.core.util.Colors;
import dev.vantage.render.R3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * The Pet's "Real Fox": a smooth red fox with layered fur instead of the blocky vanilla model. The Pet module still walks an unseen
 * vanilla fox as the pose holder (position, heading, sitting); this class rebuilds the fox's body around it every frame.
 *
 * Shape: every part is a tube lofted through a few control points (spine, head, four legs, tail, ears), so it bends smoothly.
 * Fur: the same surface is drawn again in thin shells pushed outward, each shell showing fewer strands of one shared strand map
 * (shell fur), so the coat has real depth and a soft outline. Motion: the legs are solved to planted footsteps (two-bone IK), walk
 * turns into a bounding run with speed, the tail trails and swings behind turns, it sits on its haunches, breathes, blinks and
 * flicks its ears. Lit by the world light where it stands. Drawn on this client only.
 */
final class RealFox {
    private RealFox() {}

    private static final Identifier TEX = Identifier.fromNamespaceAndPath("vantage", "pet/realfox_fur");
    private static final Identifier STRANDS = Identifier.fromNamespaceAndPath("vantage", "textures/pet/fur_strands.png");
    private static final int TILE = 64, SHELLS = 12, PERIOD = 8;
    private static final int ORANGE = 0xD8782C, RUST = 0xB45A1E, CREAM = 0xF4EEE4, DARK = 0x2B1F1B, NOSE = 0x17120F, EAR_IN = 0xE9D6C6;
    private static final double PY = 0.2, PZ = -0.24; // the point the body tips around when it sits

    private static boolean texReady;
    private static final Random RNG = new Random(7);

    // animation state, one game tick apart (drawn in between)
    private static double phase, phaseO, amp, ampO, run, runO, sit, sitO, turn, turnO, blink, blinkO, ear, earO;
    private static int blinkIn = 60, blinkLeft, earIn = 90, earLeft;
    private static float lastYaw;
    private static boolean haveYaw;

    /** For the rig: quads sent in the last frame. */
    static int drawn;

    private interface Col { int at(double u, double sa, double ca); }
    private interface Fur { double at(double u, double sa, double ca); }

    private static final class Patch {
        int rings, cols;
        boolean wrap;
        double[] p, n, t, f;
        int[] c;
        double lean;
    }

    static void reset() {
        phase = phaseO = amp = ampO = run = runO = sit = sitO = turn = turnO = 0;
        haveYaw = false;
    }

    /** Once per game tick, from the Pet module. */
    static void tick(double moved, boolean sitting, float bodyYaw) {
        phaseO = phase; ampO = amp; runO = run; sitO = sit; turnO = turn; blinkO = blink; earO = ear;
        amp += (Math.min(1, moved / 0.06) - amp) * 0.3;
        run += (Mth.clamp((moved - 0.12) / 0.2, 0, 1) - run) * 0.2;
        phase += moved / (0.5 + 0.7 * run);
        sit += ((sitting ? 1 : 0) - sit) * 0.14;
        double d = haveYaw ? Mth.wrapDegrees(bodyYaw - lastYaw) : 0;
        lastYaw = bodyYaw; haveYaw = true;
        turn += (Mth.clamp(d, -20, 20) - turn) * 0.25;
        if (--blinkIn <= 0) { blinkLeft = 4; blinkIn = 50 + RNG.nextInt(90); }
        blink += ((blinkLeft-- > 0 ? 1 : 0) - blink) * 0.6;
        if (--earIn <= 0) { earLeft = 5; earIn = 60 + RNG.nextInt(140); }
        ear += ((earLeft-- > 0 ? 1 : 0) - ear) * 0.5;
    }

    // ---- the strand map ----------------------------------------------------------------------------------------------------

    /** One image, 4 x 4 tiles: tile 0 is the solid coat, tiles 1..12 keep only the strands taller than that shell, tile 15 is plain white. */
    private static void texture() {
        if (texReady) return;
        NativeImage img = new NativeImage(TILE * 4, TILE * 4, true);
        double[][] hgt = new double[TILE][TILE], bri = new double[TILE][TILE];
        // The strand map: how tall the hair is at each point and how bright. It comes from a generated fur picture
        // (assets/vantage/textures/pet/fur_strands.png, 64 x 64 grey, made to repeat without a seam), so strands run in streaks
        // the way hair lies, instead of as random dots.
        boolean fromPicture = false;
        try (var in = Minecraft.getInstance().getResourceManager().getResource(STRANDS).orElseThrow().open(); NativeImage map = NativeImage.read(in)) {
            if (map.getWidth() == TILE && map.getHeight() == TILE) {
                for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
                    int px = map.getPixel(x, y);
                    double lum = (((px >> 16) & 255) + ((px >> 8) & 255) + (px & 255)) / 765.0;
                    hgt[x][y] = lum < 0.16 ? 0 : Math.pow(lum, 0.7); // the darkest sixth is the gap between strands
                    bri[x][y] = 0.72 + 0.28 * lum;
                }
                fromPicture = true;
            }
        } catch (Exception e) {
            dev.vantage.core.Vantage.LOG.warn("Real Fox: the fur picture could not be read, using plain strands", e);
        }
        if (!fromPicture) {
            Random r = new Random(4211);
            for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
                hgt[x][y] = r.nextDouble() < 0.2 ? 0 : Math.pow(r.nextDouble(), 0.55);
                bri[x][y] = 0.74 + 0.26 * r.nextDouble();
            }
        }
        for (int tile = 0; tile < 16; tile++) {
            int ox = (tile % 4) * TILE, oy = (tile / 4) * TILE;
            for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
                int a = 255, g = 255;
                if (tile == 0) g = (int) (255 * bri[x][y] * 0.9);
                else if (tile <= SHELLS) {
                    double h = tile / (double) (SHELLS + 1);
                    a = hgt[x][y] > Math.pow(h, 0.85) ? 255 : 0;
                    g = (int) (255 * Math.min(1, bri[x][y] * (0.86 + 0.2 * h)));
                }
                img.setPixel(ox + x, oy + y, (a << 24) | (g << 16) | (g << 8) | g);
            }
        }
        Minecraft.getInstance().getTextureManager().register(TEX, new DynamicTexture(TEX::toString, img));
        texReady = true;
    }

    // ---- one frame ---------------------------------------------------------------------------------------------------------

    static void render(R3 r, Mob pet, double s) {
        Minecraft mc = Minecraft.getInstance();
        drawn = 0;
        if (mc.level == null) return;
        float pt = r.partial;
        Vec3 cam = mc.gameRenderer.getMainCamera().position(), pos = pet.getPosition(pt);
        double dist = Math.sqrt(pos.distanceToSqr(cam));
        if (dist > 72) return;
        texture();
        double time = (mc.level.getGameTime() + pt) / 20.0;
        double ph = Mth.lerp(pt, phaseO, phase), am = Mth.lerp(pt, ampO, amp), rn = Mth.lerp(pt, runO, run), st = ease(Mth.lerp(pt, sitO, sit));
        double tr = Mth.lerp(pt, turnO, turn), bl = Mth.lerp(pt, blinkO, blink), et = Mth.lerp(pt, earO, ear);
        float bodyYaw = Mth.rotLerp(pt, pet.yBodyRotO, pet.yBodyRot);
        double headRel = Math.toRadians(Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(pt, pet.yHeadRotO, pet.yHeadRot) - bodyYaw), -65, 65));
        double headPitch = Math.toRadians(Mth.clamp(pet.getXRot(), -30, 35)) + 0.06 * Math.sin(time * 0.7);

        List<Patch> mesh = new ArrayList<>();
        build(mesh, time, ph, am, rn, st, tr, bl, et, headRel, headPitch);

        // a soft shadow on the ground
        for (int j = 0; j < 12; j++) {
            double a = Math.PI * 2 * j / 12, b = Math.PI * 2 * (j + 1) / 12, rad = 0.3 * s;
            r.quad(0x3A000000, false, (float) pos.x, (float) pos.y + 0.012f, (float) pos.z, (float) (pos.x + Math.cos(a) * rad), (float) pos.y + 0.012f, (float) (pos.z + Math.sin(a) * rad),
                    (float) (pos.x + Math.cos(b) * rad), (float) pos.y + 0.012f, (float) (pos.z + Math.sin(b) * rad), (float) pos.x, (float) pos.y + 0.012f, (float) pos.z);
        }

        double yaw = Math.toRadians(bodyYaw);
        final double rx = -Math.cos(yaw), rz = -Math.sin(yaw), fx = -Math.sin(yaw), fz = Math.cos(yaw);
        final int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.x, pos.y + 0.4, pos.z));
        final int shells = dist < 10 ? SHELLS : dist < 24 ? 6 : 3;
        r.custom((pose, src, cx, cy, cz) -> {
            RenderType rt = RenderTypes.entityCutoutNoCull(TEX);
            VertexConsumer vc = src.getBuffer(rt);
            for (int layer = 0; layer <= shells; layer++) {
                double h = layer / (double) shells;
                int tile = layer == 0 ? 0 : Math.max(1, (int) Math.round(h * SHELLS));
                float shadeK = (float) (0.7 + 0.3 * h);
                for (Patch m : mesh) emit(vc, pose, m, layer, h, tile, shadeK, light, pos, s, rx, rz, fx, fz, cx, cy, cz);
            }
            if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch(rt);
        });
    }

    private static void emit(VertexConsumer vc, PoseStack.Pose pose, Patch m, int layer, double h, int tile, float shadeK, int light, Vec3 pos, double s,
            double rx, double rz, double fx, double fz, float cx, float cy, float cz) {
        int jn = m.wrap ? m.cols : m.cols - 1;
        float tu = (tile % 4) / 4f, tv = (tile / 4) / 4f, q = 0.25f / PERIOD, eps = 0.0008f;
        if (layer == 0 && m.lean == -99) { tu = 0.75f; tv = 0.75f; } // flat details (eyes) use the plain white tile
        for (int i = 0; i + 1 < m.rings; i++) {
            for (int j = 0; j < jn; j++) {
                int j2 = m.wrap ? (j + 1) % m.cols : j + 1;
                int[] idx = {i * m.cols + j, i * m.cols + j2, (i + 1) * m.cols + j2, (i + 1) * m.cols + j};
                if (layer > 0 && m.f[idx[0]] + m.f[idx[1]] + m.f[idx[2]] + m.f[idx[3]] < 1e-5) continue;
                float u0 = tu + (j % PERIOD) * q + eps, u1 = tu + (j % PERIOD + 1) * q - eps, v0 = tv + (i % PERIOD) * q + eps, v1 = tv + (i % PERIOD + 1) * q - eps;
                if (m.lean == -99) { u0 = 0.76f; u1 = 0.99f; v0 = 0.76f; v1 = 0.99f; }
                float[] us = {u0, u1, u1, u0}, vs = {v0, v0, v1, v1};
                for (int k = 0; k < 4; k++) {
                    int a = idx[k] * 3;
                    double fl = m.f[idx[k]] * h, lean = m.lean == -99 ? 0 : m.lean;
                    double lx = m.p[a] + (m.n[a] * 0.62 + m.t[a] * lean) * fl, ly = m.p[a + 1] + (m.n[a + 1] * 0.62 + m.t[a + 1] * lean) * fl - fl * h * 0.35, lz = m.p[a + 2] + (m.n[a + 2] * 0.62 + m.t[a + 2] * lean) * fl;
                    float wx = (float) (pos.x + (rx * lx + fx * lz) * s), wy = (float) (pos.y + ly * s), wz = (float) (pos.z + (rz * lx + fz * lz) * s);
                    float nx = (float) (rx * m.n[a] + fx * m.n[a + 2]), ny = (float) m.n[a + 1], nz = (float) (rz * m.n[a] + fz * m.n[a + 2]);
                    int c = m.c[idx[k]];
                    float kk = m.f[idx[k]] > 0 ? shadeK : 1f;
                    int rr = Math.min(255, (int) (((c >> 16) & 255) * kk)), g = Math.min(255, (int) (((c >> 8) & 255) * kk)), b = Math.min(255, (int) ((c & 255) * kk));
                    vc.addVertex(pose, wx - cx, wy - cy, wz - cz).setColor(0xFF000000 | (rr << 16) | (g << 8) | b).setUv(us[k], vs[k]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
                }
                drawn++;
            }
        }
    }

    // ---- the body, in fox space: x to its right, y up, z forward, origin on the ground under its middle -----------------------------

    private static void build(List<Patch> out, double time, double ph, double am, double rn, double st, double tr, double bl, double et, double headRel, double headPitch) {
        final double th = st * 0.5, cs = Math.cos(th), sn = Math.sin(th), drop = 0.165 * st;
        final double bob = Math.sin(ph * Math.PI * 4) * 0.006 * am + Math.abs(Math.sin(ph * Math.PI * 2)) * 0.022 * rn * am;
        final double rock = Math.sin(ph * Math.PI * 2) * 0.11 * rn * am; // the back rocks in a bounding run
        final double br = 1 + 0.022 * Math.sin(time * 1.9);
        // sp: a point of the standing fox moved to where it is now (rocking, sitting down, bobbing)
        java.util.function.Function<double[], double[]> sp = p -> {
            double y = p[1] + p[2] * rock, dy = y - PY, dz = p[2] - PZ;
            return new double[] {p[0] + Math.sin(ph * Math.PI * 2 + p[2] * 4) * 0.008 * am, PY + dy * cs + dz * sn - drop + bob, PZ + dz * cs - dy * sn, p.length > 3 ? p[3] : 0, p.length > 4 ? p[4] : 0};
        };

        // torso and neck, rump to throat
        double[][] spine = {{0, 0.325, -0.335, 0.004, 0.004}, {0, 0.325, -0.31, 0.05, 0.055}, {0, 0.335, -0.24, 0.082, 0.092}, {0, 0.325, -0.08, 0.088, 0.098},
                {0, 0.33, 0.10, 0.092 * br, 0.108 * br}, {0, 0.345, 0.22, 0.08, 0.098}, {0, 0.385, 0.29, 0.06, 0.075}, {0, 0.44, 0.345, 0.05, 0.058}, {0, 0.485, 0.385, 0.046, 0.05}};
        for (int i = 0; i < spine.length; i++) spine[i] = sp.apply(spine[i]);
        tube(out, spine, 18, 12, new double[] {0, 1, 0}, (u, sa, ca) -> {
            double edge = -0.5 + Math.max(0, u - 0.62) * 2.0; // the white of the belly climbs up the chest and throat
            int base = sa > 0.82 ? Colors.mix(ORANGE, RUST, 0.55) : ORANGE;
            return Colors.mix(CREAM, base, smooth(edge, edge + 0.4, sa));
        }, (u, sa, ca) -> u < 0.04 ? 0.012 : (sa < -0.3 && u > 0.55 ? 0.05 : 0.034), -0.55);

        // head
        double[] hp = spine[spine.length - 1];
        double cp = Math.cos(headPitch), spn = Math.sin(headPitch);
        double[] fH = {Math.sin(headRel) * cp, -spn, Math.cos(headRel) * cp}, rH = {Math.cos(headRel), 0, -Math.sin(headRel)}, uH = cross(fH, rH);
        double[][] hs = {{-0.075, 0, 0.004, 0.004}, {-0.06, 0.005, 0.04, 0.042}, {-0.02, 0.012, 0.066, 0.062}, {0.025, 0.008, 0.068, 0.058}, {0.065, -0.004, 0.046, 0.04},
                {0.105, -0.014, 0.03, 0.026}, {0.15, -0.02, 0.022, 0.019}, {0.176, -0.02, 0.016, 0.014}, {0.187, -0.02, 0.003, 0.003}};
        double[][] head = new double[hs.length][];
        for (int i = 0; i < hs.length; i++) { double[] c = add(hp, add(mul(fH, hs[i][0] + 0.03), mul(uH, hs[i][1] + 0.012))); head[i] = new double[] {c[0], c[1], c[2], hs[i][2], hs[i][3]}; }
        tube(out, head, 16, 12, uH, (u, sa, ca) -> {
            if (u > 0.93) return NOSE;
            if (u > 0.5) return Colors.mix(CREAM, ORANGE, smooth(0.15, 0.6, sa));
            return Colors.mix(CREAM, ORANGE, smooth(-0.55, -0.1, sa));
        }, (u, sa, ca) -> u > 0.93 ? 0 : u > 0.55 ? 0.006 : (Math.abs(ca) > 0.55 && sa < 0.25 && u > 0.12 ? 0.046 : (u > 0.34 ? 0.011 : 0.022)), -0.5);

        // eyes: a dark rim, an amber iris, a pupil and a glint, closing when it blinks
        for (int sg = -1; sg <= 1; sg += 2) {
            double[] nrm = norm(add(add(mul(rH, sg * 0.8), mul(fH, 0.55)), mul(uH, 0.2)));
            double[] e1 = norm(cross(uH, nrm)), e2 = norm(cross(nrm, e1));
            double[] c = add(add(hp, add(mul(fH, 0.03 + 0.047), mul(uH, 0.012 + 0.026))), add(mul(rH, sg * 0.05), mul(nrm, 0.017)));
            double open = 1 - 0.92 * bl;
            flat(out, c, e1, e2, nrm, 0.019, 0.0115 * open + 0.002, 0x15100D, 0);
            flat(out, c, e1, e2, nrm, 0.0145, 0.0085 * open, 0x7A4714, 0.0012);
            flat(out, c, e1, e2, nrm, 0.0058, 0.008 * open, 0x0A0706, 0.0022);
            flat(out, add(c, add(mul(e1, -0.0035 * sg), mul(e2, 0.003 * open))), e1, e2, nrm, 0.0024, 0.0024 * open, 0xFFFFFF, 0.003);
        }

        // ears: dark behind, pale inside, one of them flicks now and then
        for (int sg = -1; sg <= 1; sg += 2) {
            double flick = sg > 0 ? et * 0.35 : et * 0.1;
            double[] axis = norm(add(add(uH, mul(rH, sg * (0.3 + flick))), mul(fH, -0.08 - flick * 0.4)));
            double[] eb = add(hp, add(add(mul(fH, 0.03 - 0.022), mul(uH, 0.012 + 0.05)), mul(rH, sg * 0.043)));
            double[][] es = {{-0.012, 0.034, 0.014}, {0.035, 0.031, 0.012}, {0.075, 0.019, 0.008}, {0.108, 0.006, 0.004}, {0.116, 0.002, 0.002}};
            double[][] earC = new double[es.length][];
            for (int i = 0; i < es.length; i++) { double[] c = add(eb, mul(axis, es[i][0])); earC[i] = new double[] {c[0], c[1], c[2], es[i][1], es[i][2]}; }
            tube(out, earC, 8, 8, mul(fH, -1), (u, sa, ca) -> u > 0.8 ? DARK : sa > 0.1 ? Colors.mix(RUST, DARK, smooth(0.25, 0.7, u)) : EAR_IN, (u, sa, ca) -> sa < 0 ? 0.004 : 0.008, 0.3);
        }

        // legs: every paw follows its own footstep, the joints are solved from hip to paw
        double cyc = 0.5 + 0.7 * rn, reach = (0.26 + 0.06 * rn) * am, duty = Mth.clamp(0.32 / cyc, 0.25, 0.6), lift = (0.04 + 0.04 * rn) * am;
        double[] offTrot = {0, 0.5, 0.5, 0}, offRun = {0, 0.12, 0.55, 0.67};
        for (int leg = 0; leg < 4; leg++) {
            boolean front = leg < 2;
            int sg = leg % 2 == 0 ? -1 : 1;
            double lp = frac(ph + Mth.lerp(rn, offTrot[leg], offRun[leg])), dz, dy = 0;
            if (lp < duty) dz = reach * (0.5 - lp / duty);
            else { double q = ease((lp - duty) / (1 - duty)); dz = reach * (q - 0.5); dy = Math.sin(Math.PI * q) * lift; }
            if (front) {
                double[] j = sp.apply(new double[] {sg * 0.058, 0.30, 0.20});
                double[] foot = {j[0], 0.035 + dy * (1 - st), Mth.lerp(st, 0.20 + dz, j[2] + 0.02)};
                double[][] k = ik(j, foot, 0.15, 0.145, -1);
                double[][] c = {{j[0] * 0.8, j[1] + 0.05, j[2], 0.03, 0.04}, {j[0], j[1], j[2], 0.036, 0.046}, {k[0][0], k[0][1], k[0][2], 0.026, 0.03}, {k[1][0], k[1][1] + 0.01, k[1][2], 0.02, 0.022}, {k[1][0], k[1][1] - 0.012, k[1][2] + 0.004, 0.019, 0.021}};
                tube(out, c, 10, 8, new double[] {0, 0, 1}, (u, sa, ca) -> Colors.mix(ORANGE, DARK, smooth(0.38, 0.62, u)), (u, sa, ca) -> u < 0.4 ? 0.018 : 0.008, 0.5);
                paw(out, k[1]);
            } else {
                double[] j = sp.apply(new double[] {sg * 0.062, 0.30, -0.22});
                double[] foot = {sg * 0.066 * (1 + 0.5 * st), 0.035 + dy * (1 - st), Mth.lerp(st, -0.24 + dz, j[2] + 0.15)};
                double[] hock = {foot[0], foot[1] + Mth.lerp(st, 0.09, 0.012), foot[2] - Mth.lerp(st, 0.03, 0.095)};
                double[][] k = ik(j, hock, 0.15, 0.15, 1);
                double[] hk = k[1], ft = {hk[0], foot[1], hk[2] + (foot[2] - hock[2])};
                double[][] c = {{j[0] * 0.8, j[1] + 0.05, j[2], 0.04, 0.05}, {j[0], j[1], j[2], 0.05, 0.066}, {k[0][0], k[0][1], k[0][2], 0.034, 0.042}, {hk[0], hk[1], hk[2], 0.021, 0.025}, {ft[0], ft[1] + 0.005, ft[2], 0.019, 0.021}};
                tube(out, c, 12, 8, new double[] {0, 0, 1}, (u, sa, ca) -> Colors.mix(ORANGE, DARK, smooth(0.55, 0.78, u)), (u, sa, ca) -> u < 0.5 ? 0.022 : 0.008, 0.5);
                paw(out, ft);
            }
        }

        // tail: big and bushy, trails behind, swings out when it turns, curls round when it sits
        final int n = 12;
        double[][] tail = new double[n + 2][];
        double[] at = sp.apply(new double[] {0, 0.335, -0.30});
        double yawAcc = 0;
        tail[0] = new double[] {at[0], at[1], at[2] + 0.03, 0.02, 0.02};
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            double rest = -40 + 24 * u, moving = -12 + 8 * Math.sin(ph * Math.PI * 4 - i * 0.55) + 10 * rn;
            double elev = Math.toRadians(Mth.lerp(st, Mth.lerp(am, rest, moving), -34 + 30 * u));
            yawAcc += Math.toRadians(Math.sin(time * 1.4 - i * 0.38) * 3.2 * (1 - 0.6 * am) - tr * 0.85 + 9 * st);
            double rad = 0.02 + 0.052 * Math.pow(Math.sin(Math.PI * (0.07 + 0.86 * u)), 0.8);
            tail[i + 1] = new double[] {at[0], Math.max(at[1], rad * 0.55 + 0.045), at[2], rad, rad};
            at = add(at, mul(new double[] {Math.sin(yawAcc) * Math.cos(elev), Math.sin(elev), -Math.cos(yawAcc) * Math.cos(elev)}, 0.044));
        }
        tail[n + 1][3] = 0.004; tail[n + 1][4] = 0.004;
        tube(out, tail, 20, 10, new double[] {0, 1, 0}, (u, sa, ca) -> Colors.mix(Colors.mix(sa > 0.6 ? Colors.mix(ORANGE, RUST, 0.6) : ORANGE, DARK, 0.5 * smooth(0.6, 0.74, u) * (1 - smooth(0.76, 0.82, u))), CREAM, smooth(0.76, 0.84, u)),
                (u, sa, ca) -> 0.028 + 0.034 * Math.sin(Math.PI * Math.min(1, u * 1.15)), 0.75);
    }

    private static void paw(List<Patch> out, double[] ankle) {
        double y = Math.max(0.019, ankle[1] - 0.017);
        double[][] c = {{ankle[0], y, ankle[2] - 0.026, 0.004, 0.004}, {ankle[0], y, ankle[2] - 0.014, 0.02, 0.016}, {ankle[0], y, ankle[2] + 0.02, 0.025, 0.018}, {ankle[0], y - 0.003, ankle[2] + 0.046, 0.019, 0.014}, {ankle[0], y - 0.004, ankle[2] + 0.06, 0.003, 0.003}};
        tube(out, c, 7, 8, new double[] {0, 1, 0}, (u, sa, ca) -> DARK, (u, sa, ca) -> 0.005, 0.4);
    }

    /** Two-bone leg in the fox's side plane: the middle joint and where the leg really ends (the target, or as far as it reaches). */
    private static double[][] ik(double[] hip, double[] target, double l1, double l2, int bend) {
        double dy = target[1] - hip[1], dz = target[2] - hip[2], d = Math.sqrt(dy * dy + dz * dz), dd = Mth.clamp(d, 0.02, l1 + l2 - 0.002);
        double uy = dy / Math.max(d, 1e-6), uz = dz / Math.max(d, 1e-6);
        double a = (l1 * l1 - l2 * l2 + dd * dd) / (2 * dd), hgt = Math.sqrt(Math.max(0, l1 * l1 - a * a));
        double x1 = hip[0] + (target[0] - hip[0]) * 0.5;
        return new double[][] {{x1, hip[1] + uy * a + uz * hgt * bend, hip[2] + uz * a - uy * hgt * bend}, {target[0], hip[1] + uy * dd, hip[2] + uz * dd}};
    }

    // ---- surfaces ----------------------------------------------------------------------------------------------------------

    /** A tube lofted through control points {x, y, z, half width, half height}, smoothed, with a colour and a fur length for every point. */
    private static void tube(List<Patch> out, double[][] ctrl, int rings, int sides, double[] hint, Col col, Fur fur, double lean) {
        Patch m = new Patch();
        m.rings = rings; m.cols = sides; m.wrap = true; m.lean = lean;
        m.p = new double[rings * sides * 3]; m.n = new double[rings * sides * 3]; m.t = new double[rings * sides * 3]; m.f = new double[rings * sides]; m.c = new int[rings * sides];
        double[][] q = new double[rings][];
        for (int i = 0; i < rings; i++) q[i] = spline(ctrl, i / (double) (rings - 1));
        for (int i = 0; i < rings; i++) {
            double[] a = q[Math.max(0, i - 1)], b = q[Math.min(rings - 1, i + 1)];
            double[] t = norm(new double[] {b[0] - a[0], b[1] - a[1], b[2] - a[2]}), side = norm(cross(hint, t)), upv = cross(t, side);
            double u = i / (double) (rings - 1);
            for (int j = 0; j < sides; j++) {
                double ang = Math.PI * 2 * j / sides, ca = Math.cos(ang), sa = Math.sin(ang);
                int k = (i * sides + j) * 3;
                double[] nn = norm(add(mul(side, ca * q[i][4]), mul(upv, sa * q[i][3])));
                for (int x = 0; x < 3; x++) {
                    m.p[k + x] = q[i][x] + side[x] * ca * q[i][3] + upv[x] * sa * q[i][4];
                    m.n[k + x] = nn[x];
                    m.t[k + x] = t[x];
                }
                m.c[i * sides + j] = col.at(u, sa, ca);
                m.f[i * sides + j] = fur.at(u, sa, ca);
            }
        }
        out.add(m);
    }

    /** A small flat four-sided detail (no fur), pushed a hair off the surface. */
    private static void flat(List<Patch> out, double[] c, double[] e1, double[] e2, double[] nrm, double w, double h, int color, double off) {
        Patch m = new Patch();
        m.rings = 2; m.cols = 2; m.wrap = false; m.lean = -99;
        m.p = new double[12]; m.n = new double[12]; m.t = new double[12]; m.f = new double[4]; m.c = new int[] {color, color, color, color};
        double[][] sgn = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}}; // a diamond: wide and low makes an almond eye
        for (int v = 0; v < 4; v++) for (int x = 0; x < 3; x++) {
            m.p[v * 3 + x] = c[x] + e1[x] * w * sgn[v][0] + e2[x] * h * sgn[v][1] + nrm[x] * off;
            m.n[v * 3 + x] = nrm[x];
        }
        out.add(m);
    }

    /** Catmull-Rom through the control points, all five numbers at once. */
    private static double[] spline(double[][] c, double u) {
        double x = u * (c.length - 1);
        int i = Math.min(c.length - 2, (int) x);
        double t = x - i;
        double[] p0 = c[Math.max(0, i - 1)], p1 = c[i], p2 = c[i + 1], p3 = c[Math.min(c.length - 1, i + 2)], o = new double[5];
        for (int k = 0; k < 5; k++) {
            o[k] = 0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t * t + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t * t * t);
        }
        o[3] = Math.max(0.002, o[3]); o[4] = Math.max(0.002, o[4]);
        return o;
    }

    private static double frac(double v) { return v - Math.floor(v); }
    private static double ease(double t) { t = Mth.clamp(t, 0, 1); return t * t * (3 - 2 * t); }
    private static double smooth(double a, double b, double v) { return ease((v - a) / (b - a)); }
    private static double[] add(double[] a, double[] b) { return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    private static double[] mul(double[] a, double k) { return new double[] {a[0] * k, a[1] * k, a[2] * k}; }
    private static double[] cross(double[] a, double[] b) { return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }

    private static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return l < 1e-7 ? new double[] {1, 0, 0} : mul(a, 1 / l);
    }

}
