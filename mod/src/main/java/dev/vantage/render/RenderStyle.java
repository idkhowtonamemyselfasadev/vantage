package dev.vantage.render;

/**
 * The look of one frame's world drawing, as plain values: everything {@link R3} used to read from ESP Style while it drew.
 * ESP Style fills one reused instance per frame ({@code EspStyle.snapshot()}), so R3 never reaches into a module and nothing
 * is allocated per frame. A fresh instance holds the "ESP Style is off" values (no merge, glow, fade or declutter).
 */
public final class RenderStyle {
    /** The one outline weight before the global Line width (ESP Style's WEIGHT). */
    public static final float WEIGHT = 1.4f;

    public boolean reveal, merge, declutter, fillsFade, depth, glow, shrink, labelBacking, farLabels, groupBoxes, groupOutline, groupFill;
    /** Distance fade is on (the curve itself is in {@link #fade(double)}). */
    public boolean fadeOn;
    public float minLabel = 1f, labelSize = 1f, labelRange = 512f, lineScale = 1f;
    public int groupMin, maxBoxes, maxLabels;

    /** {@link R3#fadeAt} (the one copy of the curve: 1 up to 64 blocks, 0.45 at 256 and beyond); always 1 with the fade off. */
    public float fade(double dist) {
        return fadeOn ? R3.fadeAt(dist) : 1f;
    }

    /** Lines get 30% thinner by 256 blocks (with the fade on), after the global Line width. */
    public float widthAt(float base, double dist) {
        float w = base * lineScale;
        if (!fadeOn) return w;
        return w * (float) (1 - 0.3 * Math.min(1, dist / 256));
    }
}
