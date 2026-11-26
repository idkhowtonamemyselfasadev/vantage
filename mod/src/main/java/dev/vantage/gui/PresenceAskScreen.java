package dev.vantage.gui;

import dev.vantage.core.PresenceConsent;
import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import dev.vantage.core.util.Colors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The one-time question (beta): may Vantage tell its presence service that you run it? Yes or No is remembered; Escape or closing
 * the window leaves it unanswered (it is asked again next launch) and nothing is sent. The answer can be changed in Settings.
 */
public class PresenceAskScreen extends Screen {
    private static final float W = 440, H = 214;
    private final Screen parent;
    private final float bw = 150, bh = 28;
    private float yesX, noX, btnY;
    private boolean drawn;

    public PresenceAskScreen(Screen parent) {
        super(Component.literal(Vantage.NAME + " presence"));
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
        Ui.serif(g, "Show that you use " + Vantage.NAME + "?", x + 46, y + 14, Ui.TEXT, 1.6f, false);
        String[] lines = {
            "Other Vantage users see a small mark on your name, and you see it on theirs.",
            "For that, Vantage tells its presence service your Minecraft UUID and a hash of the",
            "server address (or the word menu on the title screen and in singleplayer), once a minute.",
            PresenceConsent.OWNER_SENTENCE,
            "Nothing else is sent, and nothing is sent until you answer.",
            "You can change this any time in Settings > Presence.",
        };
        float ty = y + 56;
        for (String l : lines) { Ui.text(g, Ui.fit(l, W - 44, 0.88f), x + 22, ty, Ui.SUB, 0.88f, false); ty += 15; }
        btnY = y + H - 22 - bh;
        noX = x + W - 22 - bw;
        yesX = noX - bw - 12;
        button(g, "Yes, show it", yesX, btnY, fx, fy, true);
        button(g, "No", noX, btnY, fx, fy, false);
        drawn = true;
        Ui.text(g, "Esc = ask me later", x + 22, btnY + 10, Ui.FAINT, 0.8f, false);
        Ui.end(g);
    }

    private void button(GuiGraphics g, String label, float x, float y, float fx, float fy, boolean primary) {
        boolean hot = fx >= x && fx < x + bw && fy >= y && fy < y + bh;
        int bg = primary ? Colors.mix(Ui.ACCENT, 0xFFFFFFFF, hot ? 0.15f : 0) : Colors.mix(Ui.SELECT, 0xFFFFFFFF, hot ? 0.1f : 0);
        Ui.rr(g, x, y, bw, bh, 8, bg, primary ? 0 : Ui.WIN_LINE);
        Ui.center(g, label, x + bw / 2, y + (bh - 9) / 2, Ui.TEXT, 1f, true);
    }

    private boolean in(float fx, float fy, float x) {
        return fx >= x && fx < x + bw && fy >= btnY && fy < btnY + bh;
    }

    /** Records the answer and goes back (also what the rig clicks). */
    public void answer(boolean yes) {
        PresenceConsent.set(yes ? PresenceConsent.Answer.YES : PresenceConsent.Answer.NO);
        onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        if (in(fx, fy, yesX)) answer(true);
        else if (in(fx, fy, noX)) answer(false);
        return true;
    }

    /** Where the buttons are, in screen pixels (for the rig): {yes x, no x, y, w, h}. */
    public float[] buttons() {
        if (!drawn) return null;
        float s = Ui.scale();
        return new float[] {(yesX + bw / 2) * s, (noX + bw / 2) * s, (btnY + bh / 2) * s};
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
