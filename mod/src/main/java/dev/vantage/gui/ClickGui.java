package dev.vantage.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vantage.core.Category;
import dev.vantage.core.Config;
import dev.vantage.core.ConfigShare;
import dev.vantage.core.Favourites;
import dev.vantage.core.Keys;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Profiles;
import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import dev.vantage.core.util.Colors;
import dev.vantage.module.client.ClickGuiModule;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BlockListSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.Header;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import dev.vantage.setting.Setting;
import dev.vantage.setting.TextSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The menu. Sidebar: a logo block, MODULES (Active + the categories, with counts) and GENERAL (settings, keybinds, configs,
 * friends, waypoints, HUD layout). Main side: a serif headline, "N modules · M enabled", a search pill, section chips and a list/grid switch, then one
 * card per module with its description, key, chevron and switch; enabled cards get an accent bar. A page per module for its
 * settings, every setting with its description under the name. Left click = on/off, right click or the chevron = settings,
 * middle click = set a key, type anywhere = search. The whole menu scales with the Menu size setting.
 */
public class ClickGui extends Screen {
    /** "RELEASE 1.0.1": the version of the jar that is running, straight from fabric.mod.json. */
    private static final String RELEASE = "RELEASE " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("vantage")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
    private static final float WIN_W = 900, WIN_H = 600, SIDE = 200, CARD = 54, CGAP = 8, ROW = 40, GAP = 10, SET = 40, HEAD = 30, BAR = 18, PART = 40, FOOT = 28, PICK = 96, WHEEL = 80;
    private static final float NAV_Y = 122, NAV_STEP = 32, NAV_H = 29, GEN_STEP = 30, GEN_H = 27, POWER_Y = 58, POWER_H = 30;
    private static final float OPEN_MS = 190, CLOSE_MS = 120;
    /** Wheel notch in design units, and how fast the list follows (per second): low enough to glide, high enough to feel direct. */
    private static final float NOTCH = 46, GLIDE = 10;
    private static final String[] GENERAL = {"Settings", "Keybinds", "Configs", "Friends", "Waypoints", "HUD layout"};

    private enum View { CATEGORY, ACTIVE, KEYBINDS, CONFIGS }

    /** Kept between openings. */
    private static Category category = Category.FINDER;
    private static View view = View.CATEGORY;
    private static Module page;
    private static boolean grid;
    /** Section chip that filters the list, "" = all. */
    private static String chip = "";
    private static final Set<ColorSetting> COLOR_OPEN = new HashSet<>();
    private static final Set<BoolSetting> PART_OPEN = new HashSet<>();

    private record Hit(float x, float y, float w, float h, Click run) {
        boolean at(float mx, float my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private interface Click { void run(int button); }
    private interface Drag { void to(float f); }
    private interface Drag2 { void to(float mx, float my); }

    private final List<Hit> hits = new ArrayList<>();
    private final Map<Object, float[]> spots = new HashMap<>();
    private final ArrayDeque<float[]> clips = new ArrayDeque<>();
    private float clipTop = -1e6f, clipBottom = 1e6f;
    private String search = "";
    private float scroll, shownScroll, maxScroll, listScroll;
    /** The scrollbar's track and thumb from the last frame, and the grab offset while it is being dragged (NaN = not). */
    private float trackY, trackH, thumbH, barGrab = Float.NaN;
    private float mouseX, mouseY;
    private Module listening;
    /** After a key was bound: until when its own typed character is dropped (0 = none pending). */
    private long swallowCharUntil;

    /** Rig: start waiting for a key to bind to this module, as a click on its bind field does. */
    public void testListen(Module m) {
        listening = m;
    }
    /** The text box being typed into, or null. */
    private TextSetting editing;
    /** The number box being typed into (click the box, type, Enter), its text so far, and the slider the wheel is over this frame. */
    private Setting<?> numEditing;
    private final List<Object[]> wheels = new ArrayList<>();
    private String numText = "";
    private Drag dragging;
    private Drag2 dragging2;
    private float dragX, dragW;
    private final long openedAt = System.currentTimeMillis();
    /** Configs page: the "Save current as..." box, the row being renamed (its file key) and its text, the last answer, the armed confirm. */
    private String cfgName = "", cfgRename, cfgRenameText = "", cfgMsg, cfgArm;
    private long cfgMsgAt, cfgArmAt;
    private boolean cfgMsgBad;

    // Animation state. Every value eases towards its target at a rate per second, so speed doesn't depend on FPS.
    private final Map<Object, Float> hover = new HashMap<>(), onOff = new HashMap<>(), misc = new HashMap<>();
    private long lastFrame, openStart, closeStart, listAt;
    private float dt;
    private boolean closed;
    /** Direction new content comes in from: 1 into a page, -1 back out of one, 0 a change of category. */
    private int slide;
    private int seq;
    private int drawnFrames;
    /** Rig: frames this menu has drawn. */
    public int frames() { return drawnFrames; }
    /** Rig: whether the bottom line was drawn in the last frame. */
    public static boolean footerShown;
    private String help;
    /** Beta: a tooltip near the mouse for the number in a card's corner (replaces the bottom help line). */
    private String tip;
    /** Rig: the ms per frame shown on each Active card in the last frame. */
    public final java.util.Map<String, Double> lastMs = new java.util.HashMap<>();
    private boolean hudWas, hudHid;
    /** What the number in a card's corner counts, by module (beta: shown as a tiny label under it). */
    private static final java.util.Map<String, String> INFO_LABEL = java.util.Map.ofEntries(
            java.util.Map.entry("Storage ESP", "boxes"), java.util.Map.entry("Search", "blocks"), java.util.Map.entry("Item ESP", "items"),
            java.util.Map.entry("Hole ESP", "holes"), java.util.Map.entry("Base ESP", "groups"), java.util.Map.entry("Shard Watch", "players"),
            java.util.Map.entry("Activated Spawners", "spawners"), java.util.Map.entry("Vanish Watch", "known"), java.util.Map.entry("Friends", "friends"),
            java.util.Map.entry("Trail Finder", "trails"), java.util.Map.entry("Structures", "found"), java.util.Map.entry("Entity Scan", "marked"),
            java.util.Map.entry("Palette Scan", "areas"), java.util.Map.entry("Hidden Players", "signals"), java.util.Map.entry("Stash Finder", "stashes"),
            java.util.Map.entry("Light Scan", "finds"), java.util.Map.entry("Base Finder", "places"), java.util.Map.entry("Find Log", "finds"),
            java.util.Map.entry("Deep Leaks", "finds"), java.util.Map.entry("Sky Fingerprint", "bases/hits"), java.util.Map.entry("New Chunks", "new/old"),
            java.util.Map.entry("Seed Crack", "found/needed"), java.util.Map.entry("Logout Spots", "spots"), java.util.Map.entry("Anchor Finder", "found"));

    /** Where Esc goes: null in a world (back to the game), the title screen when opened from its Settings button. */
    private final net.minecraft.client.gui.screens.Screen parent;

    public ClickGui() {
        this(null);
    }

    public ClickGui(net.minecraft.client.gui.screens.Screen parent) {
        super(Component.literal(Vantage.NAME));
        this.parent = parent;
    }

    /** Opens on the Settings page (the menu's own settings), for the title screen's button. */
    public static void showSettings() {
        ClickGuiModule m = Modules.get(ClickGuiModule.class);
        page = m;
        if (m != null) category = m.category;
        view = View.CATEGORY;
        chip = "";
    }

    private void finish() {
        minecraft.setScreen(parent);
    }

    /** Opens straight on a module's settings; used by the test rig. */
    /** For the rig: the colour picker of this setting open when the menu next draws. */
    public static void openColor(ColorSetting c) { COLOR_OPEN.add(c); }

    public static void expand(Module m) {
        page = m;
        if (m != null) { category = m.category; view = View.CATEGORY; }
    }

    /** Opens on the Configs page (saved setups); used by the test rig. */
    public static void showConfigs() {
        view = View.CONFIGS;
        page = null;
        chip = "";
    }

    /** Opens on the Active tab; used by the test rig. */
    public static void showActive() {
        view = View.ACTIVE;
        page = null;
        chip = "";
    }

    public static void show(Category c) {
        category = c;
        page = null;
        view = View.CATEGORY;
        chip = "";
    }

    /** Middle of something drawn in the last frame (a Module's row, a part's switch), for the test rig. */
    public float[] spot(Object key) {
        return spots.get(key);
    }

    /** True once everything has stopped moving, for the test rig. */
    public boolean settled() {
        return open() >= 1 && msSince(listAt) > 450 && scroll == shownScroll;
    }

    @Override public boolean isPauseScreen() { return false; }

    private ClickGuiModule gui() {
        return Modules.get(ClickGuiModule.class);
    }

    private boolean animated() {
        ClickGuiModule m = gui();
        return m == null || m.animations.on();
    }

    /** The window: 900x600 in a world, the whole screen when opened from the title. Set at the start of every frame. */
    private float W = WIN_W, H = WIN_H;

    private boolean full() {
        return parent != null;
    }

    /** Where the GENERAL block starts: under Active plus every category, whatever their number. */
    private static float genY() {
        return NAV_Y + (Category.values().length + 1) * NAV_STEP + 24;
    }

    private float zoom() {
        ClickGuiModule m = gui();
        return m == null ? 1f : m.size.f();
    }

    /** GUI units per design unit with the menu's own size applied. */
    private float scaleNow() {
        Ui.zoom = zoom();
        try { return Ui.scale(); } finally { Ui.zoom = 1f; }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float ease(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }

    private static float msSince(long nanos) {
        return (System.nanoTime() - nanos) / 1e6f;
    }

    private float anim(Map<Object, Float> map, Object key, float target, float rate) {
        if (!animated()) return target;
        Float now = map.get(key);
        if (now == null) { map.put(key, target); return target; }
        float v = now + (target - now) * (1 - (float) Math.exp(-rate * dt));
        if (Math.abs(target - v) < 0.002f) v = target;
        map.put(key, v);
        return v;
    }

    /** 1 while fully open, 0 while gone. */
    private float open() {
        if (!animated()) return 1;
        if (openStart == 0) return 0;
        float t = ease(clamp(msSince(openStart) / OPEN_MS));
        return closeStart == 0 ? t : t * (1 - clamp(msSince(closeStart) / CLOSE_MS));
    }

    /** How far row number i has come in: rows arrive one after the other. */
    private float enter() {
        int i = Math.min(seq++, 14);
        if (!animated()) return 1;
        return ease(clamp((msSince(listAt) - i * 14) / 170));
    }

    private void go(int direction) {
        slide = direction;
        listAt = System.nanoTime();
        scroll = shownScroll = 0;
    }

    private void openPage(Module m) {
        listScroll = scroll;
        page = m;
        go(1);
    }

    private void back() {
        float was = listScroll;
        page = null;
        go(-1);
        scroll = shownScroll = was;
    }

    private void select(View v, Category c) {
        view = v;
        if (c != null) category = c;
        page = null;
        search = "";
        chip = "";
        go(0);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (minecraft.level == null) { renderPanorama(g, delta); g.fill(0, 0, width, height, 0x66050817); }
        ClickGuiModule m = gui();
        if (m != null && m.blur.on()) g.fill(0, 0, width, height, Ui.fade(Modules.betaUnlocked() ? 0x90050817 : 0x58050817, open()));
    }

    private boolean hot(float x, float y, float w, float h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= clipTop && mouseY < clipBottom;
    }

    /** A click area, cut to what's visible so scrolled away rows can't be pressed. */
    private void hit(float x, float y, float w, float h, Click run) {
        float top = Math.max(y, clipTop), bottom = Math.min(y + h, clipBottom);
        if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, run));
    }

    private void pushClip(GuiGraphics g, float x, float y, float w, float h) {
        clips.push(new float[] {clipTop, clipBottom});
        clipTop = Math.max(clipTop, y);
        clipBottom = Math.min(clipBottom, y + h);
        g.enableScissor((int) x, (int) y, (int) Math.ceil(x + w), (int) Math.ceil(y + h));
    }

