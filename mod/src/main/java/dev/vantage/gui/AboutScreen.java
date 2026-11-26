package dev.vantage.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Config;
import dev.vantage.core.Ui;
import dev.vantage.core.Updater;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** About this build (beta): version, the running jar's short SHA-256, where the config lives, and the bundled changelog. No link, nothing is fetched. */
public class AboutScreen extends Screen {
    private static final float W = 560, H = 330, PAD = 28;
    private final Screen parent;
    public int frames;
    public final List<String> lines = new ArrayList<>();
    private final List<float[]> hits = new ArrayList<>();
    private final List<Runnable> acts = new ArrayList<>();
    private final List<String> tags = new ArrayList<>();

    public AboutScreen(Screen parent) {
        super(Component.literal("About"));
        this.parent = parent;
    }

    public static String version() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("vantage").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    /** "3f9a1c2b7e41" of the running jar, "hashing..." while it is computed, or a dev-run note. */
    public static String shaShort() {
        String sha = Updater.ownSha();
        if (sha == null) return Updater.verifyLine().startsWith("dev run") ? "dev run, no jar" : "hashing...";
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    /** The config folder with the home folder written as "~". */
    public static String configFolder() {
        Path dir = Config.dir().toAbsolutePath().normalize();
        String s = dir.toString(), home = System.getProperty("user.home", "");
        return !home.isEmpty() && s.startsWith(home) ? "~" + s.substring(home.length()) : s;
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

    private void button(GuiGraphics g, String label, String tag, float x, float y, float fx, float fy, Runnable act) {
        float bw = Ui.width(label, 0.95f, true) + 36;
        boolean hov = fx >= x && fx < x + bw && fy >= y && fy < y + 28;
        Ui.rr(g, x, y, bw, 28, 14, hov ? Ui.fade(Ui.ACCENT, 0.35f) : Ui.fade(Ui.ACCENT, 0.2f), Ui.ACCENT);
        Ui.center(g, label, x + bw / 2, y + 9, Ui.TEXT, 0.95f, true);
        lines.add(label);
        hits.add(new float[] {x, y, bw, 28});
        acts.add(act);
        tags.add(tag);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float s = Ui.scale(), fx = mx / s, fy = my / s;
        frames++;
        lines.clear(); hits.clear(); acts.clear(); tags.clear();
        Ui.begin(g);
        float x = x0(), y = y0(), cx = x + PAD;
        Ui.rr(g, x, y, W, H, 14, Ui.WIN, Ui.WIN_LINE);
        Ui.serif(g, "About this build", cx, y + 22, Ui.TEXT, 2f, false);
        lines.add("About this build");
        float ry = y + 76;
        String[][] rows = {{"Version", version()}, {"Running jar", shaShort().matches("[0-9a-f]{12}") ? "sha256 " + shaShort() : shaShort()}, {"Config folder", configFolder()}};
        for (String[] r : rows) {
            Ui.rr(g, cx, ry, W - 2 * PAD, 40, 8, 0x40101522, Ui.CARD_LINE);
            text(g, r[0], cx + 16, ry + 8, Ui.SUB, 0.8f, false);
            text(g, Ui.fit(r[1], W - 2 * PAD - 32, 0.95f), cx + 16, ry + 22, Ui.TEXT, 0.95f, false);
            ry += 48;
        }
        text(g, "The changelog is bundled in the jar: nothing is fetched and there is no link.", cx + 2, ry + 2, Ui.FAINT, 0.8f, false);
        button(g, "What's new", "new", cx, y + H - 48, fx, fy, () -> minecraft.setScreen(new ChangelogScreen(this)));
        button(g, "Close", "close", x + W - PAD - (Ui.width("Close", 0.95f, true) + 36), y + H - 48, fx, fy, this::onClose);
        Ui.end(g);
    }

    public float[] spot(String tag) {
        for (int i = 0; i < tags.size(); i++) if (tags.get(i).equals(tag)) return hits.get(i);
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (dbl || e.button() != 0) return super.mouseClicked(e, dbl);
        float s = Ui.scale(), fx = (float) e.x() / s, fy = (float) e.y() / s;
        for (int i = 0; i < hits.size(); i++) {
            float[] h = hits.get(i);
            if (fx >= h[0] && fx < h[0] + h[2] && fy >= h[1] && fy < h[1] + h[3]) { acts.get(i).run(); return true; }
        }
        return super.mouseClicked(e, dbl);
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
