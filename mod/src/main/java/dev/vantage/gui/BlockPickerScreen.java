package dev.vantage.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.vantage.core.Theme;
import dev.vantage.core.Ui;
import dev.vantage.setting.BlockListSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;

/** Grid of every block with its icon. Click to add/remove; type to filter; Tab shows only the picked ones. */
public class BlockPickerScreen extends Screen {
    private record Entry(String id, String name, Block block, ItemStack icon) {}

    private static final int CELL = 20;
    private final Screen parent;
    private final BlockListSetting setting;
    private final List<Entry> all = new ArrayList<>();
    private List<Entry> view = List.of();
    private String search = "";
    private boolean onlyPicked;
    private int scroll;
    private String hover;

    public BlockPickerScreen(Screen parent, BlockListSetting setting) {
        super(Component.literal(setting.name));
        this.parent = parent;
        this.setting = setting;
        for (Block b : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            String sid = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
            if (sid.equals("air") || sid.endsWith("_air")) continue;
            ItemStack icon = new ItemStack(b.asItem());
            if (icon.isEmpty() || icon.is(Items.AIR)) {
                // blocks without an item (fluids, portals, wall torches...) get a stand-in icon
                icon = new ItemStack(sid.contains("lava") ? Items.LAVA_BUCKET : sid.contains("water") ? Items.WATER_BUCKET
                        : sid.contains("portal") ? Items.OBSIDIAN : sid.contains("fire") ? Items.FLINT_AND_STEEL : Items.BARRIER);
            }
            all.add(new Entry(sid, b.getName().getString(), b, icon));
        }
        refilter();
    }

