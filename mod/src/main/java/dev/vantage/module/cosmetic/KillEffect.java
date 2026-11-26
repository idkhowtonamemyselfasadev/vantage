package dev.vantage.module.cosmetic;

import java.util.ArrayDeque;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.util.Colors;
import dev.vantage.render.R3;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

/** A burst where something you hit went down: rising souls, sparks, or expanding rings. Particles and lines on your own client; read from the health the server already sent. */
public class KillEffect extends Module {
    public final ModeSetting style = add(new ModeSetting("Style", "Souls rise, Sparks fly out, Rings spread", "Souls", "Souls", "Sparks", "Rings"));
    public final ModeSetting targets = add(new ModeSetting("Targets", "Which kills count", "All", "All", "Players"));
    public final ModeSetting colour = add(Cos.mode());
    public final ColorSetting manual = add(Cos.manual()).visibleIf(() -> colour.is("Manual"));
    public final NumberSetting amount = add(new NumberSetting("Amount", "Particles per kill", 30, 4, 60, 1));
    public final NumberSetting size = add(new NumberSetting("Size", "How wide the rings spread", 1, 0.5, 2.5, 0.1));
    public static int kills, drawn, spawned;
    /** Rig: the style of the last burst. */
    public static String lastStyle = "";

    /** Calm shows only the rising souls, never sparks or a ring. */
    private String style() { return Cos.calm() ? "Souls" : style.get(); }
    private record Ring(double x, double y, double z, long t0, double h) {}
    private final ArrayDeque<Ring> rings = new ArrayDeque<>();

    public KillEffect() {
        super("Kill Effect", "A burst where a player or mob you killed went down", Category.COSMETIC);
        beta();
    }

    public static KillEffect active() {
        KillEffect k = Modules.get(KillEffect.class);
        return k != null && k.on() ? k : null;
    }

    @Override
    protected void onDisable() { rings.clear(); }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        rings.clear();
    }

    @Override
    public void onTick() {
        HitTracker.update();
        for (var k : HitTracker.KILLS) {
            if (targets.is("Players") && !k.player()) continue;
            burst(k.x(), k.y(), k.z(), k.height());
        }
    }

    /** The burst itself; public so the rig can fire one. */
    public void burst(double x, double y, double z, double h) {
        if (mc.level == null) return;
        kills++;
        var rnd = mc.level.random;
        int n = Cos.calm() ? Math.max(4, amount.i() / 2) : amount.i();
        double mid = y + h * 0.5;
        lastStyle = style();
        switch (lastStyle) {
            case "Sparks" -> {
                for (int i = 0; i < n; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, up = rnd.nextDouble() * 0.7 - 0.1, s = 0.18 + rnd.nextDouble() * 0.22;
                    mc.level.addParticle(i % 3 == 0 ? ParticleTypes.CRIT : ParticleTypes.ELECTRIC_SPARK, true, true, x, mid, z, Math.cos(a) * s, up * s + 0.04, Math.sin(a) * s);
                }
                spawned += n;
            }
            case "Rings" -> {
                if (rings.size() >= 8) rings.pollFirst();
                rings.add(new Ring(x, y + 0.05, z, System.currentTimeMillis(), h));
                var dust = new DustParticleOptions(Cos.pick(colour, manual) & 0xFFFFFF, 1f);
                for (int i = 0; i < n / 2; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    mc.level.addParticle(dust, true, true, x, mid, z, Math.cos(a) * 0.12, 0.04, Math.sin(a) * 0.12);
                }
                spawned += n / 2;
            }
            default -> {
                if (rings.size() >= 8) rings.pollFirst();
                rings.add(new Ring(x, y, z, System.currentTimeMillis(), h)); // the wisps' spiral, drawn in onRender3D
                var glow = new DustParticleOptions(0x8FEFFF, 2.4f);
                for (int i = 0; i < n * 2; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * 0.55;
                    double up = 0.07 + rnd.nextDouble() * 0.12;
                    var p = i % 3 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : i % 3 == 1 ? ParticleTypes.SOUL : glow;
                    mc.level.addParticle(p, true, true, x + Math.cos(a) * r, y + rnd.nextDouble() * h, z + Math.sin(a) * r, Math.cos(a) * 0.01, up, Math.sin(a) * 0.01);
                }
                spawned += n * 2;
            }
        }
    }

    @Override
    public void onRender3D(R3 r) {
        if (rings.isEmpty()) return;
        long now = System.currentTimeMillis();
        rings.removeIf(g -> now - g.t0 > 2000);
        int base = Cos.pick(colour, manual) & 0xFFFFFF;
        boolean souls = style().equals("Souls");
        for (Ring g : rings) {
            if (souls) { // three strands spiral up and thin out, brightest at the bottom
                double t = (now - g.t0) / 1800.0;
                if (t >= 1) continue;
                float head = Cos.out((float) t);
                for (int strand = 0; strand < 3; strand++) {
                    double px = 0, py = 0, pz = 0;
                    for (int i = 0; i <= 26; i++) {
                        double f = i / 26.0 * head, ang = f * 9 + strand * 2.094 + t * 3;
                        double rad = 0.5 * (1 - f * 0.7) * size.get(), yy = g.y + 0.1 + f * (2.4 + g.h) ;
                        double x = g.x + Math.cos(ang) * rad, z = g.z + Math.sin(ang) * rad;
                        if (i > 0) {
                            float fade = (float) Math.pow(1 - t, 1.2) * (1 - (float) (i / 26.0) * 0.6f);
                            r.line(px, py, pz, x, yy, z, Colors.fade(0xFF9FF0FF, fade * 0.35f), 12f, false);
                            r.line(px, py, pz, x, yy, z, Colors.fade(0xFFE8FFFF, fade), 4f, false);
                        }
                        px = x; py = yy; pz = z;
                    }
                }
                drawn++;
                continue;
            }
            for (int k = 0; k < 3; k++) { // three rings, each a little later and higher
                double t = ((now - g.t0) - k * 130) / 900.0;
                if (t <= 0 || t >= 1) continue;
                float e = Cos.out((float) t);
                double rad = size.get() * (0.3 + 1.5 * e), y = g.y + k * 0.35 + e * 0.5;
                int col = Colors.fade(base | 0xFF000000, (float) Math.pow(1 - t, 1.3));
                double px = g.x + rad, pz = g.z;
                for (int i = 1; i <= 40; i++) {
                    double a = i / 40.0 * Math.PI * 2, x = g.x + Math.cos(a) * rad, z = g.z + Math.sin(a) * rad;
                    r.line(px, y, pz, x, y, z, col, 3f, false);
                    px = x; pz = z;
                }
            }
            drawn++;
        }
    }
}
