package dev.vantage.module.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.vantage.core.Category;
import dev.vantage.core.HiddenStorage;
import dev.vantage.core.Module;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Chests, barrels, shulkers, ender chests and other containers, boxed through walls. */
public class StorageEsp extends Module {
    public final ModeSetting mode = add(new ModeSetting("Mode", "How to draw: Outline keeps a wall of chests readable, Fill paints them", "Outline", "Outline", "Fill", "Both"));
    public final NumberSetting fillAlpha = add(new NumberSetting("Fill alpha", "Fill opacity; fills stack up through walls, keep it low", 20, 5, 200, 5)).visibleIf(() -> !mode.is("Outline"));
    public final NumberSetting width = add(new NumberSetting("Line width", "Outline thickness", 2, 1, 5, 0.5));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines from the crosshair", false));
    public final NumberSetting max = add(new NumberSetting("Max boxes", "Nearest ones only", 500, 50, 3000, 50));
    public final BoolSetting chests = add(new BoolSetting("Chests", "Chests and trapped chests", true));
    public final ColorSetting chestColor = add(new ColorSetting("Chest color", "", 0xFFE0A040)).visibleIf(chests::on);
    public final BoolSetting barrels = add(new BoolSetting("Barrels", "", true));
    public final ColorSetting barrelColor = add(new ColorSetting("Barrel color", "", 0xFFB07848)).visibleIf(barrels::on);
    public final BoolSetting shulkers = add(new BoolSetting("Shulkers", "", true));
    public final ColorSetting shulkerColor = add(new ColorSetting("Shulker color", "", 0xFFD070D0)).visibleIf(shulkers::on);
    public final BoolSetting ender = add(new BoolSetting("Ender chests", "", true));
    public final ColorSetting enderColor = add(new ColorSetting("Ender color", "", 0xFF9060E0)).visibleIf(ender::on);
    public final BoolSetting hoppers = add(new BoolSetting("Hoppers", "Hoppers, droppers, dispensers, crafters", false));
    public final ColorSetting hopperColor = add(new ColorSetting("Hopper color", "", 0xFF909890)).visibleIf(hoppers::on);
    public final BoolSetting furnaces = add(new BoolSetting("Furnaces", "Furnaces, smokers, blast furnaces", false));
    public final ColorSetting furnaceColor = add(new ColorSetting("Furnace color", "", 0xFFC8C8C8)).visibleIf(furnaces::on);
    public final BoolSetting other = add(new BoolSetting("Other", "Brewing stands, decorated pots, chiseled shelves", false));
    public final ColorSetting otherColor = add(new ColorSetting("Other color", "", 0xFF7FBEA4)).visibleIf(other::on);

    public final BoolSetting hidden = add(new BoolSetting("Hidden containers", "Ones the server sent as block entities but shows as another block, and ones only heard opening (label \"heard\")", true));

    private record Target(AABB box, int color, boolean hidden, boolean heard, String noun) {}

    private List<Target> targets = List.of();
    /** Block positions (BlockPos.asLong) of everything boxed, so Base ESP does not outline the same chests twice. */
    private java.util.Set<Long> positions = java.util.Set.of();
    private int timer, hiddenCount;

    public StorageEsp() {
        super("Storage ESP", "Boxes around chests, shulkers and other storage", Category.RENDER);
        onByDefault();
    }

    @Override
    public String info() {
        return String.valueOf(targets.size());
    }

    public java.util.Set<Long> positions() {
        return positions;
    }

    public int hiddenCount() {
        return hiddenCount;
    }

