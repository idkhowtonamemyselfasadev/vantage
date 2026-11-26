package dev.vantage.module.render;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Safe 1x1 holes for crystal PvP: two air blocks with blast-proof blocks on all four sides and below.
 * Green = all bedrock, yellow = bedrock + obsidian, red = all obsidian.
 */
public class HoleEsp extends Module {
    public final NumberSetting hRange = add(new NumberSetting("Range", "Horizontal search radius", 10, 3, 24, 1));
    public final NumberSetting vRange = add(new NumberSetting("Vertical", "Blocks up and down", 5, 2, 16, 1));
    public final ModeSetting mode = add(new ModeSetting("Mode", "Floor square, box or both", "Both", "Floor", "Box", "Both"));
    public final NumberSetting boxHeight = add(new NumberSetting("Box height", "Height of the box", 0.6, 0.1, 2, 0.1)).visibleIf(() -> !mode.is("Floor"));
    public final BoolSetting doubles = add(new BoolSetting("Double holes", "2x1 holes too", true));
    public final BoolSetting throughWalls = add(new BoolSetting("Through walls", "Show holes behind blocks", false));
    public final ColorSetting bedrock = add(new ColorSetting("Bedrock", "All-bedrock hole", 0xFF6CBC7A));
    public final ColorSetting mixed = add(new ColorSetting("Mixed", "Bedrock + obsidian", 0xFFE0BD62));
    public final ColorSetting obsidian = add(new ColorSetting("Obsidian", "All-obsidian hole", 0xFFDE6F4D));

    private record Hole(AABB floor, int color) {}

    private List<Hole> holes = List.of();
    private int timer;

    public HoleEsp() {
        super("Hole ESP", "Shows safe bedrock/obsidian holes for crystal PvP", Category.RENDER);
    }

    @Override
    public String info() {
        return String.valueOf(holes.size());
    }

    /** 2 = bedrock, 1 = obsidian-grade, 0 = not safe. */
    private static int hard(BlockState s) {
        if (s.is(Blocks.BEDROCK)) return 2;
        if (s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN) || s.is(Blocks.NETHERITE_BLOCK)
                || s.is(Blocks.RESPAWN_ANCHOR) || s.is(Blocks.ENDER_CHEST) || s.is(Blocks.ANVIL)
                || s.is(Blocks.CHIPPED_ANVIL) || s.is(Blocks.DAMAGED_ANVIL) || s.is(Blocks.REINFORCED_DEEPSLATE)) return 1;
        return 0;
    }

    private boolean air(BlockPos p) {
        return mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
    }

    @Override
    public void onTick() {
        if (--timer > 0) return;
        timer = 6; // a 21x21x11 sweep is ~0.8 ms: every 6 ticks keeps it off the frame time without feeling late
        List<Hole> out = new ArrayList<>();
        BlockPos c = mc.player.blockPosition();
        int h = hRange.i(), v = vRange.i();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -h; dx <= h; dx++) for (int dz = -h; dz <= h; dz++) for (int dy = -v; dy <= v; dy++) {
            p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
            if (!air(p) || !air(p.above()) || !air(p.above(2))) continue;
            int below = hard(mc.level.getBlockState(p.below()));
            if (below == 0) continue;
            int bed = below == 2 ? 1 : 0, obs = below == 1 ? 1 : 0;
            Direction open = null;
            boolean ok = true;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int k = hard(mc.level.getBlockState(p.relative(d)));
                if (k == 2) bed++;
                else if (k == 1) obs++;
                else if (doubles.on() && open == null && air(p.relative(d))) open = d;
                else { ok = false; break; }
            }
            if (!ok) continue;
            if (open == null) {
                out.add(new Hole(new AABB(p), obs == 0 ? bedrock.argb() : bed == 0 ? obsidian.argb() : mixed.argb()));
                continue;
            }
            // double hole: only report it once, from its north/west half
            if (open != Direction.SOUTH && open != Direction.EAST) continue;
            BlockPos q = p.relative(open);
            if (!air(q.above())) continue;
            int qb = hard(mc.level.getBlockState(q.below()));
            if (qb == 0) continue;
            if (qb == 2) bed++; else obs++;
            boolean ok2 = true;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (d == open.getOpposite()) continue;
                int k = hard(mc.level.getBlockState(q.relative(d)));
                if (k == 2) bed++; else if (k == 1) obs++; else { ok2 = false; break; }
            }
            if (!ok2) continue;
            out.add(new Hole(new AABB(p).minmax(new AABB(q)), obs == 0 ? bedrock.argb() : bed == 0 ? obsidian.argb() : mixed.argb()));
        }
        holes = out;
    }

    @Override
    public void onRender3D(R3 r) {
        boolean xr = throughWalls.on();
        for (Hole h : holes) {
            AABB b = h.floor();
            int c = h.color();
            // floor alone = square with outline and fill; with the box the floor is only the fill under its outline
            if (mode.is("Floor")) EspStyle.zone(r, b.minX, b.minZ, b.maxX, b.maxZ, b.minY + 0.01, c, true, true, xr);
            else if (mode.is("Both")) r.flat(b.minX, b.minZ, b.maxX, b.maxZ, b.minY + 0.01, EspStyle.fill(c, true, 1f), xr);
            if (!mode.is("Floor")) {
                AABB box = new AABB(b.minX, b.minY, b.minZ, b.maxX, b.minY + boxHeight.get(), b.maxZ);
                EspStyle.box(r, box, c, xr);
            }
        }
    }
}
