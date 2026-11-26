package dev.vantage.gui;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.lwjgl.glfw.GLFW;

/** Singleplayer: the saved worlds as rows, Play, Create, Edit and Delete as buttons. Same world flows as vanilla underneath. */
public class VantageWorldsScreen extends Screen implements VantageScreen {
    private static final SimpleDateFormat WHEN = new SimpleDateFormat("d MMM yyyy, HH:mm");
    private final Screen parent;
    private final MenuKit kit = new MenuKit();
    private List<LevelSummary> worlds = new ArrayList<>();
    private String status = "Reading your saves";
    private int selected = -1, scroll;
    private long opened;

    public VantageWorldsScreen(Screen parent) {
        super(Component.translatable("selectWorld.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        opened = System.currentTimeMillis();
        refresh();
    }

    private void refresh() {
        try {
            LevelStorageSource.LevelCandidates found = minecraft.getLevelSource().findLevelCandidates();
            if (found.isEmpty()) { worlds = new ArrayList<>(); status = "No worlds yet"; return; }
            status = "Reading your saves";
            minecraft.getLevelSource().loadLevelSummaries(found).thenAcceptAsync(list -> {
                worlds = new ArrayList<>(list);
                status = worlds.isEmpty() ? "No worlds yet" : worlds.size() + " worlds";
                if (selected >= worlds.size()) selected = worlds.isEmpty() ? -1 : 0;
                if (selected < 0 && !worlds.isEmpty()) selected = 0;
            }, minecraft).exceptionally(t -> { status = "Could not read the saves"; Vantage.LOG.warn("World list", t); return null; });
        } catch (Exception e) {
            status = "Could not read the saves";
            Vantage.LOG.warn("World list", e);
        }
    }

    public List<LevelSummary> worlds() { return worlds; }

    /** One favicon texture per world, loaded from its icon.png once; vanilla's unknown-server picture when it has none. */
    private final java.util.Map<String, net.minecraft.client.gui.screens.FaviconTexture> icons = new java.util.HashMap<>();

    private net.minecraft.resources.Identifier icon(LevelSummary w) {
        var f = icons.get(w.getLevelId());
        if (f == null) {
            f = net.minecraft.client.gui.screens.FaviconTexture.forWorld(minecraft.getTextureManager(), w.getLevelId());
            icons.put(w.getLevelId(), f);
            java.nio.file.Path p = w.getIcon();
            if (p != null && java.nio.file.Files.isRegularFile(p)) {
                try (var in = java.nio.file.Files.newInputStream(p)) { f.upload(com.mojang.blaze3d.platform.NativeImage.read(in)); } catch (Throwable t) { f.clear(); }
            }
        }
        return f.textureLocation();
    }

    @Override
    public void removed() {
        for (var f : icons.values()) f.close();
        icons.clear();
    }

    private int visible() { return (int) ((MenuKit.H() - 100 - MenuKit.PAD) / (MenuKit.ROW + MenuKit.GAP)); }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (minecraft.level == null) renderPanorama(g, delta);
        g.fill(0, 0, width, height, 0x66050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        kit.begin(g, mx, my);
        Ui.alpha = Ui.ease(Math.min(1f, (System.currentTimeMillis() - opened) / 280f));
        kit.window(g, "Singleplayer", status);
        float x = kit.x0(), y = kit.y0();
        float lw = MenuKit.W() - 2 * MenuKit.PAD - 220, lx = x + MenuKit.PAD, ly = y + 92;
        int vis = visible();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, worlds.size() - vis)));
        for (int i = scroll; i < worlds.size() && i < scroll + vis; i++) {
            LevelSummary w = worlds.get(i);
            String sub = w.getGameMode().getName() + "  ·  " + w.getWorldVersionName().getString() + "  ·  " + WHEN.format(new Date(w.getLastPlayed()));
            if (w.isHardcore()) sub = "hardcore  ·  " + sub;
            if (w.isLocked()) sub = "in use  ·  " + sub;
            kit.row(g, "world:" + i, icon(w), w.getLevelName(), sub, w.getLevelId().equals(w.getLevelName()) ? null : w.getLevelId(), lx, ly, lw, i == selected);
            ly += MenuKit.ROW + MenuKit.GAP;
        }
        if (worlds.isEmpty()) Ui.text(g, "Create one on the right, it opens the vanilla creator.", lx, ly + 6, Ui.FAINT, 0.9f, false);
        if (worlds.size() > vis) Ui.right(g, (scroll + 1) + "-" + Math.min(worlds.size(), scroll + vis) + " of " + worlds.size() + "  ·  scroll", lx + lw, y + MenuKit.H() - MenuKit.PAD - 10, Ui.FAINT, 0.78f, false);
        float bx = x + MenuKit.W() - MenuKit.PAD - 200, by = y + 92, bw = 200;
        LevelSummary sel = selected >= 0 && selected < worlds.size() ? worlds.get(selected) : null;
        boolean none = worlds().isEmpty(); // nothing to play yet: creating a world is the main action
        kit.button(g, "play", sel != null ? sel.primaryActionMessage().getString() : "Play", bx, by, bw, !none, sel != null && sel.primaryActionActive());
        kit.button(g, "create", "Create new world", bx, by += 44, bw, none, true);
        kit.button(g, "edit", "Edit", bx, by += 56, bw / 2 - 4, false, sel != null && sel.canEdit());
        kit.button(g, "delete", "Delete", bx + bw / 2 + 4, by, bw / 2 - 4, false, sel != null && sel.canDelete());
        kit.button(g, "refresh", "Refresh", bx, by += 44, bw, false, true);
        kit.button(g, "back", "Back", bx, y + MenuKit.H() - MenuKit.PAD - MenuKit.BTN, bw, false, true);
        Ui.alpha = 1f;
        kit.end(g);
    }

    @Override
    public void act(String id) {
        if (id.startsWith("world:")) {
            int i = Integer.parseInt(id.substring(6));
            if (i == selected) act("play"); else selected = i;
            return;
        }
        LevelSummary sel = selected >= 0 && selected < worlds.size() ? worlds.get(selected) : null;
        switch (id) {
            case "play" -> { if (sel != null && sel.primaryActionActive()) minecraft.createWorldOpenFlows().openWorld(sel.getLevelId(), () -> minecraft.setScreen(this)); }
            case "create" -> CreateWorldScreen.openFresh(minecraft, () -> minecraft.setScreen(this));
            case "edit" -> { if (sel != null) edit(sel); }
            case "delete" -> { if (sel != null) delete(sel); }
            case "refresh" -> refresh();
            case "back" -> Screens.back(parent);
            default -> Vantage.LOG.info("Worlds screen: no action {}", id);
        }
    }

    private void edit(LevelSummary w) {
        LevelStorageSource.LevelStorageAccess access;
        try {
            access = minecraft.getLevelSource().validateAndCreateAccess(w.getLevelId());
        } catch (Exception e) {
            Vantage.LOG.warn("Could not open the world for editing", e);
            status = "Could not open " + w.getLevelName();
            return;
        }
        try {
            minecraft.setScreen(EditWorldScreen.create(minecraft, access, ok -> { access.safeClose(); minecraft.setScreen(this); }));
        } catch (IOException e) {
            access.safeClose();
            throw new ReportedException(CrashReport.forThrowable(e, "Editing world " + w.getLevelId()));
        }
    }

    private void delete(LevelSummary w) {
        minecraft.setScreen(new ConfirmScreen(ok -> {
            if (ok) {
                minecraft.setScreen(new ProgressScreen(true));
                try (LevelStorageSource.LevelStorageAccess a = minecraft.getLevelSource().createAccess(w.getLevelId())) {
                    a.deleteLevel();
                } catch (IOException e) {
                    Vantage.LOG.error("Failed to delete world {}", w.getLevelId(), e);
                }
            }
            minecraft.setScreen(this);
        }, Component.translatable("selectWorld.deleteQuestion"), Component.translatable("selectWorld.deleteWarning", w.getLevelName()),
                Component.translatable("selectWorld.deleteButton"), CommonComponents.GUI_CANCEL));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        String id = kit.hit(e.x(), e.y());
        if (id != null) { act(id); return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, Math.min(Math.max(0, worlds.size() - visible()), scroll - (int) Math.signum(v)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { act("back"); return true; }
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) { act("play"); return true; }
        if (k == GLFW.GLFW_KEY_DOWN && selected < worlds.size() - 1) { selected++; return true; }
        if (k == GLFW.GLFW_KEY_UP && selected > 0) { selected--; return true; }
        return false;
    }

    @Override
    public void onClose() {
        Screens.back(parent);
    }
}
