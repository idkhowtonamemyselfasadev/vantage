package dev.vantage.core.util;

import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/**
 * ARGB colour helpers (0xAARRGGBB as an int). Channel bytes are read and written with the game's {@link ARGB}.
 *
 * <p>One mix rule for the whole code base: linear in each channel (not gamma corrected), alpha mixed like the colour channels,
 * every channel rounded to the nearest value, and {@code t} clamped to 0..1. The code had six functions called {@code mix}: two
 * truncated, one produced alpha 0, two forced the alpha to 255 and one mixed in sRGB; this is the rounding one of {@code Ui}.
 */
public final class Colors {
    /** The colour with its alpha replaced by {@code alpha}, clamped to 0..255. */
    public static int withAlpha(int argb, int alpha) {
        return ARGB.color(Mth.clamp(alpha, 0, 255), argb);
    }

    /** The colour with its alpha multiplied by {@code t} (clamped to 0..1) and rounded: 0 makes it clear, 1 keeps it. */
    public static int fade(int argb, float t) {
        return ARGB.color(Math.round(ARGB.alpha(argb) * Mth.clamp(t, 0f, 1f)), argb);
    }

    /** The colour with alpha 255. */
    public static int opaque(int argb) {
        return ARGB.opaque(argb);
    }

    /** Colour between two colours: t clamped to 0..1, every channel (alpha too) linear and rounded to nearest; t 0 is a, 1 is b. */
    public static int mix(int a, int b, float t) {
        float f = Mth.clamp(t, 0f, 1f);
        return ARGB.color(chan(ARGB.alpha(a), ARGB.alpha(b), f), chan(ARGB.red(a), ARGB.red(b), f), chan(ARGB.green(a), ARGB.green(b), f), chan(ARGB.blue(a), ARGB.blue(b), f));
    }

    /** {@link #mix(int, int, float)} with a double t. */
    public static int mix(int a, int b, double t) {
        return mix(a, b, (float) t);
    }

    private static int chan(int p, int q, float f) {
        return Math.round(p + (q - p) * f);
    }

    /** Colour channels (not alpha) multiplied by {@code k}, each rounded and capped at 255; negative k counts as 0; alpha is kept. */
    public static int scale(int argb, float k) {
        float f = Math.max(0f, k);
        return ARGB.color(ARGB.alpha(argb), Math.min(255, Math.round(ARGB.red(argb) * f)), Math.min(255, Math.round(ARGB.green(argb) * f)), Math.min(255, Math.round(ARGB.blue(argb) * f)));
    }

    /** Colour moved toward white by {@code k} (clamped to 0..1: 0 keeps it, 1 is white); alpha is kept. */
    public static int lighten(int argb, float k) {
        return mix(argb, 0xFFFFFFFF, k) & 0x00FFFFFF | argb & 0xFF000000;
    }

    /** Colour moved toward black by {@code k} (clamped to 0..1: 0 keeps it, 1 is black); alpha is kept. */
    public static int darken(int argb, float k) {
        return mix(argb, 0xFF000000, k) & 0x00FFFFFF | argb & 0xFF000000;
    }

    /** Hue, saturation and value, each 0..1, of the colour's red, green and blue (alpha ignored); grey and black give hue 0. */
    public static float[] toHsv(int argb) {
        float r = ARGB.red(argb) / 255f, g = ARGB.green(argb) / 255f, b = ARGB.blue(argb) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        return new float[] {h, max == 0 ? 0 : d / max, max};
    }

    /** Colour of a hue, saturation and value (each 0..1; hue wraps) with the given alpha 0..255 (not clamped: pass a valid byte). */
    public static int fromHsv(float h, float s, float v, int alpha) {
        int rgb = java.awt.Color.HSBtoRGB(h, s, v) & 0xFFFFFF;
        return (alpha << 24) | rgb;
    }

    /** Text "#AARRGGBB" in capitals. */
    public static String hex(int argb) {
        return String.format(java.util.Locale.ROOT, "#%08X", argb);
    }

    /**
     * Colour of "#AARRGGBB", "AARRGGBB", "#RRGGBB" or "RRGGBB" (upper or lower case). Six digits are opaque. One to five digits and
     * the other lengths up to 8 are read as a plain number, as the settings file always did ("#FF" is 0x000000FF). Throws
     * {@link NumberFormatException} for anything that is not 1 to 8 hex digits after the optional '#'.
     */
    public static int parse(String text) {
        String h = text.startsWith("#") ? text.substring(1) : text;
        if (!h.matches("[0-9a-fA-F]{1,8}")) throw new NumberFormatException("not a colour: " + text);
        int v = (int) Long.parseLong(h, 16);
        return h.length() == 6 ? v | 0xFF000000 : v;
    }

    private Colors() {}
}
