package dev.vantage.module.cosmetic;

import java.util.ArrayDeque;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.util.Colors;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/** A sonar ping when Base Finder or Stash Finder marks something new: a ring spreads from your feet with a bright arc pointing at the find, another rings the find itself when it is near, and the screen edges flash softly. */
public class FindPulse extends Module {
    public final ModeSetting colour = add(Cos.mode());
    public final ColorSetting manual = add(Cos.manual()).visibleIf(() -> colour.is("Manual"));
    public final NumberSetting size = add(new NumberSetting("Size", "How far the ring spreads, in blocks", 14, 4, 40, 1));
    public final NumberSetting duration = add(new NumberSetting("Duration", "Seconds the ring takes", 2.2, 0.8, 5, 0.1));
    public final BoolSetting flash = add(new BoolSetting("Screen flash", "A soft flash at the screen edges", true));
    public final NumberSetting flashStrength = add(new NumberSetting("Flash strength", "How strong the edge flash is", 1.0, 0.1, 1, 0.05)).visibleIf(() -> flash.on());
    public static int pulses, drawn, flashes;
    private static final int SEG = 64;
    private record Pulse(double fx, double fy, double fz, double px, double py, double pz, long t0) {}
    private final ArrayDeque<Pulse> live = new ArrayDeque<>();

    public FindPulse() {
        super("Find Pulse", "A sonar ring and a soft edge flash when a finder marks something new", Category.COSMETIC);
        beta();
    }

    public static FindPulse active() {
        FindPulse f = Modules.get(FindPulse.class);
        return f != null && f.on() ? f : null;
    }

    @Override
    protected void onDisable() { live.clear(); }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        live.clear();
    }

    /** A finder marked (x, y, z). */
    public void pulse(double x, double y, double z) {
        if (mc.player == null) return;
        if (live.size() >= 4) live.pollFirst();
        live.add(new Pulse(x, y, z, mc.player.getX(), mc.player.getY() + 0.05, mc.player.getZ(), System.currentTimeMillis()));
        pulses++;
    }

    private void ring(R3 r, double cx, double cy, double cz, double rad, int col, float width, boolean xray, double arcMid, double arcHalf) {
        double px = cx + rad, pz = cz;
        for (int i = 1; i <= SEG; i++) {
            double a = i / (double) SEG * Math.PI * 2, x = cx + Math.cos(a) * rad, z = cz + Math.sin(a) * rad;
            boolean bright = !Double.isNaN(arcMid) && Math.abs(Math.atan2(Math.sin(a - arcMid), Math.cos(a - arcMid))) < arcHalf;
            int c = bright ? Colors.fade(col | 0xFF000000, 1f) : Colors.fade(col, 0.7f);
            r.line(px, cy, pz, x, cy, z, Colors.fade(c, 0.28f), width * 3.2f, xray); // soft glow falloff around the core line
            r.line(px, cy, pz, x, cy, z, c, bright ? width * 1.7f : width, xray);
            double in = Math.max(0.05, rad - 0.45 - rad * 0.06);
            double ix = cx + Math.cos(a) * in, iz = cz + Math.sin(a) * in, jx = cx + Math.cos((i - 1) / (double) SEG * Math.PI * 2) * in, jz = cz + Math.sin((i - 1) / (double) SEG * Math.PI * 2) * in;
            r.quad(Colors.fade(c, 0.22f), xray, (float) px, (float) cy, (float) pz, (float) x, (float) cy, (float) z, (float) ix, (float) cy, (float) iz, (float) jx, (float) cy, (float) jz);
            px = x; pz = z;
        }
    }

    @Override
    public void onRender3D(R3 r) {
        if (live.isEmpty()) return;
        long now = System.currentTimeMillis();
        double life = duration.get() * 1000;
        live.removeIf(p -> now - p.t0 > life + 400);
        int base = Cos.pick(colour, manual) & 0xFFFFFF;
        for (Pulse p : live) {
            double toX = p.fx - p.px, toZ = p.fz - p.pz, dist = Math.sqrt(toX * toX + toZ * toZ);
            double dir = Math.atan2(toZ, toX);
            for (int k = 0; k < (Cos.calm() ? 1 : 2); k++) { // calm: one ring, no echo
                double t = ((now - p.t0) - k * 260) / life;
                if (t <= 0 || t >= 1) continue;
                float e = Cos.out((float) t);
                float a = (float) Math.pow(1 - t, 1.2);
                int col = Colors.fade(base | 0xFF000000, a);
                ring(r, p.px, p.py, p.pz, size.get() * e, col, 8f, false, dist > 3 ? dir : Double.NaN, 0.32);
                if (dist <= 96 && k == 0) ring(r, p.fx, p.fy, p.fz, Math.max(0.5, Math.min(size.get(), 10) * e), col, 8f, true, Double.NaN, 0);
            }
            drawn++;
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!flash.on() || Cos.calm() || live.isEmpty()) return;
        long now = System.currentTimeMillis();
        float best = 0;
        for (Pulse p : live) {
            double t = (now - p.t0) / 550.0;
            if (t < 0 || t > 1) continue;
            float v = (float) (t < 0.12 ? t / 0.12 : Math.pow(1 - (t - 0.12) / 0.88, 1.6));
            best = Math.max(best, v);
        }
        if (best < 0.01f) return;
        int base = Cos.pick(colour, manual) & 0xFFFFFF;
        int w = g.guiWidth(), h = g.guiHeight(), steps = 10;
        int rx = Math.max(1, Math.round(w * 0.14f)), ry = Math.max(1, Math.round(h * 0.14f));
        for (int i = 0; i < steps; i++) {
            float t = i / (float) steps;
            int alpha = Math.round(255 * flashStrength.f() * best * (1 - t) * (1 - t) * 0.9f);
            if (alpha <= 0) continue;
            int col = (alpha << 24) | base;
            int x0 = rx * i / steps, x1 = rx * (i + 1) / steps, y0 = ry * i / steps, y1 = ry * (i + 1) / steps;
            g.fill(x0, 0, x1, h, col);
            g.fill(w - x1, 0, w - x0, h, col);
            g.fill(0, y0, w, y1, col);
            g.fill(0, h - y1, w, h - y0, col);
        }
        flashes++;
    }
}