    private void popClip(GuiGraphics g) {
        g.disableScissor();
        float[] c = clips.pop();
        clipTop = c[0];
        clipBottom = c[1];
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        long now = System.nanoTime();
        if (openStart == 0) { openStart = now; listAt = now + 60_000_000L; lastFrame = now; }
        dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float t = open();
        if (closeStart != 0 && t <= 0) {
            if (!closed) { closed = true; minecraft.execute(this::finish); }
            return;
        }
        Ui.zoom = zoom();
        float s = Ui.scale();
        mouseX = mx / s;
        mouseY = my / s;
        hits.clear();
        wheels.clear();
        spots.clear();
        seq = 0;
        help = null;
        tip = null;
        lastMs.clear();
        drawnFrames++;
        Ui.begin(g);
        Ui.alpha = t;
        try {
            W = full() ? Ui.W() : WIN_W;
            H = full() ? Ui.H() : WIN_H;
            float x = (Ui.W() - W) / 2, y = (Ui.H() - H) / 2 + 12 * (1 - t);
            float r = full() ? 0 : 14;
            Ui.rr(g, x, y, W, H, r, Ui.WIN, Ui.WIN_LINE);
            Ui.rr(g, x + 1, y + 1, SIDE - 1, H - 2, Math.max(0, r - 1), Ui.SIDE, 0);
            Ui.rect(g, x + SIDE - 14, y + 1, 14, H - 2, Ui.SIDE); // square off the sidebar's inner edge
            sidebar(g, x, y);
            Ui.vline(g, x + SIDE, y + 1, H - 2, Ui.WIN_LINE);
            float cx = x + SIDE + 24, cw = W - SIDE - 48;
            ClickGuiModule m = gui();
            boolean beta = Modules.betaUnlocked();
            if (beta && minecraft.level != null && !hudHid) { hudWas = minecraft.options.hideGui; minecraft.options.hideGui = true; hudHid = true; } // beta: the game HUD stays out of the way
            if (hudHid) dev.vantage.VantageClient.screenFrame();
            boolean prompt = listening != null || editing != null || (view == View.CONFIGS && page == null && (cfgRename != null || !cfgName.isEmpty()));
            boolean foot = beta ? prompt : m == null || m.descriptions.on() || listening != null; // beta: the help line only says things the card does not already say
            float bottom = foot ? FOOT : 12;
            float top = page != null ? 92 : view == View.CONFIGS ? 140 : 106;
            header(g, cx, y, cw);
            shownScroll += (scroll - shownScroll) * (animated() ? 1 - (float) Math.exp(-GLIDE * dt) : 1);
            if (Math.abs(scroll - shownScroll) < 0.05f) shownScroll = scroll;
            float listH = H - top - bottom;
            pushClip(g, cx - 6, y + top, cw + 12, listH);
            float end = page != null ? settingsPage(g, cx, y + top - shownScroll, cw)
                    : view == View.CONFIGS ? configList(g, cx, y + top - shownScroll, cw) : moduleList(g, cx, y + top - shownScroll, cw);
            popClip(g);
            float content = end - (y + top - shownScroll);
            maxScroll = Math.max(0, content - listH);
            scroll = Math.min(scroll, maxScroll);
            scrollbar(g, x + W - 12, y + top, listH, content);
            if (foot) footer(g, cx, y + H - FOOT, cw);
            footerShown = foot;
            if (tip != null) tooltip(g, tip);
        } finally {
            Ui.alpha = 1f;
            Ui.end(g);
            Ui.zoom = 1f;
        }
    }

    /** Thin thumb at the right edge; grows under the mouse and can be dragged. */
    private void scrollbar(GuiGraphics g, float x, float y, float h, float content) {
        float t = anim(misc, "bar", maxScroll > 0 ? 1 : 0, 12);
        trackY = y;
        trackH = h;
        if (t <= 0 || content <= 0) { thumbH = 0; return; }
        thumbH = Math.max(24, h * h / content);
        float at = maxScroll <= 0 ? 0 : shownScroll / maxScroll;
        boolean over = hot(x - 6, y, 14, h) || !Float.isNaN(barGrab);
        float wide = anim(misc, "barW", over ? 1 : 0, 16);
        float ty = y + (h - thumbH) * clamp(at), tw = 3 + 2 * wide;
        Ui.rr(g, x + 1.5f - tw / 2, ty, tw, thumbH, tw / 2, Ui.fade(Colors.mix(Ui.tone(0xFF343A66), Ui.tone(0xFF4A4F86), wide), t), 0);
        hit(x - 6, y, 14, h, b -> {
            float cur = y + (h - thumbH) * clamp(maxScroll <= 0 ? 0 : scroll / maxScroll);
            barGrab = mouseY >= cur && mouseY < cur + thumbH ? mouseY - cur : thumbH / 2;
            barTo(mouseY);
        });
    }

    private void barTo(float my) {
        float room = trackH - thumbH;
        if (room <= 0) return;
        scroll = Math.max(0, Math.min(maxScroll, (my - barGrab - trackY) / room * maxScroll));
    }

    /** Only speaks when there is something to say: the key prompt, or the description of what the mouse is over. */
    /** A small tip pill next to the mouse. */
    private void tooltip(GuiGraphics g, String t) {
        float w = Ui.width(t, 0.82f, false) + 16, x = Math.min(mouseX + 14, Ui.W() - w - 8), y = mouseY + 18;
        Ui.rr(g, x, y, w, 20, 6, Ui.GLASS, Ui.BORDER);
        Ui.text(g, t, x + 8, y + 6, Ui.TEXT, 0.82f, false);
    }

    @Override
    public void removed() {
        if (hudHid) { minecraft.options.hideGui = hudWas; hudHid = false; }
        super.removed();
    }

    /** What the number or word in a module's card corner means (beta): "27 storage boxes drawn now", or the module's own status text. */
    public static String infoTip(Module m, String info) {
        String label = INFO_LABEL.get(m.name);
        if (label != null && info.matches("\\d+")) return info + " " + label + " right now (" + m.name + ")";
        if (label != null && info.matches("\\d+/\\d+")) return info + " " + label + " (" + m.name + ")";
        return m.name + ": " + info;
    }

    private void footer(GuiGraphics g, float x, float y, float w) {
        Ui.hline(g, x, y, w, Ui.WIN_LINE);
        boolean cfg = view == View.CONFIGS && page == null;
        String t = listening != null ? "Press a key for " + listening.name + "  ·  esc clears it" : editing != null ? "Typing " + editing.name + "  ·  enter or esc when done"
                : cfg && cfgRename != null ? "Typing a new name  ·  enter renames it, esc cancels"
                : cfg && !cfgName.isEmpty() ? "Enter saves everything as it is now as \"" + cfgName + "\"  ·  esc clears" : help;
        if (t == null) return;
        Ui.text(g, Ui.fit(t, w, 0.84f), x, y + 10, listening != null ? Ui.YELLOW : Ui.SUB, 0.84f, false);
    }

    // ---- sidebar --------------------------------------------------------------------------------

    private void sidebar(GuiGraphics g, float x, float y) {
        // the mark: two accent chevrons pointing up, then the name
        Ui.chevron(g, x + 20, y + 20, 14, 3, Ui.ACCENT);
        Ui.chevron(g, x + 20, y + 26, 14, 3, Ui.fade(Ui.ACCENT, 0.45f));
        Ui.serif(g, Vantage.NAME, x + 44, y + 13, Ui.TEXT, 1.75f, false);
        Ui.text(g, RELEASE, x + 45, y + 38, Ui.FAINT, 0.66f, false);

        float nx = x + 10, nw = SIDE - 20;
        power(g, nx, y + POWER_Y, nw);
        Ui.text(g, "MODULES", x + 20, y + NAV_Y - 16, Ui.FAINT, 0.68f, false);
        // the highlight slides from one entry to the next
        float targetY = y + (page instanceof ClickGuiModule ? genY() : view == View.KEYBINDS ? genY() + GEN_STEP
                : view == View.CONFIGS ? genY() + 2 * GEN_STEP : NAV_Y + (view == View.ACTIVE ? 0 : category.ordinal() + 1) * NAV_STEP);
        float targetH = page instanceof ClickGuiModule || view == View.KEYBINDS || view == View.CONFIGS ? GEN_H : NAV_H;
        boolean showPill = search.isEmpty() || page != null;
        float py = anim(misc, "pill", targetY, 20), ph = anim(misc, "pillH", targetH, 20), pa = anim(misc, "pillA", showPill ? 1 : 0, 16);
        if (pa > 0) Ui.rr(g, nx, py, nw, ph, 7, Ui.fade(Ui.SELECT, pa), 0);
        long on = Modules.all().stream().filter(m -> m.owner == null && m.on() && !(m instanceof ClickGuiModule)).count();
        float ny = y + NAV_Y;
        navItem(g, "Active", on, "active", "active", view == View.ACTIVE && page == null && showPill, nx, ny, nw, NAV_H, () -> select(View.ACTIVE, null));
        for (Category c : Category.values()) {
            ny += NAV_STEP;
            long n = Modules.in(c).stream().filter(m -> !(m instanceof ClickGuiModule)).count();
            navItem(g, c.title, n, c, c.name().toLowerCase(Locale.ROOT), view == View.CATEGORY && c == category && page == null && showPill, nx, ny, nw, NAV_H, () -> select(View.CATEGORY, c));
        }
        Ui.text(g, "GENERAL", x + 20, y + genY() - 16, Ui.FAINT, 0.68f, false);
        float gy = y + genY();
        long keys = Modules.all().stream().filter(m -> m.owner == null && m.bind != -1).count();
        navItem(g, GENERAL[0], 0, "settings", "settings", page instanceof ClickGuiModule, nx, gy, nw, GEN_H, () -> { view = View.CATEGORY; search = ""; openPage(gui()); });
        navItem(g, GENERAL[1], keys, "keybinds", "keybinds", view == View.KEYBINDS && page == null, nx, gy + GEN_STEP, nw, GEN_H, () -> select(View.KEYBINDS, null));
        navItem(g, GENERAL[2], Profiles.count(), "configs", "configs", view == View.CONFIGS && page == null, nx, gy + 2 * GEN_STEP, nw, GEN_H, () -> { cfgRename = null; cfgArm = null; select(View.CONFIGS, null); });
        navItem(g, GENERAL[3], dev.vantage.core.Friends.list().size(), "friends", "friends", false, nx, gy + 3 * GEN_STEP, nw, GEN_H, () -> minecraft.setScreen(new FriendsScreen(this)));
        navItem(g, GENERAL[4], 0, "waypoints", "waypoints", false, nx, gy + 4 * GEN_STEP, nw, GEN_H, () -> minecraft.setScreen(new WaypointScreen(this)));
        navItem(g, GENERAL[5], Config.PANELS.size(), "hudlayout", "render", false, nx, gy + 5 * GEN_STEP, nw, GEN_H, () -> minecraft.setScreen(new HudEditScreen(null)));
    }

    /** The master switch, first thing under the name: off puts every module to sleep, on brings the same set back. */
    private void power(GuiGraphics g, float x, float y, float w) {
        boolean on = !Modules.paused();
        float st = anim(onOff, "power", on ? 1 : 0, 14), hv = anim(hover, "power", hot(x, y, w, POWER_H) ? 1 : 0, 18);
        Ui.rr(g, x, y, w, POWER_H, 8, Colors.mix(Colors.mix(0x30101522, 0x28FFFFFF, hv), Colors.mix(0x3A2A2860, 0x4A3A3880, hv), st), Colors.mix(0xFF3A2E3A, Ui.tone(0xFF3B3786), st));
        int c = Colors.mix(Ui.YELLOW, Ui.TEXT, st);
        Ui.rr(g, x + 13, y + 9, 12, 12, 6, 0, c);
        Ui.rect(g, x + 18.3f, y + 7, 1.5f, 6, Colors.mix(0xFF2A2A3A, Ui.tone(0xFF1D1C42), st));
        Ui.rect(g, x + 18.3f, y + 7.5f, 1.5f, 5.5f, c);
        Ui.text(g, on ? "Client on" : "Client off", x + 33, y + (POWER_H - 9) / 2, c, 1.02f, true);
        Ui.toggle(g, x + w - 38, y + (POWER_H - 15) / 2, st);
        spots.put("power", new float[] {x + w / 2, y + POWER_H / 2});
        hit(x, y, w, POWER_H, b -> { Modules.setPaused(!Modules.paused()); go(0); });
    }

