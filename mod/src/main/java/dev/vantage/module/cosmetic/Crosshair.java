package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/** Your own crosshair instead of the vanilla cross: a cross, a dot, or both, with a gap, a thickness, a colour and an outline. */
public class Crosshair extends Module {
    public final ModeSetting style = add(new ModeSetting("Style", "Cross, a dot, or a cross with a dot in the middle", "Cross", "Cross", "Dot", "Cross + dot"));
    public final NumberSetting size = add(new NumberSetting("Length", "Arm length in pixels", 5, 1, 20, 1));
    public final NumberSetting gap = add(new NumberSetting("Gap", "Space in the middle in pixels", 2, 0, 10, 1));
    public final NumberSetting thick = add(new NumberSetting("Thickness", "Arm thickness in pixels", 1, 1, 4, 1));
    public final ColorSetting color = add(new ColorSetting("Colour", "Crosshair colour", 0xFFFFFFFF));
    public final BoolSetting outline = add(new BoolSetting("Outline", "Dark 1 px edge so it stays visible on snow", true));
    public final BoolSetting hitFlash = add(new BoolSetting("Hit flash", "Turns the accent colour for a moment when you hit a mob", true));
    public final BoolSetting thirdPerson = add(new BoolSetting("In third person", "Draw it in third person too", false));
    public static int drawn;
    private long lastHit;

    public Crosshair() {
        super("Crosshair", "A clean crosshair of your own", Category.COSMETIC);
    }

    public static boolean replacesVanilla() {
        Crosshair c = Modules.get(Crosshair.class);
        return c != null && c.on();
    }

    @Override
    public void onTick() {
        if (hitFlash.on() && !Cos.calm() && mc.player != null && mc.player.getLastHurtMobTimestamp() > 0 && mc.player.tickCount - mc.player.getLastHurtMobTimestamp() < 1) lastHit = System.currentTimeMillis();
    }

    @Override
    public void onRender2D(GuiGraphics g, DeltaTracker delta) {
        if (mc.player == null || mc.screen != null) return;
        if (!mc.options.getCameraType().isFirstPerson() && !thirdPerson.on()) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        int len = size.i(), gp = gap.i(), t = thick.i();
        int c = color.argb();
        if (hitFlash.on() && !Cos.calm() && System.currentTimeMillis() - lastHit < 180) c = dev.vantage.core.Theme.accent();
        boolean cross = !style.is("Dot"), dot = !style.is("Cross");
        int half = t / 2;
        if (outline.on()) {
            int o = 0xA0000000;
            if (cross) {
                box(g, cx - gp - len - 1, cy - half - 1, len + 2, t + 2, o);
                box(g, cx + gp - 1, cy - half - 1, len + 2, t + 2, o);
                box(g, cx - half - 1, cy - gp - len - 1, t + 2, len + 2, o);
                box(g, cx - half - 1, cy + gp - 1, t + 2, len + 2, o);
            }
            if (dot) box(g, cx - half - 1, cy - half - 1, t + 2, t + 2, o);
        }
        if (cross) {
            box(g, cx - gp - len, cy - half, len, t, c);
            box(g, cx + gp, cy - half, len, t, c);
            box(g, cx - half, cy - gp - len, t, len, c);
            box(g, cx - half, cy + gp, t, len, c);
        }
        if (dot) box(g, cx - half, cy - half, t, t, c);
        drawn++;
    }

    private static void box(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + Math.max(1, w), y + Math.max(1, h), c);
    }
}
