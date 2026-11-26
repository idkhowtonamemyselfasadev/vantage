package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;

/** The sky and the light at a time of your choosing. Client-side only: mobs, crops and the server clock keep the real time. */
public class TimeChanger extends Module {
    public final ModeSetting mode = add(new ModeSetting("Time", "A fixed time of day; Custom uses the number below", "Noon", "Sunrise", "Noon", "Sunset", "Night", "Midnight", "Custom"));
    public final NumberSetting custom = add(new NumberSetting("Custom time", "Ticks of the day, 0 = sunrise, 6000 = noon, 18000 = midnight", 6000, 0, 24000, 100));

    public TimeChanger() {
        super("Time Changer", "Always noon, always night, or any time you like, for your eyes only", Category.COSMETIC);
    }

    /** The day time to show, or -1 to leave the server's. */
    public static long override() {
        TimeChanger t = Modules.get(TimeChanger.class);
        if (t == null || !t.on()) return -1;
        return switch (t.mode.get()) {
            case "Sunrise" -> 23000;
            case "Noon" -> 6000;
            case "Sunset" -> 12500;
            case "Night" -> 14000;
            case "Midnight" -> 18000;
            default -> t.custom.get().longValue();
        };
    }
}