    private int colorFor(BlockEntityType<?> t) {
        if (t == BlockEntityType.CHEST || t == BlockEntityType.TRAPPED_CHEST) return chests.on() ? chestColor.argb() : 0;
        if (t == BlockEntityType.BARREL) return barrels.on() ? barrelColor.argb() : 0;
        if (t == BlockEntityType.SHULKER_BOX) return shulkers.on() ? shulkerColor.argb() : 0;
        if (t == BlockEntityType.ENDER_CHEST) return ender.on() ? enderColor.argb() : 0;
        if (t == BlockEntityType.HOPPER || t == BlockEntityType.DISPENSER || t == BlockEntityType.DROPPER || t == BlockEntityType.CRAFTER)
            return hoppers.on() ? hopperColor.argb() : 0;
        if (t == BlockEntityType.FURNACE || t == BlockEntityType.SMOKER || t == BlockEntityType.BLAST_FURNACE) return furnaces.on() ? furnaceColor.argb() : 0;
        if (t == BlockEntityType.BREWING_STAND || t == BlockEntityType.DECORATED_POT || t == BlockEntityType.CHISELED_BOOKSHELF) return other.on() ? otherColor.argb() : 0;
        return 0;
    }

    /** What a box marks, for Declutter's group label ("32 chests"). */
    private static String nounFor(BlockEntityType<?> t) {
        if (t == BlockEntityType.CHEST || t == BlockEntityType.TRAPPED_CHEST) return "chest";
        if (t == BlockEntityType.BARREL) return "barrel";
        if (t == BlockEntityType.SHULKER_BOX) return "shulker";
        if (t == BlockEntityType.ENDER_CHEST) return "ender chest";
        if (t == BlockEntityType.HOPPER || t == BlockEntityType.DISPENSER || t == BlockEntityType.DROPPER || t == BlockEntityType.CRAFTER) return "hopper";
        if (t == BlockEntityType.FURNACE || t == BlockEntityType.SMOKER || t == BlockEntityType.BLAST_FURNACE) return "furnace";
        return "container";
    }

    private int colorFor(BlockEntity be) {
        return colorFor(be.getType());
    }

    @Override
    public void onTick() {
        if (--timer > 0) return;
        timer = 10;
        int r = mc.options.getEffectiveRenderDistance() + 1;
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        List<Target> out = new ArrayList<>();
        int found = 0;
        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                if (!(mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    int c = colorFor(be);
                    if (c != 0) out.add(new Target(shapeBox(be.getBlockPos(), be.getBlockState()), c, false, false, nounFor(be.getType())));
                }
                if (!hidden.on()) continue;
                for (HiddenStorage.Hit h : HiddenStorage.in(cx, cz)) {
                    int c = colorFor(h.type());
                    if (c != 0 && chunk.getBlockEntity(h.pos()) == null) { out.add(new Target(new AABB(h.pos()), c, true, h.heard(), nounFor(h.type()))); found++; }
                }
            }
        }
        if (out.size() > max.i()) {
            var eye = mc.player.position();
            out.sort(Comparator.comparingDouble(t -> t.box().getCenter().distanceToSqr(eye)));
            out = new ArrayList<>(out.subList(0, max.i()));
        }
        targets = out;
        java.util.Set<Long> pos = new java.util.HashSet<>();
        for (Target t : out) pos.add(BlockPos.containing(t.box().getCenter()).asLong());
        positions = pos;
        hiddenCount = found;
    }

    private AABB shapeBox(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getShape(mc.level, pos);
        return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
    }

    @Override
    public void onRender3D(R3 r) {
        java.util.Set<Long> labelled = new java.util.HashSet<>(); // one label per 8-block cell, a row of chests would be unreadable
        for (Target t : targets) {
            r.kind(t.noun());
            Esp.draw(r, t.box(), t.color(), mode.get(), fillAlpha.i(), width.f(), tracers.on());
            if (!t.hidden()) continue;
            var c = t.box().getCenter();
            if (labelled.add(BlockPos.asLong((int) c.x >> 3, (int) c.y >> 3, (int) c.z >> 3))) r.label(c.add(0, 0.8, 0), t.heard() ? "heard" : "hidden", t.color());
        }
    }

    @Override
    public int labelClass() {
        return LABEL_EVIDENCE;
    }

    @Override
    public boolean groupsBoxes() {
        return true;
    }
}
