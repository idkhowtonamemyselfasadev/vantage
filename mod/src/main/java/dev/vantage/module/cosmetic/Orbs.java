package dev.vantage.module.cosmetic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.util.Colors;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3fc;

/**
 * A few glowing orbs drift around you: a bright core inside layered glow, a ribbon trail that thins and fades towards its tail,
 * and small sparks shed along the way. Their little physics (gravity, buoyancy, a pull back towards you, a swirl, bounces off
 * blocks) runs on your client only; nothing exists in the world. Three looks: Comet (long cool ribbon, few sparks), Ember (short
 * trail, embers rising, flicker), Plasma (pulsing core, twin twisted ribbon, bright motes).
 */
public class Orbs extends Module {
    public final ModeSetting style = add(new ModeSetting("Style", "Comet: long ribbon; Ember: embers rise off it; Plasma: pulsing core with a twin ribbon", "Comet", "Comet", "Ember", "Plasma"));
    public final ModeSetting look = add(new ModeSetting("Colour", "Orb colour", "Comet", "Comet", "Fire", "Rainbow", "Accent", "Ice", "Pink", "Violet"));
    public final BoolSetting shift = add(new BoolSetting("Colour shift", "The colour slowly wanders around its hue", false));
    public final NumberSetting count = add(new NumberSetting("Count", "How many orbs", 5, 1, 32, 1));
    public final NumberSetting size = add(new NumberSetting("Size", "Orb radius in blocks", 0.3, 0.1, 1, 0.05));
    public final NumberSetting glow = add(new NumberSetting("Glow", "How wide and bright the halo is", 1, 0.3, 2.5, 0.1));
    public final NumberSetting pulse = add(new NumberSetting("Pulse", "How much each orb swells and brightens; they ripple out of step with each other", 0.45, 0, 1, 0.05));
    public final NumberSetting pulseSpeed = add(new NumberSetting("Pulse speed", "Seconds per pulse", 1.2, 0.3, 4, 0.1));
    public final NumberSetting trailLen = add(new NumberSetting("Trail", "Trail length", 1, 0, 2, 0.1));
    public final NumberSetting sparks = add(new NumberSetting("Sparks", "Little motes shed by each orb", 1, 0, 3, 0.1));
    public final NumberSetting spread = add(new NumberSetting("Spread", "How far from you they orbit", 2.6, 1, 8, 0.1));
    public final NumberSetting bounce = add(new NumberSetting("Bounce", "How much speed survives a bounce", 0.6, 0, 1, 0.05));
    public final NumberSetting drift = add(new NumberSetting("Drift", "Random wandering", 0.35, 0, 2, 0.05));
    public final BoolSetting blockBounce = add(new BoolSetting("Bounce off blocks", "Orbs bounce off solid blocks instead of passing through", true));
    public static int drawn;
    private static final double GRAVITY = 0.016, DRAG = 0.982, PULL = 0.012, SWIRL = 0.0025, WANDER = 0.003, FOLLOW = 0.035, CAP = 0.9;
    private static final int TRAIL_MAX = 70, SPARK_LIFE = 26;
    private final Random rnd = new Random();
    private final List<Orb> orbs = new ArrayList<>();
    private double lastPx, lastPy, lastPz, fx, fy, fz;
    private boolean havePrev;

    private static final class Orb {
        double x, y, z, px, py, pz, vx, vy, vz, phase;
        final ArrayDeque<double[]> trail = new ArrayDeque<>();
        final List<Spark> sparks = new ArrayList<>();
    }

    /** A mote shed by an orb: position, velocity, age in ticks, its own size. */
    private static final class Spark {
        double x, y, z, px, py, pz, vx, vy, vz, size;
        int age;
    }

    public Orbs() {
        super("Orbs", "Glowing orbs drift around you with trails and sparks", Category.COSMETIC);
    }

    @Override
    protected void onDisable() { orbs.clear(); havePrev = false; }

    /** Where the first orb is, for the rig to look at; null when there is none. */
    public double[] anyPos() {
        return orbs.isEmpty() ? null : new double[] {orbs.get(0).x, orbs.get(0).y, orbs.get(0).z};
    }

