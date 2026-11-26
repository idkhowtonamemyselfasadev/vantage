package dev.vantage.gui;

import java.util.Optional;

import dev.vantage.core.Ui;
import dev.vantage.core.Updater;
import dev.vantage.core.Vantage;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.NowPlayingToast;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Dialogs;
import net.minecraft.tags.DialogTags;
import net.minecraft.util.CommonLinks;

/**
 * The Esc menu in the menu's look: the game blurred behind, "Paused" with where you are under it, the vanilla actions as rounded
 * buttons plus Vantage settings, and pills for waypoints, friends and the HUD editor. Extends the vanilla pause screen so the
 * game still pauses in singleplayer and everything that asks "is this the pause menu" still says yes; none of the vanilla widgets are built.
 */
public class VantagePauseScreen extends PauseScreen implements VantageScreen {
    private final MenuKit kit = new MenuKit();
    private long opened;
    private boolean leaving;
    /** Entrance: quick, this screen opens all the time. */
    private static final long IN_MS = 260;
    private static final float LIFT = 10;

    public VantagePauseScreen() {
        super(true);
    }

    @Override
    protected void init() {
        opened = System.currentTimeMillis();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        super.renderBackground(g, mx, my, delta); // vanilla's blur and gradient over the game
        g.fill(0, 0, width, height, 0x4A07061A);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        kit.begin(g, mx, my);
        long now = System.currentTimeMillis();
        float x = kit.x0(), y = kit.y0(), H = MenuKit.H(), cx = x + MenuKit.W() / 2;
        float a = in(now, 0);
        Ui.alpha = a;
        Ui.serif(g, "Paused", cx - Ui.serifWidth("Paused", 3.2f, false) / 2, y + H * 0.14f + LIFT * (1 - a), Ui.TEXT, 3.2f, false);
        a = in(now, 60); Ui.alpha = a;
        Ui.center(g, where(), cx, y + H * 0.14f + 56 + LIFT * (1 - a), Ui.SUB, 1f, false);
        float bw = 320, bx = cx - bw / 2, by = y + H * 0.36f, half = bw / 2 - 4;
        a = in(now, 110); Ui.alpha = a;
        kit.button(g, "back", "Back to game", bx, by + LIFT * (1 - a), bw, true, true);
        a = in(now, 150); Ui.alpha = a;
        kit.button(g, "advancements", "Advancements", bx, (by += 54) + LIFT * (1 - a), half, false, true);
        kit.button(g, "stats", "Statistics", bx + bw / 2 + 4, by + LIFT * (1 - a), half, false, true);
        a = in(now, 190); Ui.alpha = a;
        kit.button(g, "options", "Options", bx, (by += 44) + LIFT * (1 - a), half, false, true);
        kit.button(g, "settings", "Vantage settings", bx + bw / 2 + 4, by + LIFT * (1 - a), half, false, true);
        a = in(now, 230); Ui.alpha = a;
        boolean lan = minecraft.hasSingleplayerServer() && !minecraft.getSingleplayerServer().isPublished();
        kit.button(g, lan ? "lan" : "social", lan ? "Open to LAN" : "Player reporting", bx, (by += 44) + LIFT * (1 - a), half, false, true);
        Optional<? extends Holder<Dialog>> extra = additions();
        if (extra.isPresent()) kit.button(g, "dialog", Ui.fit(extra.get().value().common().computeExternalTitle().getString(), half - 20, 1f), bx + bw / 2 + 4, by + LIFT * (1 - a), half, false, true);
        else kit.button(g, "feedback", "Give feedback", bx + bw / 2 + 4, by + LIFT * (1 - a), half, false, true);
        a = in(now, 280); Ui.alpha = a;
        kit.button(g, "disconnect", CommonComponents.disconnectButtonLabel(minecraft.isLocalServer()).getString(), bx, (by += 64) + LIFT * (1 - a), bw, false, !leaving);
        // corners: Vantage's own tools, the version and update state
        a = in(now, 330); Ui.alpha = a;
        float px = x + MenuKit.W() - MenuKit.PAD, py = y + H - MenuKit.PAD - 24;
        kit.pill(g, "hud", "HUD editor", px - 100, py, 100, Ui.TEAL);
        kit.pill(g, "friends", "Friends", px - 100 - 8 - 84, py, 84, Ui.GREEN);
        kit.pill(g, "waypoints", "Waypoints", px - 100 - 8 - 84 - 8 - 100, py, 100, Ui.ACCENT);
        float iy = y + H - MenuKit.PAD - 20;
        String state = Updater.state();
        if (!state.isEmpty()) Ui.text(g, "Update: " + state, x + MenuKit.PAD, iy - 18, Updater.pending() != null ? Ui.YELLOW : Ui.FAINT, 0.84f, false);
        Ui.text(g, "Vantage  ·  Minecraft " + SharedConstants.getCurrentVersion().name(), x + MenuKit.PAD, iy, Ui.FAINT, 0.84f, false);
        Ui.alpha = 1f;
        kit.end(g);
        if (rendersNowPlayingToast()) NowPlayingToast.renderToast(g, font);
    }

