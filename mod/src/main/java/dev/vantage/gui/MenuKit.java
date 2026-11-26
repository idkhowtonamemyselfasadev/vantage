package dev.vantage.gui;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Ui;
import dev.vantage.core.util.Colors;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The bits the Vantage screens share: the whole screen tinted in the menu's navy with a centred content column, rounded buttons
 * with a hover glow, rows that can be picked, and hit testing in design units. Buttons are declared while drawing and cleared every frame, like the HUD does it.
 */
public final class MenuKit {
    public static final float PAD = 40, BTN = 34, ROW = 48, GAP = 6;
    /** The content column: as wide as the screen up to 1180 design units, always the full height. */
    public static float W() { return Math.min(Ui.W(), 1180); }
    public static float H() { return Ui.H(); }
    /** Over the panorama: nearly the menu's navy, the panorama just showing through. */
    /** Over the panorama: nearly the menu's navy, the panorama just showing through; follows the theme. */
    public static int tint() { return Ui.tone(0xD60B0A20); }
    public record Hit(String id, float x, float y, float w, float h) {}
    private final List<Hit> hits = new ArrayList<>();
    private final java.util.Map<String, Float> hover = new java.util.HashMap<>();
    private long last = System.currentTimeMillis();
    public float fx, fy;

    public float x0() { return (Ui.W() - W()) / 2; }
    public float y0() { return 0; }

    /** Call first in render: converts the mouse and clears the hit list. */
    public void begin(GuiGraphics g, int mx, int my) {
        Ui.begin(g);
        float s = Ui.scale();
        fx = mx / s;
        fy = my / s;
        hits.clear();
        last = System.currentTimeMillis();
    }

    public void end(GuiGraphics g) {
        Ui.end(g);
    }

    public boolean over(float x, float y, float w, float h) {
        return fx >= x && fx < x + w && fy >= y && fy < y + h;
    }

    private float glow(String id, boolean on) {
        float v = hover.getOrDefault(id, 0f);
        v += ((on ? 1 : 0) - v) * 0.35f;
        hover.put(id, v);
        return v;
    }

    /** The full-screen tint, the headline and a small line under it. */
    public void window(GuiGraphics g, String title, String sub) {
        float x = x0(), y = y0();
        Ui.rect(g, 0, 0, Ui.W(), Ui.H(), tint());
        Ui.serif(g, title, x + PAD, y + 24, Ui.TEXT, 2.3f, false);
        if (sub != null) Ui.text(g, sub, x + PAD, y + 62, Ui.FAINT, 0.86f, false);
    }

    /** A rounded button; accent = the main action; enabled = false draws it dim and ignores clicks. */
    public void button(GuiGraphics g, String id, String label, float x, float y, float w, boolean accent, boolean enabled) {
        boolean hot = enabled && over(x, y, w, BTN);
        float hv = glow(id, hot);
        int base = accent ? Colors.mix(Ui.tone(0xF0231F5C), Ui.tone(0xF02E2A78), hv) : Colors.mix(Ui.CARD, Ui.tone(0xF0191838), hv);
        int line = accent ? Colors.mix(Ui.tone(0xFF3B3786), Ui.tone(0xFF6C63D8), hv) : Colors.mix(Ui.CARD_LINE, Ui.tone(0xFF3B3786), hv);
        float a = Ui.alpha;
        if (!enabled) Ui.alpha = a * 0.45f;
        Ui.rr(g, x, y, w, BTN, 9, base, line);
        if (accent) Ui.rr(g, x + 1.5f, y + 9, 3, BTN - 18, 1.5f, Ui.ACCENT, 0);
        Ui.center(g, label, x + w / 2, y + 11, Ui.TEXT, 1f, accent);
        Ui.alpha = a;
        if (enabled) hits.add(new Hit(id, x, y, w, BTN));
    }

    /** A small pill, for side actions like Join Donut. */
    public void pill(GuiGraphics g, String id, String label, float x, float y, float w, int color) {
        boolean hot = over(x, y, w, 24);
        float hv = glow(id, hot);
        Ui.rr(g, x, y, w, 24, 12, Colors.mix(0x30000000, 0x50000000, hv), Colors.mix(Ui.fade(color, 0.5f), color, hv));
        Ui.center(g, label, x + w / 2, y + 7, Colors.mix(Ui.fade(color, 0.85f), color, hv), 0.86f, true);
        hits.add(new Hit(id, x, y, w, 24));
    }

