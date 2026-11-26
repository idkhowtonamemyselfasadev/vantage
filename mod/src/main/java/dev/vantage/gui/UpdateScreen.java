package dev.vantage.gui;

import dev.vantage.core.Ui;
import dev.vantage.core.Updater;
import dev.vantage.core.Vantage;
import dev.vantage.core.util.Colors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The question on the title screen: a newer build is on GitHub, fetch it for the next launch? */
public class UpdateScreen extends Screen {
    private static final float W = 380, H = 150;
    private final Screen parent;
    private float bx, by, bw = 110, bh = 28;

    public UpdateScreen(Screen parent) {
        super(Component.literal(Vantage.NAME + " update"));
        this.parent = parent;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (parent != null) parent.render(g, -1, -1, delta);
        g.fill(0, 0, width, height, 0x90050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        Ui.begin(g);
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        float x = (Ui.W() - W) / 2, y = (Ui.H() - H) / 2;
        Ui.rr(g, x, y, W, H, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.chevron(g, x + 22, y + 22, 14, 3, Ui.ACCENT);
        Ui.serif(g, "Update", x + 46, y + 14, Ui.TEXT, 1.9f, false);
        Updater.Release r = Updater.pending();
        String line1 = r != null ? "A newer build of " + Vantage.NAME + " is on GitHub (" + Updater.state() + ")." : Updater.state();
        if (r != null && !r.verifiable()) line1 = "A newer build is on GitHub, but it has no checksum (SHA-256), so Vantage cannot check it.";
        String line2 = r == null ? "" : !r.verifiable() ? "It does not install unchecked builds, so nothing was downloaded." : "It is checked against the release's SHA-256, then runs from the next launch (your old jar is kept).";
        Ui.text(g, Ui.fit(line1, W - 40, 0.9f), x + 22, y + 52, Ui.SUB, 0.9f, false);
        if (!line2.isEmpty()) Ui.text(g, Ui.fit(line2, W - 40, 0.9f), x + 22, y + 66, Ui.FAINT, 0.9f, false);
        boolean done = (r == null || !r.verifiable()) && !Updater.busy();
        bx = x + W - 22 - bw;
        by = y + H - 22 - bh;
        if (done) {
            button(g, "OK", bx, by, fx, fy, true);
        } else {
            button(g, Updater.busy() ? "Downloading..." : "Update", bx, by, fx, fy, true);
            button(g, "Later", bx - bw - 10, by, fx, fy, false);
        }
        Ui.end(g);
    }

    private void button(GuiGraphics g, String label, float x, float y, float fx, float fy, boolean primary) {
        boolean hot = fx >= x && fx < x + bw && fy >= y && fy < y + bh;
        int bg = primary ? Colors.mix(Ui.ACCENT, 0xFFFFFFFF, hot ? 0.15f : 0) : Colors.mix(Ui.SELECT, 0xFFFFFFFF, hot ? 0.1f : 0);
        Ui.rr(g, x, y, bw, bh, 8, bg, primary ? 0 : Ui.WIN_LINE);
        Ui.center(g, label, x + bw / 2, y + (bh - 9) / 2, Ui.TEXT, 1f, true);
    }

    private boolean in(float fx, float fy, float x, float y) {
        return fx >= x && fx < x + bw && fy >= y && fy < y + bh;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        boolean done = (Updater.pending() == null || !Updater.pending().verifiable()) && !Updater.busy();
        if (in(fx, fy, bx, by)) {
            if (done) onClose();
            else if (!Updater.busy()) Updater.update();
            return true;
        }
        if (!done && in(fx, fy, bx - bw - 10, by)) { onClose(); return true; }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
