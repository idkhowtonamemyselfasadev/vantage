package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.NumberSetting;

/** How your own hand looks: how fast the arm swings and how big held items are. Only what you see, attack timing doesn't change. */
public class HandView extends Module {
    public final NumberSetting speed = add(new NumberSetting("Swing speed", "Below 1 is slower, above 1 is faster", 1, 0.2, 3, 0.05));
    public final NumberSetting size = add(new NumberSetting("Item size", "Size of held items in first person", 1, 0.3, 2.5, 0.05));

    public HandView() {
        super("Hand View", "Slow or fast arm swing, bigger or smaller held items", Category.RENDER);
    }

    public static int swingTicks(int ticks) {
        HandView m = Modules.get(HandView.class);
        return m == null || !m.on() ? ticks : Math.max(1, Math.round(ticks / m.speed.f()));
    }

    /** 1 when the module is off, so callers can skip the work. */
    public static float itemSize() {
        HandView m = Modules.get(HandView.class);
        return m == null || !m.on() ? 1f : m.size.f();
    }
}
