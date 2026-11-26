package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Keys;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

/**
 * Hold a key to zoom in. The view eases in and out in log space, so a 4x and a 2x zoom feel the same speed; the wheel changes the
 * zoom while held, the mouse slows down to match, and an optional cinematic camera smooths the turn. Only the world's field of view
 * changes; hands, reach and aim are untouched.
 */
public class Zoom extends Module {
    public final NumberSetting zoom = add(new NumberSetting("Zoom", "How many times closer while the key is held", 4, 1.5, 20, 0.5));
    public final NumberSetting smoothness = add(new NumberSetting("Smoothness", "0 snaps, 100 glides in slowly", 60, 0, 100, 5));
    public final BoolSetting scroll = add(new BoolSetting("Scroll to adjust", "The mouse wheel changes the zoom while held", true));
    public final BoolSetting lowerSens = add(new BoolSetting("Lower sensitivity", "Mouse speed divided by the zoom, so aiming stays steady", true));
    public final BoolSetting cinematic = add(new BoolSetting("Cinematic camera", "Vanilla's smooth camera while zoomed", false));
    /** Frames the field of view was changed, for the rig. */
    public static int applied;
    /** The rig has no keyboard: pretend the key is held. */
    public static boolean force;
    private double current = 1, target = 1;
    private long lastNs;
    private boolean held, smoothWas;

    public Zoom() {
        super("Zoom", "Hold a key to zoom in, smooth in and out", Category.COSMETIC);
        bind = GLFW.GLFW_KEY_C;
    }

    @Override
    public boolean holdKey() { return true; }

    public static Zoom active() {
        Zoom z = Modules.get(Zoom.class);
        return z != null && z.on() ? z : null;
    }

    /** Is the key down right now (or the rig pretending). */
    public boolean held() { return held; }

    @Override
    public void onTick() {
        boolean now = force || (bind != -1 && Keys.down(mc, bind) && mc.screen == null);
        if (now && !held) target = zoom.get();
        if (!now && held) target = 1;
        if (cinematic.on()) {
            if (now && !held) { smoothWas = mc.options.smoothCamera; mc.options.smoothCamera = true; }
            if (!now && held) mc.options.smoothCamera = smoothWas;
        }
        held = now;
    }

    @Override
    protected void onDisable() {
        if (held && cinematic.on()) mc.options.smoothCamera = smoothWas;
        held = false;
        target = 1;
        current = 1;
    }

    /** The field of view to draw with this frame: the vanilla one divided by the eased zoom. */
    public float apply(float fov) {
        step();
        if (current == 1) return fov;
        applied++;
        return (float) (fov / current);
    }

    private void step() {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (current == target) return;
        double rate = 30 - 25 * smoothness.get() / 100;
        double lc = Math.log(current), lt = Math.log(target);
        current = Math.exp(lc + (lt - lc) * (1 - Math.exp(-dt * rate)));
        if (Math.abs(target - current) < 0.002) current = target;
    }

    /** Mouse speed factor: smaller while zoomed. */
    public double sensitivity() {
        return lowerSens.on() && current > 1 ? 1 / current : 1;
    }

    /** A wheel notch while held: closer or further, clamped. True when it was taken. */
    public boolean wheel(double y) {
        if (!held || !scroll.on() || y == 0) return false;
        target = Math.max(1.1, Math.min(50, target * (y > 0 ? 1.2 : 0.8333333)));
        return true;
    }
}
