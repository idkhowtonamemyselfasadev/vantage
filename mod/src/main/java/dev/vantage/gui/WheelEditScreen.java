package dev.vantage.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import dev.vantage.core.Ui;
import dev.vantage.module.donut.DonutWheel;
import dev.vantage.module.donut.DonutWheel.Node;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Wheel editor: folders and actions on the left, ready-made shortcuts on the right, or edit the selected entry. */
public class WheelEditScreen extends Screen {
    private static final float W = 704, H = 582;
    private record Hit(float x, float y, float w, float h, Runnable run) {
        boolean at(float mx, float my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final Screen parent;
    private final DonutWheel wheel;
    private final Node root;
    private final Deque<Node> path = new ArrayDeque<>();
    private final List<Hit> hits = new ArrayList<>();
    private Node selected;
    /** 0 = none, 1 = name field, 2 = action field. */
    private int typing;
    private float mouseX, mouseY;

    public WheelEditScreen(Screen parent, DonutWheel wheel) {
        super(Component.literal("Edit Donut Wheel"));
        this.parent = parent;
        this.wheel = wheel;
        this.root = wheel.copyRoot();
        path.push(root);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}

    private Node here() {
        return path.peek();
    }

    private String trail() {
        List<String> names = new ArrayList<>();
        path.descendingIterator().forEachRemaining(n -> names.add(n.name));
        return String.join(" / ", names).toUpperCase().replace(" / ", " / ");
    }

    private void button(GuiGraphics g, String label, float x, float y, float w, float h, int bg, int color, Runnable run) {
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        Ui.rr(g, x, y, w, h, 3.5f, hot ? 0x40FFFFFF : bg, Ui.BORDER);
        Ui.text(g, label, x + 8, y + h / 2 - 4, color, 0.95f, false);
        hits.add(new Hit(x, y, w, h, run));
    }

    private void add(Node n) {
        here().kids.add(n);
        selected = n;
    }

    private void folder() {
        Node n = new Node("Folder");
        n.kids = new ArrayList<>();
        add(n);
        typing = 1;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        float s = Ui.scale();
        mouseX = mx / s;
        mouseY = my / s;
        hits.clear();
        Ui.begin(g);
        float x = (Ui.W() - W) / 2, y = (Ui.H() - H) / 2 + 8;
        Ui.rr(g, x, y, W, H, 8, 0xE8141826, Ui.BORDER);
        Ui.text(g, "EDIT DONUT WHEEL", x + 15, y + 15, Ui.SUB, 0.9f, false);
        Ui.text(g, trail(), x + 15, y + 36, Ui.TEXT, 1f, false);
        if (path.size() > 1) button(g, "<  Back", x + W - 105, y + 17, 90, 30, 0x20FFFFFF, Ui.TEXT, () -> { path.pop(); selected = null; typing = 0; });
        Ui.hline(g, x + 15, y + 62, W - 30, Ui.LINE);
        Ui.vline(g, x + 221, y + 72, H - 136, Ui.LINE);

        float ly = y + 73;
        for (Node n : new ArrayList<>(here().kids)) {
            float w = n.folder() ? 151 : 193;
            int bg = n == selected ? 0x50808CFF : 0x20FFFFFF;
            button(g, n.name, x + 13, ly, w, 31, bg, Ui.TEXT, () -> { selected = n; typing = 0; });
            if (n.folder()) button(g, ">", x + 13 + 157, ly, 36, 31, 0x20FFFFFF, Ui.SUB, () -> { path.push(n); selected = null; typing = 0; });
            ly += 34;
        }
        float by = y + H - 196;
        if (path.size() == 1) {
            button(g, "+ Action", x + 13, by, 93, 31, 0x20FFFFFF, Ui.TEXT, () -> { add(new Node("Action", "/")); typing = 2; });
            button(g, "+ Folder", x + 113, by, 93, 31, 0x20FFFFFF, Ui.TEXT, this::folder);
        } else {
            button(g, "+ Action", x + 13, by, 193, 31, 0x20FFFFFF, Ui.TEXT, () -> { add(new Node("Action", "/")); typing = 2; });
        }
        Ui.text(g, path.size() > 1 ? "Select an action to edit" : "Click > to open a folder", x + 13, y + H - 80, Ui.SUB, 0.85f, false);

        if (selected != null) editor(g, x + 236, y + 76);
        else shortcuts(g, x + 236, y + 76);

        Ui.hline(g, x + 15, y + H - 50, W - 30, Ui.LINE);
        Ui.text(g, "Saved in config/vantage/wheel.json", x + 15, y + H - 30, Ui.SUB, 0.85f, false);
        button(g, "Cancel", x + W - 268, y + H - 39, 94, 30, 0x20FFFFFF, Ui.TEXT, this::onClose);
        button(g, "Apply changes", x + W - 164, y + H - 39, 149, 30, 0x50584EC8, Ui.TEXT, () -> { wheel.setRoot(root); onClose(); });
        Ui.end(g);
    }

    private void shortcuts(GuiGraphics g, float x, float y) {
        Ui.text(g, "Add a shortcut", x, y, Ui.TEXT, 1.1f, false);
        Ui.text(g, "These buttons add actions to the current folder.", x, y + 31, Ui.SUB, 0.95f, false);
        float w = 146, gap = 7.5f;
        y += 70;
        Ui.text(g, "Homes", x, y - 7, Ui.SUB, 0.95f, false);
        y += 15;
        for (int i = 1; i <= 3; i++) {
            int n = i;
            button(g, "Home " + n, x + (i - 1) * (w + gap), y, w, 33, 0x20FFFFFF, Ui.SUB, () -> add(new Node("Home " + n, "/home " + n)));
        }
        y += 68;
        Ui.text(g, "Buy", x, y - 7, Ui.SUB, 0.95f, false);
        y += 15;
        String[][] buy = {{"Totems", "/ah totem of undying"}, {"Pearls", "/ah ender pearl"}, {"Rockets", "/ah firework rocket"}};
        for (int i = 0; i < 3; i++) {
            String[] b = buy[i];
            button(g, b[0], x + i * (w + gap), y, w, 33, 0x20FFFFFF, Ui.SUB, () -> add(new Node(b[0], b[1])));
        }
        y += 68;
        Ui.text(g, "More shortcuts", x, y - 7, Ui.SUB, 0.95f, false);
        y += 15;
        String[][] more = {{"Sell inventory", "/sell"}, {"Auction", "/ah"}, {"RTP", "/rtp"}};
        for (int i = 0; i < 3; i++) {
            String[] b = more[i];
            button(g, b[0], x + i * (w + gap), y, w, 33, 0x20FFFFFF, Ui.SUB, () -> add(new Node(b[0], b[1])));
        }
        y += 69;
        float hw = (w * 3 + gap * 2 - gap) / 2;
        button(g, "Custom command", x, y, hw, 33, 0x20FFFFFF, Ui.SUB, () -> { add(new Node("Command", "/")); typing = 2; });
        button(g, "Toggle module", x + hw + gap, y, hw, 33, 0x20FFFFFF, Ui.SUB, () -> { add(new Node("Module", "toggle:Base Finder")); typing = 2; });
        Ui.text(g, "Select an existing action on the left to edit it.", x, y + 52, Ui.SUB, 0.95f, false);
    }

    private void field(GuiGraphics g, String label, String value, float x, float y, float w, int id) {
        Ui.text(g, label, x, y, Ui.SUB, 0.95f, false);
        float fy = y + 14;
        Ui.rr(g, x, fy, w, 30, 3.5f, Ui.FIELD, typing == id ? Ui.ACCENT : Ui.BORDER);
        Ui.text(g, Ui.fit(value + (typing == id ? "_" : ""), w - 16, 1f), x + 8, fy + 10, Ui.TEXT, 1f, false);
        hits.add(new Hit(x, fy, w, 30, () -> typing = id));
    }

    private void editor(GuiGraphics g, float x, float y) {
        Node n = selected;
        Ui.text(g, n.folder() ? "Edit folder" : "Edit action", x, y, Ui.TEXT, 1.1f, false);
        float w = 453;
        field(g, "Name", n.name, x, y + 32, w, 1);
        if (!n.folder()) {
            field(g, "Command, or toggle:<module>", n.action, x, y + 88, w, 2);
            Ui.text(g, "One press sends this once. Nothing repeats by itself.", x, y + 142, Ui.SUB, 0.92f, false);
        }
        float by = y + 170;
        List<Node> kids = here().kids;
        button(g, "Move up", x, by, 100, 31, 0x20FFFFFF, Ui.TEXT, () -> move(kids, n, -1));
        button(g, "Move down", x + 107, by, 100, 31, 0x20FFFFFF, Ui.TEXT, () -> move(kids, n, 1));
        button(g, "Delete", x + 214, by, 100, 31, 0x20FF6060, Ui.TEXT, () -> { kids.remove(n); selected = null; typing = 0; });
        button(g, "Done", x + w - 100, by, 100, 31, 0x20FFFFFF, Ui.TEXT, () -> { selected = null; typing = 0; });
    }

    private static void move(List<Node> kids, Node n, int dir) {
        int i = kids.indexOf(n), j = i + dir;
        if (j < 0 || j >= kids.size()) return;
        kids.set(i, kids.get(j));
        kids.set(j, n);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        float s = Ui.scale();
        float mx = (float) e.x() / s, my = (float) e.y() / s;
        // newest first: buttons inside a row win over anything under them
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).at(mx, my)) { hits.get(i).run.run(); return true; }
        }
        typing = 0;
        return false;
    }

    /** Test rig: presses a button by its screen area order is not stable, so tests use these. */
    public void testAdd(String name, String action) {
        add(new Node(name, action));
    }

    public void testApply() {
        wheel.setRoot(root);
        onClose();
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (typing == 0 || selected == null) return false;
        String c = e.codepointAsString();
        if (typing == 1) selected.name += c;
        else selected.action += c;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (typing != 0 && selected != null) {
            if (e.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (typing == 1 && !selected.name.isEmpty()) selected.name = selected.name.substring(0, selected.name.length() - 1);
                if (typing == 2 && !selected.action.isEmpty()) selected.action = selected.action.substring(0, selected.action.length() - 1);
                return true;
            }
            if (e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_ESCAPE) { typing = 0; return true; }
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
