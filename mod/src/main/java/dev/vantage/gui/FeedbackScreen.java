package dev.vantage.gui;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Feedback;
import dev.vantage.core.Presence;
import dev.vantage.core.Presence.FeedbackResult;
import dev.vantage.core.Ui;
import dev.vantage.core.util.Colors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * "Send feedback" (Settings): a text box of up to {@link Feedback#MAX} characters, Send and Cancel, and one plain line saying what is
 * sent. The text is typed at the end (no cursor movement); Enter makes a new line, Ctrl+V pastes. The text is never logged.
 */
public class FeedbackScreen extends Screen {
    private static final float W = 480, H = 340, BOX_H = 150, BTN_W = 120, BTN_H = 28, TEXT = 0.9f, LINE = 12;
    private final Screen parent;
    private final StringBuilder text = new StringBuilder();
    private volatile FeedbackResult result;
    private volatile boolean sending, finished;
    private String status = "";
    private int statusColor = Ui.SUB;
    private float sendX, cancelX, btnY;
    private boolean drawn;
    private int frames;

    public FeedbackScreen(Screen parent) {
        super(Component.literal("Send feedback"));
        this.parent = parent;
    }

    // ---- what the rig and the buttons use ----------------------------------------------------------------------------------

    public String text() { return text.toString(); }

    /** How many characters count against the limit (code points). */
    public int count() { return text.codePointCount(0, text.length()); }

    public String status() { return status; }

    public boolean sending() { return sending; }

    public FeedbackResult result() { return result; }

    /** Types text at the end like the keyboard does: control characters are dropped, the limit is kept. */
    public void type(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') continue;
            if (cp != '\n' && (cp < 0x20 || (cp >= 0x7f && cp <= 0x9f))) continue;
            if (count() >= Feedback.MAX) break;
            text.appendCodePoint(cp);
        }
        if (finished) { finished = false; status = ""; }
    }

    private void backspace() {
        if (text.length() > 0) text.setLength(text.offsetByCodePoints(text.length(), -1));
    }

    /** What the Send button does. */
    public void send() {
        if (sending) return;
        String t = Feedback.clean(text.toString());
        if (t.isEmpty()) { show(FeedbackResult.EMPTY); return; }
        sending = true;
        status = "Sending...";
        statusColor = Ui.SUB;
        Presence.sendFeedback(t, Feedback.vantageVersion(), Feedback.minecraftVersion(), this::arrived);
    }

    private void arrived(FeedbackResult r) {
        result = r;
        sending = false;
    }

    private void show(FeedbackResult r) {
        result = r;
        apply(r);
    }

    private void apply(FeedbackResult r) {
        switch (r) {
            case SENT -> { status = "Sent. Thank you!"; statusColor = Ui.ACCENT; text.setLength(0); finished = true; }
            case EMPTY -> { status = "Write something first: the text is empty."; statusColor = 0xFFE0A050; }
            case TOO_MANY -> { status = "Too many messages for now. Try again in a while."; statusColor = 0xFFE0A050; }
            case OFFLINE -> { status = "Offline: could not reach the service. Your text is kept, try again later."; statusColor = 0xFFE07070; }
            case TERMS -> { status = "Confirm the terms first (nothing is sent until you do)."; statusColor = 0xFFE0A050; }
            case FAILED -> { status = "The service did not accept it. Your text is kept, try again later."; statusColor = 0xFFE07070; }
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (parent != null) parent.render(g, -1, -1, delta);
        g.fill(0, 0, width, height, 0x90050817);
    }

    private static List<String> wrap(String s, float w) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ", -1)) {
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (Ui.width(tryLine, TEXT, false) <= w) { line.setLength(0); line.append(tryLine); continue; }
                if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                String rest = word;
                while (Ui.width(rest, TEXT, false) > w && rest.length() > 1) { // one long word: break it
                    int n = rest.length();
                    while (n > 1 && Ui.width(rest.substring(0, n), TEXT, false) > w) n--;
                    out.add(rest.substring(0, n));
                    rest = rest.substring(n);
                }
                line.append(rest);
            }
            out.add(line.toString());
        }
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        frames++;
        if (result != null && !finished && !sending && status.equals("Sending...")) apply(result); // the answer came from the network thread
        Ui.begin(g);
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        float x = (Ui.W() - W) / 2, y = (Ui.H() - H) / 2;
        Ui.rr(g, x, y, W, H, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.chevron(g, x + 22, y + 22, 14, 3, Ui.ACCENT);
        Ui.serif(g, "Send feedback", x + 46, y + 14, Ui.TEXT, 1.6f, false);
        Ui.text(g, "Tell the developer what is wrong or what you would like.", x + 22, y + 48, Ui.SUB, 0.88f, false);
        float bx = x + 22, by = y + 68, bw = W - 44;
        Ui.rr(g, bx, by, bw, BOX_H, 8, Ui.SELECT, Ui.WIN_LINE);
        List<String> lines = wrap(text.toString(), bw - 20);
        int maxLines = (int) ((BOX_H - 14) / LINE);
        int from = Math.max(0, lines.size() - maxLines);
        if (text.length() == 0) Ui.text(g, "Type here...", bx + 10, by + 8, Ui.FAINT, TEXT, false);
        for (int i = from; i < lines.size(); i++) {
            float ly = by + 8 + (i - from) * LINE;
            String l = lines.get(i);
            Ui.text(g, l, bx + 10, ly, Ui.TEXT, TEXT, false);
            if (i == lines.size() - 1 && (frames / 30) % 2 == 0 && !sending) Ui.text(g, "|", bx + 10 + Ui.width(l, TEXT, false) + 1, ly, Ui.ACCENT, TEXT, false);
        }
        int n = count();
        Ui.right(g, n + " / " + Feedback.MAX, bx + bw - 6, by + BOX_H + 5, n >= Feedback.MAX ? 0xFFE0A050 : Ui.FAINT, 0.82f, false);
        Ui.text(g, Ui.fit(status, bw - 90, 0.88f), bx, by + BOX_H + 5, statusColor, 0.88f, false);
        String sent = Feedback.sentLine();
        float ty = by + BOX_H + 26;
        List<String> sl = wrap(sent, bw);
        for (String l : sl) { Ui.text(g, l, bx, ty, Ui.SUB, TEXT, false); ty += LINE; }
        btnY = y + H - 22 - BTN_H;
        cancelX = x + W - 22 - BTN_W;
        sendX = cancelX - BTN_W - 12;
        button(g, "Send", sendX, fx, fy, true);
        button(g, finished ? "Close" : "Cancel", cancelX, fx, fy, false);
        drawn = true;
        Ui.end(g);
    }

    private void button(GuiGraphics g, String label, float x, float fx, float fy, boolean primary) {
        boolean off = primary && sending;
        boolean hot = !off && fx >= x && fx < x + BTN_W && fy >= btnY && fy < btnY + BTN_H;
        int bg = primary ? Colors.mix(Ui.ACCENT, 0xFFFFFFFF, hot ? 0.15f : 0) : Colors.mix(Ui.SELECT, 0xFFFFFFFF, hot ? 0.1f : 0);
        if (off) bg = Colors.mix(Ui.SELECT, 0xFFFFFFFF, 0);
        Ui.rr(g, x, btnY, BTN_W, BTN_H, 8, bg, primary ? 0 : Ui.WIN_LINE);
        Ui.center(g, label, x + BTN_W / 2, btnY + (BTN_H - 9) / 2, off ? Ui.FAINT : Ui.TEXT, 1f, true);
    }

    private boolean in(float fx, float fy, float x) {
        return fx >= x && fx < x + BTN_W && fy >= btnY && fy < btnY + BTN_H;
    }

    /** Where the buttons are, in screen pixels (for the rig): {send x, cancel x, y}. */
    public float[] buttons() {
        if (!drawn) return null;
        float s = Ui.scale();
        return new float[] {(sendX + BTN_W / 2) * s, (cancelX + BTN_W / 2) * s, (btnY + BTN_H / 2) * s};
    }

    // ---- input -----------------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        if (in(fx, fy, sendX)) send();
        else if (in(fx, fy, cancelX)) onClose();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (e.isPaste()) { type(minecraft.keyboardHandler.getClipboard()); return true; }
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) { type("\n"); return true; }
        if (k == GLFW.GLFW_KEY_BACKSPACE) { backspace(); return true; }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (e.isAllowedChatCharacter()) type(e.codepointAsString());
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