    private void navItem(GuiGraphics g, String name, long count, Object key, String icon, boolean sel, float x, float y, float w, float h, Runnable run) {
        float hv = anim(hover, key, hot(x, y, w, h) && !sel ? 1 : 0, 18);
        float st = anim(onOff, key, sel ? 1 : 0, 18);
        if (hv > 0) Ui.rr(g, x, y, w, h, 7, Ui.fade(0x12FFFFFF, hv), 0);
        int c = Colors.mix(Ui.SUB, Ui.TEXT, Math.max(st, hv));
        Icons.draw(g, icon, x + 12 + 2 * hv, y + (h - 13) / 2, 13, c);
        Ui.text(g, name, x + 33 + 2 * hv, y + (h - 9) / 2, c, 1.02f, false);
        if (count > 0) Ui.right(g, String.valueOf(count), x + w - 11, y + (h - 9) / 2 + 0.5f, Colors.mix(Ui.FAINT, Ui.TITLE, st), 0.82f, false);
        spots.put(key, new float[] {x + w / 2, y + h / 2});
        hit(x, y, w, h, b -> run.run());
    }

    // ---- header ---------------------------------------------------------------------------------

    private void headline(GuiGraphics g, String italic, String rest, float x, float y) {
        float w = Ui.serif(g, italic, x, y, Ui.TITLE, 1.95f, true);
        if (!rest.isEmpty()) Ui.serif(g, rest, x + w + 5, y, Ui.TEXT, 1.95f, false);
    }

    private void header(GuiGraphics g, float x, float y, float w) {
        if (page != null) {
            boolean general = page instanceof ClickGuiModule; // the menu's own page is "Settings", reached from the sidebar, not a module of a category
            String crumb = view == View.KEYBINDS ? "Keybinds" : general ? "Back" : page.category.title;
            float bw = Ui.width(crumb, 0.86f, false) + 30;
            float hv = anim(hover, "back", hot(x, y + 14, bw, 20) ? 1 : 0, 18);
            Ui.rr(g, x, y + 14, bw, 20, 10, Colors.mix(0x30101522, 0x28FFFFFF, hv), Ui.tone(0xFF262A52));
            int bc = Colors.mix(Ui.SUB, Ui.TEXT, hv);
            Ui.chevron(g, x + 10 - 2 * hv, y + 20, 8, 2, bc);
            Ui.text(g, crumb, x + 20, y + 20, bc, 0.86f, false);
            hit(x, y + 14, bw, 20, b -> back());
            float nameW = Ui.serif(g, general ? "Settings" : page.name, x, y + 38, Ui.TEXT, 2f, false);
            betaTag(g, page, x + nameW + 10, y + 46);
            Ui.text(g, Ui.fit(general ? "The menu key, the look, updates and sharing" : page.description, w - 60, 0.84f), x, y + 68, Ui.FAINT, 0.84f, false);
            if (!(page instanceof ClickGuiModule)) {
                Module m = page;
                Ui.toggle(g, x + w - 30, y + 46, anim(onOff, m, m.on() ? 1 : 0, 16));
                spots.put("master", new float[] {x + w - 15, y + 53});
                hit(x + w - 34, y + 42, 38, 23, b -> m.toggle());
            }
            return;
        }
        if (view == View.CONFIGS) { configsHeader(g, x, y, w); return; }
        List<Module> mods = shown();
        if (!search.isEmpty()) headline(g, "Search", "results", x, y + 20);
        else if (view == View.ACTIVE) headline(g, "Active", "modules", x, y + 20);
        else if (view == View.KEYBINDS) headline(g, "Keybinds", "", x, y + 20);
        else headline(g, category.title, "Modules", x, y + 20);
        long on = mods.stream().filter(Module::on).count();
        String count = !search.isEmpty() ? mods.size() + " found" : view == View.KEYBINDS ? mods.size() + " keys" : mods.size() + " modules  ·  " + on + " enabled";
        Ui.text(g, count, x + 1, y + 52, Ui.FAINT, 0.84f, false);

        // search pill
        float sw = 220, sx = x + w - sw;
        float focus = anim(misc, "search", search.isEmpty() ? 0 : 1, 16);
        Ui.rr(g, sx, y + 23, sw, 28, 14, 0x40101522, Colors.mix(Ui.tone(0xFF262A52), Ui.ACCENT, focus));
        int glass = Colors.mix(Ui.FAINT, Ui.TEXT, focus);
        Icons.draw(g, "finder", sx + 11, y + 30.5f, 13, glass);
        boolean caret = !search.isEmpty() && System.currentTimeMillis() / 500 % 2 == 0;
        Ui.text(g, search.isEmpty() ? "Search modules" : search + (caret ? "_" : ""), sx + 30, y + 32, search.isEmpty() ? Ui.FAINT : Ui.TEXT, 0.94f, false);

        // section chips and the list / grid switch
        float ry = y + 73;
        if (view == View.CATEGORY && search.isEmpty()) {
            List<String> secs = sections();
            if (secs.size() > 1) {
                float cx = x;
                cx = chipAt(g, "All", "", cx, ry);
                for (String s : secs) cx = chipAt(g, s, s, cx, ry);
            }
        }
        viewButton(g, x + w - 46, ry, false);
        viewButton(g, x + w - 22, ry, true);
    }

    private float chipAt(GuiGraphics g, String label, String key, float x, float y) {
        boolean sel = chip.equals(key);
        float st = anim(onOff, "chip" + key, sel ? 1 : 0, 18), hv = anim(hover, "chip" + key, hot(x, y, 10, 20) ? 1 : 0, 18);
        float w = Ui.width(label, 0.84f, false) + 20;
        hv = anim(hover, "chip" + key, hot(x, y, w, 20) ? 1 : 0, 18);
        Ui.rr(g, x, y, w, 20, 10, Colors.mix(Colors.mix(0x30101522, 0x28FFFFFF, hv), Ui.SELECT, st), Colors.mix(Ui.tone(0xFF262A52), Ui.tone(0xFF3B3786), st));
        Ui.text(g, label, x + 10, y + 6, Colors.mix(Ui.SUB, Ui.TEXT, Math.max(st, hv)), 0.84f, false);
        hit(x, y, w, 20, b -> { chip = sel ? "" : key; go(0); });
        return x + w + 6;
    }

    /** The two little pictograms: three lines for the list, four squares for the grid. */
    private void viewButton(GuiGraphics g, float x, float y, boolean isGrid) {
        boolean sel = grid == isGrid;
        String key = isGrid ? "viewGrid" : "viewList";
        float st = anim(onOff, key, sel ? 1 : 0, 18), hv = anim(hover, key, hot(x, y, 20, 20) ? 1 : 0, 18);
        Ui.rr(g, x, y, 20, 20, 6, Colors.mix(Ui.fade(0x28FFFFFF, hv), Ui.SELECT, st), 0);
        int c = Colors.mix(Ui.FAINT, Ui.TEXT, Math.max(st, hv));
        if (isGrid) {
            for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) Ui.rr(g, x + 5 + i * 5.5f, y + 5 + j * 5.5f, 4.5f, 4.5f, 1.2f, c, 0);
        } else {
            for (int i = 0; i < 3; i++) Ui.rr(g, x + 5, y + 5.5f + i * 3.8f, 10, 1.8f, 0.9f, c, 0);
        }
        spots.put(key, new float[] {x + 10, y + 10});
        hit(x, y, 20, 20, b -> { grid = isGrid; go(0); });
    }

    // ---- module list ----------------------------------------------------------------------------

    /** Sections of the Cosmetic page (and any module that sets no group of its own), in the order they are listed. Camera and Hands only
     *  still serve the Render page's Hand View; every cosmetic sits in one of World, Combat, Movement, Hand, Screen, Companion. */
    private static final String[] GROUP_ORDER = {"Camera", "Hands", "World", "Combat", "Movement", "Hand", "Screen", "Companion", "Effects", "HUD"};
    private static final java.util.Map<String, String> GROUPS = java.util.Map.ofEntries(
            java.util.Map.entry("Freelook", "Camera"), java.util.Map.entry("Hand View", "Hands"),
            // World: the sky, the light, what you dig and open
            java.util.Map.entry("Atmosphere", "World"), java.util.Map.entry("Time Changer", "World"), java.util.Map.entry("Block Outline", "World"),
            java.util.Map.entry("Dig Burst", "World"), java.util.Map.entry("Loot Sparkle", "World"), java.util.Map.entry("Find Pulse", "World"),
            // Combat: what happens when you hit and kill
            java.util.Map.entry("Hit Colour", "Combat"), java.util.Map.entry("Hit Particles", "Combat"), java.util.Map.entry("Totem Spirit", "Combat"),
            java.util.Map.entry("Blade Trail", "Combat"), java.util.Map.entry("Damage Numbers", "Combat"), java.util.Map.entry("Kill Effect", "Combat"),
            // Movement: jumps, steps, landings
            java.util.Map.entry("Jump Circles", "Movement"), java.util.Map.entry("Footprints", "Movement"), java.util.Map.entry("Landing Ring", "Movement"),
            // Hand: first-person hands and the held item
            java.util.Map.entry("3D Hand", "Hand"), java.util.Map.entry("Hand Aura", "Hand"), java.util.Map.entry("Swing Animation", "Hand"), java.util.Map.entry("Custom Glint", "Hand"),
            // Screen: what is drawn over the picture
            java.util.Map.entry("Crosshair", "Screen"), java.util.Map.entry("Low Health Warning", "Screen"), java.util.Map.entry("Colour Grading", "Screen"),
            java.util.Map.entry("Color Grading", "Screen"), java.util.Map.entry("Motion Blur", "Screen"), java.util.Map.entry("Zoom", "Screen"),
            java.util.Map.entry("Custom FOV", "Screen"), java.util.Map.entry("Pay Pop", "Screen"),
            // Companion: things that keep you company
            java.util.Map.entry("Orbs", "Companion"), java.util.Map.entry("Pet", "Companion"));

    public static String groupOf(Module m) {
        String g = GROUPS.get(m.name);
        return g != null ? g : m.group == null ? "" : m.group;
    }

    private static int groupOrder(Module m) {
        String gr = groupOf(m);
        for (int i = 0; i < GROUP_ORDER.length; i++) if (GROUP_ORDER[i].equals(gr)) return i;
        return gr.isEmpty() ? 99 : 50;
    }

