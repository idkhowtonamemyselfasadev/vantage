package dev.vantage.module.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.vantage.core.Category;
import dev.vantage.core.Hud;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Theme;
import dev.vantage.core.Ui;
import dev.vantage.core.hud.HudElements;
import dev.vantage.core.util.Colors;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ModeSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/** The list of modules that are on, longest first, sliding in and out at the screen edge. */
public class ModuleList extends Module {
    public final ModeSetting corner = add(new ModeSetting("Corner", "Where the list goes", "Top Right", Hud.CORNERS));
    public final BoolSetting info = add(new BoolSetting("Info", "Counts next to the names", true));
    public final BoolSetting background = add(new BoolSetting("Background", "Dark strip behind each line", true));
    public final BoolSetting bar = add(new BoolSetting("Side bar", "Accent line at the screen edge", true));
    public final ModeSetting style = add(new ModeSetting("Style", "How the names are coloured", "Gradient", "Static", "Gradient", "Rainbow"));
    public final dev.vantage.setting.ColorSetting second = add(new dev.vantage.setting.ColorSetting("Second color", "Other end of the gradient", 0xFF78A8A8)).visibleIf(() -> style.is("Gradient"));
    public final BoolSetting lower = add(new BoolSetting("Lowercase", "write names in lowercase", false));

    private final Map<Module, Float> anim = new HashMap<>();

    static {
        HudElements.declare("modules", "Module List", "Top Right", ModuleList.class);
    }

    public ModuleList() {
        super("Module List", "Shows which modules are on", Category.RENDER);
        onByDefault();
    }

    private String name(Module m) {
        return lower.on() ? m.name.toLowerCase() : m.name;
    }

    private String text(Module m) {
        String i = info.on() ? m.info() : null;
        return i == null ? name(m) : name(m) + " " + i;
    }

    /** Colour of line i: one accent, a moving wave between two colours, or a moving rainbow. */
    private int lineColor(int i) {
        double t = System.currentTimeMillis() / 1000.0;
        if (style.is("Rainbow")) {
            float h = (float) ((t * 0.15 + i * 0.045) % 1.0);
            return Colors.fromHsv(h, 0.45f, 0.95f, 255);
        }
        int a = Theme.accent();
        if (!style.is("Gradient")) return a;
        double f = 0.5 + 0.5 * Math.sin(t * 2.0 - i * 0.35);
        return Colors.opaque(Colors.mix(a, second.argb(), (float) f));
    }

    /** Has a line in the list: not a client module, and not a part unless it runs by hand while its owner is off (beta). */
    public static boolean lists(Module m) {
        return m.category != Category.CLIENT && (m.owner == null || m.aloneByHand());
    }

    /** Rig: where the last accent bar was drawn. */
    public static float lastBarX;

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        List<Module> mods = new ArrayList<>();
        float step = Math.min(1f, delta.getRealtimeDeltaTicks() * 0.25f);
        for (Module m : Modules.all()) {
            if (m == this || !lists(m)) continue;
            float a = anim.getOrDefault(m, 0f);
            a += ((m.on() ? 1f : 0f) - a) * step;
            if (Math.abs(a - (m.on() ? 1 : 0)) < 0.01f) a = m.on() ? 1 : 0;
            anim.put(m, a);
            if (a > 0) mods.add(m);
        }
        if (mods.isEmpty()) return;
        float size = 0.95f;
        mods.sort((a, b) -> Float.compare(Ui.width(text(b), size, false), Ui.width(text(a), size, false)));
        String cn = corner.get();
        boolean right = Hud.right(cn);
        float s = Ui.scale(), lineH = 13;
        float total = 0, widest = 0;
        for (Module m : mods) { total += lineH * anim.get(m); widest = Math.max(widest, Ui.width(text(m), size, false) + 14); }
        float[] at = Hud.slot("modules", cn, widest, total);
        if (at == null) return;
        Ui.begin(g);
        // the slot is the box around all lines; in a right corner lines hang off its right edge, otherwise off its left
        float sw = at[0] + widest + 3, y = at[1];
        int line = 0;
        for (Module m : mods) {
            float a = anim.get(m);
            int accent = lineColor(line++);
            String name = name(m);
            String inf = info.on() ? m.info() : null;
            float w = Ui.width(text(m), size, false) + 14;
            float slide = (1 - a) * (w + 6);
            float x = right ? sw - w - 3 + slide : at[0] - slide;
            float h = lineH * a;
            if (h < 1) { y += h; continue; }
            // one glass strip per line, rounded on the side away from the screen edge
            if (background.on()) Ui.rr(g, x, y, w, h - 1, 3.5f, Ui.fade(Ui.GLASS, a), 0);
            lastBarX = right ? sw - 3 : Math.max(0, at[0] - 3); // beside the list wherever it sits; a left stack starts at the screen edge
            if (bar.on()) Ui.rr(g, lastBarX, y, 3, h - 1, 1.2f, Ui.fade(accent, a), 0);
            Ui.text(g, name, x + 7, y + 2.5f, Ui.fade(accent, a), size, false);
            if (inf != null) Ui.text(g, inf, x + 7 + Ui.width(name + " ", size, false), y + 2.5f, Ui.fade(Ui.SUB, a), size, false);
            y += h;
        }
        Ui.end(g);
    }
}
