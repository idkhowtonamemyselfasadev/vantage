package dev.vantage.module.client;

import dev.vantage.core.Category;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Perf;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Beta. Shows what each module costs (ms per frame, a column in the Active tab), sets the number of the chunk scans' time budget
 * (the budget itself is on for everyone, 2 ms per tick; while this module is on it uses the setting here) and, when the frame
 * rate is under a target, names the heaviest module on the HUD.
 */
public class FrameBudget extends Module {
    public final NumberSetting budget = add(new NumberSetting("Scan budget (ms)", "Chunk scans of the finders may use this much time per tick together; the rest waits for the next tick (without this module it is 2 ms)", 3.0, 0.5, 10, 0.5));
    public final BoolSetting column = add(new BoolSetting("ms column", "A ms per frame column on every card of the Active tab", true));
    public final BoolSetting hint = add(new BoolSetting("Heaviest module hint", "While the frame rate is under the target, a HUD line names the module that costs the most, but only one that takes at least 1 ms and at least 10% of the frame; otherwise nothing is shown", true));
    public final NumberSetting target = add(new NumberSetting("FPS target", "The hint shows below this frame rate", 60, 20, 240, 5)).visibleIf(hint::on);
    public final ModeSetting corner = add(new ModeSetting("HUD corner", "Where the hint goes", "Bottom Left", Hud.CORNERS)).visibleIf(hint::on);
    public static int drawn;

    static {
        HudElements.declare("framebudget", "Frame Budget", "Bottom Left", FrameBudget.class);
    }

    public FrameBudget() {
        super("Frame Budget", "Which module costs what (ms per frame in the Active tab), a time budget for the finders' chunk scans, and a hint naming the heaviest module when the frame rate is low", Category.CLIENT);
        beta();
    }

    @Override
    protected void onEnable() {
        Perf.track = true;
    }

    @Override
    protected void onDisable() {
        Perf.track = false;
    }

    /** The module to blame for a slow frame, or null: it must cost at least 1 ms AND at least 10% of the frame time (1000 / fps), else nothing is named. */
    public static String heaviest(int fps) {
        return Perf.heaviest(Math.max(1.0, 0.1 * 1000.0 / Math.max(1, fps)));
    }

    public static String hintText(int fps) {
        String h = heaviest(fps);
        return h == null ? null : "fps " + fps + "  -  heaviest: " + h + " " + String.format(java.util.Locale.ROOT, "%.2f", Perf.msFor(h)) + " ms";
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!hint.on() || Hud.hidden()) return;
        int fps = mc.getFps();
        if (fps >= target.i()) return;
        String s = hintText(fps);
        if (s == null) return;
        float w = Ui.width(s, 0.9f, false) + 20, h = 20;
        float[] at = Hud.slot("framebudget", corner.get(), w, h);
        if (at == null) return;
        Ui.begin(g);
        Ui.rr(g, at[0], at[1], w, h, 7, Ui.GLASS, Ui.BORDER);
        Ui.text(g, s, at[0] + 10, at[1] + 6, Ui.YELLOW, 0.9f, false);
        Ui.end(g);
        drawn++;
    }
}
