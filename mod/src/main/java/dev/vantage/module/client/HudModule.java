package dev.vantage.module.client;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Category;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Theme;
import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.util.Dims;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import dev.vantage.setting.Setting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/** Name, coordinates (with the other dimension's), facing and FPS. */
public class HudModule extends Module {
    private static final java.time.format.DateTimeFormatter CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
    public final ModeSetting corner = add(new ModeSetting("Corner", "Where the lines go", "Bottom Right", Hud.CORNERS));
    public final BoolSetting pingSound = add(new BoolSetting("Ping sound", "Ding on finder alerts", false));
    public final BoolSetting watermark = add(new BoolSetting("Watermark", "Mod name", true));
    public final ModeSetting wmStyle = add(new ModeSetting("Watermark style", "Plain name or a bar with fps, ping and time", "Bar", "Text", "Bar")).visibleIf(watermark::on);
    public final BoolSetting coords = add(new BoolSetting("Coords", "Your position", true));
    public final BoolSetting otherDim = add(new BoolSetting("Nether coords", "Matching overworld/nether position", true));
    public final BoolSetting facing = add(new BoolSetting("Facing", "Direction you look", true));
    public final BoolSetting fps = add(new BoolSetting("FPS", "Frames per second", true));
    public final NumberSetting uiSize = add(new NumberSetting("Panel size", "Size of the panels, cards and the menu", 1, 0.6, 1.6, 0.05));

    public HudModule() {
        super("HUD", "Coordinates, direction and FPS on screen", Category.CLIENT);
        onByDefault();
        HudElements.declare("coords", "Coords", "Bottom Right", HudModule.class);
        HudElements.declare("watermark", "Watermark", "Top Left", HudModule.class);
        Ui.panelSize = uiSize.f();
    }

    @Override
    protected void onSettingChanged(Setting<?> setting) {
        if (setting == uiSize) Ui.panelSize = uiSize.f();
    }

    /** The text lines the panel draws right now ({label, value}); the rig reads what streamer mode left of them. */
    public List<String[]> textLines() {
        List<String[]> lines = new ArrayList<>(); // {label, value}
        if (watermark.on() && !wmStyle.is("Bar")) lines.add(new String[] {Vantage.NAME, ""});
        if (coords.on()) {
            var p = mc.player.position();
            boolean hide = dev.vantage.core.Streamer.hidesCoords(); // streamer mode
            lines.add(new String[] {"XYZ ", hide ? dev.vantage.core.Streamer.HIDDEN : String.format("%.0f %.0f %.0f", p.x, p.y, p.z)});
            if (otherDim.on() && !hide) {
                String dim = mc.level.dimension().identifier().getPath();
                if (Dims.isNether(dim)) lines.add(new String[] {"Overworld ", String.format("%.0f %.0f", Dims.netherToOverworld(p.x), Dims.netherToOverworld(p.z))});
                else if (Dims.isOverworld(dim)) lines.add(new String[] {"Nether ", String.format("%.0f %.0f", Dims.overworldToNether(p.x), Dims.overworldToNether(p.z))});
            }
        }
        if (facing.on()) {
            String[] n = {"South +Z", "West -X", "North -Z", "East +X"};
            int i = Math.floorMod(Math.round(mc.player.getYRot() / 90f), 4);
            lines.add(new String[] {"Facing ", n[i]});
        }
        if (fps.on()) lines.add(new String[] {"FPS ", String.valueOf(mc.getFps())});
        return lines;
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (watermark.on() && wmStyle.is("Bar")) drawBar(g);
        List<String[]> lines = textLines();
        if (lines.isEmpty()) return;
        String cn = corner.get();
        float s = Ui.scale(), lh = 12, pad = 8;
        float w = 0;
        for (String[] l : lines) w = Math.max(w, Ui.width(l[0], 0.9f, false) + Ui.width(l[1], 0.9f, false));
        w += 2 * pad;
        float h = lines.size() * lh + 2 * pad - 3;
        float[] at = Hud.slot("coords", cn, w, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.panel(g, x, y, w, h);
        float ly = y + pad - 1;
        for (String[] l : lines) {
            if (l[1].isEmpty()) Ui.serif(g, l[0], x + pad, ly - 2, Ui.TEXT, 1.3f, false);
            else {
                Ui.text(g, l[0], x + pad, ly, Ui.FAINT, 0.9f, false);
                Ui.text(g, l[1], x + pad + Ui.width(l[0], 0.9f, false), ly, Ui.TEXT, 0.9f, false);
            }
            ly += lh;
        }
        Ui.end(g);
    }

    /** Top-left bar: name | fps | ping | time, with an accent line on top. */
    private void drawBar(GuiGraphics g) {
        var font = mc.font;
        List<String> parts = new ArrayList<>();
        parts.add(String.valueOf(mc.getFps()) + " fps");
        var conn = mc.getConnection();
        var info = conn == null ? null : conn.getPlayerInfo(mc.player.getUUID());
        if (info != null && !mc.isLocalServer()) parts.add(info.getLatency() + " ms");
        parts.add(java.time.LocalTime.now().format(CLOCK));
        // a glass pill: the name in the serif, then fps, ping and time with thin dividers
        String name = Vantage.NAME;
        float s = Ui.scale(), h = 22, pad = 10;
        float w = pad + Ui.serifWidth(name, 1.35f, false) + 10;
        for (String p : parts) w += Ui.width(p, 0.88f, false) + 20;
        w += pad - 10;
        float[] at = Hud.slot("watermark", "Top Left", w, h);
        if (at == null) return;
        Ui.begin(g);
        float x = at[0], y = at[1];
        Ui.rr(g, x, y, w, h, 7, Ui.GLASS, Ui.BORDER);
        Ui.rr(g, x, y + 5, 2.5f, h - 10, 1.2f, Theme.accent(), 0);
        float tx = x + pad;
        Ui.serif(g, name, tx, y + 3, Ui.TEXT, 1.35f, false);
        tx += Ui.serifWidth(name, 1.35f, false) + 10;
        for (String p : parts) {
            Ui.vline(g, tx, y + 6, h - 12, Ui.LINE);
            tx += 10;
            Ui.text(g, p, tx, y + 7, Ui.SUB, 0.88f, false);
            tx += Ui.width(p, 0.88f, false) + 10;
        }
        Ui.end(g);
    }
}
