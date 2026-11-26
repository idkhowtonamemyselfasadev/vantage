package dev.vantage.gui;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.PresenceConsent;
import dev.vantage.core.Terms;
import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import dev.vantage.core.util.Colors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The terms gate: shown on the title screen at the first chance each launch while the terms are not confirmed. Only the two buttons
 * leave it (Escape and clicks outside do nothing). Confirm is remembered; Reject keeps Vantage off for this launch (see {@link Terms}).
 */
public class TermsScreen extends Screen {
    public static final float W = 520, PAD = 24;
    private static final float SIZE = 0.9f, LEAD = 14, BW = 150, BH = 28;

    /** Since terms version 3: says plainly that Auto update is on once you confirm. */
    public static final String UPDATE_SENTENCE = "Vantage updates itself: at game start it checks GitHub and installs a newer release whose checksum matches. You can switch this off in Settings.";

    /** The terms text, one paragraph per entry. Wrapped to the box at draw time, so nothing is cut off. */
    public static final String[] PARAGRAPHS = {
        "Vantage is a free client mod for Minecraft. It comes with no warranty, and servers may ban you for using it.",
        "Once you confirm, Vantage tells its presence service your Minecraft name, your UUID and a hash of the server address (or the word menu on the title screen and in singleplayer), once a minute. It also asks github.com once at start whether a newer build exists. If you add or remove a friend in the Friends screen, that friend's UUID is sent to the presence service too. Nothing else is sent.",
        PresenceConsent.OWNER_SENTENCE,
        UPDATE_SENTENCE,
        "The full Terms and the Privacy notice are on the Vantage website.",
        "Under 16: ask a parent first.",
        "Reject keeps Vantage switched off for this launch and you are asked again at the next start. You can change the Presence switch later in Settings.",
    };

    private final Screen parent;
    /** Rig: every wrapped line of the last frame, the box and the buttons (in screen pixels), frames drawn. */
    public final List<String> lines = new ArrayList<>();
    public float[] box;
    /** Rig: the text size the last frame used (it shrinks on a small window). */
    public float textSize;
    private float confirmX, rejectX, btnY;
    public int frames;
    private boolean drawn;

    public TermsScreen(Screen parent) {
        super(Component.literal(Vantage.NAME + " terms"));
        this.parent = parent;
    }

    private static List<String> wrap(String text, float width, float size) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = cur.length() == 0 ? word : cur + " " + word;
            if (cur.length() > 0 && Ui.width(next, size, false) > width) { out.add(cur.toString()); cur = new StringBuilder(word); }
            else { cur.setLength(0); cur.append(next); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (parent != null) parent.render(g, -1, -1, delta);
        g.fill(0, 0, width, height, 0xB0050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        frames++;
        Ui.begin(g);
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        // the text shrinks when the window is small, so the box always fits on screen
        float size = SIZE;
        float h;
        List<String> wrapped = new ArrayList<>();
        List<Integer> paraEnds = new ArrayList<>();
        while (true) {
            wrapped.clear();
            paraEnds.clear();
            for (String p : PARAGRAPHS) { wrapped.addAll(wrap(p, W - 2 * PAD, size)); paraEnds.add(wrapped.size()); }
            h = 56 + wrapped.size() * (LEAD * size / SIZE) + paraEnds.size() * 6 + 20 + BH + PAD;
            if (h <= Ui.H() - 16 || size < 0.5f) break;
            size -= 0.05f;
        }
        textSize = size;
        float lead = LEAD * size / SIZE;
        float x = (Ui.W() - W) / 2, y = (Ui.H() - h) / 2;
        Ui.rr(g, x, y, W, h, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.chevron(g, x + PAD, y + 26, 14, 3, Ui.ACCENT);
        Ui.serif(g, Vantage.NAME + " terms", x + PAD + 24, y + 16, Ui.TEXT, 1.6f, false);
        lines.clear();
        float ty = y + 56;
        int line = 0;
        for (int pi = 0; pi < paraEnds.size(); pi++) {
            for (; line < paraEnds.get(pi); line++) {
                String l = wrapped.get(line);
                Ui.text(g, l, x + PAD, ty, pi == 2 || pi == 3 ? Ui.TEXT : Ui.SUB, size, false);
                lines.add(l);
                ty += lead;
            }
            ty += 6;
        }
        btnY = y + h - PAD - BH;
        rejectX = x + W - PAD - BW;
        confirmX = rejectX - BW - 12;
        button(g, "Confirm", confirmX, btnY, fx, fy, true);
        button(g, "Reject", rejectX, btnY, fx, fy, false);
        box = new float[] {x * s, y * s, (x + W) * s, (y + h) * s};
        drawn = true;
        Ui.end(g);
    }

    private void button(GuiGraphics g, String label, float x, float y, float fx, float fy, boolean primary) {
        boolean hot = fx >= x && fx < x + BW && fy >= y && fy < y + BH;
        int bg = primary ? Colors.mix(Ui.ACCENT, 0xFFFFFFFF, hot ? 0.15f : 0) : Colors.mix(Ui.SELECT, 0xFFFFFFFF, hot ? 0.1f : 0);
        Ui.rr(g, x, y, BW, BH, 8, bg, primary ? 0 : Ui.WIN_LINE);
        Ui.center(g, label, x + BW / 2, y + (BH - 9) / 2, Ui.TEXT, 1f, true);
    }

    private boolean in(float fx, float fy, float x) {
        return fx >= x && fx < x + BW && fy >= btnY && fy < btnY + BH;
    }

    /** What Confirm does (also what the rig clicks through the button). */
    public void confirm() {
        Terms.confirm();
        leave();
    }

    /** What Reject does. */
    public void reject() {
        Terms.reject();
        leave();
    }

    private void leave() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        if (in(fx, fy, confirmX)) confirm();
        else if (in(fx, fy, rejectX)) reject();
        return true; // a click anywhere else does nothing
    }

    /** Button centres in screen pixels (for the rig): {confirm x, reject x, y}. Null until drawn. */
    public float[] buttons() {
        if (!drawn) return null;
        float s = Ui.scale();
        return new float[] {(confirmX + BW / 2) * s, (rejectX + BW / 2) * s, (btnY + BH / 2) * s};
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        return true; // Escape and everything else: only the buttons leave this screen
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        // nothing: only Confirm and Reject leave
    }
}
