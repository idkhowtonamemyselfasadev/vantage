package dev.vantage.module.cosmetic;

import java.util.ArrayDeque;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.util.Colors;
import dev.vantage.render.R3;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** A soft glowing trail on the ground behind you while you walk or sprint, fading from the tail. Flat quads, nothing leaves your client. */
public class Footprints extends Module {
    public final ModeSetting colour = add(Cos.mode());
    public final ColorSetting manual = add(Cos.manual()).visibleIf(() -> colour.is("Manual"));
    public final NumberSetting duration = add(new NumberSetting("Duration", "Seconds until the trail is gone", 2, 0.5, 6, 0.1).legacy(3));
    public final NumberSetting width = add(new NumberSetting("Width", "How wide the glow is, in blocks", 0.35, 0.15, 0.8, 0.05));

    /** Rig counters: points stored, segments drawn (summed over frames), lines restarted after a break, segments alive now. */
    public static int placed, drawn, breaks, segs;

    private static final double SPACING = 0.3;     // blocks of walking between two stored points
    private static final int MAX_PTS = 160;        // hard cap: 6 s at 7 blocks per second still fits
    private static final double LIFT = 0.03;       // blocks above the feet, keeps the band off the ground surface
    private static final double MAX_DY = 0.6;      // blocks of height change that still joins two points
    private static final double MAX_GAP = 1.0;     // blocks between two points that still join (also: a jump this far in one tick is a teleport)
    private static final int AIR_BREAK = 3;        // ticks in the air before the band breaks
    private static final double MIN_TURN_DOT = 0.2; // sharper turns than this start a new line
    private static final double MIN_MOVE = 0.02;   // blocks per tick below which you count as standing still
    // layers from the bottom: faint dark underlay (reads on sand and snow), outer glow, inner glow, bright core
    private static final float[] LAYER_W = {0.55f, 1.0f, 0.6f, 0.26f};    // share of the full width
    private static final float[] LAYER_A = {0.16f, 0.14f, 0.22f, 0.85f};  // alpha at full strength
    private static final int DARK = 0xFF101018;

    /** A stored point on the ground. nx/nz is the half-width direction (mitred once the next point is known); dx/dz the direction you came from. */
    private static final class Pt {
        final double x, y, z;
        final long t0;
        double nx, nz, dx, dz;
        float wf = 1f;          // width factor, small at the start of a line
        int idx;                // 0 for the first point of a line
        boolean start, hasDir;
        Pt(double x, double y, double z, long t0) { this.x = x; this.y = y; this.z = z; this.t0 = t0; }
    }

    private final ArrayDeque<Pt> pts = new ArrayDeque<>();
    private double lastX, lastZ, since;
    private int air;
    private boolean have, brk = true, headOk;

    public Footprints() {
        super("Footprints", "A soft glowing trail on the ground behind you", Category.COSMETIC);
        beta();
    }

    @Override
    protected void onDisable() { reset(); }

    @Override
    public void onLeaveWorld() {
        reset();
    }

    @Override
    public void onDimensionChange() {
        reset();
    }

    private void reset() {
        pts.clear();
        have = false;
        brk = true;
        headOk = false;
        since = 0;
        air = 0;
        segs = 0;
    }

    @Override
    public void onTick() {
        Player p = mc.player;
        if (p == null) return;
        double dx = p.getX() - lastX, dz = p.getZ() - lastZ;
        lastX = p.getX();
        lastZ = p.getZ();
        expire(System.currentTimeMillis());
        if (!have) { have = true; return; }
        double moved = Math.sqrt(dx * dx + dz * dz);
        air = p.onGround() ? 0 : air + 1;
        boolean bad = p.isInWater() || p.isUnderWater() || p.isSwimming() || p.isPassenger() || p.getAbilities().flying || p.isCrouching() || p.isSpectator() || p.isInvisible();
        if (bad || air > AIR_BREAK || moved > MAX_GAP) { brk = true; since = 0; headOk = false; return; }
        if (!p.onGround()) { headOk = false; return; } // a short hop: no new points, the line stays
        if (moved < MIN_MOVE) return;
        since += moved;
        if (!brk && since < SPACING) { headOk = true; return; }
        store(p);
    }

    /** Calm: the trail is 55% as strong and 80% as wide. */
    public static float alphaScale() { return Cos.calmAlpha(); }
    public static double sizeScale() { return Cos.calm() ? 0.8 : 1.0; }