    private final java.util.Map<String, float[]> tracks = new java.util.HashMap<>();

    /** A slider row: label left, track in the middle, the value right. Dragging is the screen's job: track(id) gives x and width. */
    public void slider(GuiGraphics g, String id, String label, String value, float frac, float x, float y, float w) {
        boolean hot = over(x, y, w, BTN);
        float hv = glow(id, hot);
        Ui.rr(g, x, y, w, BTN, 9, Colors.mix(Ui.CARD, Ui.tone(0xF0191838), hv), Colors.mix(Ui.CARD_LINE, Ui.tone(0xFF3B3786), hv));
        Ui.text(g, label, x + 14, y + 11, Ui.TEXT, 1f, false);
        Ui.right(g, value, x + w - 14, y + 11.5f, Ui.SUB, 0.9f, false);
        float tx = x + 14 + Ui.width(label, 1f, false) + 18, tw = x + w - 14 - Ui.width(value, 0.9f, false) - 18 - tx;
        frac = Math.max(0f, Math.min(1f, frac));
        Ui.rr(g, tx, y + BTN / 2 - 1.75f, tw, 3.5f, 1.7f, Ui.tone(0xFF262A4C), 0);
        Ui.rr(g, tx, y + BTN / 2 - 1.75f, tw * frac, 3.5f, 1.7f, Ui.ACCENT, 0);
        Ui.rr(g, tx + tw * frac - 4.5f, y + BTN / 2 - 4.5f, 9, 9, 4.5f, Ui.tone(0xFFB0A8FF), 0);
        tracks.put(id, new float[] {tx, tw, y + BTN / 2});
        hits.add(new Hit(id, x, y, w, BTN));
    }

    /** The track of a slider drawn this frame: {x, width} in design units, or null. */
    public float[] track(String id) { return tracks.get(id); }

    /** A list row: title, a line under it, something on the right; selected rows carry the accent bar. */
    public void row(GuiGraphics g, String id, String title, String sub, String right, float x, float y, float w, boolean selected) {
        row(g, id, null, title, sub, right, x, y, w, selected);
    }

    /** A row with a square picture at the left (a server or world icon), the text moved over by it. */
    public void row(GuiGraphics g, String id, net.minecraft.resources.Identifier icon, String title, String sub, String right, float x, float y, float w, boolean selected) {
        boolean hot = over(x, y, w, ROW);
        float hv = glow(id, hot);
        int bg = selected ? Ui.SELECT : Colors.mix(Ui.CARD, Ui.tone(0xF0191838), hv);
        Ui.rr(g, x, y, w, ROW, 8, bg, selected ? Ui.tone(0xFF3B3786) : Colors.mix(Ui.CARD_LINE, Ui.tone(0xFF3B3786), hv));
        if (selected) Ui.rr(g, x + 1.5f, y + 10, 3, ROW - 20, 1.5f, Ui.ACCENT, 0);
        float rw = right == null ? 0 : Ui.width(right, 0.84f, false) + 20;
        float tx = x + 18;
        if (icon != null) {
            float is = ROW - 12;
            Ui.image(g, icon, x + 12, y + 6, is, is);
            tx += is + 12;
        }
        Ui.text(g, Ui.fit(title, w - (tx - x) - 16 - rw, 1.08f), tx, y + 9, Ui.TEXT, 1.08f, true);
        if (sub != null) Ui.text(g, Ui.fit(sub, w - (tx - x) - 16 - rw, 0.82f), tx, y + 28, Ui.SUB, 0.82f, false);
        if (right != null) Ui.right(g, right, x + w - 14, y + 17, Ui.FAINT, 0.84f, false);
        hits.add(new Hit(id, x, y, w, ROW));
    }

    /** The id under the mouse, or null. */
    public String hit(double mx, double my) {
        float s = Ui.scale(), px = (float) mx / s, py = (float) my / s;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (px >= h.x() && px < h.x() + h.w() && py >= h.y() && py < h.y() + h.h()) return h.id();
        }
        return null;
    }

    public List<Hit> hits() { return hits; }
}
