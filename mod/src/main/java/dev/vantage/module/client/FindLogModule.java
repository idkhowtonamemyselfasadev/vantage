package dev.vantage.module.client;

import dev.vantage.core.Category;
import dev.vantage.core.FindLog;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.gui.FindLogScreen;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/** Beta: a list of what the finders marked this session (kind, distance, coordinates, time) and an optional "Bases found: 3" line on the HUD. */
public class FindLogModule extends Module {
    public final BoolSetting counter = add(new BoolSetting("HUD counter", "A line on the HUD: Bases found: 3", true));
    public final ModeSetting corner = add(new ModeSetting("HUD corner", "Where the counter goes", "Top Right", Hud.CORNERS)).visibleIf(counter::on);
    public static int drawn;

    static {
        HudElements.declare("findlog", "Find Log", "Top Right", FindLogModule.class);
    }

    public FindLogModule() {
        super("Find Log", "Everything Base Finder and Stash Finder marked this session: kind, distance, coordinates, time. Memory only, nothing is saved or sent", Category.CLIENT);
        beta();
        add(new ActionSetting("Open log", "The session list", () -> mc.setScreen(new FindLogScreen(mc.screen))));
        add(new ActionSetting("Clear log", "Forget the list and the counter", FindLog::clear));
    }

    public static FindLogModule active() {
        FindLogModule m = Modules.get(FindLogModule.class);
        return m != null && m.on() ? m : null;
    }

    @Override
    public String info() {
        return String.valueOf(FindLog.count(null));
    }

    /** The HUD counter text: "Bases found: 3", plus the stashes once there is one. */
    public static String line() {
        int bases = FindLog.count("Base"), stashes = FindLog.count("Stash");
        return "Bases found: " + bases + (stashes > 0 ? "  \u00b7  Stashes: " + stashes : "");
    }

    public void record(String kind, String detail, double x, double y, double z) {
        FindLog.add(kind, detail, x, y, z);
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (!counter.on() || Hud.hidden()) return;
        int bases = FindLog.count("Base"), stashes = FindLog.count("Stash");
        String s = "Bases found: " + bases + (stashes > 0 ? "  ·  Stashes: " + stashes : "");
        float w = Ui.width(s, 0.9f, false) + 20, h = 20;
        float[] at = Hud.slot("findlog", corner.get(), w, h);
        if (at == null) return;
        Ui.begin(g);
        Ui.rr(g, at[0], at[1], w, h, 7, Ui.GLASS, Ui.BORDER);
        Ui.text(g, s, at[0] + 10, at[1] + 6, Ui.TEXT, 0.9f, false);
        Ui.end(g);
        drawn++;
    }
}