    /** Adds a point at your feet and finishes the corner of the previous one. */
    private void store(Player p) {
        since = 0;
        double x = p.getX(), y = p.getY() + LIFT, z = p.getZ();
        Pt last = pts.peekLast();
        Pt n = new Pt(x, y, z, System.currentTimeMillis());
        boolean join = last != null && !brk;
        double ex = 0, ez = 0;
        if (join) {
            double ax = x - last.x, az = z - last.z, len = Math.sqrt(ax * ax + az * az);
            if (len < 1e-4 || len > MAX_GAP || Math.abs(y - last.y) > MAX_DY) join = false;
            else {
                ex = ax / len;
                ez = az / len;
                if (last.hasDir && last.dx * ex + last.dz * ez < MIN_TURN_DOT) join = false;
            }
        }
        if (join) {
            // the previous point gets the mitred normal, so the two segments share their corners
            double onx = -ez, onz = ex;
            if (last.hasDir) {
                double mx = -last.dz + onx, mz = last.dx + onz, ml = Math.sqrt(mx * mx + mz * mz);
                mx /= ml;
                mz /= ml;
                double cos = Math.max(0.6, mx * onx + mz * onz);
                last.nx = mx / cos;
                last.nz = mz / cos;
            } else {
                last.nx = onx;
                last.nz = onz;
            }
            n.dx = ex;
            n.dz = ez;
            n.nx = onx;   // provisional, until the next point is known
            n.nz = onz;
            n.hasDir = true;
            n.idx = last.idx + 1;
            n.wf = n.idx == 1 ? 0.75f : 1f;
        } else {
            n.start = true;
            n.wf = 0.4f;
            if (last != null) breaks++;
        }
        brk = false;
        headOk = true;
        if (pts.size() >= MAX_PTS) pts.pollFirst();
        pts.addLast(n);
        placed++;
        count();
    }

    private void expire(long now) {
        double life = duration.get() * 1000;
        while (!pts.isEmpty() && now - pts.peekFirst().t0 > life) pts.pollFirst();
        count();
    }

    private void count() {
        int n = 0;
        for (Pt q : pts) if (!q.start) n++;
        segs = n;
    }

    private static float fade(long now, long t, double life) {
        float k = (float) Math.max(0, Math.min(1, (now - t) / life));
        return (float) Math.pow(1 - k, 1.4) * alphaScale();
    }

    @Override
    public void onRender3D(R3 r) {
        if (pts.isEmpty() || mc.player == null) return;
        long now = System.currentTimeMillis();
        double life = duration.get() * 1000;
        expire(now);
        if (pts.isEmpty()) return;
        int base = Cos.pick(colour, manual) | 0xFF000000;
        int light = Colors.mix(base, 0xFFFFFFFF, 0.35f) | 0xFF000000;
        double full = width.get() * sizeScale() * 0.5; // half width of the outer glow
        // the live head: from the last point to where the player is drawn now, so there is no gap behind the feet
        Pt last = pts.peekLast();
        boolean head = false;
        double hx = 0, hy = 0, hz = 0, hnx = 0, hnz = 0;
        if (headOk) {
            Vec3 q = mc.player.getPosition(r.partial);
            double ax = q.x - last.x, az = q.z - last.z, len = Math.sqrt(ax * ax + az * az);
            hy = q.y + LIFT;
            if (len > 0.03 && len < MAX_GAP && Math.abs(hy - last.y) < MAX_DY && (!last.hasDir || ax * last.dx + az * last.dz > 0)) {
                head = true;
                hx = q.x;
                hz = q.z;
                hnx = last.hasDir ? last.nx : -az / len;
                hnz = last.hasDir ? last.nz : ax / len;
            }
        }
        float headA = fade(now, last.t0, life);
        for (int layer = 0; layer < LAYER_W.length; layer++) {
            int col = layer == 0 ? DARK : layer == 3 ? light : base;
            double hw = full * LAYER_W[layer];
            Pt prev = null;
            for (Pt q : pts) {
                if (prev != null && !q.start) {
                    float a = fade(now, (prev.t0 + q.t0) / 2, life);
                    if (a > 0.01f) {
                        band(r, Colors.fade(col, a * LAYER_A[layer]), prev.x, prev.y, prev.z, prev.nx * hw * prev.wf, prev.nz * hw * prev.wf, q.x, q.y, q.z, q.nx * hw * q.wf, q.nz * hw * q.wf);
                        if (layer == 3) drawn++;
                    }
                }
                prev = q;
            }
            if (head && headA > 0.01f) {
                band(r, Colors.fade(col, headA * LAYER_A[layer]), last.x, last.y, last.z, hnx * hw * last.wf, hnz * hw * last.wf, hx, hy, hz, hnx * hw, hnz * hw);
                if (layer == 3) drawn++;
            }
        }
    }

    /** One flat quad from point a to point b with the given sideways offsets; a fresh array each time because R3 keeps it. */
    private static void band(R3 r, int color, double ax, double ay, double az, double anx, double anz, double bx, double by, double bz, double bnx, double bnz) {
        r.quad(color, false,
                (float) (ax - anx), (float) ay, (float) (az - anz),
                (float) (ax + anx), (float) ay, (float) (az + anz),
                (float) (bx + bnx), (float) by, (float) (bz + bnz),
                (float) (bx - bnx), (float) by, (float) (bz - bnz));
    }
}
