package dev.vantage.gui;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.FindLog;
import dev.vantage.core.Toasts;
import dev.vantage.core.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Beta: the session list of finder marks, newest first. A click on a row copies its coordinates. Same window as Friends. */
public class FindLogScreen extends Screen {
    private static final float W = 560, H = 480, ROW = 30, GAP = 6, PAD = 24, TOP = 78;
    private final Screen parent;
    private int scroll;
    /** Rig: frames drawn so far, rows drawn in the last frame, and the rectangle of each row (x, y, w, h). */
    public int frames, rowsDrawn;
    public final List<float[]> rowRects = new ArrayList<>();

    public FindLogScreen(Screen parent) {
        super(Component.literal("Find Log"));
        this.parent = parent;
    }

    private float x0() { return (Ui.W() - W) / 2; }
    private float y0() { return (Ui.H() - H) / 2; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x58050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        frames++;
        rowRects.clear();
        Ui.begin(g);
        float x = x0(), y = y0();
        Ui.rr(g, x, y, W, H, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.serif(g, "Find Log", x + PAD, y + 22, Ui.TEXT, 2f, false);
        Ui.text(g, "this session only: nothing is saved or sent. Click a row to copy its coordinates", x + PAD, y + 52, Ui.FAINT, 0.84f, false);
        List<FindLog.Entry> all = FindLog.list();
        Ui.right(g, all.size() + (all.size() == 1 ? " find" : " finds"), x + W - PAD, y + 28, Ui.FAINT, 0.84f, false);
        float ry = y + TOP, bottom = y + H - 34;
        int rows = Math.max(1, (int) ((bottom - ry) / (ROW + GAP)));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - rows)));
        if (all.isEmpty()) {
            Ui.text(g, "Nothing marked yet. Base Finder and Stash Finder add a line here when they mark something new.", x + PAD, ry + 8, Ui.FAINT, 0.9f, false);
        }
        int drawn = 0;
        for (int i = scroll; i < all.size() && drawn < rows; i++) {
            FindLog.Entry e = all.get(all.size() - 1 - i);
            float rx = x + PAD, rw = W - 2 * PAD;
            boolean hov = fx >= rx && fx < rx + rw && fy >= ry && fy < ry + ROW;
            Ui.rr(g, rx, ry, rw, ROW, 7, hov ? 0xF0191838 : Ui.CARD, Ui.CARD_LINE);
            Ui.text(g, e.kind(), rx + 14, ry + 10, Ui.TEXT, 1f, true);
            float cx = rx + 110;
            int d = e.distance();
            Ui.text(g, d < 0 ? "other dimension" : d + " m", cx, ry + 11, Ui.SUB, 0.86f, false);
            Ui.text(g, e.coords(), cx + 110, ry + 11, Ui.SUB, 0.86f, false);
            String dt = e.detail().length() > 16 ? e.detail().substring(0, 15) + ".." : e.detail();
            Ui.text(g, dt, cx + 250, ry + 11, Ui.FAINT, 0.82f, false);
            Ui.right(g, FindLog.ago(e.time()) + " ago", rx + rw - 14, ry + 11, Ui.FAINT, 0.82f, false);
            rowRects.add(new float[] {rx, ry, rw, ROW});
            ry += ROW + GAP;
            drawn++;
        }
        rowsDrawn = drawn;
        if (all.size() > rows) Ui.right(g, (scroll + 1) + "-" + (scroll + drawn) + " of " + all.size() + "  ·  scroll", x + W - PAD, y + H - 20, Ui.FAINT, 0.8f, false);
        Ui.text(g, "Esc = back", x + PAD, y + H - 20, Ui.FAINT, 0.8f, false);
        Ui.end(g);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (dbl || e.button() != 0) return super.mouseClicked(e, dbl);
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        List<FindLog.Entry> all = FindLog.list();
        for (int i = 0; i < rowRects.size(); i++) {
            float[] r = rowRects.get(i);
            if (fx >= r[0] && fx < r[0] + r[2] && fy >= r[1] && fy < r[1] + r[3]) {
                FindLog.Entry en = all.get(all.size() - 1 - (scroll + i));
                String c = (int) Math.floor(en.x()) + " " + (int) Math.floor(en.y()) + " " + (int) Math.floor(en.z());
                minecraft.keyboardHandler.setClipboard(c);
                Toasts.ok("Find Log", "Copied " + c);
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int) Math.signum(v));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
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
