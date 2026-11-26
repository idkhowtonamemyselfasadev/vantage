package dev.vantage.gui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import dev.vantage.core.Keys;
import dev.vantage.core.Ui;
import dev.vantage.module.donut.DonutWheel;
import dev.vantage.module.donut.DonutWheel.Node;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The radial menu. Move onto a slice and let go of the key to use it; resting on a folder opens it. */
public class WheelScreen extends Screen {
    private static final float R = 180, HUB = 82;
    private static final long FOLDER_MS = 280;

    private final DonutWheel wheel;
    /** Test rig: no physical key is held, so the wheel must not close by itself. */
    private final boolean pinned;
    private final Deque<Node> path = new ArrayDeque<>();
    private int hover = -1;
    private long hoverSince;
    private boolean editHover;

    public WheelScreen(DonutWheel wheel, boolean pinned) {
        super(Component.literal("Donut Wheel"));
        this.wheel = wheel;
        this.pinned = pinned;
        path.push(wheel.root());
    }

    private Node here() {
        return path.peek();
    }

    private List<Node> items() {
        return here().kids;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}

    /** Slice under a point (design units from the centre), -1 for none. */
    private int slice(float dx, float dy) {
        int n = items().size();
        double d = Math.sqrt(dx * dx + dy * dy);
        if (n == 0 || d < HUB) return -1;
        double a = Math.toDegrees(Math.atan2(dx, -dy)) + 180.0 / n; // 0 = straight up, clockwise
        return Math.floorMod((int) Math.floor(a / (360.0 / n)), n);
    }

    /** Test rig: picks a slice as if the mouse were on it. */
    public void pick(int i) {
        hover = i;
    }

    public void useHovered() {
        if (hover < 0 || hover >= items().size()) return;
        Node n = items().get(hover);
        if (n.folder()) { open(n); return; }
        minecraft.setScreen(null);
        wheel.run(n);
    }

    private void open(Node n) {
        path.push(n);
        hover = -1;
    }

    @Override
    public void tick() {
        if (pinned || Keys.down(minecraft, wheel.bind)) return;
        useHovered();
        if (minecraft.screen == this) minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float s = Ui.scale(), cx = Ui.W() / 2, cy = Ui.H() / 2;
        float dx = mx / s - cx, dy = my / s - cy;
        if (!pinned) {
            int h = slice(dx, dy);
            if (h != hover) { hover = h; hoverSince = System.currentTimeMillis(); }
            if (h >= 0 && h < items().size() && items().get(h).folder() && System.currentTimeMillis() - hoverSince > FOLDER_MS) open(items().get(h));
        }
        editHover = Math.abs(dx) < 48 && dy > 32 && dy < 56;

        Ui.begin(g);
        Ui.center(g, here().name.toUpperCase(), cx, cy - R - 34, Ui.SUB, 0.9f, false);
        disc(g, cx, cy);
        List<Node> list = items();
        int n = list.size();
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(i * 360.0 / n);
            float lx = cx + (float) Math.sin(a) * (R + HUB) / 2, ly = cy - (float) Math.cos(a) * (R + HUB) / 2;
            Node node = list.get(i);
            Ui.center(g, node.name, lx, ly - 11, Ui.TEXT, 1.1f, false);
            Ui.center(g, Ui.fit(node.hint(), 104, 0.9f), lx, ly + 11, Ui.SUB, 0.9f, false);
        }
        Node hot = hover >= 0 && hover < n ? list.get(hover) : null;
        Ui.center(g, hot != null ? hot.name : here().name, cx, cy - 38, Ui.TEXT, 1.25f, false);
        Ui.center(g, hot != null ? Ui.fit(hot.hint(), 130, 0.92f) : path.size() > 1 ? "Click to go back" : "Hover an action", cx, cy - 8, Ui.SUB, 0.92f, false);
        Ui.rr(g, cx - 48, cy + 32, 96, 24, 4, editHover ? 0x40FFFFFF : 0x28101420, Ui.BORDER);
        Ui.text(g, "Edit wheel", cx - 40, cy + 40, Ui.SUB, 0.92f, false);
        Ui.center(g, "Hover to use   |   Release to close   |   E: Edit", cx, cy + R + 26, Ui.fade(Ui.SUB, 0.75f), 0.85f, false);
        Ui.end(g);
    }

    /** The disc with its slices, drawn row by row in real pixels. */
    private void disc(GuiGraphics g, float cx, float cy) {
        float d = Ui.px();
        g.pose().pushMatrix();
        g.pose().scale(1f / d, 1f / d);
        int X = Math.round(cx * d), Y = Math.round(cy * d), RO = Math.round(R * d), RI = Math.round(HUB * d);
        int n = Math.max(1, items().size());
        double step = 2 * Math.PI / n;
        int base = 0xD0141826, hub = 0xE0121624, lit = 0xE0262D45, edge = 0x70808AB0;
        for (int y = -RO; y <= RO; y++) {
            int xo = (int) Math.sqrt((double) RO * RO - (double) y * y);
            int xi = Math.abs(y) < RI ? (int) Math.sqrt((double) RI * RI - (double) y * y) : -1;
            int row = Y + y;
            if (xi < 0) {
                span(g, X, row, -xo, xo, y, step, n, base, lit);
            } else {
                span(g, X, row, -xo, -xi, y, step, n, base, lit);
                g.fill(X - xi, row, X + xi, row + 1, hub);
                span(g, X, row, xi, xo, y, step, n, base, lit);
                g.fill(X - xi - 1, row, X - xi + 1, row + 1, edge);
                g.fill(X + xi - 1, row, X + xi + 1, row + 1, edge);
            }
            g.fill(X - xo - 1, row, X - xo + 1, row + 1, edge);
            g.fill(X + xo - 1, row, X + xo + 1, row + 1, edge);
        }
        g.pose().popMatrix();
    }

    /** One row of the ring: splits where the lit slice starts and stops. */
    private void span(GuiGraphics g, int X, int row, int a, int b, int y, double step, int n, int base, int lit) {
        if (hover < 0) { g.fill(X + a, row, X + b, row + 1, base); return; }
        int start = a;
        boolean cur = inHover(a, y, step, n);
        for (int x = a + 1; x <= b; x++) {
            boolean in = x < b && inHover(x, y, step, n);
            if (in != cur || x == b) {
                g.fill(X + start, row, X + x, row + 1, cur ? lit : base);
                start = x;
                cur = in;
            }
        }
    }

    private boolean inHover(int x, int y, double step, int n) {
        double a = Math.atan2(x, -y) + step / 2;
        return Math.floorMod((int) Math.floor(a / step), n) == hover;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() != 0) return false;
        if (editHover) { edit(); return true; }
        if (hover >= 0) { useHovered(); return true; }
        if (path.size() > 1) { path.pop(); hover = -1; return true; }
        return false;
    }

    private void edit() {
        minecraft.setScreen(new WheelEditScreen(null, wheel));
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_E) { edit(); return true; }
        return super.keyPressed(e);
    }
}
