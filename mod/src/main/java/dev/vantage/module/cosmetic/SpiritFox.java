package dev.vantage.module.cosmetic;

import dev.vantage.core.util.Colors;
import dev.vantage.render.R3;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * The fire of the Pet's "Spirit Fox" type, drawn around the vanilla fox the Pet module already walks: a warm glow on the ground,
 * three burning tails, flames licking up from its back, two blue wisps circling it and embers left behind where it ran. All of it is
 * flat coloured quads through R3, drawn on this client only. Nothing here is an entity or a particle of the world.
 */
final class SpiritFox {
    private SpiritFox() {}

    private static final int TRAIL = 28;
    private static final double[][] trail = new double[TRAIL][4]; // x y z age-stamp (game time when dropped)
    private static int trailAt;
    private static double boost; // 0 calm to 1 running flat out, eased

    /** For the rig: quads added in the last frame. */
    static int drawn;

    static void reset() {
        for (double[] t : trail) t[3] = -1e9;
        boost = 0;
    }

    /** Once per game tick: leaves an ember where the fox is while it moves, and eases the fire up when it runs. */
    static void tick(double x, double y, double z, double moved, boolean fast, long gameTime) {
        boost += ((fast ? 1 : moved > 0.05 ? 0.45 : 0) - boost) * 0.12;
        if (moved > 0.04 && gameTime % 2 == 0) {
            trail[trailAt] = new double[] {x, y, z, gameTime};
            trailAt = (trailAt + 1) % TRAIL;
        }
    }

    static void render(R3 r, Mob pet, double s, boolean sitting) {
        Minecraft mc = Minecraft.getInstance();
        drawn = 0;
        if (mc.level == null) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().position(), pos = pet.getPosition(r.partial);
        if (pos.distanceToSqr(cam) > 64 * 64) return;
        double time = (mc.level.getGameTime() + r.partial) / 20.0, k = 1 + 0.7 * boost;
        double yaw = Math.toRadians(Mth.rotLerp(r.partial, pet.yBodyRotO, pet.yBodyRot));
        double[] right = {-Math.cos(yaw), 0, -Math.sin(yaw)}, fwd = {-Math.sin(yaw), 0, Math.cos(yaw)}, back = mul(fwd, -1), up = {0, 1, 0};
        double[] o = {pos.x, pos.y, pos.z};

        // embers where it ran: they rise, shrink and fade over two seconds
        double now = mc.level.getGameTime() + r.partial;
        for (double[] t : trail) {
            double age = (now - t[3]) / 40.0;
            if (age < 0 || age >= 1) continue;
            int a = (int) (200 * (1 - age));
            double[] c = {t[0] + Math.sin(t[3] * 1.7 + age * 5) * 0.06, t[1] + 0.08 + age * 0.55, t[2] + Math.cos(t[3] * 2.3 + age * 4) * 0.06};
            gem(r, (a << 24) | (age < 0.4 ? 0xFFC04A : 0xFF6A1A), c, cam, 0.03 * s * (1 - 0.6 * age), 0.045 * s * (1 - 0.6 * age));
        }

        // the glow it stands in
        double pulse = 1 + 0.08 * Math.sin(time * 3.1);
        disc(r, o, 0.02, 0.62 * s * pulse * k, 0x1EFF7A1E);
        disc(r, o, 0.025, 0.38 * s * pulse * k, 0x30FFA83C);

        // three burning tails fanned out behind it
        double[] base = add(o, add(mul(back, (sitting ? 0.2 : 0.42) * s), mul(up, (sitting ? 0.16 : 0.42) * s)));
        for (int ti = -1; ti <= 1; ti++) tail(r, base, back, right, up, s, k, ti, time);

        // flames licking up from its back and tails
        for (int i = 0; i < 24; i++) {
            double ph = (time * (1.1 + 0.07 * (i % 5)) + i * 0.3819) % 1.0;
            double along = -0.55 + 0.9 * ((i * 0.618) % 1.0), side = 0.16 * Math.sin(i * 2.4);
            double[] c = add(o, add(add(mul(fwd, along * s), mul(right, side * s)), mul(up, ((sitting && along < 0 ? 0.3 : 0.5) + 0.5 * ph * k) * s)));
            c[0] += Math.sin(time * 4 + i) * 0.02 * s;
            double sz = (0.105 - 0.075 * ph) * s * k;
            int a = (int) (225 * Math.sin(Math.PI * Math.min(1, ph * 1.15)));
            gem(r, (a << 24) | (ph < 0.3 ? 0xFFE9A0 : ph < 0.65 ? 0xFF9A2E : 0xE8421A), c, cam, sz * 0.6, sz * 1.5);
        }

        // two blue wisps circling it, each with a short fading tail
        for (int w = 0; w < 2; w++) {
            for (int q = 5; q >= 0; q--) {
                double a = time * 1.6 + w * Math.PI - q * 0.16, rad = (0.62 + 0.06 * Math.sin(time * 2 + w)) * s;
                double[] c = {o[0] + Math.cos(a) * rad, o[1] + (0.55 + 0.14 * Math.sin(time * 2.3 + w * 2 - q * 0.2)) * s, o[2] + Math.sin(a) * rad};
                double sz = (0.05 - 0.007 * q) * s;
                if (q == 0) { gem(r, 0x505AC8FF, c, cam, sz * 2.1, sz * 2.6); gem(r, 0xFFDFF6FF, c, cam, sz * 0.7, sz); }
                else gem(r, ((150 - q * 24) << 24) | 0x49B4FF, c, cam, sz, sz * 1.3);
            }
        }
    }

