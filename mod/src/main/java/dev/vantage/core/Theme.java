package dev.vantage.core;

import java.util.HashMap;
import java.util.Map;

import dev.vantage.core.util.Colors;
import dev.vantage.module.client.ClickGuiModule;

/**
 * The client's colours. A preset picks the accent, and every navy tone of the menu and HUD is turned to the accent's hue
 * (Mono drops the colour out), so the whole client follows one look; text stays light so it reads on every preset.
 * Presets live on Client > ClickGUI > Theme; Custom takes the accent from the colour wheel there. The legacy names below
 * (BG, TEXT, ...) are kept for the chat and HUD code that used them.
 */
public final class Theme {
    public record Preset(String name, int accent) {}
    public static final Preset[] PRESETS = {
            new Preset("Vantage", 0xFF7B6BFF), new Preset("Crimson", 0xFFE4556A), new Preset("Emerald", 0xFF4CCF8E), new Preset("Ocean", 0xFF4FA9E8),
            new Preset("Midnight", 0xFF6F8CFF), new Preset("Sakura", 0xFFF28CC4), new Preset("Amber", 0xFFF0B24C), new Preset("Mono", 0xFFD2D6E0), new Preset("Custom", 0)};
    /** The Accent setting's old green default: it means "the theme's own accent", not green. */
    private static final int OLD_ACCENT = 0xFF6CBC7A;
    // the Vantage look, everything else is derived from it
    private static final int B_ACCENT = 0xFF7B6BFF, B_TITLE = 0xFF8D84F2, B_WIN = 0xF60B0A20, B_SIDE = 0xF2100F2C, B_CARD = 0xE8131230, B_CARD_LINE = 0xFF1F1E44,
            B_SELECT = 0xFF26245C, B_WIN_LINE = 0xFF1E1C44, B_BG = 0xE41B2132, B_GLASS = 0xD8151A28, B_FIELD = 0x70101522, B_BORDER = 0x60566080;

    public static int BG = B_BG, BG_HEADER = B_GLASS, BG_HOVER = 0x24FFFFFF, BG_SETTING = B_FIELD;
    public static final int TEXT = Ui.TEXT, TEXT_DIM = Ui.SUB, TEXT_FAINT = Ui.FAINT, LINE = Ui.LINE, RED = Ui.RED, YELLOW = Ui.YELLOW, GREEN = Ui.GREEN;

    private static String appliedTheme = "";
    private static int appliedAccent;
    private static boolean appliedOnce;
    private static float hue;
    private static boolean mono, plain = true;
    private static final Map<Integer, Integer> TONES = new HashMap<>();

    public static String[] names() {
        String[] out = new String[PRESETS.length];
        for (int i = 0; i < out.length; i++) out[i] = PRESETS[i].name();
        return out;
    }

    /** Applies the ClickGUI theme setting when it changed; called every frame from Ui.begin, cheap when nothing moved. */
    public static void sync() {
        ClickGuiModule m = Modules.get(ClickGuiModule.class);
        if (m == null) return;
        String theme = m.theme.get();
        int accent = m.accent.argb();
        if (appliedOnce && accent == appliedAccent && theme.equals(appliedTheme)) return; // no string is built per frame (B-174)
        appliedOnce = true;
        appliedTheme = theme;
        appliedAccent = accent;
        apply(theme, accent);
    }

    static void apply(String preset, int custom) {
        int accent = B_ACCENT;
        for (Preset p : PRESETS) if (p.name().equals(preset) && p.accent() != 0) accent = p.accent();
        if (preset.equals("Custom")) {
            int c = 0xFF000000 | custom;
            accent = c == OLD_ACCENT ? B_ACCENT : c;
        }
        mono = preset.equals("Mono");
        plain = accent == B_ACCENT && !mono;
        float[] h = Colors.toHsv(accent);
        hue = h[0];
        TONES.clear();
        Ui.ACCENT = accent;
        Ui.TITLE = plain ? B_TITLE : Colors.fromHsv(h[0], h[1] * 0.75f, Math.min(1f, h[2] * 1.05f), 255);
        Ui.WIN = tone(B_WIN); Ui.SIDE = tone(B_SIDE); Ui.CARD = tone(B_CARD); Ui.CARD_LINE = tone(B_CARD_LINE);
        Ui.SELECT = tone(B_SELECT); Ui.WIN_LINE = tone(B_WIN_LINE); Ui.BG = tone(B_BG); Ui.GLASS = tone(B_GLASS);
        Ui.FIELD = tone(B_FIELD); Ui.BORDER = tone(B_BORDER);
        BG = Ui.BG; BG_HEADER = Ui.GLASS; BG_SETTING = Ui.FIELD;
    }

    /** A navy tone of the Vantage look in the current theme's hue (unchanged on the Vantage preset, grey on Mono). */
    public static int tone(int base) {
        if (plain) return base;
        Integer t = TONES.get(base);
        if (t != null) return t;
        float[] h = Colors.toHsv(base);
        int out = Colors.fromHsv(hue, mono ? 0f : h[1], h[2], base >>> 24);
        TONES.put(base, out);
        return out;
    }

    public static int accent() {
        sync();
        return Ui.ACCENT;
    }

    private Theme() {}
}
