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
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;

/** An impact ring where you land, bigger the further you fell. Read from your own fall distance; the landing itself is vanilla. */
public class LandingRing extends Module {
    public final ModeSetting colour = add(Cos.mode());
    public final ColorSetting manual = add(Cos.manual()).visibleIf(() -> colour.is("Manual"));
    public final NumberSetting minFall = add(new NumberSetting("Min fall", "Blocks you must fall before a ring shows", 3, 1, 20, 0.5));
    public final NumberSetting size = add(new NumberSetting("Size", "Scales the ring", 1, 0.4, 2.5, 0.1));
    public final NumberSetting duration = add(new NumberSetting("Duration", "Seconds until the ring is gone", 1, 0.3, 3, 0.1));
    public static int rings, drawn;
    private static final int SEG = 48;
    private record Ring(double x, double y, double z, double reach, long t0) {}
    private final ArrayDeque<Ring> live = new ArrayDeque<>();
    private float maxFall;
    private boolean wasAir;

    public LandingRing() {
        super("Landing Ring", "An impact ring where you land, bigger the further you fell", Category.COSMETIC);
        beta();
    }

    public static LandingRing active() {
        LandingRing l = Modules.get(LandingRing.class);
        return l != null && l.on() ? l : null;
    }

    @Override
    protected void onDisable() { live.clear(); maxFall = 0; wasAir = false; }

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
        maxFall = 0;
        wasAir = false;
    }

    @Override
    public void onTick() {
        Player p = mc.player;
        if (p == null) return;
        if (!p.onGround()) {
            wasAir = true;
            if (p.fallDistance > maxFall) maxFall = (float) p.fallDistance;
            return;
        }
        if (wasAir && maxFall >= minFall.get() && !p.isInWater() && !p.getAbilities().flying && !p.isSpectator()) land(p, maxFall);
        wasAir = false;
        maxFall = 0;
    }

    /** You touched down after falling this far; public so the rig can fire one. */
    public void land(Player p, double fall) {
        double reach = size.get() * Math.min(7.0, 1.1 + Math.min(fall, 24) * 0.3);
        if (live.size() >= 6) live.pollFirst();
        live.add(new Ring(p.getX(), p.getY() + 0.03, p.getZ(), reach, System.currentTimeMillis()));
        rings++;
        // a few crumbs of what you landed on
        var below = mc.level.getBlockState(p.blockPosition().below());
        if (!below.isAir()) {
            var crumb = new BlockParticleOption(ParticleTypes.BLOCK, below);
            var rnd = mc.level.random;
            int n = Cos.calmCount((int) Math.min(18, 4 + fall));
            lastCrumbs = n;
            for (int i = 0; i < n; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, s = 0.08 + rnd.nextDouble() * 0.12;
                mc.level.addParticle(crumb, true, true, p.getX() + Math.cos(a) * 0.4, p.getY() + 0.1, p.getZ() + Math.sin(a) * 0.4, Math.cos(a) * s, 0.08 + rnd.nextDouble() * 0.1, Math.sin(a) * s);
            }
        }
    }

    /** Rig: crumbs thrown by the last landing. */
    public static int lastCrumbs;

    /** Rings per landing: two, or one with Calm. */
    public static int echoes() {
        return Cos.calm() ? 1 : 2;
    }

    @Override
    public void onRender3D(R3 r) {
        if (live.isEmpty()) return;
        long now = System.currentTimeMillis();
        double life = duration.get() * 1000;
        live.removeIf(g -> now - g.t0 > life + 220);
        int base = Cos.pick(colour, manual);
        for (Ring g : live) {
            for (int k = 0; k < echoes(); k++) { // a second, smaller ring a beat behind the first (Calm: only the first)
                double t = ((now - g.t0) - k * 140) / life;
                if (t <= 0 || t >= 1) continue;
                float e = Cos.out((float) t);
                double rad = g.reach * (k == 0 ? 1 : 0.62) * (0.15 + 0.85 * e);
                float a = (float) Math.pow(1 - t, 1.4) * Cos.calmAlpha();
                int line = Colors.fade((base & 0xFFFFFF) | 0xFF000000, a);
                int band = Colors.fade((base & 0xFFFFFF) | 0xFF000000, a * 0.32f);
                double inner = Math.max(0.05, rad - (0.12 + 0.5 * (1 - e) * g.reach * 0.1));
                double px = g.x + rad, pz = g.z, ix = g.x + inner, iz = g.z;
                for (int i = 1; i <= SEG; i++) {
                    double ang = i / (double) SEG * Math.PI * 2, c = Math.cos(ang), s = Math.sin(ang);
                    double x = g.x + c * rad, z = g.z + s * rad, x2 = g.x + c * inner, z2 = g.z + s * inner;
                    r.line(px, g.y, pz, x, g.y, z, line, 3.5f, false);
                    r.quad(band, false, (float) px, (float) g.y, (float) pz, (float) x, (float) g.y, (float) z, (float) x2, (float) g.y, (float) z2, (float) ix, (float) g.y, (float) iz);
                    px = x; pz = z; ix = x2; iz = z2;
                }
            }
            drawn++;
        }
    }
}
