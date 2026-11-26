package dev.vantage.gui;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Keys;
import dev.vantage.core.Modules;
import dev.vantage.core.Presence;
import dev.vantage.core.Ui;
import dev.vantage.core.Welcome;
import dev.vantage.module.client.ClickGuiModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * First-run welcome (beta). The real menu key, three modules to begin with (a click switches one), a plain warning that clients like
 * this can get you banned, and what the client sends with where to switch it off. Same window as Friends and Find Log.
 */
public class WelcomeScreen extends Screen {
    private static final float W = 600, H = 560, PAD = 28;
    private final Screen parent;
    /** Rig: frames drawn, every text line of the last frame, and clickable rectangles by tag. */
    public int frames;
    public final List<String> lines = new ArrayList<>();
    private final List<Object[]> hits = new ArrayList<>(); // {x, y, w, h, tag, Runnable}

    public WelcomeScreen(Screen parent) {
        super(Component.literal("Welcome"));
        this.parent = parent;
        Welcome.markShown();
    }

    /** The menu key as the player has set it (ClickGUI's bind), not a hard-coded one. */
    public static String menuKey() {
        var gui = Modules.get(ClickGuiModule.class);
        return gui == null ? "?" : Keys.name(gui.bind);
    }

    private float x0() { return (Ui.W() - W) / 2; }
    private float y0() { return (Ui.H() - H) / 2; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x90050817);
    }

    private void text(GuiGraphics g, String s, float x, float y, int col, float sc, boolean bold) {
        lines.add(s);
        Ui.text(g, s, x, y, col, sc, bold);
    }

    /** A row with a switch on the right: clicking anywhere on it flips the thing. */
    private void row(GuiGraphics g, float x, float y, float w, String name, String sub, boolean on, String tag, Runnable flip, float fx, float fy) {
        boolean hov = fx >= x && fx < x + w && fy >= y && fy < y + 36;
        Ui.rr(g, x, y, w, 36, 8, hov ? 0xF0191838 : Ui.CARD, on ? Ui.tone(0xFF3B3786) : Ui.CARD_LINE);
        if (on) Ui.rr(g, x + 1.5f, y + 9, 3, 18, 1.5f, Ui.ACCENT, 0);
        text(g, name, x + 16, y + 8, Ui.TEXT, 1f, true);
        text(g, Ui.fit(sub, w - 110, 0.8f), x + 16, y + 21, Ui.SUB, 0.8f, false);
        Ui.toggle(g, x + w - 42, y + 10.5f, on);
        hits.add(new Object[] {x, y, w, 36f, tag, flip});
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        frames++;
        lines.clear();
        hits.clear();
        Ui.begin(g);
        float x = x0(), y = y0(), cw = W - 2 * PAD, cx = x + PAD;
        Ui.rr(g, x, y, W, H, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.serif(g, "Welcome to Vantage", cx, y + 22, Ui.TEXT, 2f, false);
        lines.add("Welcome to Vantage");
        float tw = Ui.width("BETA", 0.66f, false) + 10;
        Ui.rr(g, x + W - PAD - tw, y + 28, tw, 12, 6, Ui.fade(Ui.ACCENT, 0.18f), Ui.fade(Ui.ACCENT, 0.7f));
        Ui.text(g, "BETA", x + W - PAD - tw + 5, y + 31, Ui.ACCENT, 0.66f, false);

        float ry = y + 66;
        Ui.rr(g, cx, ry, cw, 40, 8, 0x40101522, Ui.CARD_LINE);
        text(g, "Menu key", cx + 16, ry + 8, Ui.SUB, 0.8f, false);
        String key = menuKey();
        text(g, "Press  " + key + "  to open the menu. You can change it in Settings.", cx + 16, ry + 22, Ui.TEXT, 0.95f, false);
        ry += 54;

        text(g, "THREE MODULES TO START WITH", cx + 2, ry, Ui.SUB, 0.74f, true);
        ry += 16;
        String[][] start = {{"Base Finder", "Scores every chunk you load and marks the likely bases."},
                {"Base ESP", "Boxes storage and redstone clusters below Y 0, with a card on how sure it is."},
                {"Sound Detector", "Finds farms, storage and hidden players from the sounds the server sends you."}};
        for (String[] m : start) {
            var mod = Modules.byName(m[0]);
            if (mod != null) row(g, cx, ry, cw, m[0], m[1], mod.on(), "mod:" + m[0], mod::toggle, fx, fy);
            ry += 42;
        }
        ry += 4;

        Ui.rr(g, cx, ry, cw, 50, 8, 0x40101522, Ui.YELLOW & 0x60FFFFFF | 0x60000000);
        text(g, "Careful", cx + 16, ry + 8, Ui.YELLOW, 0.8f, true);
        text(g, Ui.fit("Clients like this can get you banned on servers that forbid them. Read a server's rules first.", cw - 32, 0.9f), cx + 16, ry + 24, Ui.TEXT, 0.9f, false);
        ry += 64;

        text(g, "WHAT THIS CLIENT SENDS", cx + 2, ry, Ui.SUB, 0.74f, true);
        ry += 16;
        var gui = Modules.get(ClickGuiModule.class);
        boolean up = gui != null && gui.updates.on();
        row(g, cx, ry, cw, "Update check", up ? "On: asks github.com once at start whether a newer build exists. Nothing else." : "Off: it never looks for updates.", up, "updates", () -> { if (gui != null) gui.updates.set(!gui.updates.on()); }, fx, fy);
        ry += 42;
        boolean pr = Presence.enabled();
        row(g, cx, ry, cw, "Presence", pr ? "On: tells the presence service your UUID and a hash of the server address, once a minute." : "Off: nothing is sent, nobody sees a mark on you.", pr, "presence", () -> { if (gui != null) gui.presence.set(!gui.presence.on()); }, fx, fy);
        ry += 42;
        text(g, "Switch both here or in the menu under Settings. You can open this screen again from Settings.", cx + 2, ry + 2, Ui.FAINT, 0.8f, false);

        float bw = 110, bx = x + W - PAD - bw, by = y + H - 46;
        boolean hov = fx >= bx && fx < bx + bw && fy >= by && fy < by + 28;
        Ui.rr(g, bx, by, bw, 28, 14, hov ? Ui.fade(Ui.ACCENT, 0.35f) : Ui.fade(Ui.ACCENT, 0.2f), Ui.ACCENT);
        Ui.center(g, "Got it", bx + bw / 2, by + 9, Ui.TEXT, 0.95f, true);
        lines.add("Got it");
        hits.add(new Object[] {bx, by, bw, 28f, "close", (Runnable) this::onClose});
        Ui.end(g);
    }

    /** Rig: the rectangle of a clickable thing ("close", "mod:Base Finder", "updates", "presence"), or null. */
    public float[] spot(String tag) {
        for (Object[] h : hits) if (h[4].equals(tag)) return new float[] {(float) h[0], (float) h[1], (float) h[2], (float) h[3]};
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (dbl || e.button() != 0) return super.mouseClicked(e, dbl);
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        for (Object[] h : hits) {
            if (fx >= (float) h[0] && fx < (float) h[0] + (float) h[2] && fy >= (float) h[1] && fy < (float) h[1] + (float) h[3]) {
                ((Runnable) h[5]).run();
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == GLFW.GLFW_KEY_ESCAPE || k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) { onClose(); return true; }
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
