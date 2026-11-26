package dev.vantage.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Small cards stacked in the bottom right corner: an accent bar (yellow = warning, teal = done), a title and up to two
 * lines. A new card slides in from the right edge and the ones above it glide up out of its way; a card that is done
 * fades and slips back out while the stack settles down again. Nothing jumps.
 */
public final class Toasts {
    private static final long LIFE = 5000, IN = 280, OUT = 320;
    private static final int MAX = 4;
    private static final float W = 190;

    private static final class Toast {
        final String title;
        String body;
        final boolean warn;
        long born;
        /** Where the card's top edge is, glided towards its place in the stack; NaN until first drawn. */
        float y = Float.NaN;

        Toast(String title, String body, boolean warn) {
            this.title = title;
            this.body = body;
            this.warn = warn;
            born = System.currentTimeMillis();
        }
    }

    private static final List<Toast> LIST = new ArrayList<>();
    private static long lastFrame;

    public static void warn(String title, String body) {
        add(new Toast(title, body, true));
    }

    public static void ok(String title, String body) {
        add(new Toast(title, body, false));
    }

    /** A confirmation that updates in place (a value being dragged): the same title refreshes its card instead of stacking a new one. */
    public static void note(String title, String body) {
        for (Toast t : LIST) if (!t.warn && t.title.equals(title)) { t.body = body; t.born = System.currentTimeMillis(); return; }
        add(new Toast(title, body, false));
    }

    /** What the last toast titled so, for the rig. */
    public static String last(String title) {
        for (int i = LIST.size() - 1; i >= 0; i--) if (LIST.get(i).title.equals(title)) return LIST.get(i).body;
        return null;
    }

    private static void add(Toast t) {
        LIST.add(t);
        while (LIST.size() > MAX) LIST.remove(0);
    }

    private static List<String> wrap(String s, float w) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String word : s.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (Ui.width(next, 0.82f, false) > w && !line.isEmpty()) { out.add(line); line = word; }
            else line = next;
        }
        if (!line.isEmpty()) out.add(line);
        return out.size() > 2 ? out.subList(0, 2) : out;
    }

    /** For the rigs: nothing on screen. */
    public static void clear() { LIST.clear(); }

    /** Where the toasts were drawn last frame, in design units (Callouts keeps clear of them). */
    private static List<float[]> rects = new ArrayList<>();

    public static List<float[]> rects() {
        return rects;
    }

    public static void render(GuiGraphics g) {
        if (Panic.active()) { LIST.clear(); rects = List.of(); return; } // panic key: nothing of Vantage on screen
        long now = System.currentTimeMillis();
        List<float[]> drawnRects = new ArrayList<>();
        rects = drawnRects;
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        for (Iterator<Toast> it = LIST.iterator(); it.hasNext();) if (now - it.next().born > LIFE) it.remove();
        if (LIST.isEmpty()) return;
        Ui.begin(g);
        float x = Ui.W() - W - 8;
        // the text of every card first: the stack has to know how tall it is to find a place above the HUD panels
        List<List<String>> wrapped = new ArrayList<>(LIST.size());
        float stackH = 0;
        for (Toast t : LIST) {
            List<String> lines = wrap(Streamer.scrub(Streamer.scrubCoords(t.body)), W - 30);
            wrapped.add(lines);
            stackH += 22 + lines.size() * 10;
        }
        float bottom = clearOfPanels(x, Ui.H() - 8, stackH);
        // lay the stack out from the bottom up: the newest card sits lowest, each older one above it
        float targetY = bottom;
        for (int i = LIST.size() - 1; i >= 0; i--) {
            Toast t = LIST.get(i);
            long age = now - t.born;
            List<String> lines = wrapped.get(i);
            float h = 22 + lines.size() * 10;
            targetY -= h;
            // the slot a card leaves behind closes once it starts going, so the rest glide down early
            float leaving = Ui.ease((age - (LIFE - OUT)) / (float) OUT);
            float in = Ui.ease(age / (float) IN);
            float y = Float.isNaN(t.y) ? targetY : Ui.glide(t.y, targetY, 14, dt);
            t.y = y;
            float a = Math.min(in, 1 - leaving);
            float sx = x + (W + 12) * (1 - in) + 18 * leaving;
            draw(g, t, lines, sx, y, h, a);
            if (a > 0.01f) drawnRects.add(new float[] {sx, y, W, h});
            targetY += h * leaving - 4 * (1 - leaving); // a leaving card gives its height back, so the stack settles
        }
        Ui.end(g);
    }

    /**
     * The bottom edge for a stack of the given height: the usual 8 units above the screen edge, or higher when a HUD panel (the
     * bottom right corner stack, a panel pinned or sent there) is in the way, so a card never covers one. Panels of this frame are
     * known because the toasts are drawn after every module. If the stack would not fit above them it is kept on screen and the
     * overlap is accepted: a message you have to read beats a panel you can read again in five seconds.
     */
    private static float clearOfPanels(float x, float bottom, float stackH) {
        List<Hud.Slot> slots = Hud.current();
        for (int pass = 0; pass <= slots.size(); pass++) {
            float lifted = bottom;
            for (Hud.Slot s : slots) {
                boolean sideways = s.x() < x + W && s.x() + s.w() > x;
                boolean vertical = s.y() < bottom && s.y() + s.h() > bottom - stackH;
                if (sideways && vertical) lifted = Math.min(lifted, s.y() - 4);
            }
            if (lifted == bottom) break;
            bottom = lifted;
        }
        return Math.max(bottom, Math.min(stackH + 8, Ui.H() - 8));
    }

    private static void draw(GuiGraphics g, Toast t, List<String> lines, float x, float y, float h, float a) {
        if (a <= 0.01f) return;
        Ui.alpha = a;
        int col = t.warn ? Ui.YELLOW : Ui.TEAL;
        Ui.card(g, x, y, W, h, col);
        Ui.text(g, t.title, x + 12, y + 6, Ui.TEXT, 0.9f, false);
        float ly = y + 17;
        for (String l : lines) { Ui.text(g, l, x + 12, ly, Ui.SUB, 0.82f, false); ly += 10; }
        long age = System.currentTimeMillis() - t.born;
        Ui.rect(g, x + 10, y + h - 1.5f, (W - 20) * (1f - Math.min(1f, age / (float) LIFE)), 1, Ui.fade(col, 0.45f));
        Ui.alpha = 1f;
    }

    private Toasts() {}
}