    /** For the rig: how many sections a category shows. */
    public static int groupsIn(Category c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Module m : Modules.in(c)) if (!groupOf(m).isEmpty()) out.add(groupOf(m));
        return out.size();
    }

    private List<String> sections() {
        List<String> out = new ArrayList<>();
        for (Module m : Modules.in(category).stream().sorted(java.util.Comparator.comparingInt(ClickGui::groupOrder)).toList()) {
            String gr = groupOf(m);
            if (!gr.isEmpty() && !out.contains(gr)) out.add(gr);
        }
        return out;
    }

    /** A row of the Active tab: a module that is on, or whose scanner runs by hand (beta). */
    public static boolean activeRow(Module m) {
        return m.owner == null && (m.on() || m.hasHandRunning()) && !(m instanceof ClickGuiModule);
    }

    private List<Module> shown() {
        if (!search.isEmpty()) {
            String q = search.toLowerCase(Locale.ROOT);
            return Modules.all().stream().filter(m -> m.owner == null && (matches(m, q)
                    || m.visibleParts().stream().anyMatch(p -> p.toggle().name.toLowerCase(Locale.ROOT).contains(q) || matches(p.module(), q)))).toList();
        }
        if (view == View.ACTIVE) return withFavourites(Modules.all().stream().filter(ClickGui::activeRow).toList(),
                Modules.all().stream().filter(m -> m.owner == null && Favourites.is(m) && !(m instanceof ClickGuiModule)).toList());
        if (view == View.KEYBINDS) return Modules.all().stream().filter(m -> m.owner == null && m.bind != -1).toList();
        List<Module> list = Modules.in(category).stream().sorted(java.util.Comparator.comparingInt(ClickGui::groupOrder)).toList();
        if (!chip.isEmpty()) list = list.stream().filter(m -> groupOf(m).equals(chip)).toList();
        return withFavourites(list, list.stream().filter(Favourites::is).toList());
    }

    /** The starred ones first (under their own "Favourites" title), then the rest in their usual order. */
    private static List<Module> withFavourites(List<Module> rest, List<Module> favs) {
        if (favs.isEmpty()) return rest;
        List<Module> out = new ArrayList<>(favs);
        for (Module m : rest) if (!favs.contains(m)) out.add(m);
        return out;
    }

    /** Is this module drawn in the Favourites section right now (for the rig). */
    public boolean inFavourites(Module m) { return FAV.equals(sectionOf(m)); }

    private static final String FAV = "Favourites";

    private static boolean matches(Module m, String q) {
        return m.name.toLowerCase(Locale.ROOT).contains(q) || m.description.toLowerCase(Locale.ROOT).contains(q);
    }

    private String sectionOf(Module m) {
        if (!search.isEmpty()) return "";
        if ((view == View.ACTIVE || view == View.CATEGORY) && Favourites.is(m) && !(m instanceof ClickGuiModule)) return FAV;
        return view != View.CATEGORY ? m.category.title : groupOf(m);
    }

    private float moduleList(GuiGraphics g, float x, float y, float w) {
        List<Module> list = shown();
        if (list.isEmpty()) {
            float a = Ui.alpha;
            Ui.alpha = a * enter();
            Ui.text(g, view == View.ACTIVE ? "Nothing on yet" : view == View.KEYBINDS ? "No keys set. Middle click a module to give it one" : "No module with that name", x, y + 12, Ui.FAINT, 1f, false);
            Ui.alpha = a;
            return y + 30;
        }
        boolean titles = list.stream().map(this::sectionOf).distinct().count() > 1 || list.stream().anyMatch(m -> FAV.equals(sectionOf(m)));
        float cw = (w - GAP) / 2, ry = y;
        int col = 0;
        String last = null;
        for (Module m : list) {
            String sec = sectionOf(m);
            if (titles && !sec.equals(last)) {
                if (col == 1) { ry += ROW; col = 0; }
                if (last != null) ry += 6;
                sectionTitle(g, sec, x, ry, w);
                ry += HEAD - 4;
            }
            last = sec;
            if (grid) {
                moduleRow(g, m, x + col * (cw + GAP), ry, cw);
                if (col == 1) { ry += ROW; col = 0; } else col = 1;
            } else {
                card(g, m, x, ry, w);
                ry += CARD + CGAP;
            }
        }
        return ry + (col == 1 ? ROW : 0) + 2;
    }

    private void sectionTitle(GuiGraphics g, String name, float x, float y, float w) {
        float a = Ui.alpha, t = enter();
        Ui.alpha = a * t;
        String up = name.toUpperCase(Locale.ROOT);
        Ui.text(g, up, x + 2, y + 7, Ui.FAINT, 0.76f, false);
        Ui.hline(g, x + Ui.width(up, 0.76f, false) + 10, y + 10, w - Ui.width(up, 0.76f, false) - 10, Ui.WIN_LINE);
        Ui.alpha = a;
    }

    private float keyChip(GuiGraphics g, int bind, float x, float y) {
        String k = Keys.name(bind);
        float kw = Ui.width(k, 0.72f, false) + 8;
        Ui.rr(g, x, y, kw, 12, 3, 0x1CFFFFFF, 0x30FFFFFF);
        Ui.text(g, k, x + 4, y + 3, Ui.SUB, 0.72f, false);
        return kw;
    }

    /** The small BETA label (same pill as the config ACTIVE tag); returns the width it took, 0 for a normal module. */
    private float betaTag(GuiGraphics g, Module m, float x, float y) {
        if (!m.isBeta()) return 0;
        float tw = Ui.width("BETA", 0.66f, false) + 10;
        Ui.rr(g, x, y, tw, 12, 6, Ui.fade(Ui.ACCENT, 0.18f), Ui.fade(Ui.ACCENT, 0.7f));
        Ui.text(g, "BETA", x + 5, y + 3, Ui.ACCENT, 0.66f, false);
        spots.put("beta:" + m.name, new float[] {x + tw / 2, y + 6});
        return tw + 6;
    }

    /** One module: name, key, description, info, chevron, switch. Enabled ones carry an accent bar on the left. */
    private void card(GuiGraphics g, Module m, float x, float y, float w) {
        float t = enter(), a = Ui.alpha;
        boolean always = m instanceof ClickGuiModule;
        boolean visible = y + CARD > clipTop && y < clipBottom;
        boolean over = hot(x, y, w, CARD);
        float hv = anim(hover, m, over ? 1 : 0, 18), on = anim(onOff, m, m.on() || always ? 1 : 0, 16);
        if (over) help = Modules.betaUnlocked() ? null : m.description;
        spots.put(m, new float[] {x + 40, y + CARD / 2});
        if (!visible) return;
        float dx = 12 * slide * (1 - t), dy = slide == 0 ? 8 * (1 - t) : 0, px = x + dx, py = y + dy;
        Ui.alpha = a * t;
        int bg = Colors.mix(Colors.mix(Ui.CARD, Ui.tone(0xF0191838), hv), Colors.mix(Ui.tone(0xF01B1A42), Ui.tone(0xF0201E4C), hv), on);
        Ui.rr(g, px, py, w, CARD, 8, bg, Colors.mix(Ui.CARD_LINE, Ui.tone(0xFF3B3786), on));
        if (on > 0) Ui.rr(g, px + 1.5f, py + 12, 3, CARD - 24, 1.5f, Ui.fade(Ui.ACCENT, on), 0);
        float arrowX = px + w - (always ? 24 : 60);
        float tx = px + 18;
        Ui.text(g, m.name, tx, py + 12, Colors.mix(0xFFD6DAEC, Ui.TEXT, Math.max(on, hv)), 1.12f, true);
        tx += Ui.width(m.name, 1.12f, true) + 8;
        tx += betaTag(g, m, tx, py + 12);
        if (m.bind != -1) tx += keyChip(g, m.bind, tx, py + 12) + 6;
        String info = m.info();
        boolean beta = Modules.betaUnlocked();
        float starGap = always ? 0 : beta ? 34 : 20; // beta: more air between the number, the star, the chevron and the switch
        if (info != null) {
            String shown = Ui.fit(info, arrowX - tx - 10 - starGap, 0.82f);
            Ui.right(g, shown, arrowX - 10 - starGap, py + 14, Ui.FAINT, 0.82f, false);
            if (beta) {
                String lab = INFO_LABEL.get(m.name);
                if (lab != null && info.matches("[0-9/]+")) Ui.right(g, lab, arrowX - 10 - starGap, py + 26, Ui.FAINT, 0.6f, false);
                float iw = Ui.width(shown, 0.82f, false);
                if (hot(arrowX - 10 - starGap - iw - 4, py + 8, iw + 8, 24)) { tip = infoTip(m, info); help = null; spots.put("info:" + m.name, new float[] {arrowX - 10 - starGap - iw / 2, py + 18}); }
            }
        }
        var fb = Modules.get(dev.vantage.module.client.FrameBudget.class);
        if (beta && !always && view == View.ACTIVE && fb != null && fb.on() && fb.column.on()) {
            // Frame Budget (beta): what the module costs per frame, parts included
            double ms = dev.vantage.core.Perf.msFor(m.name);
            for (Module.Part pt : m.parts) ms += dev.vantage.core.Perf.msFor(pt.module().name);
            String mt = String.format(Locale.ROOT, "%.2f ms", ms);
            float iw2 = info == null ? 0 : Ui.width(Ui.fit(info, arrowX - tx - 10 - starGap, 0.82f), 0.82f, false) + 16;
            Ui.right(g, mt, arrowX - 10 - starGap - iw2, py + 14, ms >= 1 ? Ui.YELLOW : Ui.FAINT, 0.82f, false);
            spots.put("ms:" + m.name, new float[] {arrowX - 10 - starGap - iw2 - 20, py + 18});
            lastMs.put(m.name, ms);
        }
        Ui.text(g, Ui.fit(m.description, arrowX - px - 30, 0.82f), px + 18, py + 32, Colors.mix(Ui.FAINT, Ui.SUB, hv), 0.82f, false);
        if (!always) star(g, m, arrowX - (beta ? 22 : 12), py + 17, hv);
        Ui.chevron(g, arrowX + 2 * hv, py + CARD / 2 - 5, 10, 0, Colors.mix(Ui.FAINT, Ui.TEXT, hv));
        if (!always) Ui.toggle(g, px + w - 42, py + CARD / 2 - 7.5f, on);
        Ui.alpha = a;
        hit(x, y, w, CARD, b -> {
            boolean arrow = mouseX >= x + w - 66 && mouseX < x + w - 46;
            if (b == 2) listening = m;
            else if (b == 1 || arrow || always) openPage(m);
            else m.toggle();
        });
        if (!always) starHit(m, arrowX - (beta ? 22 : 12), y, CARD);
    }

    /** The star of a module: gold when it is a favourite, a faint outline otherwise that lights up with the card. */
    private void star(GuiGraphics g, Module m, float cx, float cy, float hv) {
        boolean fav = Favourites.is(m);
        float over = anim(misc, "star:" + m.name, hot(cx - 9, cy - 12, 18, 24) ? 1 : 0, 18);
        boolean beta = Modules.betaUnlocked();
        int col = fav ? Colors.mix(Ui.YELLOW, 0xFFFFE9A0, over) : Ui.fade(Colors.mix(beta ? Ui.SUB : Ui.FAINT, Ui.YELLOW, over), (beta ? 0.7f : 0.35f) + 0.3f * Math.max(hv, over));
        Ui.center(g, fav ? "\u2605" : "\u2606", cx, cy - 7, col, beta ? 1.65f : 1.5f, false);
        spots.put("star:" + m.name, new float[] {cx, cy});
    }

    private void starHit(Module m, float cx, float y, float h) {
        hit(cx - 9, y, 18, h, b -> Favourites.toggle(m));
    }

    /** The compact two-column row of the grid view. */
    private void moduleRow(GuiGraphics g, Module m, float x, float y, float w) {
        float t = enter(), a = Ui.alpha;
        boolean always = m instanceof ClickGuiModule;
        boolean visible = y + ROW > clipTop && y < clipBottom;
        float h = ROW - 4;
        boolean over = hot(x, y, w, h);
        float hv = anim(hover, m, over ? 1 : 0, 18), on = anim(onOff, m, m.on() || always ? 1 : 0, 16);
        if (over) help = m.description;
        spots.put(m, new float[] {x + 40, y + h / 2});
        if (!visible) return;
        float dx = 12 * slide * (1 - t), dy = slide == 0 ? 8 * (1 - t) : 0, px = x + dx, py = y + dy;
        Ui.alpha = a * t;
        Ui.rr(g, px, py, w, h, 7, Colors.mix(Colors.mix(Ui.CARD, Ui.tone(0xF0191838), hv), Ui.tone(0xF01B1A42), on), Colors.mix(Ui.CARD_LINE, Ui.tone(0xFF3B3786), on));
        if (on > 0) Ui.rr(g, px + 1.5f, py + 9, 3, h - 18, 1.5f, Ui.fade(Ui.ACCENT, on), 0);
        Ui.text(g, m.name, px + 13, py + 12, Colors.mix(0xFFD6DAEC, Ui.TEXT, Math.max(on, hv)), 1.02f, true);
        float tx = px + 13 + Ui.width(m.name, 1.02f, true) + 7;
        tx += betaTag(g, m, tx, py + 10);
        if (m.bind != -1) tx += keyChip(g, m.bind, tx, py + 10) + 6;
        float arrowX = px + w - (always ? 16 : 54);
        String info = m.info();
        if (info != null) Ui.text(g, Ui.fit(info, arrowX - tx - 4 - (always ? 0 : 18), 0.8f), tx, py + 13, Ui.FAINT, 0.8f, false);
        if (!always) star(g, m, arrowX - 9, py + h / 2, hv);
        if (always || hv > 0) Ui.chevron(g, arrowX + 3 * hv, py + h / 2 - 5, 10, 0, Ui.fade(Ui.SUB, always ? 1 : hv));
        if (!always) Ui.toggle(g, px + w - 38, py + h / 2 - 7.5f, on);
        Ui.alpha = a;
        hit(x, y, w, h, b -> {
            boolean arrow = mouseX >= x + w - 60 && mouseX < x + w - 42;
            if (b == 2) listening = m;
            else if (b == 1 || arrow || always) openPage(m);
            else m.toggle();
        });
        if (!always) starHit(m, arrowX - 9, y, h);
    }

    // ---- settings page --------------------------------------------------------------------------

    private boolean ownSwitch(Module m, Setting<?> s) {
        for (Module.Part p : m.parts) if (p.toggle() == s) return true;
        return false;
    }

    private float heightOf(Module m) {
        float h = 0;
        for (Setting<?> s : m.settings) {
            if (!s.visible() || ownSwitch(m, s)) continue;
            h += s instanceof Header ? HEAD : SET;
            if (s instanceof ColorSetting c) h += PICK * misc.getOrDefault(c, COLOR_OPEN.contains(c) ? 1f : 0f);
        }
        return h;
    }

    private float settingsPage(GuiGraphics g, float x, float y, float w) {
        Module m = page;
        float ry = y;
        if (!m.visibleParts().isEmpty()) {
            sectionTitle(g, "Scanners", x, ry, w);
            ry += HEAD - 4;
            for (Module.Part p : m.visibleParts()) ry = partCard(g, m, p, x, ry, w);
            ry += 4;
        }
        ry = bindRow(g, m, x, ry, w);
        return settingList(g, m, x, ry, w);
    }

    /** A scanner inside the module: its switch, and its settings folded away under the arrow so every switch stays in view. */
    private float partCard(GuiGraphics g, Module owner, Module.Part p, float x, float y, float w) {
        float t = enter(), a = Ui.alpha;
        boolean beta = Modules.betaUnlocked();
        // beta: the switch is lit when the scanner really runs; the three states (off / with Base Finder / by hand) are spelled out on the right
        boolean runs = beta ? p.module().isEnabled() : p.toggle().on();
        float on = anim(onOff, p.toggle(), runs ? 1 : 0, 16);
        boolean unfolded = PART_OPEN.contains(p.toggle());
        float body = (heightOf(p.module()) + 6) * anim(misc, p, unfolded ? 1 : 0, 12);
        float dx = 12 * slide * (1 - t), cx = x + dx;
        Ui.alpha = a * t;
        Ui.rr(g, cx, y, w, PART + body, 8, Colors.mix(Ui.CARD, Ui.tone(0xF01B1A42), on), Colors.mix(Ui.CARD_LINE, Ui.tone(0xFF3B3786), on));
        if (on > 0) Ui.rr(g, cx + 1.5f, y + 9, 3, PART - 18, 1.5f, Ui.fade(Ui.ACCENT, on), 0);
        boolean over = hot(x, y, w, PART);
        if (over) help = p.toggle().description;
        Ui.text(g, p.toggle().name, cx + 14, y + 9, Colors.mix(0xFFD6DAEC, Ui.TEXT, on), 1.04f, true);
        Ui.text(g, Ui.fit(p.toggle().description, w - 230, 0.76f), cx + 14, y + 23, Ui.FAINT, 0.76f, false);
        String state = p.toggle().on() && !owner.on() ? "needs " + owner.name + " on" : p.module().info();
        int stateColor = p.toggle().on() && !owner.on() ? Ui.YELLOW : Ui.FAINT;
        if (beta) {
            String info = runs ? p.module().info() : null;
            state = switch (p.state()) {
                case OFF -> "off";
                case WITH -> owner.on() ? (runs ? "running with " + owner.name : "off (paused)") : "starts with " + owner.name;
                case HAND -> runs ? "running by hand" : "by hand (paused)";
            } + (info == null ? "" : " · " + info);
            stateColor = p.state() == Module.Part.State.HAND ? Ui.GREEN : p.state() == Module.Part.State.WITH && owner.on() ? Ui.TEXT : Ui.FAINT;
        }
        if (state != null) Ui.right(g, Ui.fit(state, w - 300, 0.8f), cx + w - 74, y + PART / 2 - 4, stateColor, 0.8f, false);
        Ui.toggle(g, cx + w - 42, y + PART / 2 - 7.5f, on);
        spots.put(p.toggle(), new float[] {x + w - 27, y + PART / 2});
        boolean arrowOver = hot(x + w - 66, y, 22, PART);
        float fold = anim(misc, "fold" + System.identityHashCode(p), unfolded ? 1 : 0, 14);
        Ui.chevron(g, cx + w - 60, y + PART / 2 - 5 + 2 * fold, 10, fold > 0.5f ? 1 : 0, arrowOver ? Ui.TEXT : Ui.SUB);
        spots.put(p.module(), new float[] {x + w - 55, y + PART / 2});
        hit(x, y, w, PART, b -> {
            boolean arrow = mouseX >= x + w - 66 && mouseX < x + w - 44;
            if (b == 1 || arrow) { if (!PART_OPEN.add(p.toggle())) PART_OPEN.remove(p.toggle()); }
            else if (beta) p.cycle();
            else p.toggle().toggle();
        });
        if (body > 0.5f) {
            pushClip(g, cx, y + PART, w, body);
            settingList(g, p.module(), cx + 4, y + PART, w - 8);
            popClip(g);
        }
        Ui.alpha = a;
        return y + PART + body + 8;
    }

    private float bindRow(GuiGraphics g, Module m, float x, float y, float w) {
        float t = enter(), a = Ui.alpha;
        Ui.alpha = a * t;
        float dx = 12 * slide * (1 - t), px = x + dx;
        String what = m.holdKey() ? "Held, it opens " + m.name : "Switches " + m.name + " on and off";
        rowBack(g, "bind", x, px, y, w, what);
        Ui.text(g, "Key", px + 10, y + 9, Ui.TEXT, 1f, false);
        Ui.text(g, what, px + 10, y + 23, Ui.FAINT, 0.76f, false);
        float bw = 80, bx = px + w - bw - 8;
        Ui.rr(g, bx, y + 10.5f, bw, 19, 5, Ui.FIELD, Ui.tone(0xFF262A52));
        Ui.center(g, listening == m ? "..." : Keys.name(m.bind), bx + bw / 2, y + 16.5f, listening == m ? Ui.YELLOW : Ui.TEXT, 0.85f, false);
        hit(bx, y + 10.5f, bw, 19, b -> listening = m);
        Ui.alpha = a;
        return y + SET;
    }

    private void rowBack(GuiGraphics g, Object key, float x, float px, float y, float w, String description) {
        boolean over = hot(x, y, w, SET - 2);
        float hv = anim(hover, key, over ? 1 : 0, 18);
        if (over && description != null && !description.isEmpty()) help = description;
        if (hv > 0) Ui.rr(g, px, y + 1, w, SET - 2, 6, Ui.fade(0x10FFFFFF, hv), 0);
    }

    private float settingList(GuiGraphics g, Module m, float x, float y, float w) {
        float ry = y;
        for (Setting<?> s : m.settings) {
            if (!s.visible() || ownSwitch(m, s)) continue;
            if (s instanceof Header) {
                if (ry + HEAD > clipTop && ry < clipBottom) sectionTitle(g, s.name, x, ry + 4, w);
                ry += HEAD;
                continue;
            }
            if (ry + SET > clipTop && ry < clipBottom) settingRow(g, s, x, ry, w);
            else seq++;
            ry += SET;
            if (s instanceof ColorSetting c) {
                float h = PICK * anim(misc, c, COLOR_OPEN.contains(c) ? 1 : 0, 14);
                if (h > 0.5f) {
                    pushClip(g, x, ry, w, h);
                    colorPicker(g, c, x, ry, w);
                    popClip(g);
                }
                ry += h;
            }
        }
        return ry;
    }

    /**
     * A number setting as the slider row sees it: a NumberSetting, or an IntSetting, which behaves like a NumberSetting with step 1 and
     * no decimals (B-209). The row draws, drags, scrolls and types through this, so the two kinds share all of it.
     */
    private record Num(Setting<?> s, double min, double max, double step) {
        static Num of(Setting<?> s) {
            if (s instanceof NumberSetting n) return new Num(n, n.min, n.max, n.step);
            if (s instanceof dev.vantage.setting.IntSetting i) return new Num(i, i.min, i.max, 1);
            return null;
        }

        double get() { return s instanceof NumberSetting n ? n.get() : ((dev.vantage.setting.IntSetting) s).i(); }
        double defaultValue() { return s instanceof NumberSetting n ? n.defaultValue() : ((dev.vantage.setting.IntSetting) s).defaultValue(); }
        String text() { return s instanceof NumberSetting n ? n.text() : String.valueOf(((dev.vantage.setting.IntSetting) s).i()); }
        void reset() { s.reset(); }

        /** Snapped and kept inside the range by the setting itself; an IntSetting rounds. */
        void set(double v) {
            if (s instanceof NumberSetting n) n.set(v);
            else ((dev.vantage.setting.IntSetting) s).set((int) Math.round(Math.max(min, Math.min(max, v))));
        }
    }

    private void settingRow(GuiGraphics g, Setting<?> s, float x, float y, float w) {
        float t = enter(), a = Ui.alpha;
        float px = x + 12 * slide * (1 - t);
        Ui.alpha = a * t;
        rowBack(g, s, x, px, y, w, s.description);
        float right = px + w - 8;
        float ctl = s instanceof dev.vantage.setting.InfoSetting ? 300 : s instanceof ColorSetting ? 124 : s instanceof NumberSetting || s instanceof dev.vantage.setting.IntSetting || s instanceof TextSetting ? 224 : s instanceof BlockListSetting || s instanceof ModeSetting ? 110 : 80;
        Ui.text(g, s.name, px + 10, y + 9, Ui.TEXT, 1f, false);
        if (s.description != null && !s.description.isEmpty()) Ui.text(g, Ui.fit(s.description, w - ctl - 24, 0.76f), px + 10, y + 23, Ui.FAINT, 0.76f, false);
        if (s instanceof BoolSetting b) {
            Ui.toggle(g, right - 30, y + 12.5f, anim(onOff, b, b.on() ? 1 : 0, 16));
            spots.put(b, new float[] {right - 15, y + 20});
            hit(x, y, w, SET - 1, k -> b.toggle());
        } else if (Num.of(s) instanceof Num n) {
            float tx = right - 208, tw = 108;
            float f = anim(misc, n.s, (float) ((n.get() - n.min) / (n.max - n.min)), 30);
            Ui.rr(g, tx, y + 18.5f, tw, 3.5f, 1.7f, Ui.tone(0xFF262A4C), 0);
            Ui.rr(g, tx, y + 18.5f, tw * f, 3.5f, 1.7f, Ui.ACCENT, 0);
            Ui.rr(g, tx + tw * f - 4.5f, y + 15.5f, 9, 9, 4.5f, Ui.tone(0xFFB0A8FF), 0);
            // the number is a box you can type into: click it, type, Enter
            boolean typing = numEditing == n.s;
            Ui.rr(g, right - 64, y + 10.5f, 64, 19, 5, Ui.FIELD, typing ? Ui.ACCENT : Ui.tone(0xFF262A52));
            String shown = typing ? numText + (System.currentTimeMillis() % 1000 < 500 ? "_" : "") : n.text();
            Ui.center(g, shown, right - 32, y + 16.5f, Ui.TEXT, 0.85f, false);
            float wt = Math.max(y, clipTop), wb = Math.min(y + 22 + 9, clipBottom);
            if (wb > wt) wheels.add(new Object[] {tx - 6, wt, tw + 82f, wb - wt, n.s});
            hit(tx - 6, y + 9, tw + 12, 22, k -> {
                if (k == 1) n.reset();
                else beginDrag(tx, tw, v -> n.set(n.min + (n.max - n.min) * v));
            });
            spots.put(n.s, new float[] {tx + tw / 2, y + 20});
            spots.put("numbox:" + n.s.name, new float[] {right - 32, y + 20});
            hit(right - 64, y + 10.5f, 64, 19, k -> { numEditing = n.s; numText = ""; });
            // a small reset chip while the value is not the default (right-click on the slider does the same)
            boolean moved = Math.abs(n.get() - n.defaultValue()) > 1e-9;
            if (moved) {
                boolean ro = hot(right - 96, y + 11.5f, 28, 17);
                Ui.rr(g, right - 96, y + 11.5f, 28, 17, 5, Colors.mix(Ui.FIELD, 0x40808CFF, ro ? 1 : 0), Ui.tone(0xFF262A52));
                Ui.center(g, "reset", right - 82, y + 16.5f, ro ? Ui.TEXT : Ui.SUB, 0.72f, false);
                spots.put("numreset:" + n.s.name, new float[] {right - 82, y + 20});
                hit(right - 96, y + 11.5f, 28, 17, k -> n.reset());
            }
        } else if (s instanceof ModeSetting m) {
            float bw = 94, bx = right - bw;
            Ui.rr(g, bx, y + 10.5f, bw, 19, 5, Ui.FIELD, Ui.tone(0xFF262A52));
            Ui.text(g, Ui.fit(m.get(), bw - 24, 0.85f), bx + 8, y + 16.5f, Ui.TEXT, 0.85f, false);
            Ui.chevron(g, right - 15, y + 17, 7, 1, Ui.SUB);
            hit(bx, y + 10.5f, bw, 19, k -> m.cycle(k == 1 ? -1 : 1));
        } else if (s instanceof TextSetting ts) {
            boolean live = editing == ts;
            Ui.rr(g, right - 224, y + 10.5f, 224, 19, 5, Ui.FIELD, live ? Ui.ACCENT : Ui.tone(0xFF262A52));
            String shown = ts.text();
            if (live && System.currentTimeMillis() % 1000 < 500) shown += "_";
            // show the tail while typing, the head otherwise
            float maxW = 208;
            if (live) { while (shown.length() > 1 && Ui.width(shown, 0.85f, false) > maxW) shown = shown.substring(1); }
            else shown = Ui.fit(shown, maxW, 0.85f);
            Ui.text(g, shown, right - 216, y + 16.5f, shown.isEmpty() ? Ui.FAINT : Ui.TEXT, 0.85f, false);
            hit(right - 224, y + 10.5f, 224, 19, k -> editing = ts);
        } else if (s instanceof ColorSetting c) {
            colorSample(g, c, right - 114, y + 11.5f, 72, 17);
            Ui.rr(g, right - 34, y + 11.5f, 34, 17, 5, c.argb() | 0xFF000000, Ui.tone(0xFF3A3F66));
            hit(right - 34, y + 11.5f, 34, 17, k -> { if (!COLOR_OPEN.add(c)) COLOR_OPEN.remove(c); });
        } else if (s instanceof BlockListSetting bl) {
            button(g, bl, bl.get().size() + " blocks", right - 94, y + 10.5f, 94, () -> minecraft.setScreen(new BlockPickerScreen(this, bl)));
        } else if (s instanceof ActionSetting act) {
            button(g, act, "Run", right - 64, y + 10.5f, 64, act.action);
        } else if (s instanceof dev.vantage.setting.InfoSetting info) {
            Ui.right(g, Ui.fit(info.text(), 290, 0.82f), right, y + 16.5f, Ui.SUB, 0.82f, false);
        }
        Ui.alpha = a;
    }

    private void button(GuiGraphics g, Object key, String label, float x, float y, float w, Runnable run) {
        float hv = anim(misc, key, hot(x, y, w, 19) ? 1 : 0, 18);
        Ui.rr(g, x, y, w, 19, 5, Colors.mix(Ui.FIELD, 0x40808CFF, hv), Ui.tone(0xFF262A52));
        Ui.center(g, label, x + w / 2, y + 6, Ui.TEXT, 0.85f, false);
        hit(x, y, w, 19, k -> run.run());
    }

    /** Colour as it will look: an outlined box with a word in it, drawn in the colour (alpha included) on a dark and on a bright background. */
    private final Map<ColorSetting, Integer> sampled = new HashMap<>();

    private void colorSample(GuiGraphics g, ColorSetting c, float x, float y, float w, float h) {
        int col = c.argb();
        float hw = w / 2;
        Ui.rect(g, x + 1, y + 1, hw, h - 2, 0xFF0C0B22);
        Ui.rect(g, x + hw, y + 1, w - hw - 1, h - 2, 0xFFC4CEDA);
        Ui.rr(g, x, y, w, h, 3, 0, Ui.tone(0xFF3A3F66));
        for (int i = 0; i < 2; i++) {
            float bx = x + i * hw + 4;
            Ui.rr(g, bx, y + 3.5f, hw - 8, h - 7, 3, Ui.fade(col, 0.18f), col);
            Ui.center(g, "Aa", bx + (hw - 8) / 2, y + h / 2 - 4, col, 0.74f, true);
        }
        sampled.put(c, col);
        spots.put("sample:" + c.name, new float[] {x + w / 2, y + h / 2});
    }

    /** The colour the last frame's sample for this setting was drawn in (for the rig). */
    public Integer sampleDrawn(ColorSetting c) { return sampled.get(c); }

    /** Hue and saturation on a wheel, value and alpha as bars beside it, the hex code and a swatch. */
    private void colorPicker(GuiGraphics g, ColorSetting c, float x, float y, float w) {
        float r = WHEEL / 2, cx = x + 30 + r, cy = y + PICK / 2;
        float[] hsv = Colors.toHsv(c.argb());
        int alpha = c.argb() >>> 24;
        Ui.image(g, wheel(), cx - r, cy - r, WHEEL, WHEEL);
        // darker towards the current value, so the wheel shows the colours you would actually get
        int shade = Math.round((1 - hsv[2]) * 255) << 24;
        if (shade != 0) Ui.rr(g, cx - r, cy - r, WHEEL, WHEEL, r, shade, 0);
        Ui.rr(g, cx - r - 1, cy - r - 1, WHEEL + 2, WHEEL + 2, r + 1, 0, Ui.tone(0xFF3A3F66));
        double ang = hsv[0] * Math.PI * 2;
        float mx = cx + (float) Math.cos(ang) * hsv[1] * r, my = cy + (float) Math.sin(ang) * hsv[1] * r;
        Ui.rr(g, mx - 5, my - 5, 10, 10, 5, 0xFFFFFFFF, 0);
        Ui.rr(g, mx - 3.5f, my - 3.5f, 7, 7, 3.5f, c.argb() | 0xFF000000, 0);
        hit(cx - r, cy - r, WHEEL, WHEEL, k -> {
            dragging2 = (px, py) -> {
                float dx = px - cx, dy = py - cy;
                float hue = (float) ((Math.atan2(dy, dx) / (Math.PI * 2) + 1) % 1), sat = Math.min(1f, (float) Math.hypot(dx, dy) / r);
                float[] now = Colors.toHsv(c.argb());
                c.set(Colors.fromHsv(hue, sat, now[2], c.argb() >>> 24));
            };
            dragging2.to(mouseX, mouseY);
        });
        // right of the wheel: the code and swatch, then value and alpha
        float tx = cx + r + 24;
        colorSample(g, c, x + w - 168, y + 8, 156, 24);
        Ui.text(g, String.format("#%06X", c.argb() & 0xFFFFFF) + (alpha < 255 ? String.format("  %d%%", Math.round(alpha / 2.55f)) : ""), tx, y + 12, Ui.SUB, 0.82f, false);
        colorBar(g, c, 2, tx - 22, y + 36, x + w - tx + 22);
        colorBar(g, c, 3, tx - 22, y + 36 + BAR + 6, x + w - tx + 22);
    }

    private static Identifier wheelTex;

    /** The hue/saturation disc at full value, drawn once into a texture: hue by angle, saturation by radius, soft edge. */
    private static Identifier wheel() {
        if (wheelTex != null) return wheelTex;
        int n = 160;
        NativeImage img = new NativeImage(n, n, true);
        float r = n / 2f;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float dx = x + 0.5f - r, dy = y + 0.5f - r, d = (float) Math.hypot(dx, dy);
            float edge = Math.max(0f, Math.min(1f, r - d));
            if (edge <= 0) { img.setPixel(x, y, 0); continue; }
            float hue = (float) ((Math.atan2(dy, dx) / (Math.PI * 2) + 1) % 1), sat = Math.min(1f, d / r);
            img.setPixel(x, y, Colors.fromHsv(hue, sat, 1f, Math.round(edge * 255)));
        }
        wheelTex = Identifier.fromNamespaceAndPath("vantage", "ui/wheel");
        Minecraft.getInstance().getTextureManager().register(wheelTex, new DynamicTexture(wheelTex::toString, img));
        return wheelTex;
    }

    private void colorBar(GuiGraphics g, ColorSetting c, int bar, float x, float y, float w) {
        String[] names = {"Hue", "Saturation", "Value", "Alpha"};
        float[] hsv = Colors.toHsv(c.argb());
        float a = (c.argb() >>> 24) / 255f;
        float value = bar == 3 ? a : hsv[bar];
        float tx = x + w - 216, tw = 208;
        Ui.text(g, names[bar], x + 22, y + 4, Ui.SUB, 0.82f, false);
        Ui.rr(g, tx, y + 6, tw, 3.5f, 1.7f, Ui.tone(0xFF262A4C), 0);
        Ui.rr(g, tx + tw * value - 4, y + 3.5f, 8, 8, 4, Ui.tone(0xFFB0A8FF), 0);
        hit(tx - 6, y, tw + 12, 16, k -> beginDrag(tx, tw, v -> {
            float[] now = Colors.toHsv(c.argb());
            int alpha = c.argb() >>> 24;
            if (bar == 3) alpha = Math.round(v * 255);
            else now[bar] = v;
            c.set(Colors.fromHsv(now[0], now[1], now[2], alpha));
        }));
    }

    private void beginDrag(float x, float w, Drag d) {
        dragging = d;
        dragX = x;
        dragW = w;
        drag(mouseX);
    }

    private void drag(float mx) {
        dragging.to(Math.max(0f, Math.min(1f, (mx - dragX) / dragW)));
    }

    // ---- configs page ---------------------------------------------------------------------------------------------

    private void say(Profiles.Result r) {
        cfgMsg = r.msg();
        cfgMsgBad = !r.ok();
        cfgMsgAt = System.currentTimeMillis();
    }

    /** The last answer the Configs page gave (test rig). */
    public String configMessage() {
        return cfgMsg;
    }

    private void say(boolean ok, String msg) {
        say(new Profiles.Result(ok, msg));
    }

    /** A second press of the same thing within 4 s confirms it (overwrite, delete). */
    private boolean armed(String what) {
        return what.equals(cfgArm) && System.currentTimeMillis() - cfgArmAt < 4000;
    }

    private void arm(String what) {
        cfgArm = what;
        cfgArmAt = System.currentTimeMillis();
    }

    /** Headline, the count or the last answer under it, and the "Save current as..." box where the search pill sits elsewhere. */
    private void configsHeader(GuiGraphics g, float x, float y, float w) {
        headline(g, "Configs", "", x, y + 20);
        List<Profiles.Info> l = Profiles.list();
        boolean fresh = cfgMsg != null && System.currentTimeMillis() - cfgMsgAt < 5000;
        String line = fresh ? cfgMsg : l.size() + (l.size() == 1 ? " config" : " configs")
                + (Profiles.active().isEmpty() ? "" : "  ·  active: " + Profiles.active() + (Profiles.edited() ? " (edited)" : ""));
        Ui.text(g, Ui.fit(line, w - 290, 0.84f), x + 1, y + 52, fresh ? (cfgMsgBad ? Ui.YELLOW : Ui.TITLE) : Ui.FAINT, 0.84f, false);

        float sw = 260, sx = x + w - sw, sy = y + 23;
        boolean typing = !cfgName.isEmpty() && cfgRename == null;
        float focus = anim(misc, "cfgBox", typing ? 1 : 0, 16);
        Ui.rr(g, sx, sy, sw, 28, 14, 0x40101522, Colors.mix(Ui.tone(0xFF262A52), Ui.ACCENT, focus));
        int pc = Colors.mix(Ui.FAINT, Ui.TEXT, focus);
        Ui.rect(g, sx + 12, sy + 13.3f, 11, 1.4f, pc);
        Ui.rect(g, sx + 16.8f, sy + 8.5f, 1.4f, 11, pc);
        float tw = sw - 44;
        if (typing) {
            float ew = Ui.width("enter", 0.72f, false) + 12, ex = sx + sw - ew - 7;
            float hv = anim(hover, "cfgEnter", hot(ex, sy + 6, ew, 16) ? 1 : 0, 18);
            Ui.rr(g, ex, sy + 6, ew, 16, 8, Colors.mix(Ui.fade(Ui.ACCENT, 0.25f), Ui.fade(Ui.ACCENT, 0.5f), hv), 0);
            Ui.text(g, "enter", ex + 6, sy + 10, Ui.TEXT, 0.72f, false);
            hit(ex, sy + 6, ew, 16, b -> saveFromBox());
            spots.put("cfg:enter", new float[] {ex + ew / 2, sy + 14});
            tw = ex - sx - 36;
        }
        String shown = cfgName;
        if (typing && System.currentTimeMillis() / 500 % 2 == 0) shown += "_";
        while (shown.length() > 1 && Ui.width(shown, 0.94f, false) > tw) shown = shown.substring(1);
        Ui.text(g, cfgName.isEmpty() ? (ConfigShare.pending != null ? "Name for the import..." : "Save current as...") : shown, sx + 30, sy + 9, cfgName.isEmpty() ? Ui.FAINT : Ui.TEXT, 0.94f, false);
        spots.put("cfg:box", new float[] {sx + sw / 2, sy + 14});

        Ui.text(g, Ui.fit("Click a config to load it. Keys, friends, waypoints, HUD layout and the menu's look stay as they are unless a config carries them", w, 0.78f),
                x + 1, y + 78, Ui.FAINT, 0.78f, false);
        shareBar(g, x, y + 94, w);
    }

    /** Import / export buttons and the three "Include ..." switches for a share code. */
    private void shareBar(GuiGraphics g, float x, float y, float w) {
        float bx = x;
        bx += rowButtonLeft(g, "cfg:import", "Import from clipboard", bx, y, () -> { cfgArm = null; say(ConfigShare.importClipboard()); }) + 6;
        bx += rowButtonLeft(g, "cfg:exportcur", "Export current", bx, y, () -> say(ConfigShare.exportCurrent())) + 6;
        Ui.text(g, "Export includes:", bx + 8, y + 6, Ui.FAINT, 0.78f, false);
        float cy = y + 27;
        float sx = x;
        sx += shareSwitch(g, "cfg:optkeys", "Include keybinds", ConfigShare.optKeys, sx, cy, () -> ConfigShare.optKeys = !ConfigShare.optKeys,
                "Adds your module keybinds to exported codes (friends and waypoints are never exported)") + 14;
        sx += shareSwitch(g, "cfg:opthud", "Include HUD layout (positions, corners, panel size)", ConfigShare.optHud, sx, cy, () -> ConfigShare.optHud = !ConfigShare.optHud,
                "Adds HUD piece positions, corners and the panel size to exported codes") + 14;
        shareSwitch(g, "cfg:opttheme", "Include theme", ConfigShare.optTheme, sx, cy, () -> ConfigShare.optTheme = !ConfigShare.optTheme,
                "Adds the theme, accent and menu size to exported codes");
    }

    /** Like rowButton but with its left edge at x. Returns its width. */
    private float rowButtonLeft(GuiGraphics g, Object key, String label, float left, float y, Runnable run) {
        float w = Math.max(54, Ui.width(label, 0.8f, false) + 18);
        rowButton(g, key, label, 0, left + w, y, run);
        return w;
    }

    /** A small switch with its label to the right; returns the total width. */
    private float shareSwitch(GuiGraphics g, Object key, String label, boolean on, float x, float y, Runnable run, String tip) {
        float lw = Ui.width(label, 0.78f, false), tw = 30 + 6 + lw;
        float t = anim(onOff, key, on ? 1 : 0, 16);
        boolean over = hot(x, y, tw, 15);
        if (over) help = tip;
        Ui.toggle(g, x, y, t);
        Ui.text(g, label, x + 36, y + 3.5f, Colors.mix(Ui.SUB, Ui.TEXT, on || over ? 1 : 0), 0.78f, false);
        spots.put(key, new float[] {x + 15, y + 7.5f});
        hit(x, y, tw, 15, b -> run.run());
        return tw;
    }

    /** The checked import waiting for its second click: preview, extras, and Save / Cancel. */
    private float importCard(GuiGraphics g, ConfigShare.Decoded d, float x, float y, float w) {
        float t = enter(), a = Ui.alpha;
        Ui.alpha = a * t;
        Ui.rr(g, x, y, w, CARD, 8, Ui.tone(0xF01B1A42), Ui.fade(Ui.ACCENT, 0.7f));
        String nm = Profiles.clean(cfgName).isEmpty() ? d.name() : Profiles.clean(cfgName);
        boolean exists = Profiles.find(nm) != null;
        float bx = x + w - 12, by = y + (CARD - 19) / 2;
        String what = "imp:" + Profiles.key(nm);
        bx -= rowButton(g, "cfg:impcancel", "Cancel", 0, bx, by, () -> { ConfigShare.pending = null; cfgArm = null; }) + 6;
        bx -= rowButton(g, "cfg:impsave", exists && armed(what) ? "Sure?" : exists ? "Replace" : "Save", exists ? Ui.YELLOW : 0, bx, by, () -> {
            if (exists && !armed(what)) { arm(what); say(false, nm + " already exists. Click again to replace it with the imported code"); return; }
            cfgArm = null;
            Profiles.Result r = ConfigShare.savePending(nm, true);
            say(r);
            if (r.ok()) cfgName = "";
        }) + 6;
        float tr = bx - 8;
        Ui.text(g, Ui.fit("Import as \"" + nm + "\"" + (exists ? " (replaces the existing one)" : ""), tr - x - 18, 1.0f), x + 18, y + 11, Ui.TEXT, 1.0f, true);
        List<String> ex = d.extraParts();
        String sub = d.mods() + " modules on, " + d.settings() + " settings, " + d.skipped() + " skipped" + (ex.isEmpty() ? "" : "  ·  also: " + String.join(", ", ex))
                + "  ·  type a name above to change it";
        Ui.text(g, Ui.fit(sub, tr - x - 18, 0.82f), x + 18, y + 32, Ui.SUB, 0.82f, false);
        Ui.alpha = a;
        return y + CARD + CGAP;
    }

    private void saveFromBox() {
        if (ConfigShare.pending != null) { Profiles.Result r = ConfigShare.savePending(cfgName, false); say(r); if (r.ok()) cfgName = ""; return; }
        String name = Profiles.clean(cfgName);
        if (name.isEmpty()) { say(false, "Type a name first: letters, digits, space, - and _"); return; }
        Profiles.Info had = Profiles.find(name);
        String what = "save:" + Profiles.key(name);
        if (had != null && !armed(what)) {
            arm(what);
            say(false, had.name() + " already exists. Enter again overwrites it with the current setup");
            return;
        }
        cfgArm = null;
        Profiles.Result r = Profiles.save(name);
        say(r);
        if (r.ok()) cfgName = "";
    }

    private float configList(GuiGraphics g, float x, float y, float w) {
        List<Profiles.Info> l = Profiles.list();
        if (ConfigShare.pending != null) y = importCard(g, ConfigShare.pending, x, y, w);
        y = presetRows(g, x, y, w);
        if (l.isEmpty()) {
            float a = Ui.alpha;
            Ui.alpha = a * enter();
            Ui.text(g, "No configs yet", x, y + 12, Ui.SUB, 1.02f, false);
            Ui.text(g, Ui.fit("Set modules up the way you like, type a name (say \"Base hunting\") and press Enter. Make as many as you want.", w, 0.84f),
                    x, y + 30, Ui.FAINT, 0.84f, false);
            Ui.alpha = a;
            return y + 50;
        }
        float ry = y;
        for (Profiles.Info i : l) {
            configRow(g, i, x, ry, w);
            ry += CARD + CGAP;
        }
        return ry + 2;
    }

    /** Three ready-made setups with an Apply button each. Applying never saves anything and never touches a saved config. */
    private float presetRows(GuiGraphics g, float x, float y, float w) {
        List<dev.vantage.core.Presets.Preset> ps = dev.vantage.core.Presets.list();
        if (ps.isEmpty()) return y;
        float a = Ui.alpha;
        Ui.alpha = a * enter();
        sectionTitle(g, "Presets  -  applied only when you press Apply, never saved over your configs", x, y, w);
        float ry = y + HEAD - 4;
        for (var p : ps) {
            boolean over = hot(x, ry, w, 44);
            if (over) help = p.blurb();
            Ui.rr(g, x, ry, w, 44, 8, Colors.mix(Ui.CARD, Ui.tone(0xF0191838), over ? 1f : 0f), Ui.CARD_LINE);
            Ui.text(g, p.name(), x + 16, ry + 9, Ui.TEXT, 1.02f, true);
            Ui.text(g, Ui.fit(p.blurb(), w - 150, 0.8f), x + 16, ry + 25, Ui.FAINT, 0.8f, false);
            rowButton(g, "preset:" + p.key(), "Apply", 0, x + w - 12, ry + 12.5f, () -> say(dev.vantage.core.Presets.apply(p.key())));
            ry += 44 + 6;
        }
        Ui.alpha = a;
        return ry + 10;
    }

    /** One saved setup: name (a text box while renaming), active/edited tag, modules on, when saved, and Overwrite / Rename / Delete. */
    private void configRow(GuiGraphics g, Profiles.Info i, float x, float y, float w) {
        float t = enter(), a = Ui.alpha;
        String key = "cfg:row:" + i.key();
        boolean active = Profiles.isActive(i), renaming = i.key().equals(cfgRename);
        boolean visible = y + CARD > clipTop && y < clipBottom;
        boolean over = hot(x, y, w, CARD);
        float hv = anim(hover, key, over ? 1 : 0, 18), on = anim(onOff, key, active ? 1 : 0, 16);
        if (over) help = i.broken() ? "This file can't be read. Delete it, or fix it by hand in config/vantage/profiles"
                : "Click to load " + i.name() + ": modules switch to how they were when it was saved";
        spots.put(key, new float[] {x + 40, y + CARD / 2});
        if (!visible) return;
        float dx = 12 * slide * (1 - t), dy = slide == 0 ? 8 * (1 - t) : 0, px = x + dx, py = y + dy;
        Ui.alpha = a * t;
        int bg = Colors.mix(Colors.mix(Ui.CARD, Ui.tone(0xF0191838), hv), Colors.mix(Ui.tone(0xF01B1A42), Ui.tone(0xF0201E4C), hv), on);
        Ui.rr(g, px, py, w, CARD, 8, bg, Colors.mix(Ui.CARD_LINE, Ui.tone(0xFF3B3786), on));
        if (on > 0) Ui.rr(g, px + 1.5f, py + 12, 3, CARD - 24, 1.5f, Ui.fade(Ui.ACCENT, on), 0);
        hit(x, y, w, CARD, b -> {
            if (renaming) return;
            if (i.broken()) { say(false, i.name() + " can't be read. Delete it or fix the file"); return; }
            cfgArm = null;
            say(Profiles.load(i.key()));
        });

        // the buttons, right to left
        float bx = px + w - 12, by = py + (CARD - 19) / 2;
        if (!renaming) {
            String del = "del:" + i.key();
            bx -= rowButton(g, "cfg:del:" + i.key(), armed(del) ? "Sure?" : "Delete", armed(del) ? Ui.RED : 0, bx, by, () -> {
                if (!armed(del)) { arm(del); say(false, "Click Delete again to delete " + i.name()); return; }
                cfgArm = null;
                say(Profiles.delete(i.key()));
            }) + 6;
            if (!i.broken()) {
                bx -= rowButton(g, "cfg:ren:" + i.key(), "Rename", 0, bx, by, () -> { cfgRename = i.key(); cfgRenameText = i.name(); cfgArm = null; }) + 6;
                bx -= rowButton(g, "cfg:exp:" + i.key(), "Export", 0, bx, by, () -> say(ConfigShare.exportConfig(i.key()))) + 6;
                String ow = "over:" + i.key();
                bx -= rowButton(g, "cfg:over:" + i.key(), armed(ow) ? "Sure?" : "Overwrite", armed(ow) ? Ui.YELLOW : 0, bx, by, () -> {
                    if (!armed(ow)) { arm(ow); say(false, "Click Overwrite again to replace " + i.name() + " with the current setup"); return; }
                    cfgArm = null;
                    say(Profiles.save(i.name()));
                }) + 6;
            }
        }
        float textR = bx - 10;

        float tx = px + 18;
        if (renaming) {
            float fw = Math.min(260, textR - tx);
            Ui.rr(g, tx - 6, py + 7, fw, 20, 5, Ui.FIELD, Ui.ACCENT);
            String shown = cfgRenameText + (System.currentTimeMillis() % 1000 < 500 ? "_" : "");
            while (shown.length() > 1 && Ui.width(shown, 1f, false) > fw - 12) shown = shown.substring(1);
            Ui.text(g, shown, tx, py + 12.5f, Ui.TEXT, 1f, false);
            hit(tx - 6, py + 7, fw, 20, b -> cfgRename = i.key());
            spots.put("cfg:renbox:" + i.key(), new float[] {tx - 6 + fw / 2, py + 17});
            tx += fw + 4;
        } else {
            Ui.text(g, Ui.fit(i.name(), textR - tx - 60, 1.12f), tx, py + 12, Colors.mix(0xFFD6DAEC, Ui.TEXT, Math.max(on, hv)), 1.12f, true);
            tx += Math.min(Ui.width(i.name(), 1.12f, true), textR - tx - 60) + 8;
        }
        if (active || i.broken()) {
            boolean edited = active && Profiles.edited();
            String tag = i.broken() ? "BROKEN" : edited ? "EDITED" : "ACTIVE";
            int tc = i.broken() ? Ui.RED : edited ? Ui.YELLOW : Ui.ACCENT;
            float tw = Ui.width(tag, 0.66f, false) + 10;
            Ui.rr(g, tx, py + 12, tw, 12, 6, Ui.fade(tc, 0.18f), Ui.fade(tc, 0.7f));
            Ui.text(g, tag, tx + 5, py + 15, tc, 0.66f, false);
        }
        String sub = i.broken() ? "Unreadable file: " + i.key() + ".json"
                : i.on() + (i.on() == 1 ? " module on" : " modules on") + "  ·  saved " + Profiles.ago(i.saved())
                + (i.extras().isEmpty() ? "" : "  ·  + " + i.extras())
                + (i.preview().isEmpty() ? "" : "  ·  " + String.join(", ", i.preview()) + (i.on() > i.preview().size() ? ", ..." : ""));
        Ui.text(g, Ui.fit(sub, textR - px - 18, 0.82f), px + 18, py + 32, Colors.mix(Ui.FAINT, Ui.SUB, hv), 0.82f, false);
        Ui.alpha = a;
    }

    /** A small pill button for a config row, drawn with its right edge at x. Returns its width. */
    private float rowButton(GuiGraphics g, Object key, String label, int warn, float right, float y, Runnable run) {
        float w = Math.max(54, Ui.width(label, 0.8f, false) + 18), x = right - w;
        float hv = anim(misc, key, hot(x, y, w, 19) ? 1 : 0, 18);
        int line = warn != 0 ? Ui.fade(warn, 0.8f) : Colors.mix(Ui.tone(0xFF262A52), Ui.tone(0xFF3B3786), hv);
        Ui.rr(g, x, y, w, 19, 9.5f, Colors.mix(Ui.FIELD, warn != 0 ? Ui.fade(warn, 0.3f) : 0x40808CFF, warn != 0 ? 1 : hv), line);
        Ui.center(g, label, x + w / 2, y + 6, warn != 0 ? warn : Colors.mix(Ui.SUB, Ui.TEXT, hv), 0.8f, false);
        spots.put(key, new float[] {x + w / 2, y + 9.5f});
        hit(x, y, w, 19, b -> run.run());
        return w;
    }

    /** Keys while the Configs page is open. True = handled. */
    private boolean configKey(int key) {
        boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
        if (cfgRename != null) {
            if (enter) {
                Profiles.Result r = Profiles.rename(cfgRename, cfgRenameText);
                say(r);
                if (r.ok()) cfgRename = null;
            } else if (key == GLFW.GLFW_KEY_ESCAPE) cfgRename = null;
            else if (key == GLFW.GLFW_KEY_BACKSPACE && !cfgRenameText.isEmpty()) cfgRenameText = cfgRenameText.substring(0, cfgRenameText.length() - 1);
            return true;
        }
        if (enter) { saveFromBox(); return true; }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!cfgName.isEmpty()) cfgName = cfgName.substring(0, cfgName.length() - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && !cfgName.isEmpty()) { cfgName = ""; cfgArm = null; return true; }
        return !cfgName.isEmpty(); // while a name is being typed, the menu key is just a letter
    }

    /** Ctrl+V on the Configs page: a share code on the clipboard is imported (same as the Import button), anything else is pasted into the name box. */
    private void pasteOnConfigs() {
        String clip = ConfigShare.clipboard();
        if (ConfigShare.looksLikeCode(clip)) { cfgArm = null; say(ConfigShare.importClipboard()); return; }
        String name = Profiles.clean(clip);
        if (name.isEmpty()) { say(false, clip == null || clip.isBlank() ? "The clipboard is empty" : "The clipboard holds no config code and no usable name"); return; }
        cfgName = Profiles.clean(cfgName + name);
        cfgArm = null;
    }

    /** Typed letters go into the rename box or the "Save current as..." box: letters, digits, space, - and _. */
    private void configChar(String c) {
        if (!c.matches("[A-Za-z0-9 _-]")) return;
        if (cfgRename != null) {
            if (cfgRenameText.length() < Profiles.MAX_NAME) cfgRenameText += c;
        } else if (cfgName.length() < Profiles.MAX_NAME && !(cfgName.isEmpty() && c.equals(" "))) {
            cfgName += c;
            cfgArm = null;
        }
    }

    // ---- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        editing = null;
        commitNumber();
        cfgRename = null; // a click on the rename box itself starts it again
        if (closeStart != 0) return true;
        int button = e.button();
        if (listening != null) {
            if (button >= 3) listening.bind = -100 - button;
            listening = null;
            Config.markDirty();
            return true;
        }
        float s = scaleNow();
        click((float) e.x() / s, (float) e.y() / s, button);
        return true;
    }

    /** Also used by the test rig to press things where a player would. */
    public boolean click(float mx, float my, int button) {
        commitNumber();
        mouseX = mx;
        mouseY = my;
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).at(mx, my)) { hits.get(i).run.run(button); return true; }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragging = null;
        dragging2 = null;
        barGrab = Float.NaN;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        float s = scaleNow();
        if (!Float.isNaN(barGrab)) { barTo((float) e.y() / s); return true; }
        if (dragging2 != null) { dragging2.to((float) e.x() / s, (float) e.y() / s); return true; }
        if (dragging == null) return false;
        drag((float) e.x() / s);
        return true;
    }

    /** Each notch moves the target; the list glides there over a few frames instead of jumping. */
    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        // over a slider the wheel changes the number (Shift: 5 steps) instead of scrolling the page
        float sc = scaleNow(), px = (float) mx / sc, py = (float) my / sc;
        Num n = null;
        for (Object[] r : wheels) if (px >= (float) r[0] && px < (float) r[0] + (float) r[2] && py >= (float) r[1] && py < (float) r[1] + (float) r[3]) n = Num.of((Setting<?>) r[4]);
        if (n != null && v != 0) {
            boolean shift = GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS;
            n.set(n.get() + Math.signum(v) * n.step * (shift ? 5 : 1));
            return true;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll - (float) v * NOTCH));
        return true;
    }

    /** What is typed in the search box (for the rig). */
    public String searchText() {
        return search;
    }

    /** True while the menu waits for a key to bind: then no key may do anything else. */
    public boolean capturingKey() {
        return listening != null;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (closeStart != 0) return true;
        int key = e.key();
        if (numEditing != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) commitNumber();
            else if (key == GLFW.GLFW_KEY_ESCAPE) numEditing = null;
            else if (key == GLFW.GLFW_KEY_BACKSPACE && !numText.isEmpty()) numText = numText.substring(0, numText.length() - 1);
            return true;
        }
        if (editing != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) editing = null;
            else if (key == GLFW.GLFW_KEY_BACKSPACE && !editing.text().isEmpty()) editing.set(editing.text().substring(0, editing.text().length() - 1));
            return true;
        }
        if (listening != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) listening.bind = -1;
            else listening.bind = key;
            listening = null;
            swallowCharUntil = Keys.producesCharacter(key) ? System.currentTimeMillis() + 300 : 0; // the key's own character arrives after the press: it is the bind, not search text
            Config.markDirty();
            return true;
        }
        if (view == View.CONFIGS && page == null && cfgRename == null && e.isPaste()) { pasteOnConfigs(); return true; }
        if (view == View.CONFIGS && page == null && configKey(key)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!search.isEmpty()) { search = ""; go(0); }
            else if (page != null) back();
            else onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!search.isEmpty()) { search = search.substring(0, search.length() - 1); scroll = shownScroll = 0; }
            return true;
        }
        ClickGuiModule g = gui();
        if (g != null && key == g.bind && search.isEmpty()) {
            onClose();
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (listening != null || closeStart != 0 || System.currentTimeMillis() - openedAt < 150) return true;
        if (swallowCharUntil != 0) {
            boolean mine = System.currentTimeMillis() < swallowCharUntil;
            swallowCharUntil = 0;
            if (mine) return true;
        }
        if (numEditing != null) {
            if ((e.codepoint() >= '0' && e.codepoint() <= '9' || e.codepoint() == '.' || e.codepoint() == '-') && numText.length() < 8) numText += e.codepointAsString();
            return true;
        }
        if (editing != null) {
            if (e.isAllowedChatCharacter() && editing.text().length() < TextSetting.MAX) editing.set(editing.text() + e.codepointAsString());
            return true;
        }
        if (view == View.CONFIGS && page == null) {
            if (e.isAllowedChatCharacter()) configChar(e.codepointAsString());
            return true;
        }
        if (e.isAllowedChatCharacter() && search.length() < 24) {
            boolean first = search.isEmpty();
            search += e.codepointAsString();
            page = null;
            if (first) go(0);
            else scroll = shownScroll = 0;
        }
        return true;
    }

    /** Enter or a click elsewhere: the typed text becomes the value (clamped, snapped to the step); junk is dropped. */
    private void commitNumber() {
        Num n = numEditing == null ? null : Num.of(numEditing);
        numEditing = null;
        if (n == null || numText.isEmpty()) return;
        try { n.set(Double.parseDouble(numText)); } catch (NumberFormatException ignored) {}
        numText = "";
    }

    @Override
    public void onClose() {
        commitNumber();
        editing = null;
        Config.markDirty();
        if (!animated() || openStart == 0) { finish(); return; }
        if (closeStart == 0) closeStart = System.nanoTime();
    }
}