    private void refilter() {
        String q = search.toLowerCase(Locale.ROOT);
        List<Entry> l = new ArrayList<>();
        for (Entry e : all) {
            if (onlyPicked && !setting.get().contains(e.id)) continue;
            if (!q.isEmpty() && !e.id.contains(q) && !e.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            l.add(e);
        }
        view = l;
        scroll = 0;
    }

    private int cols() {
        return Math.max(4, Math.min(24, (width - 40) / CELL));
    }

    private int gridX() {
        return (width - cols() * CELL) / 2;
    }

    private int gridY() {
        return setting.presets.isEmpty() ? 44 : 60;
    }

    /** Preset chips sit under the search field: {x, width} in GUI pixels, in the order of the presets. */
    private List<int[]> chips() {
        float k = Ui.scale();
        List<int[]> l = new ArrayList<>();
        int x = gridX();
        for (String n : setting.presets.keySet()) {
            int w = Math.round((Ui.width(n, 0.84f, false) + 14) * k);
            l.add(new int[] {x, w});
            x += w + 4;
        }
        return l;
    }

    private static final int CHIP_Y = 40, CHIP_H = 15;

    private void applyPreset(String name) {
        var match = setting.presets.get(name);
        List<String> ids = new ArrayList<>();
        for (Entry e : all) if (match.test(e.id)) ids.add(e.id);
        List<String> now = new ArrayList<>(setting.get());
        if (now.containsAll(ids)) now.removeAll(ids);
        else for (String id : ids) if (!now.contains(id)) now.add(id);
        setting.set(List.copyOf(now));
        if (onlyPicked) refilter();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x58050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        hover = null;
        int accent = Theme.accent();
        int cols = cols(), x0 = gridX();
        int sx = gridX(), sw = cols() * CELL;
        // the frame, headline and search field are drawn with the kit; the grid stays in GUI pixels so the item icons are crisp
        float k = Ui.scale();
        Ui.begin(g);
        Ui.rr(g, (sx - 12) / k, 0, (sw + 24) / k, Ui.H(), 0, Ui.WIN, Ui.WIN_LINE);
        Ui.serif(g, setting.name, (sx + 2) / k, 4 / k, Ui.TEXT, 1.5f, false);
        Ui.right(g, setting.get().size() + " picked  ·  " + (onlyPicked ? "Tab shows all" : "Tab shows picked"), (sx + sw) / k, 8 / k, Ui.FAINT, 0.82f, false);
        boolean typing = !search.isEmpty();
        Ui.rr(g, sx / k, 22 / k, sw / k, 14 / k, 7 / k, 0x40101522, typing ? accent : 0xFF262A52);
        Ui.text(g, typing ? search + "_" : "Type to search blocks", (sx + 7) / k, 25.5f / k, typing ? Ui.TEXT : Ui.FAINT, 0.84f, false);
        if (!setting.presets.isEmpty()) {
            int i = 0;
            for (String n : setting.presets.keySet()) {
                int[] c = chips().get(i++);
                boolean hv = mx >= c[0] && mx < c[0] + c[1] && my >= CHIP_Y && my < CHIP_Y + CHIP_H;
                boolean full = true;
                for (Entry e : all) if (setting.presets.get(n).test(e.id) && !setting.get().contains(e.id)) { full = false; break; }
                Ui.rr(g, c[0] / k, CHIP_Y / k, c[1] / k, CHIP_H / k, 7 / k, full ? (accent & 0xFFFFFF) | 0x50000000 : hv ? 0x40808CFF : 0x40101522, full ? accent : 0xFF262A52);
                Ui.center(g, n, (c[0] + c[1] / 2f) / k, (CHIP_Y + 3f) / k, Ui.TEXT, 0.84f, false);
            }
        }
        Ui.end(g);

        int visRows = (height - gridY() - 20) / CELL;
        int maxScroll = Math.max(0, (view.size() + cols - 1) / cols - visRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int start = scroll * cols;
        for (int i = start; i < view.size() && i < start + visRows * cols; i++) {
            Entry e = view.get(i);
            int cx = x0 + ((i - start) % cols) * CELL, cy = gridY() + ((i - start) / cols) * CELL;
            boolean picked = setting.get().contains(e.id);
            boolean hov = mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
            if (picked) g.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, (accent & 0xFFFFFF) | 0x50000000);
            if (hov) {
                g.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, Theme.BG_HOVER);
                hover = e.name + "  (" + e.id + ")" + (picked ? "  picked" + (setting.colors != null ? (setting.colors.off(e.id) ? ", OFF" : "") : "") : "");
            }
            if (picked) g.renderOutline(cx, cy, CELL - 1, CELL - 1, accent);
            g.renderItem(e.icon, cx + 2, cy + 2);
            if (picked && setting.colors != null) {
                boolean off = setting.colors.off(e.id);
                int col = setting.colors.colorOr0(e.id);
                if (col == 0) col = dev.vantage.module.render.BlockEsp.defaultColor(e.id, e.block);
                g.fill(cx + 1, cy + CELL - 4, cx + CELL - 2, cy + CELL - 2, 0xFF000000 | col);
                if (off) { g.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, 0xA0000000); g.fill(cx + 2, cy + CELL / 2 - 1, cx + CELL - 3, cy + CELL / 2, 0xFFFF4A4A); }
            }
        }
        String foot = hover != null ? hover : setting.colors != null ? "Click = add or remove  ·  right click = colour  ·  middle click = on/off  ·  Esc = back" : "Click to add or remove  ·  Esc = back";
        Ui.begin(g);
        Ui.center(g, foot, Ui.W() / 2, (height - 14) / k, hover != null ? Ui.TEXT : Ui.FAINT, 0.84f, false);
        Ui.end(g);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        int cols = cols(), x0 = gridX();
        if (!setting.presets.isEmpty() && e.y() >= CHIP_Y && e.y() < CHIP_Y + CHIP_H) {
            int i = 0;
            for (String n : setting.presets.keySet()) {
                int[] c = chips().get(i++);
                if (e.x() >= c[0] && e.x() < c[0] + c[1]) { applyPreset(n); return true; }
            }
        }
        int col = (int) Math.floor((e.x() - x0) / CELL), row = (int) Math.floor((e.y() - gridY()) / CELL);
        if (e.y() >= gridY() && col >= 0 && col < cols && row >= 0) {
            int i = (scroll + row) * cols + col;
            if (i < view.size()) {
                Entry en = view.get(i);
                boolean picked = setting.get().contains(en.id);
                if (setting.colors != null && picked && e.button() == 1) {
                    int cur = setting.colors.colorOr0(en.id);
                    setting.colors.cycle(en.id, cur == 0 ? dev.vantage.module.render.BlockEsp.defaultColor(en.id, en.block) : cur);
                } else if (setting.colors != null && picked && e.button() == 2) {
                    setting.colors.toggleOff(en.id);
                } else if (e.button() == 0) {
                    setting.toggle(en.id);
                    if (onlyPicked) { int s = scroll; refilter(); scroll = s; }
                }
            }
            return true;
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll -= (int) Math.signum(v) * 2;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (k == GLFW.GLFW_KEY_TAB) { onlyPicked = !onlyPicked; refilter(); return true; }
        if (k == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) { search = search.substring(0, search.length() - 1); refilter(); return true; }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (e.isAllowedChatCharacter() && search.length() < 32) { search += e.codepointAsString(); refilter(); }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