    private static void tail(R3 r, double[] at, double[] back, double[] right, double[] up, double s, double k, int ti, double time) {
        final int segs = 8, sides = 5;
        double[][] prev = null;
        double[] dir = back;
        for (int i = 0; i <= segs; i++) {
            double u = i / (double) segs;
            double rad = (0.015 + 0.09 * Math.sin(Math.PI * (0.08 + 0.84 * u))) * s * (0.9 + 0.1 * k);
            double elev = Math.toRadians(8 + 78 * Math.pow(u, 1.2) + (ti == 0 ? 14 : 0));
            double fan = Math.toRadians(ti * (34 - 10 * u)) + Math.sin(time * (2.4 + 0.3 * ti) - i * 0.6 + ti * 1.9) * (0.1 + 0.05 * i) * k;
            dir = norm(add(add(mul(back, Math.cos(elev) * Math.cos(fan)), mul(up, Math.sin(elev))), mul(right, Math.sin(fan) * Math.cos(elev))));
            double[] n1 = norm(cross(dir, up)), n2 = cross(n1, dir);
            double[][] ring = new double[sides][];
            for (int j = 0; j < sides; j++) {
                double a = Math.PI * 2 * j / sides;
                ring[j] = add(at, add(mul(n1, Math.cos(a) * rad), mul(n2, Math.sin(a) * rad)));
            }
            if (prev != null) {
                double flick = 0.92 + 0.08 * Math.sin(time * 9 + i * 1.3 + ti);
                int col = Colors.mix(u < 0.5 ? 0xFFE8501A : 0xFFFF9A2A, u < 0.5 ? 0xFFFF9A2A : 0xFFFFD25A, u < 0.5 ? u * 2 : u * 2 - 1);
                for (int j = 0; j < sides; j++) {
                    int j2 = (j + 1) % sides;
                    quad(r, Colors.scale(col, (float) (flick * (0.88 + 0.12 * Math.sin(j * 1.3)))), prev[j], prev[j2], ring[j2], ring[j]);
                }
            }
            prev = ring;
            at = add(at, mul(dir, 0.085 * s));
        }
        double[] end = add(at, mul(dir, 0.05 * s));
        for (int j = 0; j < sides; j++) quad(r, 0xFFFFE08A, prev[j], prev[(j + 1) % sides], end, end);
    }

    private static void disc(R3 r, double[] o, double y, double rad, int color) {
        for (int j = 0; j < 12; j++) {
            double a = Math.PI * 2 * j / 12, b = Math.PI * 2 * (j + 1) / 12;
            double[] c = {o[0], o[1] + y, o[2]};
            quad(r, color, c, new double[] {o[0] + Math.cos(a) * rad, o[1] + y, o[2] + Math.sin(a) * rad}, new double[] {o[0] + Math.cos(b) * rad, o[1] + y, o[2] + Math.sin(b) * rad}, c);
        }
    }

    /** A flat diamond that always faces the camera. */
    private static void gem(R3 r, int color, double[] c, Vec3 cam, double w, double h) {
        double[] d = norm(new double[] {cam.x - c[0], cam.y - c[1], cam.z - c[2]}), bx = norm(cross(new double[] {0, 1, 0}, d)), by = cross(d, bx);
        quad(r, color, add(c, mul(bx, -w)), add(c, mul(by, -h)), add(c, mul(bx, w)), add(c, mul(by, h)));
    }

    private static double[] add(double[] a, double[] b) { return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    private static double[] mul(double[] a, double k) { return new double[] {a[0] * k, a[1] * k, a[2] * k}; }
    private static double[] cross(double[] a, double[] b) { return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }

    private static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return l < 1e-6 ? new double[] {1, 0, 0} : mul(a, 1 / l);
    }

    /** Both windings, so the face shows from either side whatever the pipeline culls. */
    private static void quad(R3 r, int color, double[] a, double[] b, double[] c, double[] d) {
        r.quad(color, false, (float) a[0], (float) a[1], (float) a[2], (float) b[0], (float) b[1], (float) b[2], (float) c[0], (float) c[1], (float) c[2], (float) d[0], (float) d[1], (float) d[2]);
        r.quad(color, false, (float) d[0], (float) d[1], (float) d[2], (float) c[0], (float) c[1], (float) c[2], (float) b[0], (float) b[1], (float) b[2], (float) a[0], (float) a[1], (float) a[2]);
        drawn += 2;
    }
}
