package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The box on the block you look at, in place of the thin black vanilla one: a coloured outline with a glow and a soft fill that
 * glides from one block to the next instead of jumping, and fades out when you look at nothing. The vanilla outline is not drawn
 * while this is on (LevelRendererMixin). Looks only, nothing a server can see.
 */
public class BlockOutline extends Module {
    public final ColorSetting color = add(new ColorSetting("Colour", "Outline colour", 0xFF7B6BFF));
    public final NumberSetting width = add(new NumberSetting("Line width", "Thickness of the outline", 4, 1, 6, 0.5));
    public final BoolSetting glow = add(new BoolSetting("Glow", "A wide soft line under the outline", true));
    public final NumberSetting fill = add(new NumberSetting("Fill", "How solid the faces are, 0 = none", 14, 0, 60, 1));
    public final ModeSetting shape = add(new ModeSetting("Shape", "Full block or the block's real shape (slabs, stairs, fences)", "Shape", "Shape", "Block"));
    public final BoolSetting glide = add(new BoolSetting("Glide", "Slide to the next block instead of jumping", true));
    public final NumberSetting speed = add(new NumberSetting("Glide speed", "Higher = snappier", 22, 4, 40, 1)).visibleIf(glide::on);
    public final BoolSetting throughWalls = add(new BoolSetting("Through walls", "Keep the box visible when something is in front of it", false));

    private AABB shown;
    private float alpha;
    private long lastNs;
    /** Frames the box was drawn, for the rig. */
    public static int drawn;

    public BlockOutline() {
        super("Block Outline", "A clean coloured box on the block you look at, gliding from block to block", Category.COSMETIC);
        onByDefault();
    }

    public static boolean active() {
        BlockOutline b = Modules.get(BlockOutline.class);
        return b != null && b.on();
    }

    private AABB target() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos p = hit.getBlockPos();
        BlockState s = mc.level.getBlockState(p);
        if (s.isAir()) return null;
        VoxelShape vs = shape.is("Block") ? null : s.getShape(mc.level, p);
        AABB b = vs == null || vs.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : vs.bounds();
        return b.move(p).inflate(0.004);
    }

    @Override
    public void onRender3D(R3 r) {
        if (mc.options.hideGui) { lastNs = 0; return; } // F1 hides the vanilla outline, so ours too
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        AABB to = target();
        float k = glide.on() ? 1 - (float) Math.exp(-speed.f() * dt) : 1;
        if (to != null) {
            // a far jump (teleport, looking across the map) snaps; a neighbour glides
            if (shown == null || shown.getCenter().distanceToSqr(to.getCenter()) > 16) shown = to;
            else shown = lerp(shown, to, k);
            alpha += (1 - alpha) * Math.min(1, k * 1.5f);
        } else {
            alpha += (0 - alpha) * Math.min(1, k * 1.5f);
        }
        if (shown == null || alpha < 0.02f) return;
        int c = color.argb();
        int line = ARGB.color(Math.round(ARGB.alpha(c) * alpha), c);
        boolean xray = throughWalls.on();
        float w = width.f();
        if (glow.on()) r.box(shown, ARGB.color(Math.round(0x70 * alpha), c), w * 3.5f, xray);
        r.box(shown, line, w, xray);
        if (fill.f() > 0) r.fill(shown, ARGB.color(Math.round(fill.f() / 100f * 255 * alpha), c), xray);
        drawn++;
    }

    private static AABB lerp(AABB a, AABB b, float k) {
        return new AABB(a.minX + (b.minX - a.minX) * k, a.minY + (b.minY - a.minY) * k, a.minZ + (b.minZ - a.minZ) * k,
                a.maxX + (b.maxX - a.maxX) * k, a.maxY + (b.maxY - a.maxY) * k, a.maxZ + (b.maxZ - a.maxZ) * k);
    }
}
