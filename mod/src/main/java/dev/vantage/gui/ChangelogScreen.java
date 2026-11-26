package dev.vantage.gui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The bundled changelog (assets/vantage/changelog.txt inside the jar), scrollable. Nothing is fetched. */
public class ChangelogScreen extends Screen {
    private static final float W = 600, H = 520, PAD = 28, LINE = 15;
    private final Screen parent;
    private int scroll;
    public int frames;
    public final List<String> text = new ArrayList<>();
    public final List<String> drawn = new ArrayList<>();

    public ChangelogScreen(Screen parent) {
        super(Component.literal("What's new"));
        this.parent = parent;
        text.addAll(load());
    }

    /** The changelog lines (wrapped at about 90 characters); one line saying so when the file is missing. */
    public static List<String> load() {
        List<String> out = new ArrayList<>();
        try (InputStream in = ChangelogScreen.class.getResourceAsStream("/assets/vantage/changelog.txt")) {
            if (in == null) { out.add("The changelog is not in this build."); return out; }
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                while (l.length() > 90) {
                    int cut = l.lastIndexOf(' ', 90);
                    if (cut < 20) cut = 90;
                    out.add(l.substring(0, cut));
                    l = "  " + l.substring(cut).trim();
                }
                out.add(l);
            }
        } catch (Exception e) {
            out.add("The changelog could not be read.");
        }
        return out;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x90050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        frames++;
        drawn.clear();
        Ui.begin(g);
        float x = (Ui.W() - W) / 2, y = (Ui.H() - H) / 2;
        Ui.rr(g, x, y, W, H, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.serif(g, "What's new", x + PAD, y + 22, Ui.TEXT, 2f, false);
        int rows = (int) ((H - 110) / LINE);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, text.size() - rows)));
        float ry = y + 76;
        for (int i = scroll; i < text.size() && i < scroll + rows; i++) {
            String l = text.get(i);
            drawn.add(l);
            boolean head = l.startsWith("VERSION") || l.startsWith("BETA") || l.startsWith("What's new");
            Ui.text(g, l, x + PAD, ry, head ? Ui.ACCENT : Ui.SUB, head ? 0.9f : 0.86f, head);
            ry += LINE;
        }
        Ui.text(g, "Esc = back  -  scroll for more", x + PAD, y + H - 22, Ui.FAINT, 0.8f, false);
        Ui.end(g);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - 3 * (int) Math.signum(v));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return true;
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
