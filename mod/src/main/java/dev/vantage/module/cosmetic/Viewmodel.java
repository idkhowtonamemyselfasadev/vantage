package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;

/** Where your hands sit on screen: moved, scaled, tilted. Only the first-person view changes, nothing about reach or timing. */
public class Viewmodel extends Module {
    public final NumberSetting x = add(new NumberSetting("Sideways", "Push the hands outward (+) or inward (-)", 0, -1.5, 1.5, 0.05));
    public final NumberSetting y = add(new NumberSetting("Height", "Raise (+) or lower (-) the hands", 0, -1.5, 1.5, 0.05));
    public final NumberSetting z = add(new NumberSetting("Depth", "Pull the hands closer (+) or push them away (-)", 0, -1.5, 1.5, 0.05));
    public final NumberSetting scale = add(new NumberSetting("Size", "Size of both hands and what they hold", 1, 0.3, 2, 0.05));
    public final NumberSetting tilt = add(new NumberSetting("Tilt", "Roll the hands outward in degrees", 0, -45, 45, 1));
    public final BoolSetting mirror = add(new BoolSetting("Mirror off hand", "Sideways and tilt flip for the off hand, so both hands stay symmetric", true));
    public final BoolSetting offHand = add(new BoolSetting("Show off hand", "Off = the off hand is not drawn at all", true));
    /** Hands drawn with the pose applied, for the rig. */
    public static int applied;

    public Viewmodel() {
        super("3D Hand", "Move, scale and tilt your first-person hands", Category.COSMETIC);
    }

    public static Viewmodel active() {
        Viewmodel v = Modules.get(Viewmodel.class);
        return v != null && v.on() ? v : null;
    }
}
