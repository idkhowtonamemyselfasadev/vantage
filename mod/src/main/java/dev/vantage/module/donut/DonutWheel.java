package dev.vantage.module.donut;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vantage.core.Category;
import dev.vantage.core.Config;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Ui;
import dev.vantage.core.store.JsonStore;
import dev.vantage.core.util.Fmt;
import dev.vantage.gui.WheelEditScreen;
import dev.vantage.gui.WheelScreen;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BoolSetting;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * Hold the key for a radial menu of shortcuts. One press is one command or one module toggle, nothing runs by itself.
 * Saved in config/vantage/wheel.json.
 */
public class DonutWheel extends Module {
    /** An action ("/home 1" or "toggle:Base Finder"), or a folder when action is null. */
    public static final class Node {
        public String name;
        public String action;
        public List<Node> kids;

        public Node() {}

        public Node(String name, String action) {
            this.name = name;
            this.action = action;
        }

        public Node(String name, Node... kids) {
            this.name = name;
            this.kids = new ArrayList<>(List.of(kids));
        }

        public boolean folder() {
            return action == null;
        }

        public String hint() {
            if (folder()) return "Open  >";
            if (action.startsWith("toggle:")) return "Toggle " + action.substring(7);
            return action;
        }

        Node copy() {
            Node n = new Node();
            n.name = name;
            n.action = action;
            if (kids != null) {
                n.kids = new ArrayList<>();
                for (Node k : kids) n.kids.add(k.copy());
            }
            return n;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long RTP_WAIT_MS = 15_000, READY_MS = 5_000;

    public final BoolSetting status = add(new BoolSetting("Status cards", "Ready card and the card that waits for an RTP", true));

    private Node root;
    private long readyUntil;
    private long rtpSent;
    private String rtpRegion;
    private net.minecraft.world.phys.Vec3 rtpFrom;

    public DonutWheel() {
        super("Donut Wheel", "Opens a wheel for Donut SMP commands and actions", Category.DONUT);
        onByDefault();
        bind = GLFW.GLFW_KEY_G;
        add(new ActionSetting("Edit wheel", "Change the wheel's actions and folders", () -> mc.setScreen(new WheelEditScreen(mc.screen, this))));
        // the Ready card keeps its place (top middle) and is in no corner stack, so it only moves when the user moves it. The RTP wait card used to sit at a fixed spot (x 202, y 278 of 720) that overlapped the Debris and Sound Detector panels (B-171): it stacks in the top left now
        hudPanel("wheel-ready", "Wheel: Ready card", "Top Left").when(() -> status.on() && System.currentTimeMillis() < readyUntil).size(wh -> { wh[0] = 170; wh[1] = 58; })
                .draw((g, x, y, w, h) -> {
                    Ui.rr(g, x, y, w, h, 6, Ui.GLASS, Ui.BORDER);
                    Ui.text(g, "DONUT WHEEL", x + 9, y + 7, Ui.FAINT, 0.85f, false);
                    Ui.text(g, "Ready", x + 9, y + 23, Ui.TEXT, 1f, false);
                    Ui.text(g, "Open the wheel to pick an action", x + 9, y + 40, Ui.SUB, 0.85f, false);
                }).element().plain().defaultAt((w, h, sw, sh, out) -> { out[0] = (sw - w) / 2 - 165; out[1] = 13; });
        hudPanel("wheel-rtp", "Wheel: RTP wait card", "Top Left").when(this::waitingForRtp).size(wh -> { wh[0] = 144; wh[1] = 86; })
                .draw(this::drawRtp).element().plain();
    }

    /** True while the RTP card is up; it ends itself after a hundred blocks of movement or the wait time. */
    private boolean waitingForRtp() {
        if (!status.on() || rtpSent == 0 || mc.player == null) return false;
        boolean moved = rtpFrom != null && mc.player.position().distanceToSqr(rtpFrom) > 100 * 100;
        if (moved || System.currentTimeMillis() - rtpSent > RTP_WAIT_MS) rtpSent = 0;
        return rtpSent != 0;
    }

    private void drawRtp(GuiGraphics g, float x, float y, float w, float h) {
        Ui.rr(g, x, y, w, h, 6, Ui.GLASS, Ui.BORDER);
        Ui.text(g, "RTP", x + 10, y + 9, Ui.FAINT, 0.85f, false);
        if (rtpRegion != null) {
            int i = java.util.Arrays.asList(DonutRegions.REGIONS).indexOf(rtpRegion);
            float pw = Ui.width(rtpRegion, 0.9f, false) + 10;
            Ui.rr(g, x + w - pw - 8, y + 6, pw, 16, 4, 0x30FFFFFF, 0);
            Ui.text(g, rtpRegion, x + w - pw - 3, y + 10, i >= 0 ? DonutRegions.COLORS[i] : Ui.TEXT, 0.9f, false);
        }
        Ui.text(g, "Waiting for RTP", x + 10, y + 38, Ui.TEXT, 1.1f, false);
        Ui.text(g, Fmt.fixed((System.currentTimeMillis() - rtpSent) / 1000f, 1) + "s", x + 10, y + 64, Ui.SUB, 1f, false);
    }

    @Override public boolean holdKey() { return true; }

    @Override
    public void onBindPress() {
        mc.setScreen(new WheelScreen(this, false));
    }

    private static Path file() {
        return Config.dir().resolve("wheel.json");
    }

    private static Node defaults() {
        return new Node("Donut",
                new Node("Buy", new Node("Totems", "/ah totem of undying"), new Node("Pearls", "/ah ender pearl"), new Node("Rockets", "/ah firework rocket")),
                new Node("Homes", new Node("Home 1", "/home 1"), new Node("Home 2", "/home 2"), new Node("Home 3", "/home 3")),
                new Node("Auction", "/ah"),
                new Node("Storage ESP", "toggle:Storage ESP"),
                new Node("Base Finder", "toggle:Base Finder"),
                new Node("RTP", new Node("EU Central", "/rtp eu central"), new Node("EU West", "/rtp eu west"), new Node("NA East", "/rtp na east"),
                        new Node("NA West", "/rtp na west"), new Node("Asia", "/rtp asia"), new Node("Oceania", "/rtp oceania")));
    }

    /** The user's wheel as a pretty tree, with a v field. On JsonStore: written on the storage thread, and a damaged file is kept aside, not overwritten by the next edit. */
    private final JsonStore store = new JsonStore("wheel", DonutWheel::file, 1, () -> GSON.toJsonTree(root), (json, v) -> {
        Node n = GSON.fromJson(json, Node.class);
        if (n == null || n.kids == null) throw new IllegalStateException("the wheel has no entries");
        root = n;
    }).pretty();

    public Node root() {
        if (root != null) return root;
        if (!store.load()) root = defaults();
        return root;
    }

    public Node copyRoot() {
        return root().copy();
    }

    public void setRoot(Node n) {
        root = n;
        store.markDirty();
    }

    /** One press, one action. */
    public void run(Node n) {
        if (n.folder() || mc.player == null) return;
        readyUntil = System.currentTimeMillis() + READY_MS;
        if (n.action.startsWith("toggle:")) {
            Module m = Modules.byName(n.action.substring(7));
            if (m != null) m.toggle();
            return;
        }
        String cmd = n.action.startsWith("/") ? n.action.substring(1) : n.action;
        dev.vantage.core.command.ServerCommands.send(this, cmd);
        if (cmd.startsWith("rtp")) {
            rtpSent = System.currentTimeMillis();
            rtpRegion = cmd.length() > 4 ? n.name : null;
            rtpFrom = mc.player.position();
        }
    }

    @Override
    public void onJoinWorld() {
        armForNewWorld();
    }

    @Override
    public void onDimensionChange() {
        armForNewWorld();
    }

    private void armForNewWorld() {
        readyUntil = System.currentTimeMillis() + READY_MS;
        rtpSent = 0;
    }
}
