package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Toasts;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.Minecraft;

/**
 * One fixed field of view for the world, past the Options slider's limit, and no more widening when you sprint or drink speed.
 * While this is ON it is the only FOV that counts: the Options FOV slider is ignored for the world, so the Vantage Options screen
 * edits this number instead (see {@link #edit}). Hands and the held item keep their own fixed view.
 */
public class CustomFov extends Module {
    public static final double DEFAULT = 110;
    public final NumberSetting fov = add(new NumberSetting("Field of view", "The world's FOV in degrees (30-160). Drag, scroll, or click the number and type", DEFAULT, 30, 160, 1));
    public final ActionSetting reset = add(new ActionSetting("Reset to 110", "Back to the default of 110", () -> fov.set(DEFAULT)));
    public static int applied;
    /** The last FOV this handed to the renderer, for the rig. */
    public static float last;

    public CustomFov() {
        super("Custom FOV", "ON: replaces the Options FOV slider and sprint/speed widening (world only, hands and Zoom unaffected)", Category.COSMETIC);
        fov.onChange(this::changed);
    }

    public static CustomFov active() {
        CustomFov c = Modules.get(CustomFov.class);
        return c != null && c.on() ? c : null;
    }

    public float apply(float vanilla) {
        applied++;
        last = fov.f();
        return last;
    }

    @Override
    public String info() { return fov.i() + "°"; }

    /** Every change keeps vanilla's own slider close (so turning this off lands near it) and says what the FOV is now. */
    private void changed() {
        Minecraft m = Minecraft.getInstance();
        if (m != null && m.options != null) {
            int v = Math.max(30, Math.min(110, fov.i()));
            if (m.options.fov().get() != v) m.options.fov().set(v);
        }
        if (on()) Toasts.note("FOV", fov.i() + "° (Custom FOV)");
    }

    @Override
    protected void onEnable() { if (Minecraft.getInstance().level != null) Toasts.note("FOV", fov.i() + "° (Custom FOV on)"); }

    /** What the Options screen's FOV slider edits: this number while the module is on, else vanilla's slider. */
    public static void edit(Minecraft mc, int degrees) {
        CustomFov c = active();
        if (c != null) c.fov.set((double) Math.max(30, Math.min(160, degrees)));
        else {
            int v = Math.max(30, Math.min(110, degrees));
            if (mc.options.fov().get() != v) mc.options.fov().set(v);
            Toasts.note("FOV", v + "°");
        }
    }

    /** The FOV the player actually sees in the world, before sprint/zoom. */
    public static int shown(Minecraft mc) {
        CustomFov c = active();
        return c != null ? c.fov.i() : mc.options.fov().get();
    }
}
