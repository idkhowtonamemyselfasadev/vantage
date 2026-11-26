package dev.vantage.gui;

import dev.vantage.core.Ui;
import dev.vantage.core.Updater;
import dev.vantage.core.Vantage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * The title screen in the menu's look: the panorama behind, one window, the serif wordmark, the vanilla actions as rounded buttons
 * and a small Join Donut pill. Extends the vanilla title so everything that asks "is this the title" (the update popup, Realms)
 * still says yes; none of the vanilla widgets are built.
 */
public class VantageTitleScreen extends TitleScreen implements VantageScreen {
    public static final String DONUT_NAME = "Donut SMP", DONUT_IP = "donut.example.invalid";
    private final MenuKit kit = new MenuKit();
    private long opened;
    /** Entrance: each piece fades in and settles down by LIFT over IN_MS, a beat after the one before. */
    private static final long IN_MS = 520;
    private static final float LIFT = 14;
    private boolean fromLoading;

    public VantageTitleScreen() {
        super(false);
    }

    @Override
    protected void init() {
        // the loading screen inits us as its fade-out starts: begin the entrance when its background starts to clear
        boolean loading = minecraft.getOverlay() instanceof net.minecraft.client.gui.screens.LoadingOverlay && VantageLoading.enabled();
        opened = System.currentTimeMillis() + (loading ? VantageLoading.REVEAL_MS : 0);
        fromLoading = loading;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (minecraft.level == null) renderPanorama(g, delta);
        g.fill(0, 0, width, height, 0x66050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        kit.begin(g, mx, my);
        long now = System.currentTimeMillis();
        float x = kit.x0(), y = kit.y0(), H = MenuKit.H();
        Ui.rect(g, 0, 0, Ui.W(), Ui.H(), MenuKit.tint());
        float cx = x + MenuKit.W() / 2;
        // top centre: the wordmark, first in
        // after the loading screen its wordmark glides into this spot: ours does not move, it only takes over
        float a = fromLoading ? VantageLoading.landed(now) : in(now, 0), ly = y + H * 0.16f + (fromLoading ? 0 : LIFT * (1 - a));
        Ui.alpha = a;
        Ui.serif(g, "Vantage", cx - Ui.serifWidth("Vantage", 4.6f, false) / 2, ly, Ui.TEXT, 4.6f, false);
        a = in(now, 90);
        Ui.alpha = a;
        Ui.center(g, "finders, leaks and a clean view", cx, y + H * 0.16f + 78 + LIFT * (1 - a), Ui.SUB, 1f, false);
        // middle: the two main actions, then the smaller ones, each a beat later
        float bw = 320, bx = cx - bw / 2, by = y + H * 0.46f;
        boolean mp = minecraft.allowsMultiplayer();
        a = in(now, 170); Ui.alpha = a;
        kit.button(g, "singleplayer", "Singleplayer", bx, by + LIFT * (1 - a), bw, true, true);
        a = in(now, 230); Ui.alpha = a;
        kit.button(g, "multiplayer", "Multiplayer", bx, (by += 44) + LIFT * (1 - a), bw, true, mp);
        a = in(now, 300); Ui.alpha = a;
        kit.button(g, "options", "Options", bx, (by += 64) + LIFT * (1 - a), bw / 2 - 4, false, true);
        kit.button(g, "language", "Language", bx + bw / 2 + 4, by + LIFT * (1 - a), bw / 2 - 4, false, true);
        a = in(now, 360); Ui.alpha = a;
        kit.button(g, "accessibility", "Accessibility", bx, (by += 44) + LIFT * (1 - a), bw / 2 - 4, false, true);
        kit.button(g, "quit", "Quit game", bx + bw / 2 + 4, by + LIFT * (1 - a), bw / 2 - 4, false, true);
        // corners last: the pills, version and update state
        a = in(now, 460); Ui.alpha = a;
        kit.pill(g, "settings", "Vantage settings", x + MenuKit.W() - MenuKit.PAD - 130, y + MenuKit.PAD - 4, 130, Ui.ACCENT);
        kit.pill(g, "donut", "Join Donut", x + MenuKit.W() - MenuKit.PAD - 110, y + H - MenuKit.PAD - 24, 110, mp ? Ui.ORANGE : Ui.GREY);
        float iy = y + H - MenuKit.PAD - 20;
        String state = Updater.state();
        if (!state.isEmpty()) Ui.text(g, "Update: " + state, x + MenuKit.PAD, iy - 18, Updater.pending() != null ? Ui.YELLOW : Ui.FAINT, 0.84f, false);
        if (fromLoading) Ui.alpha = VantageLoading.footer(now); // the loading screen shows this line in the same place
        Ui.text(g, VantageLoading.versionLine(), x + MenuKit.PAD, iy, Ui.FAINT, 0.84f, false);
        Ui.alpha = 1f;
        kit.end(g);
    }

    /** How far one piece is into its entrance: 0 before its beat, eased to 1 over IN_MS after it. */
    private float in(long now, long delay) {
        return Ui.ease(Math.min(1f, Math.max(0f, (now - opened - delay) / (float) IN_MS)));
    }

    @Override
    public void act(String id) {
        switch (id) {
            case "singleplayer" -> minecraft.setScreen(new VantageWorldsScreen(this));
            case "multiplayer" -> minecraft.setScreen(minecraft.options.skipMultiplayerWarning ? new VantageServersScreen(this) : new SafetyScreen(this));
            case "settings" -> { ClickGui.showSettings(); minecraft.setScreen(new ClickGui(this)); }
            case "options" -> minecraft.setScreen(new OptionsScreen(this, minecraft.options));
            case "language" -> minecraft.setScreen(new LanguageSelectScreen(this, minecraft.options, minecraft.getLanguageManager()));
            case "accessibility" -> minecraft.setScreen(new AccessibilityOptionsScreen(this, minecraft.options));
            case "quit" -> minecraft.stop();
            case "donut" -> joinDonut();
            default -> Vantage.LOG.info("Title screen: no action {}", id);
        }
    }

    private void joinDonut() {
        if (!minecraft.allowsMultiplayer()) return;
        ServerData data = new ServerData(DONUT_NAME, DONUT_IP, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(DONUT_IP), data, false, null);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        String id = kit.hit(e.x(), e.y());
        if (id != null) { act(id); return true; }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