    private void spawn(Orb o) {
        double a = rnd.nextDouble() * Math.PI * 2, d = spread.get() * (0.6 + 0.6 * rnd.nextDouble());
        o.x = o.px = mc.player.getX() + Math.cos(a) * d;
        o.y = o.py = mc.player.getY() + 1 + rnd.nextDouble();
        o.z = o.pz = mc.player.getZ() + Math.sin(a) * d;
        o.vx = o.vy = o.vz = 0;
        o.phase = rnd.nextDouble() * Math.PI * 2;
        o.trail.clear();
        o.sparks.clear();
    }

    @Override
    public void onTick() {
        var p = mc.player;
        int n = count.i();
        while (orbs.size() < n) { Orb o = new Orb(); spawn(o); orbs.add(o); }
        while (orbs.size() > n) orbs.remove(orbs.size() - 1);
        double dpx = havePrev ? p.getX() - lastPx : 0, dpy = havePrev ? p.getY() - lastPy : 0, dpz = havePrev ? p.getZ() - lastPz : 0;
        lastPx = p.getX(); lastPy = p.getY(); lastPz = p.getZ(); havePrev = true;
        // your steps reach the orbs eased, so a sprint start is a lean, not a jolt
        fx += (dpx - fx) * 0.35; fy += (dpy - fy) * 0.35; fz += (dpz - fz) * 0.35;
        double rad = size.get(), sp = spread.get(), wander = WANDER * drift.get() * 2, home = p.getY() + 1.3;
        boolean ember = style.is("Ember"), plasma = style.is("Plasma");
        int keep = (int) Math.round(TRAIL_MAX * trailLen.get() * (ember ? 0.45 : 1));
        double sparkRate = sparks.get() * (ember ? 0.9 : plasma ? 0.55 : 0.3);
        for (Orb o : orbs) {
            o.px = o.x; o.py = o.y; o.pz = o.z;
            o.vy -= GRAVITY;
            double gap = home - o.y;
            if (gap > 0) o.vy += Math.min(gap, 1.5) * 0.045;
            // pull towards a ring of radius spread around you, swirl along it, follow your steps
            double dx = p.getX() - o.x, dz = p.getZ() - o.z, dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 1e-4) {
                double want = (dist - sp) / dist;
                o.vx += dx * want * PULL;
                o.vz += dz * want * PULL;
                o.vx += -dz / dist * SWIRL * sp;
                o.vz += dx / dist * SWIRL * sp;
            }
            o.phase += 0.05;
            // the drift is mostly a slow sine, only a little noise: smooth arcs instead of jitter
            o.vx += (rnd.nextDouble() - 0.5) * wander * 0.4 + Math.sin(o.phase) * wander * 0.8;
            o.vy += (rnd.nextDouble() - 0.5) * wander * 0.25 + Math.sin(o.phase * 1.7) * wander * 0.4;
            o.vz += (rnd.nextDouble() - 0.5) * wander * 0.4 + Math.cos(o.phase * 0.8) * wander * 0.8;
            o.vx += fx * FOLLOW; o.vy += fy * FOLLOW; o.vz += fz * FOLLOW;
            o.vx *= DRAG; o.vy *= DRAG; o.vz *= DRAG;
            double speed = Math.sqrt(o.vx * o.vx + o.vy * o.vy + o.vz * o.vz);
            if (speed > CAP) { o.vx *= CAP / speed; o.vy *= CAP / speed; o.vz *= CAP / speed; }
            if (blockBounce.on()) {
                double b = bounce.get();
                if (!free(o.x + o.vx, o.y, o.z, rad)) { o.vx = -o.vx * b; o.vz *= 0.86; }
                if (!free(o.x, o.y + o.vy, o.z, rad)) { o.vy = -o.vy * b; o.vx *= 0.86; o.vz *= 0.86; }
                if (!free(o.x, o.y, o.z + o.vz, rad)) { o.vz = -o.vz * b; o.vx *= 0.86; }
            }
            o.x += o.vx; o.y += o.vy; o.z += o.vz;
            double far = sp * 3 + 8;
            if (o.x - p.getX() > far || p.getX() - o.x > far || o.z - p.getZ() > far || p.getZ() - o.z > far || Math.abs(o.y - p.getY()) > far) spawn(o);
            o.trail.addLast(new double[] {o.x, o.y, o.z});
            while (o.trail.size() > Math.max(4, keep)) o.trail.pollFirst();
            // sparks: shed behind the orb, embers drift up, plasma motes fly off sideways
            if (rnd.nextDouble() < sparkRate && o.sparks.size() < 24) {
                Spark s = new Spark();
                s.x = s.px = o.x + (rnd.nextDouble() - 0.5) * rad; s.y = s.py = o.y + (rnd.nextDouble() - 0.5) * rad; s.z = s.pz = o.z + (rnd.nextDouble() - 0.5) * rad;
                double k = plasma ? 0.05 : 0.025;
                s.vx = -o.vx * 0.3 + (rnd.nextDouble() - 0.5) * k;
                s.vy = -o.vy * 0.3 + (rnd.nextDouble() - 0.5) * k + (ember ? 0.03 : 0.004);
                s.vz = -o.vz * 0.3 + (rnd.nextDouble() - 0.5) * k;
                s.size = rad * (0.12 + rnd.nextDouble() * 0.14);
                o.sparks.add(s);
            }
            for (int i = o.sparks.size() - 1; i >= 0; i--) {
                Spark s = o.sparks.get(i);
                s.px = s.x; s.py = s.y; s.pz = s.z;
                s.vy += ember ? 0.0015 : -0.0008;
                s.vx *= 0.94; s.vy *= 0.94; s.vz *= 0.94;
                s.x += s.vx; s.y += s.vy; s.z += s.vz;
                if (++s.age > SPARK_LIFE) o.sparks.remove(i);
            }
        }
    }

    private boolean free(double x, double y, double z, double r) {
        return mc.level.noCollision(new AABB(x - r, y - r, z - r, x + r, y + r, z + r));
    }

    @Override
    public void onRender3D(R3 r) {
        if (orbs.isEmpty()) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        var cam = mc.gameRenderer.getMainCamera();
        Vector3fc up = cam.upVector(), left = cam.leftVector();
        var cp = cam.position();
        double rad = size.get(), g = glow.get();
        long now = System.currentTimeMillis();
        boolean ember = style.is("Ember"), plasma = style.is("Plasma");
        int idx = 0;
        for (Orb o : orbs) {
            int col = colour(idx++, now);
            double x = o.px + (o.x - o.px) * partial, y = o.py + (o.y - o.py) * partial, z = o.pz + (o.z - o.pz) * partial;
            // breathing: a slow swell, embers flicker on top, plasma pulses fast
            // the pulse: 0..1 wave on its own phase per orb, strength 0.45 = +-35% size, brightness swells with it
            double amp = 0.78 * pulse.get();
            double wave = 0.5 + 0.5 * Math.sin(now / 1000.0 / pulseSpeed.get() * Math.PI * 2 + o.phase * 3);
            double scale = 1 - amp + 2 * amp * wave;
            double bright = 1 - amp * 0.6 + amp * 1.3 * wave;
            if (ember) scale *= 0.94 + 0.12 * rnd.nextDouble();
            if (plasma) scale *= 1 + 0.12 * Math.sin(now / 1000.0 * 9 + o.phase);
            if (trailLen.get() > 0 && o.trail.size() >= 4) ribbon(r, o, col, x, y, z, cp.x, cp.y, cp.z, rad * (plasma ? 0.28 : 0.34) * (1 + 0.5 * (scale - 1)), plasma);
            for (Spark s : o.sparks) {
                double sx = s.px + (s.x - s.px) * partial, sy = s.py + (s.y - s.py) * partial, sz = s.pz + (s.z - s.pz) * partial;
                float life = 1 - s.age / (float) SPARK_LIFE;
                int sc = ember ? warm(col, 1 - life) : plasma ? Colors.lighten(col, 0.6f) : Colors.lighten(col, 0.3f);
                disc(r, sx, sy, sz, s.size * (0.6 + life), (sc & 0xFFFFFF) | (Math.round(190 * life) << 24), up, left, 6);
            }
            // the halo in layers (wide and faint to tight and bright), then the body and a white-hot core
            double h = rad * scale;
            disc(r, x, y, z, h * (3.0 * g), (col & 0xFFFFFF) | (dim(alpha(0x0E, g), bright) << 24), up, left, 12);
            disc(r, x, y, z, h * (2.1 * g), (col & 0xFFFFFF) | (dim(alpha(0x1C, g), bright) << 24), up, left, 12);
            disc(r, x, y, z, h * 1.45, (col & 0xFFFFFF) | (dim(0x46, bright) << 24), up, left, 12);
            disc(r, x, y, z, h * 1.0, (col & 0xFFFFFF) | (dim(0xB4, bright) << 24), up, left, 12);
            disc(r, x, y, z, h * 0.62, (Colors.lighten(col, 0.45f) & 0xFFFFFF) | (dim(0xE0, bright) << 24), up, left, 10);
            // the core is brightest and widest at the peak of the pulse
            disc(r, x, y, z, h * (0.30 + 0.10 * wave), (dim(0xF6, 0.8 + 0.25 * wave) << 24) | 0xFFFFFF, up, left, 8);
            drawn++;
        }
    }

    /** An alpha scaled by the pulse brightness, kept in range. */
    private static int dim(int a, double k) {
        return Math.max(0, Math.min(255, (int) Math.round(a * k)));
    }

    private static int alpha(int base, double g) {
        return Math.min(255, (int) Math.round(base * Math.min(1.6, 0.5 + g * 0.5)));
    }

    /** A camera-facing disc of radius h at a point: n rim points, drawn as kite quads from the centre. */
    private static void disc(R3 r, double x, double y, double z, double h, int col, Vector3fc up, Vector3fc left, int n) {
        float[] rx = new float[n], ry = new float[n], rz = new float[n];
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n, c = Math.cos(a) * h, s = Math.sin(a) * h;
            rx[i] = (float) (x + left.x() * c + up.x() * s);
            ry[i] = (float) (y + left.y() * c + up.y() * s);
            rz[i] = (float) (z + left.z() * c + up.z() * s);
        }
        for (int i = 0; i < n; i += 2) {
            int a = i, b = (i + 1) % n, c = (i + 2) % n;
            r.quad(col, false, (float) x, (float) y, (float) z, rx[a], ry[a], rz[a], rx[b], ry[b], rz[b], rx[c], ry[c], rz[c]);
        }
    }

    /**
     * The orb's path as a camera-facing ribbon along a Catmull-Rom curve: full width and colour at the head, thinning to a hair
     * and fading to nothing at the tail. Plasma draws it twice, twisted around the path.
     */
    private void ribbon(R3 r, Orb o, int col, double hx, double hy, double hz, double cx, double cy, double cz, double wmax, boolean twin) {
        double[][] pts = o.trail.toArray(new double[0][]);
        int n = pts.length, total = n - 1;
        double[] prev = {hx, hy, hz};
        double[] prevSide = null;
        double prevW = wmax;
        int prevA = 0x9C;
        double wmin = wmax * 0.08;
        for (int i = n - 2; i >= 1; i--) {
            double[] p0 = pts[Math.min(n - 1, i + 1)], p1 = pts[i], p2 = pts[i - 1], p3 = pts[Math.max(0, i - 2)];
            for (int s = 1; s <= 2; s++) {
                double t = s / 2.0, t2 = t * t, t3 = t2 * t;
                double[] q = new double[3];
                for (int k = 0; k < 3; k++)
                    q[k] = 0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2 + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3);
                float age = ((n - 1 - i) + (float) (s - 1) / 2) / total;
                double w = wmin + (wmax - wmin) * Math.pow(1 - age, 1.3);
                int a = (int) (0x9C * Math.pow(1 - age, 2.0));
                if (a < 5) break;
                // side vector: across the path and across the view, so the strip always faces the camera
                double dx = q[0] - prev[0], dy = q[1] - prev[1], dz = q[2] - prev[2];
                double vx = prev[0] - cx, vy = prev[1] - cy, vz = prev[2] - cz;
                double sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;
                double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (sl < 1e-6) { prev = q; continue; }
                sx /= sl; sy /= sl; sz /= sl;
                double[] side = {sx, sy, sz};
                if (prevSide == null) prevSide = side;
                int c0 = (prevA << 24) | (col & 0xFFFFFF), c1 = (a << 24) | (col & 0xFFFFFF);
                strip(r, prev, prevSide, prevW, q, side, w, c0, c1, 0);
                if (twin) {
                    // the second ribbon winds around the first
                    double ph = age * 14 + System.currentTimeMillis() / 150.0;
                    strip(r, prev, prevSide, prevW * 0.5, q, side, w * 0.5, Colors.lighten(c0, 0.5f), Colors.lighten(c1, 0.5f), Math.sin(ph) * w * 1.6);
                }
                prev = q; prevSide = side; prevW = w; prevA = a;
            }
        }
    }

    /** One ribbon segment: a quad between two path points, each with its own half-width, offset sideways by off. */
    private static void strip(R3 r, double[] a, double[] sa, double wa, double[] b, double[] sb, double wb, int ca, int cb, double off) {
        float ax0 = (float) (a[0] + sa[0] * (off - wa)), ay0 = (float) (a[1] + sa[1] * (off - wa)), az0 = (float) (a[2] + sa[2] * (off - wa));
        float ax1 = (float) (a[0] + sa[0] * (off + wa)), ay1 = (float) (a[1] + sa[1] * (off + wa)), az1 = (float) (a[2] + sa[2] * (off + wa));
        float bx0 = (float) (b[0] + sb[0] * (off - wb)), by0 = (float) (b[1] + sb[1] * (off - wb)), bz0 = (float) (b[2] + sb[2] * (off - wb));
        float bx1 = (float) (b[0] + sb[0] * (off + wb)), by1 = (float) (b[1] + sb[1] * (off + wb)), bz1 = (float) (b[2] + sb[2] * (off + wb));
        // one quad with the head's colour; the per-vertex fade comes from the many short segments
        r.quad(mix(ca, cb), false, ax0, ay0, az0, ax1, ay1, az1, bx1, by1, bz1, bx0, by0, bz0);
    }

    private static int mix(int a, int b) {
        int aa = (a >>> 24) + (b >>> 24) >> 1;
        return (aa << 24) | (a & 0xFFFFFF);
    }

    /** Embers go from the orb colour through orange to a dull red as they age. */
    private static int warm(int c, float age) {
        int target = age < 0.5f ? 0xFFFFA23A : 0xFFB83A1A;
        float k = Math.min(1f, age * 1.6f);
        int r = (int) (((c >> 16) & 255) + (((target >> 16) & 255) - ((c >> 16) & 255)) * k);
        int g = (int) (((c >> 8) & 255) + (((target >> 8) & 255) - ((c >> 8) & 255)) * k);
        int b = (int) ((c & 255) + ((target & 255) - (c & 255)) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int colour(int i, long now) {
        int base = switch (look.get()) {
            case "Fire" -> 0xFFFF8A2E;
            case "Rainbow" -> Colors.fromHsv(((now / 40 + i * 37) % 360) / 360f, 0.85f, 1f, 255);
            case "Accent" -> Theme.accent() | 0xFF000000;
            case "Ice" -> 0xFF9FE7FF;
            case "Pink" -> 0xFFFF9ED4;
            case "Violet" -> 0xFF9A7BFF;
            default -> 0xFF6FB0FF;
        };
        if (!shift.on() || look.is("Rainbow")) return base;
        // a slow wander of ±25 degrees around the base hue, each orb a little apart
        float[] hsv = Colors.toHsv(base);
        float h = (float) ((hsv[0] + 0.07 * Math.sin(now / 4000.0 + i * 0.9) + 1) % 1);
        return Colors.fromHsv(h, hsv[1], hsv[2], 255);
    }
}
