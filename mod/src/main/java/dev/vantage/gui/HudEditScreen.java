package dev.vantage.gui;

import dev.vantage.core.Config;
import dev.vantage.core.Hud;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.util.Colors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Drag any HUD piece where you want it. The pieces keep drawing underneath as they do in game; this screen only draws a frame and
 * a name over each one. Left drag moves (the piece leaves its corner stack and keeps the spot), right click sends it back to its
 * corner, Reset sends all back. Esc or Done closes.
 */
public class HudEditScreen extends Screen {
    private static final float BW = 90, BH = 26;
    private final Screen parent;
    private String dragging;
    private float grabX, grabY;

    public HudEditScreen(Screen parent) {
        super(Component.literal("HUD layout"));
        this.parent = parent;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x30050817);
    }

    private float bx() { return Ui.W() / 2 - BW - 6; }
    private float by() { return Ui.H() - 44; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        Ui.begin(g);
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        for (Hud.Slot sl : Hud.slots()) {
            boolean hot = sl.id().equals(dragging) || (dragging == null && sl.has(fx, fy));
            var el = HudElements.get(sl.id());
            boolean pinned = el != null ? el.pinned() : Config.PANELS.containsKey(sl.id());
            Ui.rr(g, sl.x() - 2, sl.y() - 2, sl.w() + 4, sl.h() + 4, 8, hot ? 0x2000E5FF & 0x20FFFFFF | 0x20000000 : 0, hot ? Ui.ACCENT : pinned ? Ui.TEAL : Ui.FAINT);
            String tag = HudElements.title(sl.id()) + (pinned ? "" : "  ·  corner");
            float tw = Ui.width(tag, 0.78f, false) + 10;
            float tx = Math.max(0, Math.min(Ui.W() - tw, sl.x())), ty = sl.y() - 14 < 0 ? sl.y() + sl.h() + 3 : sl.y() - 14;
            Ui.rr(g, tx, ty, tw, 12, 4, hot ? Ui.ACCENT : 0xE0131230, 0);
            Ui.text(g, tag, tx + 5, ty + 2, Ui.TEXT, 0.78f, false);
        }
        String hint = "Drag a panel to move it  ·  right click sends it back to its corner  ·  modules that are off don't show";
        Ui.center(g, hint, Ui.W() / 2, Ui.H() - 60, Ui.SUB, 0.86f, false);
        button(g, "Reset", bx(), by(), fx, fy, false);
        button(g, "Done", bx() + BW + 12, by(), fx, fy, true);
        Ui.end(g);
    }

    private void button(GuiGraphics g, String label, float x, float y, float fx, float fy, boolean primary) {
        boolean hot = fx >= x && fx < x + BW && fy >= y && fy < y + BH;
        Ui.rr(g, x, y, BW, BH, 8, primary ? Colors.mix(Ui.ACCENT, 0xFFFFFFFF, hot ? 0.15f : 0) : Colors.mix(Ui.SELECT, 0xFFFFFFFF, hot ? 0.1f : 0), primary ? 0 : Ui.WIN_LINE);
        Ui.center(g, label, x + BW / 2, y + (BH - 9) / 2, Ui.TEXT, 1f, true);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        if (fy >= by() && fy < by() + BH) {
            if (fx >= bx() && fx < bx() + BW) { Hud.unpinAll(); return true; }
            if (fx >= bx() + BW + 12 && fx < bx() + 2 * BW + 12) { onClose(); return true; }
        }
        var slots = Hud.slots();
        for (int i = slots.size() - 1; i >= 0; i--) {
            Hud.Slot sl = slots.get(i);
            if (!sl.has(fx, fy)) continue;
            if (e.button() == 1) { Hud.unpin(sl.id()); return true; }
            dragging = sl.id();
            grabX = fx - sl.x();
            grabY = fy - sl.y();
            Hud.move(sl.id(), sl.x(), sl.y()); // pinned from the first touch, so the stack below closes up
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging == null) return false;
        float s = Ui.scale();
        Hud.move(dragging, (float) e.x() / s - grabX, (float) e.y() / s - grabY);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragging = null;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        Config.markDirty();
        minecraft.setScreen(parent);
    }
}
