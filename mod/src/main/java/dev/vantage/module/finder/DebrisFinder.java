package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.vantage.core.Toasts;
import dev.vantage.core.Ui;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Ancient debris the client can see: highlight, arrow to the nearest one, ping per vein. On top of that, sections
 * whose palette lists debris while no block of it is left to see get outlined as areas worth digging in: the client
 * knows debris is around there, just not where.
 */
public class DebrisFinder extends ArrowFinder {
    private static final int AREA_COLOR = 0xFFE8B060, BLOCK_COLOR = 0xFF62D6B0;

    public final BoolSetting areas = add(new BoolSetting("Likely areas", "Outline sections whose palette lists debris with none visible", true));
    public final BoolSetting panel = add(new BoolSetting("Panel", "Areas and blocks count on the left", true));

    /** Chunk -> section Y of each area. */
    private final Map<Long, List<Integer>> sections = new HashMap<>();
    private final List<BlockPos> toasted = new ArrayList<>();

    public DebrisFinder() {
        super("Debris Finder", "Arrow + highlight to ancient debris in loaded chunks", List.of("ancient_debris"),
                "Ancient debris", "DEBRIS", "debris", "debris", 6, BLOCK_COLOR);
        range.set(128.0);
        // bottom left: the old fixed spot (left of the middle, y 282) overlapped the Sound Detector panel and the Donut Wheel RTP card
        hudPanel("debris", "Debris panel", "Bottom Left").overScreens().when(panel::on).size(wh -> { wh[0] = 257; wh[1] = 83; }).draw((g, x, y, w, h) -> {
            int a = areaCount(), b = blockCount();
            Ui.rr(g, x, y, w, h, 7, Ui.GLASS, Ui.BORDER);
            Ui.text(g, "ANCIENT DEBRIS", x + 10, y + 8, Ui.TEXT, 1.05f, false);
            Ui.hline(g, x + 10, y + 26, w - 20, Ui.LINE);
            Ui.text(g, "Areas " + a, x + 10, y + 33, Ui.YELLOW, 0.95f, false);
            Ui.right(g, "Blocks " + b, x + w - 10, y + 33, Ui.TEAL, 0.95f, false);
            String msg = dev.vantage.core.FakeOres.detected() ? "Server fakes ores, exposed debris only" : a > 0 ? "Possible debris in outlined areas" : b > 0 ? "Debris in view, follow the arrow" : "No palette search areas nearby";
            Ui.text(g, msg, x + 10, y + 57, Ui.SUB, 0.92f, false);
        });
    }

    public int areaCount() {
        int n = 0;
        for (List<Integer> l : sections.values()) n += l.size();
        return n;
    }

    public int blockCount() {
        return nearest.size();
    }

    /** "x y z" of every section with an area or a visible block, for the test log. */
    public java.util.Set<String> knownSections() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        sections.forEach((k, l) -> { for (int y : l) out.add(ChunkPos.getX(k) + " " + y + " " + ChunkPos.getZ(k)); });
        for (BlockPos p : nearest) out.add((p.getX() >> 4) + " " + (p.getY() >> 4) + " " + (p.getZ() >> 4));
        return out;
    }

    /** The sections' palettes are scanned once per arrival, here; Search queues its block scan on the same event. */
    @Override
    public void onChunkData(LevelChunk chunk) {
        scanAreas(chunk);
        super.onChunkData(chunk);
    }

    private void scanAreas(LevelChunk chunk) {
        // on a server that fakes ores the palette says nothing: debris is in every section
        if (dev.vantage.core.FakeOres.detected()) { sections.clear(); return; }
        List<Integer> found = new ArrayList<>();
        LevelChunkSection[] secs = chunk.getSections();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection s = secs[i];
            // a global palette answers yes to anything, so it says nothing about debris
            if (s.hasOnlyAir() || s.getStates().maybeHas(st -> false) || !s.getStates().maybeHas(st -> st.is(Blocks.ANCIENT_DEBRIS))) continue;
            int[] blocks = new int[1];
            s.getStates().count((st, c) -> { if (st.is(Blocks.ANCIENT_DEBRIS)) blocks[0] += c; });
            if (blocks[0] == 0) found.add(mc.level.getSectionYFromSectionIndex(i));
        }
        long key = chunk.getPos().toLong();
        if (found.isEmpty()) sections.remove(key); else sections.put(key, found);
    }

    @Override
    public void onChunkUnload(int cx, int cz) {
        super.onChunkUnload(cx, cz);
        sections.remove(ChunkPos.asLong(cx, cz));
    }

    @Override
    protected void forgetWorld() {
        super.forgetWorld();
        sections.clear();
        toasted.clear();
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        sections.clear();
        toasted.clear();
        if (mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos pc = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (mc.level.getChunkSource().getChunk(pc.x + x, pc.z + z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) instanceof LevelChunk c) scanAreas(c);
        }
    }

    @Override
    public void onTick() {
        super.onTick();
        if (dev.vantage.core.FakeOres.detected()) sections.clear();
        for (BlockPos p : nearest) {
            boolean known = false;
            for (BlockPos t : toasted) if (t.distSqr(p) < 36) { known = true; break; }
            if (known) continue;
            toasted.add(p);
            Toasts.ok("Ancient Debris Finder", "Ancient debris found at " + p.getX() + ", " + p.getY() + ", " + p.getZ());
        }
    }

    @Override
    public void onRender3D(R3 r) {
        super.onRender3D(r);
        if (!areas.on()) return;
        sections.forEach((key, list) -> {
            int cx = ChunkPos.getX(key) << 4, cz = ChunkPos.getZ(key) << 4;
            for (int sy : list) {
                EspStyle.box(r, new AABB(cx, sy << 4, cz, cx + 16, (sy << 4) + 16, cz + 16), AREA_COLOR | 0xFF000000, true);
            }
        });
    }
}