    /** Where the player is: the server and its ping, or the world's name. */
    private String where() {
        ServerData s = minecraft.getCurrentServer();
        if (s != null && !minecraft.isLocalServer()) return "on " + dev.vantage.core.Streamer.serverLabel(s.ip) + (s.ping > 0 ? "  ·  " + s.ping + " ms" : "");
        if (minecraft.hasSingleplayerServer()) return dev.vantage.core.Streamer.serverLabel(minecraft.getSingleplayerServer().getWorldData().getLevelName()) + "  ·  singleplayer";
        return "in game";
    }

    /** What vanilla would put on its last button: a server dialog, the custom-options list or the server links. */
    private Optional<? extends Holder<Dialog>> additions() {
        try {
            Registry<Dialog> registry = minecraft.player.connection.registryAccess().lookupOrThrow(Registries.DIALOG);
            Optional<? extends HolderSet<Dialog>> tagged = registry.get(DialogTags.PAUSE_SCREEN_ADDITIONS);
            if (tagged.isPresent() && tagged.get().size() > 0) return tagged.get().size() == 1 ? Optional.of(tagged.get().get(0)) : registry.get(Dialogs.CUSTOM_OPTIONS);
            ServerLinks links = minecraft.player.connection.serverLinks();
            return links.isEmpty() ? Optional.empty() : registry.get(Dialogs.SERVER_LINKS);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private float in(long now, long delay) {
        return Ui.ease(Math.min(1f, Math.max(0f, (now - opened - delay) / (float) IN_MS)));
    }

    @Override
    public void act(String id) {
        switch (id) {
            case "back" -> { minecraft.setScreen(null); minecraft.mouseHandler.grabMouse(); }
            case "advancements" -> minecraft.setScreen(new AdvancementsScreen(minecraft.player.connection.getAdvancements(), this));
            case "stats" -> minecraft.setScreen(new StatsScreen(this, minecraft.player.getStats()));
            case "options" -> minecraft.setScreen(new OptionsScreen(this, minecraft.options));
            case "settings" -> { ClickGui.showSettings(); minecraft.setScreen(new ClickGui(this)); }
            case "lan" -> minecraft.setScreen(new ShareToLanScreen(this));
            case "social" -> minecraft.setScreen(new SocialInteractionsScreen(this));
            case "dialog" -> additions().ifPresent(h -> minecraft.player.connection.showDialog(h, this));
            case "feedback" -> ConfirmLinkScreen.confirmLinkNow(this, SharedConstants.getCurrentVersion().stable() ? CommonLinks.RELEASE_FEEDBACK : CommonLinks.SNAPSHOT_FEEDBACK);
            case "disconnect" -> {
                if (leaving) return;
                leaving = true;
                minecraft.getReportingContext().draftReportHandled(minecraft, this, () -> minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE), true);
            }
            case "hud" -> minecraft.setScreen(new HudEditScreen(this));
            case "friends" -> minecraft.setScreen(new FriendsScreen(this));
            case "waypoints" -> minecraft.setScreen(new WaypointScreen(this));
            default -> Vantage.LOG.info("Pause screen: no action {}", id);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        String id = kit.hit(e.x(), e.y());
        if (id != null) { act(id); return true; }
        return false;
    }
}
