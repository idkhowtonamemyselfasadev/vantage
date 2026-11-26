package dev.vantage.module.cosmetic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Ui;
import dev.vantage.core.util.Colors;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

/** Floating numbers over what you hit, read from the health change the client sees. Drawn on the screen at the target's projected spot; nothing is sent. */
public class DamageNumbers extends Module {
    public final ModeSetting colour = add(Cos.mode());
    public final ColorSetting manual = add(Cos.manual()).visibleIf(() -> colour.is("Manual"));
    public final ModeSetting unit = add(new ModeSetting("Unit", "Health points or hearts", "Health", "Health", "Hearts"));
    public final NumberSetting size = add(new NumberSetting("Size", "Text size", 3.4, 0.8, 5, 0.1).legacy(2.7));
    public final NumberSetting rise = add(new NumberSetting("Rise", "How far a number floats up, in blocks", 1.4, 0.2, 3, 0.1));
    public final NumberSetting duration = add(new NumberSetting("Duration", "Seconds until a number is gone", 1.3, 0.5, 3, 0.1));
    public static int shown, drawn;
    private static final int MAX = 12;
    private record Pop(double x, double y, double z, double drift, String text, boolean crit, long t0) {}
    private final List<Pop> live = new ArrayList<>();

    public DamageNumbers() {
        super("Damage Numbers", "Floating numbers on what you hit", Category.COSMETIC);
        beta();
    }

    public static DamageNumbers active() {
        DamageNumbers d = Modules.get(DamageNumbers.class);
        return d != null && d.on() ? d : null;
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

    @Override
    public void onTick() {
        HitTracker.update();
        for (var d : HitTracker.DROPS) add(d.x(), d.y(), d.z(), d.amount(), d.crit());
    }

    /** A number over a spot; public so the rig can add one. */
    public void add(double x, double y, double z, float amount) {
        add(x, y, z, amount, false);
    }

    /** A number over a spot, bigger and gold when it was a crit. */
    public void add(double x, double y, double z, float amount, boolean crit) {
        float v = unit.is("Hearts") ? amount / 2f : amount;
        String t = Math.abs(v - Math.round(v)) < 0.05f ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
        if (live.size() >= MAX) live.remove(0);
        var rnd = mc.level == null ? null : mc.level.random;
        live.add(new Pop(x, y + 0.2, z, rnd == null ? 0 : (rnd.nextDouble() - 0.5) * 0.6, "-" + t + (crit ? "!" : ""), crit, System.currentTimeMillis()));
        shown++;
    }

    /** The swell of a new number at life fraction t: past full size and back, or 1 all along when Calm (a plain fade). */
    public static float popFactor(float t) {
        return Cos.calm() ? 1f : t < 0.1f ? 0.5f + 0.85f * (t / 0.1f) : t < 0.25f ? 1.35f - 0.35f * ((t - 0.1f) / 0.15f) : 1f;
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (live.isEmpty()) return;
        long now = System.currentTimeMillis();
        double life = duration.get() * 1000;
        live.removeIf(p -> now - p.t0 > life);
        if (live.isEmpty()) return;
        int base = Cos.pick(colour, manual) & 0xFFFFFF;
        Ui.begin(g);
        var cam = mc.gameRenderer.getMainCamera().position();
        for (Pop p : live) {
            float t = (float) ((now - p.t0) / life);
            float e = Cos.out(t);
            var right = mc.gameRenderer.getMainCamera().leftVector();
            double px = p.x - right.x() * p.drift, pz = p.z - right.z() * p.drift;
            float[] at = Ui.project(new Vec3(px, p.y + rise.get() * e, pz));
            if (at == null) continue;
            double dist = cam.distanceTo(new Vec3(p.x, p.y, p.z));
            float sc = (float) (size.get() * Math.max(0.65, Math.min(1.7, 7.0 / Math.max(2.5, dist))));
            // pop: swell past full size, settle; crits are bigger and gold
            float pop = popFactor(t);
            float a = t < 0.65f ? 1f : 1 - (t - 0.65f) / 0.35f;
            float s = sc * pop * (p.crit ? 1.45f : 1f);
            int col = p.crit ? 0xFFFFC94A : base;
            float w = Ui.width(p.text, s, true);
            int shade = Colors.fade(0xFF000000, a * 0.9f);
            float o = Math.max(1f, s * 0.9f); // dark outline: the text drawn dark all around, then the colour on top
            for (int i = 0; i < 8; i++) {
                double ang = i * Math.PI / 4;
                Ui.text(g, p.text, at[0] - w / 2 + (float) Math.cos(ang) * o, at[1] + (float) Math.sin(ang) * o, shade, s, true);
            }
            Ui.text(g, p.text, at[0] - w / 2, at[1], Colors.fade(col | 0xFF000000, a), s, true);
            drawn++;
        }
        Ui.end(g);
    }
}
