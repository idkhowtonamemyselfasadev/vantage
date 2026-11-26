package dev.vantage.module.cosmetic;

import java.util.ArrayDeque;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.util.Colors;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;

/** A short glowing arc that follows your sword swing. Starts when the swing starts, hangs in the air where you swung and fades; the hit itself is vanilla. */
public class BladeTrail extends Module {
    public final ModeSetting colour = add(Cos.mode());
    public final ColorSetting manual = add(Cos.manual()).visibleIf(() -> colour.is("Manual"));
    public final NumberSetting length = add(new NumberSetting("Length", "How much of the swing the arc covers", 0.65, 0.15, 1, 0.05));
    public final NumberSetting size = add(new NumberSetting("Size", "Reach and width of the arc", 1, 0.5, 1.8, 0.05));
    public final NumberSetting duration = add(new NumberSetting("Duration", "Seconds the arc takes to sweep and fade", 0.3, 0.15, 1, 0.05));
    public final BoolSetting weaponsOnly = add(new BoolSetting("Weapons only", "Only swords, axes, maces, tridents and spears (off: any item)", true));
    public final BoolSetting blocks = add(new BoolSetting("While mining", "Also draw it when the swing is you mining or using a block", false));
    public static int arcs, drawn;
    private static final int SEG = 14;
    private record Arc(double x, double y, double z, float yaw, float pitch, boolean flip, long t0) {}
    private final ArrayDeque<Arc> live = new ArrayDeque<>();
    private boolean flip;

    public BladeTrail() {
        super("Blade Trail", "A short arc that follows your sword swing", Category.COSMETIC);
        beta();
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
        Player p = mc.player;
        if (p == null || !p.swinging || p.swingTime != 0) return; // the first tick of a swing
        if (!blocks.on() && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) return;
        if (weaponsOnly.on() && !weapon(p.getMainHandItem())) return;
        swung(p);
    }

    private static boolean weapon(ItemStack s) {
        return s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(ItemTags.SPEARS) || s.is(Items.MACE) || s.is(Items.TRIDENT);
    }

    /** An arc from where and how you swing now; public so the rig can fire one. */
    public void swung(Player p) {
        if (live.size() >= 4) live.pollFirst();
        flip = !flip;
        var eye = p.getEyePosition();
        live.add(new Arc(eye.x, eye.y - 0.25, eye.z, p.getYRot(), p.getXRot(), flip, System.currentTimeMillis()));
        arcs++;
    }

    /** Calm: the arc keeps 55% of its strength and loses the glow under its edge. */
    public static float alphaScale() { return Cos.calmAlpha(); }
    public static boolean glow() { return !Cos.calm(); }

    private double dx, dy, dz;

    /** Unit direction of yaw/pitch in Minecraft's convention into dx, dy, dz. */
    private void dir(float yaw, float pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch), c = Math.cos(pr);
        dx = -Math.sin(yr) * c;
        dy = -Math.sin(pr);
        dz = Math.cos(yr) * c;
    }

    @Override
    public void onRender3D(R3 r) {
        if (live.isEmpty()) return;
        long now = System.currentTimeMillis();
        double dur = duration.get() * 1000;
        live.removeIf(a -> now - a.t0 > dur * 1.7);
        int base = Cos.pick(colour, manual) & 0xFFFFFF;
        double out = 2.5 * size.get(), in = 0.75 * size.get();
        for (Arc a : live) {
            double t = (now - a.t0) / dur;
            float head = Cos.out((float) Math.min(t, 1));
            float tail = t <= 1 ? Math.max(0f, head - length.f()) : Math.max(0f, 1 - length.f()) + (float) Math.min(1, (t - 1) / 0.7) * Math.min(1f, length.f());
            if (tail >= head && t <= 1) continue;
            float side = a.flip ? -1 : 1;
            double ox = 0, oy = 0, oz = 0, ix = 0, iy = 0, iz = 0;
            for (int i = 0; i <= SEG; i++) {
                float s = tail + (Math.min(head, 1f) - tail) * (i / (float) SEG);
                dir(a.yaw + side * (62f - 124f * s), a.pitch - 28f + 52f * s);
                double nox = a.x + dx * out, noy = a.y + dy * out, noz = a.z + dz * out;
                double nix = a.x + dx * in, niy = a.y + dy * in, niz = a.z + dz * in;
                if (i > 0) {
                    float k = i / (float) SEG;
                    float fade = (float) (t <= 1 ? 1 : Math.max(0, 1 - (t - 1) / 0.7));
                    int fill = Colors.fade(base | 0xFF000000, (float) Math.pow(k, 1.4) * 0.85f * fade * alphaScale());
                    r.quad(fill, false, (float) ix, (float) iy, (float) iz, (float) ox, (float) oy, (float) oz, (float) nox, (float) noy, (float) noz, (float) nix, (float) niy, (float) niz);
                    int edge = Colors.fade(Colors.mix(base | 0xFF000000, 0xFFFFFFFF, 0.6f), k * 0.9f * fade * alphaScale());
                    if (glow()) r.line(ox, oy, oz, nox, noy, noz, Colors.fade(base | 0xFF000000, k * 0.5f * fade), 9f, false); // soft glow under the bright edge (Calm: none)
                    r.line(ox, oy, oz, nox, noy, noz, edge, 4.5f, false);
                }
                ox = nox; oy = noy; oz = noz; ix = nix; iy = niy; iz = niz;
            }
            drawn++;
        }
    }
}
