package dev.vantage.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * One 2D layout for everything that points at a place in the world (Base Finder's card and tags, Base ESP cards, edge arrows).
 * Modules submit what they want to show during their onRender2D; VantageClient calls flush() once after all modules, which
 * places everything at once: highest priority first, each one beside its anchor where it hits nothing already placed and
 * nothing reserved (HUD panels, the hotbar, the crosshair, toasts). Nothing is ever pushed against the screen edge: a thing that
 * fits nowhere tries its smaller form, and is left out when even that does not fit. Positions glide, new things fade in.
 * All sizes are design units (see Ui).
 */
public final class Callouts {
    public static final int FULL = 0, BRIEF = 1, PILL = 2, ESP = 3, ARROW = 4;

    public record Form(int kind, float w, float h) {}

    /** Draws one callout at x, y in the given form. Ui.alpha is already set for the fade. */
    public interface Drawer {
        void draw(GuiGraphics g, int kind, float x, float y, float w, float h);
    }

    /** One callout as placed in the last flush, for the rig. */
    public record Drawn(String id, int kind, float x, float y, float w, float h, float ax, float ay, float alpha) {}

    private record Sub(String id, float ax, float ay, float prio, int color, List<Form> forms, Drawer d, int order) {}
    private record Edge(String id, float dx, float dy, float prio, int color, String text) {}

    private static final class State {
        float x, y, w, h, ax, ay;
        int kind, slot = -1;
        long born, goneAt;
        boolean gone;
        Drawer d;
        int color;
    }

    private static final List<Sub> SUBS = new ArrayList<>();
    private static final List<Edge> EDGES = new ArrayList<>();
    private static final List<float[]> EXTRA = new ArrayList<>();
    private static final Map<String, State> STATES = new HashMap<>();
    private static List<Drawn> drawn = List.of();
    private static List<float[]> reserved = List.of(), lastRects = List.of();
    private static long frames, dropped, lastFlush;
    /** Rig: fade-out copies of things that went away, drawn so far. A card that stays up must never grow one. */
    public static long ghostDraws, ghostsHeld, adopted;
    private static int order;

    private static final float GAP1 = 16, GAP2 = 48, PAD = 6, EDGE_IN = 28, ARROW_H = 20;
    private static final long FADE_IN = 200, FADE_OUT = 150;
    /** A new card takes over the state of a vanished one when their anchors are this close (design units). */
    private static final float ADOPT_REACH = 80;

    private Callouts() {}

    public static void submit(String id, float ax, float ay, float prio, int color, List<Form> forms, Drawer d) {
        if (forms.isEmpty()) return;
        SUBS.add(new Sub(id, ax, ay, prio, color, forms, d, order++));
    }

    /** A small arrow chip at the screen edge pointing at something off screen. dirX/dirY: screen direction (y down). */
    public static void edge(String id, float dirX, float dirY, float prio, int color, String text) {
        EDGES.add(new Edge(id, dirX, dirY, prio, color, text));
    }

    /** Keeps a rectangle free this frame (a panel drawn outside Hud.slot, a player tag). */
    public static void reserve(float x, float y, float w, float h) {
        EXTRA.add(new float[] {x, y, w, h});
    }

    public static List<Drawn> drawn() { return drawn; }
    public static List<float[]> reserved() { return reserved; }
    public static long frames() { return frames; }
    public static long dropped() { return dropped; }
    /** Rectangles of every callout placed in the last flush (world labels keep clear of them). */
    public static List<float[]> lastRects() { return lastRects; }

    private static boolean hits(float x, float y, float w, float h, List<float[]> rects, float pad) {
        for (float[] r : rects) if (x < r[0] + r[2] + pad && x + w + pad > r[0] && y < r[1] + r[3] + pad && y + h + pad > r[1]) return true;
        return false;
    }

    private static List<float[]> buildReserved() {
        List<float[]> out = new ArrayList<>();
        float W = Ui.W(), H = Ui.H(), s = Ui.scale();
        for (Hud.Slot sl : Hud.current()) out.add(new float[] {sl.x() - 6, sl.y() - 6, sl.w() + 12, sl.h() + 12});
        float hw = 184 / s, hh = 56 / s;
        out.add(new float[] {W / 2 - hw / 2, H - hh, hw, hh});
        out.add(new float[] {W / 2 - 36, H / 2 - 36, 72, 72});
        out.addAll(Toasts.rects());
        out.addAll(EXTRA);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !dev.vantage.module.render.NoRender.hide(dev.vantage.module.render.NoRender.SCOREBOARD)
                && mc.level.getScoreboard().getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR) != null)
            out.add(new float[] {W - 140 / s, H / 2 - 100 / s, 140 / s, 200 / s});
        return out;
    }

    /** Candidate spots around an anchor, in slot order: right-up, left-up, right-down, left-down, above, below; first close, then farther. */
    private static float[] spot(int slot, float ax, float ay, float w, float h) {
        float g = slot < 6 ? GAP1 : GAP2;
        return switch (slot % 6) {
            case 0 -> new float[] {ax + g, ay - 0.35f * h};
            case 1 -> new float[] {ax - g - w, ay - 0.35f * h};
            case 2 -> new float[] {ax + g, ay + 8};
            case 3 -> new float[] {ax - g - w, ay + 8};
            case 4 -> new float[] {ax - w / 2, ay - g - h};
            default -> new float[] {ax - w / 2, ay + g};
        };
    }

    public static void flush(GuiGraphics g) {
        frames++;
        long now = System.currentTimeMillis();
        float dt = lastFlush == 0 ? 0.016f : Math.min(0.1f, (now - lastFlush) / 1000f);
        lastFlush = now;
        List<float[]> res = buildReserved();
        float W = Ui.W(), H = Ui.H();
        List<float[]> placed = new ArrayList<>();
        List<Drawn> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Sub> subs = new ArrayList<>(SUBS);
        subs.sort((a, b) -> a.prio != b.prio ? Float.compare(b.prio, a.prio) : a.order - b.order);
        Set<String> submitted = new HashSet<>();
        for (Sub sub : subs) submitted.add(sub.id);
        for (Edge e : EDGES) submitted.add(e.id);
        Ui.begin(g);
        for (Sub sub : subs) {
            if (!seen.add(sub.id)) continue;
            State st = STATES.get(sub.id);
            if (st == null) st = adopt(sub, submitted);
            int lastSlot = st != null && !st.gone ? st.slot : -1;
            float[] best = null;
            Form bestForm = null;
            int bestSlot = -1;
            for (Form f : sub.forms) {
                double bestCost = Double.MAX_VALUE;
                for (int slot = 0; slot < 12; slot++) {
                    float[] at = spot(slot, sub.ax, sub.ay, f.w(), f.h());
                    if (at[0] < PAD || at[1] < PAD || at[0] + f.w() > W - PAD || at[1] + f.h() > H - PAD) continue;
                    if (hits(at[0], at[1], f.w(), f.h(), placed, PAD) || hits(at[0], at[1], f.w(), f.h(), res, PAD)) continue;
                    double cost = (slot < 6 ? GAP1 : GAP2) + (slot != lastSlot ? 60 : 0) + slot * 0.01;
                    if (cost < bestCost) { bestCost = cost; best = at; bestSlot = slot; }
                }
                if (best != null) { bestForm = f; break; }
            }
            if (best == null) { dropped++; continue; }
            placed.add(new float[] {best[0], best[1], bestForm.w(), bestForm.h()});
            if (st == null || st.gone) {
                if (st == null) STATES.put(sub.id, st = new State());
                st.born = now;
                st.gone = false;
                st.x = best[0]; st.y = best[1];
            } else {
                if (st.kind != bestForm.kind()) { st.x = best[0]; st.y = best[1]; }
                st.x = Ui.glide(st.x, best[0], 14, dt);
                st.y = Ui.glide(st.y, best[1], 14, dt);
            }
            st.w = bestForm.w(); st.h = bestForm.h(); st.kind = bestForm.kind(); st.slot = bestSlot; st.d = sub.d; st.ax = sub.ax; st.ay = sub.ay; st.color = sub.color;
            float a = Ui.ease((now - st.born) / (float) FADE_IN);
            out.add(new Drawn(sub.id, bestForm.kind(), best[0], best[1], bestForm.w(), bestForm.h(), sub.ax, sub.ay, a));
            draw(g, st, a);
        }
        // arrows: on the edge where the line from the screen centre towards the thing leaves the screen
        List<Edge> edges = new ArrayList<>(EDGES);
        edges.sort((a, b) -> Float.compare(b.prio, a.prio));
        List<float[]> arrows = new ArrayList<>();
        for (Edge e : edges) {
            if (!seen.add(e.id)) continue;
            float dx = e.dx, dy = e.dy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-4f) { dx = 0; dy = 1; } else { dx /= len; dy /= len; }
            float hw = W / 2 - EDGE_IN, hh = H / 2 - EDGE_IN;
            float t = Math.min(Math.abs(dx) < 1e-4f ? Float.MAX_VALUE : hw / Math.abs(dx), Math.abs(dy) < 1e-4f ? Float.MAX_VALUE : hh / Math.abs(dy));
            float px = W / 2 + dx * t, py = H / 2 + dy * t;
            boolean side = Math.abs(dx) * hh > Math.abs(dy) * hw; // left or right edge: slide along y
            float w = Ui.width(e.text, 0.9f, false) + 30, h = ARROW_H;
            float[] at = null;
            // straight behind lands on the hotbar, so it keeps sliding along the edge in 24 steps until it is clear of it
            float reach = side ? H / 2 : W / 2;
            for (int step = 0; step <= 2 * (int) (reach / 24) && at == null; step++) {
                float off = (step + 1) / 2 * 24f * (step % 2 == 1 ? 1 : -1);
                float cx = px + (side ? 0 : off), cy = py + (side ? off : 0);
                float x = Math.max(PAD, Math.min(W - PAD - w, cx - w / 2)), y = Math.max(PAD, Math.min(H - PAD - h, cy - h / 2));
                if (hits(x, y, w, h, placed, PAD) || hits(x, y, w, h, res, PAD) || hits(x, y, w, h, arrows, 28 - 0.01f)) continue;
                at = new float[] {x, y};
            }
            if (at == null) { dropped++; continue; }
            arrows.add(new float[] {at[0], at[1], w, h});
            State st = STATES.get(e.id);
            if (st == null || st.gone) {
                if (st == null) STATES.put(e.id, st = new State());
                st.born = now; st.gone = false; st.x = at[0]; st.y = at[1];
            } else {
                st.x = Ui.glide(st.x, at[0], 14, dt);
                st.y = Ui.glide(st.y, at[1], 14, dt);
            }
            final float fdx = dx, fdy = dy;
            final String text = e.text;
            final int col = e.color;
            st.w = w; st.h = h; st.kind = ARROW; st.slot = 0; st.color = col; st.ax = px; st.ay = py;
            st.d = (gg, kind, x, y, ww, hh2) -> arrow(gg, x, y, ww, hh2, fdx, fdy, col, text);
            float a = Ui.ease((now - st.born) / (float) FADE_IN);
            out.add(new Drawn(e.id, ARROW, at[0], at[1], w, h, px, py, a));
            draw(g, st, a);
        }
        // things that went away fade out where they last were (they hold no space)
        for (Iterator<Map.Entry<String, State>> it = STATES.entrySet().iterator(); it.hasNext();) {
            var en = it.next();
            State st = en.getValue();
            if (seen.contains(en.getKey())) continue;
            if (!st.gone) { st.gone = true; st.goneAt = now; }
            float a = 1 - (now - st.goneAt) / (float) FADE_OUT;
            if (a <= 0 || now - st.goneAt > 2000) { it.remove(); continue; }
            if (st.kind != PILL && st.kind != ARROW && coveredByCard(st, placed)) { ghostsHeld++; continue; } // a card that was replaced by another card is never drawn under it: two cards of one find
            ghostDraws++;
            draw(g, st, a);
        }
        Ui.end(g);
        List<float[]> rects = new ArrayList<>(placed);
        rects.addAll(arrows);
        lastRects = rects;
        drawn = out;
        reserved = res;
        SUBS.clear();
        EDGES.clear();
        EXTRA.clear();
        order = 0;
    }

    private static boolean card(int kind) { return kind == FULL || kind == BRIEF || kind == ESP; }

    /**
     * The id of a card can change while the thing it points at stays (a base that is re-listed under a new number): the old state would fade out where it was while the new one
     * fades in beside it, the same text twice. A new card that points at about the same place takes over the state of a vanished card of its own kind instead.
     */
    private static State adopt(Sub sub, Set<String> submitted) {
        if (!card(sub.forms.get(0).kind())) return null;
        String bestKey = null;
        double bestD = ADOPT_REACH;
        for (Map.Entry<String, State> en : STATES.entrySet()) {
            State o = en.getValue();
            if (o.gone || submitted.contains(en.getKey()) || !card(o.kind)) continue;
            double d = Math.hypot(o.ax - sub.ax, o.ay - sub.ay);
            if (d < bestD) { bestD = d; bestKey = en.getKey(); }
        }
        if (bestKey == null) return null;
        State o = STATES.remove(bestKey);
        STATES.put(sub.id, o);
        adopted++;
        return o;
    }

    /** More than a third of a vanished card lies under a card that is placed now. */
    private static boolean coveredByCard(State st, List<float[]> placed) {
        float area = st.w * st.h;
        if (area <= 0) return false;
        for (float[] r : placed) {
            float ox = Math.min(st.x + st.w, r[0] + r[2]) - Math.max(st.x, r[0]), oy = Math.min(st.y + st.h, r[1] + r[3]) - Math.max(st.y, r[1]);
            if (ox > 0 && oy > 0 && ox * oy > area / 3) return true;
        }
        return false;
    }

    private static void draw(GuiGraphics g, State st, float a) {
        if (a <= 0.01f || st.d == null) return;
        Ui.alpha = a;
        if (st.kind != ARROW) {
            float x = st.x, y = st.y, w = st.w, h = st.h;
            // the leader: a hairline from the anchor to the nearest point of the callout, with a small dot where it starts
            float nx = Math.max(x, Math.min(st.ax, x + w)), ny = Math.max(y, Math.min(st.ay, y + h));
            boolean lead = st.kind != PILL || Math.hypot(nx - st.ax, ny - st.ay) > 30;
            if (lead) {
                Ui.line(g, st.ax, st.ay, nx, ny, 1, Ui.fade(Ui.BORDER, 1.6f));
                Ui.rr(g, st.ax - 2, st.ay - 2, 4, 4, 2, Ui.fade(st.color, 0.85f), 0);
            }
        }
        try {
            st.d.draw(g, st.kind, st.x, st.y, st.w, st.h);
        } finally {
            Ui.alpha = 1f;
        }
    }

    /** The arrow chip: a rotated triangle like Arrow Finder's and "99% · 70m". */
    private static void arrow(GuiGraphics g, float x, float y, float w, float h, float dx, float dy, int col, String text) {
        Ui.rr(g, x, y, w, h, 5, Ui.GLASS, Ui.BORDER);
        g.pose().pushMatrix();
        g.pose().translate(x + 12, y + h / 2);
        g.pose().rotate((float) Math.atan2(dx, -dy));
        g.pose().scale(0.8f, 0.8f);
        int c = Ui.fade(col, Ui.alpha);
        for (int i = 0; i <= 5; i++) g.fill(-i, -7 + i, i + 1, -6 + i, c);
        g.fill(-1, -2, 2, 7, c);
        g.pose().popMatrix();
        Ui.text(g, text, x + 22, y + 6, col, 0.9f, false);
    }
}
