package dev.vantage.gui;

import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import dev.vantage.module.cosmetic.CustomFov;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.ChatOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.telemetry.TelemetryInfoScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.world.Difficulty;

/**
 * The Options screen in the menu's look: FOV as our slider, the vanilla pages as rounded buttons in two columns, difficulty
 * (with its lock) in a singleplayer world or Online settings otherwise, and Done. Extends the vanilla screen so everything
 * that asks "is this the options screen" still says yes and its save-on-close still runs; none of the vanilla widgets are
 * built. The pages themselves stay vanilla for now and come back here through their own Done.
 */
public class VantageOptionsScreen extends OptionsScreen implements VantageScreen {
    private final Screen parent;
    private final Options options;
    private final MenuKit kit = new MenuKit();
    private long opened;
    private String drag;
    private static final long IN_MS = 260;
    private static final float LIFT = 10;

    public VantageOptionsScreen(Screen parent, Options options) {
        super(parent, options);
        this.parent = parent;
        this.options = options;
    }

    @Override
    protected void init() {
        opened = System.currentTimeMillis();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (minecraft.level == null) {
            renderPanorama(g, delta);
            g.fill(0, 0, width, height, 0x66050817);
        } else {
            super.renderBackground(g, mx, my, delta);
            g.fill(0, 0, width, height, 0x4A07061A);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        kit.begin(g, mx, my);
        long now = System.currentTimeMillis();
        float x = kit.x0(), y = kit.y0(), W = MenuKit.W(), H = MenuKit.H(), cx = x + W / 2;
        if (minecraft.level == null) Ui.rect(g, 0, 0, Ui.W(), Ui.H(), MenuKit.tint());
        float a = in(now, 0);
        Ui.alpha = a;
        Ui.serif(g, "Options", cx - Ui.serifWidth("Options", 3.2f, false) / 2, y + H * 0.12f + LIFT * (1 - a), Ui.TEXT, 3.2f, false);
        a = in(now, 60); Ui.alpha = a;
        Ui.center(g, "Minecraft's settings, Vantage's own are under Vantage settings", cx, y + H * 0.12f + 56 + LIFT * (1 - a), Ui.SUB, 1f, false);
        float gw = 560, gx = cx - gw / 2, half = gw / 2 - 5, by = y + H * 0.30f;
        // FOV and the online / difficulty side by side, like vanilla's header
        a = in(now, 110); Ui.alpha = a;
        boolean custom = CustomFov.active() != null;
        int fov = CustomFov.shown(minecraft), top = custom ? 160 : 110;
        // with Custom FOV on this slider edits THAT number (30-160): the vanilla one is ignored for the world while it is on
        kit.slider(g, "fov", "FOV", custom ? fov + " (Custom)" : fov == 70 ? "Normal" : fov == 110 ? "Quake Pro" : String.valueOf(fov), (fov - 30) / (float) (top - 30), gx, by + LIFT * (1 - a), half);
        boolean sp = minecraft.level != null && minecraft.hasSingleplayerServer();
        if (sp) {
            boolean locked = minecraft.level.getLevelData().isDifficultyLocked(), hardcore = minecraft.level.getLevelData().isHardcore();
            String d = "Difficulty: " + minecraft.level.getDifficulty().getDisplayName().getString();
            kit.button(g, "difficulty", d, gx + half + 10, by + LIFT * (1 - a), hardcore ? half : half - 44, false, !locked);
            if (!hardcore) kit.button(g, "lock", locked ? "Locked" : "Lock", gx + gw - 40, by + LIFT * (1 - a), 40, false, !locked);
        } else {
            kit.button(g, "online", "Online…", gx + half + 10, by + LIFT * (1 - a), half, false, true);
        }
        // the pages, two columns
        String[][] rows = {
                {"skin", "Skin Customization…", "sounds", "Music & Sounds…"},
                {"video", "Video Settings…", "controls", "Controls…"},
                {"language", "Language…", "chat", "Chat Settings…"},
                {"packs", "Resource Packs…", "accessibility", "Accessibility Settings…"},
                {"telemetry", "Telemetry Data…", "credits", "Credits & Attribution…"}};
        float ry = by + 54;
        for (int i = 0; i < rows.length; i++) {
            a = in(now, 150 + 40 * i); Ui.alpha = a;
            float yy = ry + i * 44 + LIFT * (1 - a);
            kit.button(g, rows[i][0], rows[i][1], gx, yy, half, false, true);
            kit.button(g, rows[i][2], rows[i][3], gx + half + 10, yy, half, false, !rows[i][2].equals("telemetry") || minecraft.allowsTelemetry());
        }
        a = in(now, 360); Ui.alpha = a;
        kit.button(g, "done", "Done", cx - 110, ry + rows.length * 44 + 20 + LIFT * (1 - a), 220, true, true);
        Ui.alpha = 1f;
        kit.end(g);
    }

    private float in(long now, long delay) {
        return Ui.ease(Math.min(1f, Math.max(0f, (now - opened - delay) / (float) IN_MS)));
    }

    @Override
    public void act(String id) {
        switch (id) {
            case "skin" -> minecraft.setScreen(new SkinCustomizationScreen(this, options));
            case "sounds" -> minecraft.setScreen(new SoundOptionsScreen(this, options));
            case "video" -> minecraft.setScreen(new VideoSettingsScreen(this, minecraft, options));
            case "controls" -> minecraft.setScreen(new ControlsScreen(this, options));
            case "language" -> minecraft.setScreen(new LanguageSelectScreen(this, options, minecraft.getLanguageManager()));
            case "chat" -> minecraft.setScreen(new ChatOptionsScreen(this, options));
            case "packs" -> minecraft.setScreen(new PackSelectionScreen(minecraft.getResourcePackRepository(), repo -> {
                options.updateResourcePacks(repo);
                minecraft.setScreen(this);
            }, minecraft.getResourcePackDirectory(), Component.translatable("resourcePack.title")));
            case "accessibility" -> minecraft.setScreen(new AccessibilityOptionsScreen(this, options));
            case "telemetry" -> { if (minecraft.allowsTelemetry()) minecraft.setScreen(new TelemetryInfoScreen(this, options)); }
            case "credits" -> minecraft.setScreen(new CreditsAndAttributionScreen(this));
            case "online" -> minecraft.setScreen(new OnlineOptionsScreen(this, options));
            case "difficulty" -> {
                if (minecraft.level == null || minecraft.getConnection() == null || minecraft.level.getLevelData().isDifficultyLocked()) return;
                Difficulty[] all = Difficulty.values();
                Difficulty next = all[(minecraft.level.getDifficulty().ordinal() + 1) % all.length];
                minecraft.getConnection().send(new ServerboundChangeDifficultyPacket(next));
            }
            case "lock" -> {
                if (minecraft.level == null) return;
                minecraft.setScreen(new ConfirmScreen(yes -> {
                    minecraft.setScreen(this);
                    if (yes && minecraft.getConnection() != null) minecraft.getConnection().send(new ServerboundLockDifficultyPacket(true));
                }, Component.translatable("difficulty.lock.title"), Component.translatable("difficulty.lock.question", minecraft.level.getLevelData().getDifficulty().getDisplayName())));
            }
            case "done" -> onClose();
            case "fov" -> {}
            default -> Vantage.LOG.info("Options screen: no action {}", id);
        }
    }

    /** The FOV slider's track as x and width in design units, for the rig. */
    public float[] fovTrack() { return kit.track("fov"); }

    /** Where Done goes; the rig reads it. */
    public Screen parent() { return parent; }

    private void dragTo(double mx) {
        float[] t = kit.track("fov");
        if (t == null) return;
        float v = (float) ((mx / Ui.scale() - t[0]) / t[1]);
        int top = CustomFov.active() != null ? 160 : 110;
        int fov = 30 + Math.round(Math.max(0f, Math.min(1f, v)) * (top - 30));
        if (fov != CustomFov.shown(minecraft)) CustomFov.edit(minecraft, fov);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        String id = kit.hit(e.x(), e.y());
        if (id == null) return false;
        if (id.equals("fov")) { drag = id; dragTo(e.x()); return true; }
        act(id);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (drag == null) return false;
        dragTo(e.x());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        drag = null;
        return true;
    }
}
