package dev.vantage.module.finder;

import dev.vantage.core.Category;
import dev.vantage.core.ChunkTracker;
import dev.vantage.core.Module;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.world.level.ChunkPos;

/** Shows which chunks were just generated (red) and which were loaded by someone before (green). */
public class NewChunks extends Module {
    public final ModeSetting style = add(new ModeSetting("Style", "Flat squares, outlines or both", "Fill", "Fill", "Outline", "Both"));
    public final ModeSetting height = add(new ModeSetting("Height", "Draw at your feet or at a fixed y", "Feet", "Feet", "Fixed"));
    public final NumberSetting fixedY = add(new NumberSetting("Y", "Height for the Fixed mode", 64, -64, 320, 1)).visibleIf(() -> height.is("Fixed"));
    public final BoolSetting showNew = add(new BoolSetting("Show new", "Freshly generated chunks", true));
    public final BoolSetting showOld = add(new BoolSetting("Show old", "Chunks someone loaded before", true));
    public final ColorSetting newColor = add(new ColorSetting("New color", "Colour of new chunks", 0x50DE6F4D));
    public final ColorSetting oldColor = add(new ColorSetting("Old color", "Colour of old chunks", 0x406CBC7A));
    public final NumberSetting range = add(new NumberSetting("Range", "Chunks around you to draw", 24, 4, 64, 1));

    public NewChunks() {
        super("New Chunks", "Liquid-flow detection of new vs. already visited chunks", Category.FINDER);
        add(new ActionSetting("Clear", "Forget all detected chunks", ChunkTracker::clearAll));
    }

    @Override
    public String info() {
        return ChunkTracker.newChunks().size() + "/" + ChunkTracker.oldChunks().size();
    }

    @Override
    public void onRender3D(R3 r) {
        double y = height.is("Fixed") ? fixedY.get() : Math.floor(mc.player.getY()) + 0.02;
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z, rr = range.i();
        boolean fill = !style.is("Outline"), outline = !style.is("Fill");
        if (showNew.on()) draw(r, ChunkTracker.newChunks(), newColor.argb(), y, pcx, pcz, rr, fill, outline);
        if (showOld.on()) draw(r, ChunkTracker.oldChunks(), oldColor.argb(), y, pcx, pcz, rr, fill, outline);
    }

    private static void draw(R3 r, java.util.Set<Long> set, int color, double y, int pcx, int pcz, int rr, boolean fill, boolean outline) {
        int line = color | 0xFF000000;
        for (long k : set) {
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            if (Math.abs(cx - pcx) > rr || Math.abs(cz - pcz) > rr) continue;
            double x0 = cx * 16, z0 = cz * 16;
            EspStyle.zone(r, x0, z0, x0 + 16, z0 + 16, y, line, outline, fill, false);
        }
    }
}
