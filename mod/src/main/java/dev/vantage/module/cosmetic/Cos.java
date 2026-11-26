package dev.vantage.module.cosmetic;

import dev.vantage.core.Theme;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;

/** Small shared bits of the newer cosmetics: the Theme/Manual colour pair and alpha helpers. */
final class Cos {
    private Cos() {}

    /** The "Colour" setting every new cosmetic uses, same wording as Hand Aura. */
    static ModeSetting mode() {
        return new ModeSetting("Colour", "Theme uses the menu accent, Manual your own", "Theme", "Theme", "Manual");
    }

    static ColorSetting manual() {
        return new ColorSetting("Manual colour", "The colour in Manual mode", 0xFF7B6BFF);
    }

    static int pick(ModeSetting mode, ColorSetting manual) {
        return mode.is("Theme") ? Theme.accent() : manual.argb();
    }

    /** Calm (beta): every cosmetic that flashes, pulses or blinks asks this and fades instead. Only ever true while beta is unlocked. */
    static boolean calm() {
        return dev.vantage.module.client.ClickGuiModule.calmOn();
    }

    /** Calm: how much of its strength a colour, glow or flash keeps (55%), 1 when Calm is off. */
    static float calmAlpha() {
        return calm() ? 0.55f : 1f;
    }

    /** Calm: half the particles (at least one), the full count when Calm is off. */
    static int calmCount(int n) {
        return calm() ? Math.max(1, n / 2) : n;
    }

    /** Ease-out quad, the curve the rings use. */
    static float out(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1 - (1 - t) * (1 - t);
    }
}
