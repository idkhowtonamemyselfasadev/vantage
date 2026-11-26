package dev.vantage.module.cosmetic;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Ui;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** A plain ring spreads on the ground where you jumped and fades. Optionally for other players too, read from their movement. */
public class JumpCircles extends Module {
    public final ColorSetting color = add(new ColorSetting("Colour", "Ring colour", Ui.ACCENT));
    public final NumberSetting size = add(new NumberSetting("Size", "Final radius in blocks", 1.2, 0.5, 3, 0.1));
    public final NumberSetting duration = add(new NumberSetting("Duration", "Seconds until the ring is gone", 1, 0.3, 3, 0.1));
    public final BoolSetting others = add(new BoolSetting("Other players", "Rings under other players' jumps as well", false));
    public static int rings, drawn;
    private record Ring(double x, double y, double z, long t0) {}
    private final ArrayDeque<Ring> live = new ArrayDeque<>();
    private final Map<UUID, double[]> lastY = new HashMap<>();

    public JumpCircles() {
        super("Jump Circles", "A ring on the ground where you jump", Category.COSMETIC);
    }

    public static JumpCircles active() {
        JumpCircles j = Modules.get(JumpCircles.class);
        return j != null && j.on() ? j : null;
    }

    /** Calm: the ring is 55% as strong and a thinner line (2 instead of 3). */
    public static float alphaScale() { return dev.vantage.module.client.ClickGuiModule.calmOn() ? 0.55f : 1f; }
    public static float width() { return dev.vantage.module.client.ClickGuiModule.calmOn() ? 2f : 3f; }

    /** Someone left the ground: a ring under where they stood. */
    public void jumped(LivingEntity e) {
        if (mc.player == null || e.distanceToSqr(mc.player) > 16384) return;
        if (e != mc.player && !(others.on() && e instanceof Player)) return;
        if (live.size() >= 24) live.pollFirst();
        live.add(new Ring(e.getX(), e.getY() + 0.02, e.getZ(), System.currentTimeMillis()));
        rings++;
    }

    @Override
    public void onTick() {
        if (!others.on()) { lastY.clear(); return; }
        for (var p : mc.level.players()) {
            if (p == mc.player) continue;
            double[] prev = lastY.computeIfAbsent(p.getUUID(), k -> new double[] {p.getY(), 0});
            double dy = p.getY() - prev[0];
            boolean jump = dy > 0.2 && prev[1] <= 0.01 && !p.isPassenger() && !p.getAbilities().flying && !p.isInWater() && !p.isInLava()
                    && !p.onClimbable() && p.hurtTime == 0 && !p.isSpectator() && !p.isInvisible();
            if (jump) {
                if (live.size() >= 24) live.pollFirst();
                live.add(new Ring(p.getX(), prev[0] + 0.02, p.getZ(), System.currentTimeMillis()));
                rings++;
            }
            prev[1] = dy;
            prev[0] = p.getY();
        }
    }

    @Override
    public void onRender3D(R3 r) {
        if (live.isEmpty()) return;
        long now = System.currentTimeMillis();
        double life = duration.get() * 1000;
        int base = color.argb();
        live.removeIf(g -> now - g.t0 > life);
        for (Ring g : live) {
            float t = (float) ((now - g.t0) / life);
            float e = 1 - (1 - t) * (1 - t);
            double rad = size.get() * (0.35 + 0.65 * e);
            int col = ((int) ((base >>> 24) * (1 - t) * alphaScale()) << 24) | (base & 0xFFFFFF);
            double px = g.x + rad, pz = g.z;
            for (int i = 1; i <= 64; i++) {
                double a = i / 64.0 * Math.PI * 2, x = g.x + Math.cos(a) * rad, z = g.z + Math.sin(a) * rad;
                r.line(px, g.y, pz, x, g.y, z, col, width(), false);
                px = x; pz = z;
            }
            drawn++;
        }
    }
}
