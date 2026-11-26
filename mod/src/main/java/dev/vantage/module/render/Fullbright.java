package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.NumberSetting;

/** Lightmap gamma override. Xray turns it on too, since caves are pitch black without terrain. */
public class Fullbright extends Module {
    public final NumberSetting gamma = add(new NumberSetting("Gamma", "How bright (1 = vanilla max)", 16, 1, 16, 0.5));

    public Fullbright() {
        super("Fullbright", "Everything lit, no torches needed", Category.RENDER);
    }

    public static boolean active() {
        Fullbright f = Modules.get(Fullbright.class);
        return f != null && f.on() || Xray.active() || Freecam.bright();
    }

    public static double gamma() {
        Fullbright f = Modules.get(Fullbright.class);
        return f != null && f.on() ? f.gamma.get() : 16.0;
    }
}
