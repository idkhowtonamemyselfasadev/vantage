package dev.vantage.gui;

import java.util.List;

import dev.vantage.core.Ui;
import dev.vantage.core.util.Fmt;
import dev.vantage.module.finder.Waypoints;
import dev.vantage.module.finder.Waypoints.Waypoint;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Waypoint list: add at your position, rename by typing, recolour, hide, delete. Same window as the menu. */
public class WaypointScreen extends Screen {
    private static final float W = 640, H = 440, ROW = 32, GAP = 6, TOP = 114, PAD = 24, BTN = 96;
    private final Screen parent;
    private String name = "";
    private int scroll;
    private Waypoint renaming;

    public WaypointScreen(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
    }

    private Waypoints wm() { return Waypoints.get(); }
    private float x0() { return (Ui.W() - W) / 2; }
    private float y0() { return (Ui.H() - H) / 2; }
    private int visible() { return (int) ((H - TOP - 30) / (ROW + GAP)); }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x58050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        Ui.begin(g);
        float x = x0(), y = y0();
        Ui.rr(g, x, y, W, H, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.serif(g, "Waypoints", x + PAD, y + 22, Ui.TEXT, 2f, false);
        Ui.text(g, "Beams and distance labels in the world; saved per server", x + PAD, y + 52, Ui.FAINT, 0.84f, false);
        Ui.right(g, dev.vantage.core.Streamer.serverLabel(dev.vantage.core.Config.serverKey()), x + W - PAD, y + 28, Ui.FAINT, 0.84f, false);

        // name field and the add / rename button
        boolean typing = !name.isEmpty();
        float fw = W - 2 * PAD - BTN - 8;
        Ui.rr(g, x + PAD, y + 72, fw, 28, 14, 0x40101522, typing || renaming != null ? Ui.ACCENT : 0xFF262A52);
        boolean caret = typing && System.currentTimeMillis() / 500 % 2 == 0;
        String hint = renaming != null ? "New name for " + renaming.name : "Name for a waypoint at your position";
        Ui.text(g, typing ? name + (caret ? "_" : "") : hint, x + PAD + 14, y + 81, typing ? Ui.TEXT : Ui.FAINT, 0.94f, false);
        float bx = x + W - PAD - BTN;
        boolean addHover = fx >= bx && fx < bx + BTN && fy >= y + 72 && fy < y + 100;
        Ui.rr(g, bx, y + 72, BTN, 28, 14, addHover ? 0xFF3B3786 : Ui.SELECT, 0xFF3B3786);
        Ui.center(g, renaming != null ? "Rename" : "Add here", bx + BTN / 2, y + 81, Ui.TEXT, 0.94f, true);

        List<Waypoint> list = wm().list();
        int vis = visible();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - vis)));
        float ry = y + TOP;
        if (list.isEmpty()) Ui.text(g, "No waypoints on this server yet", x + PAD, ry + 6, Ui.FAINT, 0.9f, false);
        for (int i = scroll; i < list.size() && i < scroll + vis; i++) {
            Waypoint w = list.get(i);
            boolean hov = fx >= x + PAD && fx < x + W - PAD && fy >= ry && fy < ry + ROW;
            Ui.rr(g, x + PAD, ry, W - 2 * PAD, ROW, 7, hov ? 0xF0191838 : Ui.CARD, renaming == w ? 0xFF3B3786 : Ui.CARD_LINE);
            Ui.rr(g, x + PAD + 10, ry + 9, 14, 14, 4, 0xFF000000 | w.color, 0x40FFFFFF); // colour swatch, click = next colour
            Ui.text(g, Ui.fit(w.name, 170, 1f), x + PAD + 32, ry + 11, w.visible ? Ui.TEXT : Ui.FAINT, 1f, false);
            String c = dev.vantage.core.Streamer.coords(w.x + " " + w.y + " " + w.z) + "  " + w.dim.replace("minecraft:", "").replace("the_", "");
            Ui.text(g, c, x + PAD + 212, ry + 11, Ui.SUB, 0.86f, false);
            String dist = "";
            if (minecraft.player != null) {
                var p = wm().shownPos(w);
                if (p != null) dist = Fmt.distance(minecraft.player.position().distanceTo(p));
            }
            Ui.text(g, dist, x + PAD + 400, ry + 11, Ui.SUB, 0.86f, false);
            Ui.right(g, w.visible ? "hide" : "show", x + W - PAD - 54, ry + 11, Ui.SUB, 0.86f, false);
            Ui.right(g, "delete", x + W - PAD - 12, ry + 11, Ui.RED, 0.86f, false);
            ry += ROW + GAP;
        }
        if (list.size() > vis) Ui.right(g, (scroll + 1) + "-" + Math.min(list.size(), scroll + vis) + " of " + list.size() + "  ·  scroll", x + W - PAD, y + H - 20, Ui.FAINT, 0.8f, false);
        Ui.text(g, "Click a name to rename  ·  colour square = next colour  ·  Esc = back", x + PAD, y + H - 20, Ui.FAINT, 0.8f, false);
        Ui.end(g);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        float x = x0(), y = y0(), bx = x + W - PAD - BTN;
        if (fx >= bx && fx < bx + BTN && fy >= y + 72 && fy < y + 100) { submit(); return true; }
        List<Waypoint> list = wm().list();
        if (fx >= x + PAD && fx < x + W - PAD && fy >= y + TOP) {
            int i = (int) Math.floor((fy - y - TOP) / (ROW + GAP)) + scroll;
            float inRow = (fy - y - TOP) % (ROW + GAP);
            if (i >= 0 && i < list.size() && i < scroll + visible() && inRow < ROW) {
                Waypoint w = list.get(i);
                float right = x + W - PAD;
                if (fx >= right - 60) { wm().remove(w); if (renaming == w) renaming = null; }
                else if (fx >= right - 110) { w.visible = !w.visible; wm().save(); }
                else if (fx < x + PAD + 30) {
                    int idx = 0;
                    for (int k = 0; k < Waypoints.PALETTE.length; k++) if (Waypoints.PALETTE[k] == w.color) idx = k + 1;
                    w.color = Waypoints.PALETTE[idx % Waypoints.PALETTE.length];
                    wm().save();
                } else if (fx < x + PAD + 210) { renaming = w; name = w.name; }
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    private void submit() {
        if (renaming != null) {
            if (!name.isBlank()) renaming.name = name.trim();
            renaming = null;
            name = "";
            wm().save();
            return;
        }
        if (minecraft.player == null) return;
        var p = minecraft.player.blockPosition();
        String n = name.isBlank() ? "Point " + (wm().list().size() + 1) : name.trim();
        wm().add(n, p.getX(), p.getY(), p.getZ(), Waypoints.PALETTE[wm().list().size() % Waypoints.PALETTE.length]);
        name = "";
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, Math.min(Math.max(0, wm().list().size() - visible()), scroll - (int) Math.signum(v)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            if (renaming != null) { renaming = null; name = ""; return true; }
            onClose();
            return true;
        }
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        if (k == GLFW.GLFW_KEY_BACKSPACE && !name.isEmpty()) { name = name.substring(0, name.length() - 1); return true; }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (e.isAllowedChatCharacter() && name.length() < 32) name += e.codepointAsString();
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
