package dev.vantage.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;

import dev.vantage.core.Friends;
import dev.vantage.core.Modules;
import dev.vantage.core.Presence;
import dev.vantage.core.Toasts;
import dev.vantage.core.Ui;
import dev.vantage.module.client.ClickGuiModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Who is on Vantage here (one click adds), friend requests, your friends (online / offline, this server or another) and everyone
 * else in the tab list. Friends live on the presence service (two-way: both add = mutual, then each sees the other's online state)
 * with a local copy as fallback. Click a name to switch it, or type one and press Enter. Same window as the menu.
 */
public class FriendsScreen extends Screen {
    private static final float W = 560, H = 540, ROW = 30, GAP = 6, HEAD = 24, TOP = 106, CARD = 52, PAD = 24;
    private final Screen parent;
    private String name = "";
    private int scroll;
    private boolean wipeArm;
    /** Rig: frames drawn so far (the clickable rectangles only exist after a draw). */
    public int frames;

    /** One drawn line: a section header, a hint, or a player. */
    private record Entry(int kind, String text, String right, int rightCol, String name, UUID id, boolean friend, boolean vantage, List<Btn> btns, Runnable row) {}
    private record Btn(String label, String tag, int col, boolean primary, Runnable act) {
        Btn(String label, String tag, int col, Runnable act) { this(label, tag, col, false, act); }
    }
    private record Hit(float x, float y, float w, float h, String tag, Runnable act) {}
    private static final int HEADER = 0, HINT = 1, PLAYER = 2;
    private final List<Hit> hits = new ArrayList<>();

    public FriendsScreen(Screen parent) {
        super(Component.literal("Friends"));
        this.parent = parent;
    }

    private static Btn addBtn(String n, UUID id, boolean primary) {
        return new Btn("+ Add", "add:" + n, Ui.ACCENT, primary, () -> {
            if (id != null) Presence.addFriend(id); else Friends.toggle(n);
            Toasts.ok("Friends", n + " added");
        });
    }

    private static Btn acceptBtn(String n, UUID id) {
        return new Btn("Accept", "accept:" + n, Ui.GREEN, () -> { Presence.addFriend(id); Toasts.ok("Friends", n + " added"); });
    }

    private static Btn friendBtn(String n) {
        return new Btn("★ Friend", "remove:" + n, Ui.GREEN, () -> Toasts.ok("Friends", n + (Friends.toggle(n) ? " added" : " removed")));
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        var c = minecraft.getConnection();
        String me = minecraft.player == null ? "" : minecraft.player.getGameProfile().name();
        java.util.Set<String> shown = new java.util.HashSet<>();

        // On Vantage
        List<Btn> head = List.of(new Btn("Refresh", "refresh", Ui.SUB, Presence::refresh));
        out.add(new Entry(HEADER, "On Vantage", Presence.enabled() ? Presence.onVantage().size() + " here" : "", Ui.FAINT, null, null, false, false, head, null));
        if (!Presence.enabled()) {
            out.add(new Entry(HINT, "Presence is off: nothing is sent and nobody shows up here", null, 0, null, null, false, false,
                    List.of(new Btn("Turn on", "presenceon", Ui.ACCENT, () -> { var g = Modules.get(ClickGuiModule.class); if (g != null) g.presence.set(true); })), null));
        } else {
            List<Presence.Near> vu = Presence.onVantageNear();
            Presence.Near nearest = nearest();
            if (vu.isEmpty()) out.add(new Entry(HINT, "No other Vantage users here yet", null, 0, null, null, false, false, List.of(), null));
            for (Presence.Near nr : vu) {
                UUID id = nr.id();
                String n = nr.name();
                shown.add(n.toLowerCase(Locale.ROOT));
                boolean f = Friends.is(n), asked = !f && Presence.requests().contains(id), isNearest = nearest != null && nearest.id().equals(id);
                String dist = nr.far() ? "far" : String.format(Locale.ROOT, "%.1f m", nr.dist());
                String right = asked ? "wants to be your friend  \u00B7  " + dist : isNearest ? "Nearest  \u00B7  " + dist : dist;
                out.add(new Entry(PLAYER, n, right, asked ? Ui.YELLOW : isNearest ? Ui.ACCENT : Ui.FAINT, n, id, f, true,
                        List.of(f ? friendBtn(n) : asked ? acceptBtn(n, id) : addBtn(n, id, isNearest)), null));
            }
        }

        // Requests
        var rq = new ArrayList<UUID>();
        java.util.Set<UUID> listed = new java.util.HashSet<>(Presence.onVantage()); // those already show their request in the first section
        for (UUID id : Presence.requests()) if (Presence.nameOf(id) != null && !Friends.is(Presence.nameOf(id)) && !(Presence.enabled() && listed.contains(id))) rq.add(id);
        if (!rq.isEmpty()) {
            out.add(new Entry(HEADER, "Requests", rq.size() + " waiting", Ui.YELLOW, null, null, false, false, List.of(), null));
            for (UUID id : rq) {
                String n = Presence.nameOf(id);
                shown.add(n.toLowerCase(Locale.ROOT));
                out.add(new Entry(PLAYER, n, "wants to be your friend", Ui.YELLOW, n, id, false, Presence.is(id), List.of(
                        acceptBtn(n, id),
                        new Btn("Ignore", "ignore:" + n, Ui.FAINT, () -> Presence.ignoreRequest(id))), null));
            }
        }

        // Friends: everyone we have locally plus what the service knows
        TreeMap<String, UUID> fr = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String n : Friends.list()) fr.put(n, Presence.idOf(n));
        for (var r : Presence.remoteFriends()) { String n = Presence.nameOf(r.id()); if (n != null) fr.put(n, r.id()); }
        List<String> up = Presence.notUploaded();
        java.util.Map<UUID, Presence.Remote> rem = new java.util.HashMap<>();
        for (var r : Presence.remoteFriends()) rem.put(r.id(), r);
        List<Entry> list = new ArrayList<>();
        List<Integer> rank = new ArrayList<>();
        for (var e : fr.entrySet()) {
            String n = e.getKey();
            if (shown.contains(n.toLowerCase(Locale.ROOT))) continue;
            UUID id = e.getValue();
            var r = id == null ? null : rem.get(id);
            boolean tab = c != null && c.getPlayerInfoIgnoreCase(n) != null;
            String right; int col, rk;
            if (r != null && r.mutual() && r.online()) {
                boolean here = r.server() != null && r.server().equals(Presence.serverHash());
                right = here ? "on this server" : "on another server"; col = here ? Ui.GREEN : Ui.TEAL; rk = here ? 0 : 2;
            } else if (tab) { right = "in tab list"; col = Ui.GREEN; rk = 1; }
            else if (r != null && r.mutual()) { right = "offline"; col = Ui.FAINT; rk = 3; }
            else if (r != null) { right = "request sent"; col = Ui.YELLOW; rk = 3; }
            else if (Presence.synced() && id != null) { right = "only on this device"; col = Ui.FAINT; rk = 4; }
            else { right = ""; col = Ui.FAINT; rk = 4; }
            list.add(new Entry(PLAYER, n, right, col, n, id, true, r != null && r.mutual() && r.online() || (id != null && Presence.is(id)), List.of(friendBtn(n)), null));
            rank.add(rk);
            shown.add(n.toLowerCase(Locale.ROOT));
        }
        out.add(new Entry(HEADER, "Friends", fr.size() + (Presence.synced() ? "  ·  synced" : "  ·  this device"), Ui.FAINT, null, null, false, false,
                up.isEmpty() ? List.of() : List.of(new Btn("Upload " + up.size(), "upload", Ui.ACCENT, Presence::uploadLocal)), null));
        for (int rk = 0; rk <= 4; rk++) for (int i = 0; i < list.size(); i++) if (rank.get(i) == rk) out.add(list.get(i));
        if (fr.isEmpty()) out.add(new Entry(HINT, "No friends saved yet", null, 0, null, null, false, false, List.of(), null));

        // Others in the tab list
        if (c != null) {
            TreeMap<String, UUID> others = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (var p : c.getOnlinePlayers()) {
                String n = p.getProfile().name();
                if (n.matches("[A-Za-z0-9_.]{3,17}") && !shown.contains(n.toLowerCase(Locale.ROOT)) && !n.equals(me)) others.put(n, p.getProfile().id());
            }
            if (!others.isEmpty()) {
                out.add(new Entry(HEADER, "Others in tab list", others.size() + " online", Ui.FAINT, null, null, false, false, List.of(), null));
                for (var e : others.entrySet()) out.add(new Entry(PLAYER, e.getKey(), null, 0, e.getKey(), e.getValue(), false, false, List.of(addBtn(e.getKey(), e.getValue(), false)), null));
            }
        }
        return out;
    }

    /** The closest Vantage user we can see who is not a friend yet (live: entity positions, no network). */
    private static Presence.Near nearest() {
        for (Presence.Near n : Presence.onVantageNear()) if (!n.far() && !Friends.is(n.name())) return n;
        return null;
    }

    /** Rig: the right-hand text of a player's row ("Nearest  ·  3.5 m", "far"), or null. */
    public String rightOf(String player) {
        for (Entry e : entries()) if (e.kind == PLAYER && player.equals(e.name)) return e.right;
        return null;
    }

    /** Rig: who the top card offers, or null. */
    public String nearestName() {
        var n = Presence.enabled() ? nearest() : null;
        return n == null ? null : n.name();
    }

    private float x0() { return (Ui.W() - W) / 2; }
    private float y0() { return (Ui.H() - H) / 2; }
    private static float hOf(Entry e) { return e.kind == HEADER ? HEAD : ROW + GAP; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x58050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        hits.clear();
        frames++;
        Ui.begin(g);
        float x = x0(), y = y0();
        Ui.rr(g, x, y, W, H, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.serif(g, "Friends", x + PAD, y + 22, Ui.TEXT, 2f, false);
        Ui.text(g, "never flagged, a star in tab and on name tags, their own Entity ESP colour", x + PAD, y + 52, Ui.FAINT, 0.84f, false);
        Ui.right(g, Friends.list().size() + " friends", x + W - PAD, y + 28, Ui.FAINT, 0.84f, false);

        boolean typing = !name.isEmpty();
        Ui.rr(g, x + PAD, y + 68, W - 2 * PAD, 28, 14, 0x40101522, typing ? Ui.ACCENT : 0xFF262A52);
        boolean caret = typing && System.currentTimeMillis() / 500 % 2 == 0;
        Ui.text(g, typing ? name + (caret ? "_" : "") : "Type a name, Enter adds or removes it", x + PAD + 14, y + 77, typing ? Ui.TEXT : Ui.FAINT, 0.94f, false);

        // the top card: whoever stands next to you, one big click
        var near = Presence.enabled() ? nearest() : null;
        float cy = y + TOP - 4;
        Ui.rr(g, x + PAD, cy, W - 2 * PAD, CARD - 8, 10, 0x40101522, near != null ? Ui.ACCENT : Ui.CARD_LINE);
        Ui.text(g, "NEAREST ON VANTAGE", x + PAD + 16, cy + 8, Ui.FAINT, 0.72f, true);
        if (near != null) {
            Ui.text(g, near.name(), x + PAD + 16, cy + 21, Ui.TEXT, 1.15f, true);
            Ui.text(g, String.format(Locale.ROOT, "%.1f m away", near.dist()), x + PAD + 22 + Ui.width(near.name(), 1.15f, true), cy + 23, Ui.SUB, 0.86f, false);
            UUID nid = near.id(); String nn = near.name();
            bigButton(g, "Add friend", "addnearest", x + W - PAD - 12, cy + 7, 28, fx, fy, () -> { Presence.addFriend(nid); Toasts.ok("Friends", nn + " added"); });
        } else {
            Ui.text(g, Presence.enabled() ? "Nobody close: a friend on Vantage just has to stand next to you" : "Presence is off", x + PAD + 16, cy + 23, Ui.FAINT, 0.88f, false);
        }
        List<Entry> es = entries();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, es.size() - 1)));
        float ry = y + TOP + CARD, bottom = y + H - 34;
        int last = scroll;
        for (int i = scroll; i < es.size(); i++) {
            Entry e = es.get(i);
            if (ry + hOf(e) - (e.kind == HEADER ? 0 : GAP) > bottom) break;
            last = i + 1;
            float rx = x + PAD, rw = W - 2 * PAD;
            if (e.kind == HEADER) {
                Ui.text(g, e.text.toUpperCase(Locale.ROOT), rx + 2, ry + 8, Ui.SUB, 0.74f, true);
                float bx = x + W - PAD;
                for (int b = e.btns.size() - 1; b >= 0; b--) bx = button(g, e.btns.get(b), bx, ry + 1, 20, fx, fy) - 6;
                if (e.right != null && !e.right.isEmpty()) Ui.right(g, e.right, bx - 4, ry + 8, e.rightCol, 0.78f, false);
            } else if (e.kind == HINT) {
                Ui.rr(g, rx, ry, rw, ROW, 7, 0x40101522, Ui.CARD_LINE);
                Ui.text(g, e.text, rx + 14, ry + 10, Ui.FAINT, 0.9f, false);
                float bx = x + W - PAD - 8;
                for (int b = e.btns.size() - 1; b >= 0; b--) bx = button(g, e.btns.get(b), bx, ry + 5, 20, fx, fy) - 6;
            } else {
                boolean hov = fx >= rx && fx < rx + rw && fy >= ry && fy < ry + ROW;
                Ui.rr(g, rx, ry, rw, ROW, 7, hov ? 0xF0191838 : Ui.CARD, e.friend ? 0xFF3B3786 : Ui.CARD_LINE);
                if (e.friend) Ui.rr(g, rx + 1.5f, ry + 8, 3, ROW - 16, 1.5f, Ui.GREEN, 0);
                float nx = rx + 16;
                if (e.vantage) { Presence.badge(g, nx, ry + 9, 11); nx += 16; }
                Ui.text(g, e.text, nx, ry + 10, e.friend ? Ui.TEXT : Ui.SUB, 1f, e.friend);
                float bx = x + W - PAD - 8;
                for (int b = e.btns.size() - 1; b >= 0; b--) bx = button(g, e.btns.get(b), bx, ry + 5, 20, fx, fy) - 6;
                if (e.right != null && !e.right.isEmpty()) Ui.right(g, e.right, bx - 6, ry + 11, e.rightCol, 0.82f, false);
                Btn first = e.btns.isEmpty() ? null : e.btns.get(0);
                if (first != null && e.btns.size() == 1) hits.add(0, new Hit(rx, ry, rw, ROW, "row:" + e.name, first.act)); // the whole row does the same as its one button
            }
            ry += hOf(e);
        }
        if (es.size() > 0 && (scroll > 0 || last < es.size())) Ui.right(g, (scroll + 1) + "-" + last + " of " + es.size() + "  ·  scroll", x + W - PAD, y + H - 20, Ui.FAINT, 0.8f, false);
        Ui.text(g, "Esc = back", x + PAD, y + H - 20, Ui.FAINT, 0.8f, false);
        if (Presence.synced()) {
            float wx = x + PAD + 80;
            String label = wipeArm ? "Click again: delete" : "Delete my data on the service";
            float bw = Ui.width(label, 0.78f, false) + 16;
            boolean hov = fx >= wx && fx < wx + bw && fy >= y + H - 26 && fy < y + H - 8;
            Ui.rr(g, wx, y + H - 26, bw, 18, 9, hov ? 0xF0191838 : 0, wipeArm ? Ui.RED : Ui.CARD_LINE);
            Ui.text(g, label, wx + 8, y + H - 21, wipeArm ? Ui.RED : Ui.FAINT, 0.78f, false);
            hits.add(new Hit(wx, y + H - 26, bw, 18, "wipe", () -> {
                if (!wipeArm) { wipeArm = true; return; }
                wipeArm = false;
                Presence.wipe();
                Toasts.ok("Friends", "Deleted what the service held for you");
            }));
        }
        Ui.end(g);
    }

    private void bigButton(GuiGraphics g, String label, String tag, float right, float y, float h, float fx, float fy, Runnable act) {
        float bw = Ui.width(label, 1f, true) + 36, bx = right - bw;
        boolean hov = fx >= bx && fx < bx + bw && fy >= y && fy < y + h;
        Ui.rr(g, bx, y, bw, h, h / 2, hov ? Ui.ACCENT : Ui.fade(Ui.ACCENT, 0.85f), 0);
        Ui.text(g, label, bx + 18, y + 9, 0xFF0B0A20, 1f, true);
        hits.add(new Hit(bx, y, bw, h, tag, act));
    }

    /** A pill button whose right edge is at `right`; returns its left edge. */
    private float button(GuiGraphics g, Btn b, float right, float y, float h, float fx, float fy) {
        float bw = Ui.width(b.label, 0.82f, false) + 20, bx = right - bw;
        boolean hov = fx >= bx && fx < bx + bw && fy >= y && fy < y + h;
        if (b.primary) {
            Ui.rr(g, bx, y, bw, h, h / 2, hov ? b.col : Ui.fade(b.col, 0.85f), 0);
            Ui.text(g, b.label, bx + 10, y + 5, 0xFF0B0A20, 0.82f, true);
        } else {
            Ui.rr(g, bx, y, bw, h, h / 2, hov ? Ui.fade(b.col, 0.28f) : Ui.fade(b.col, 0.12f), Ui.fade(b.col, hov ? 0.9f : 0.5f));
            Ui.text(g, b.label, bx + 10, y + 5, b.col, 0.82f, false);
        }
        hits.add(new Hit(bx, y, bw, h, b.tag, b.act));
        return bx;
    }

    /** Rig: where (window pixels) the clickable with this tag was drawn last frame, or null. Tags: add:Name, remove:Name, accept:Name, ignore:Name, refresh, presenceon, upload, wipe. */
    public double[] where(String tag) {
        float s = Ui.scale();
        for (Hit h : hits) if (h.tag.equals(tag)) return new double[] {(h.x + h.w / 2) * s, (h.y + h.h / 2) * s};
        return null;
    }

    /** Rig: is this hint or header text on the screen right now? */
    public boolean shows(String text) {
        for (Entry e : entries()) if (text.equals(e.text)) return true;
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (dbl) return true; // a double click arrives as two clicks: only the first acts
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        if (e.button() != 0) return super.mouseClicked(e, dbl);
        for (int i = hits.size() - 1; i >= 0; i--) {                // buttons were added after their row, so they win
            Hit h = hits.get(i);
            if (fx >= h.x && fx < h.x + h.w && fy >= h.y && fy < h.y + h.h) {
                if (!h.tag.equals("wipe")) wipeArm = false;
                h.act.run();
                return true;
            }
        }
        wipeArm = false;
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int) Math.signum(v));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) && !name.isBlank()) {
            if (name.trim().matches("[A-Za-z0-9_.]{3,17}")) Toasts.ok("Friends", name.trim() + (Friends.toggle(name.trim()) ? " added" : " removed"));
            else Toasts.warn("Friends", "That's not a player name");
            name = "";
            return true;
        }
        if (k == GLFW.GLFW_KEY_BACKSPACE && !name.isEmpty()) { name = name.substring(0, name.length() - 1); return true; }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (e.isAllowedChatCharacter() && name.length() < 17) name += e.codepointAsString();
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
