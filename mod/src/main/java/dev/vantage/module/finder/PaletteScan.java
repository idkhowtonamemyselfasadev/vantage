package dev.vantage.module.finder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import dev.vantage.core.Category;
import dev.vantage.core.FakeOres;
import dev.vantage.core.Module;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BlockListSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Sections whose palette lists a picked block while none of it is left in the block array: anti-xray rewrote the array but kept
 * the palette. Says which 16x16x16 section, never which block. A block mined out earlier can leave the same trace.
 */
public class PaletteScan extends Module {
    private static final int COLOR = 0xC0E8B060;

    public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "What to look for in the palettes",
            List.of("diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore", "ancient_debris", "budding_amethyst", "spawner")));
    public final NumberSetting range = add(new NumberSetting("Range", "Draw sections this close", 128, 32, 512, 16));

    private record Area(int sy, String what) {}

    private final Map<Long, List<Area>> areas = new HashMap<>();

    public PaletteScan() {
        super("Palette Scan", "Sections whose palette lists blocks anti-xray hid", Category.FINDER);
    }

    @Override
    public String info() {
        return String.valueOf(count());
    }

    public int count() {
        int n = 0;
        for (List<Area> l : areas.values()) n += l.size();
        return n;
    }

    /** "x y z" of every section, for the test log. */
    public Set<String> known() {
        Set<String> out = new TreeSet<>();
        areas.forEach((k, l) -> { for (Area a : l) out.add(ChunkPos.getX(k) + " " + a.sy() + " " + ChunkPos.getZ(k)); });
        return out;
    }

    private void scan(LevelChunk chunk) {
        if (FakeOres.detected()) { areas.clear(); return; } // the palette of a faking server lists everything
        Set<Block> want = blocks.blocks();
        List<Area> found = new ArrayList<>();
        LevelChunkSection[] secs = chunk.getSections();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection s = secs[i];
            PalettedContainer<BlockState> states = s.getStates();
            // a global palette says yes to everything, so "maybeHas(nothing)" being true means it can't tell us anything
            if (s.hasOnlyAir() || states.maybeHas(st -> false) || !states.maybeHas(st -> want.contains(st.getBlock()))) continue;
            Map<Block, Integer> seen = new HashMap<>();
            states.count((st, c) -> { if (want.contains(st.getBlock())) seen.merge(st.getBlock(), c, Integer::sum); });
            if (!seen.isEmpty()) continue; // some of it is really there: Search and Xray cover that
            List<String> names = new ArrayList<>();
            for (Block b : want) {
                if (states.maybeHas(st -> st.is(b))) names.add(BuiltInRegistries.BLOCK.getKey(b).getPath().replace("deepslate_", "").replace('_', ' '));
            }
            found.add(new Area(mc.level.getSectionYFromSectionIndex(i), String.join(", ", new TreeSet<>(names))));
            dev.vantage.core.LeakLog.add("PALETTE", chunk.getPos().getMiddleBlockX(), (mc.level.getSectionYFromSectionIndex(i) << 4) + 8, chunk.getPos().getMiddleBlockZ(), found.get(found.size() - 1).what());
        }
        long key = chunk.getPos().toLong();
        if (found.isEmpty()) areas.remove(key); else areas.put(key, found);
    }

    /** Scans once per arrival: the data event follows the load event for every new chunk, and also covers a re-sent or burst-rewritten one. */
    @Override public void onChunkData(LevelChunk chunk) { scan(chunk); }
    @Override public void onChunkUnload(int cx, int cz) { areas.remove(ChunkPos.asLong(cx, cz)); }
    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        areas.clear();
    }

    @Override
    protected void onEnable() {
        areas.clear();
        if (mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos pc = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (mc.level.getChunkSource().getChunk(pc.x + x, pc.z + z, ChunkStatus.FULL, false) instanceof LevelChunk c) scan(c);
        }
    }

    @Override
    public void onTick() {
        if (FakeOres.detected()) areas.clear();
    }

    @Override
    public void onRender3D(R3 r) {
        double max = range.get() * range.get();
        areas.forEach((key, list) -> {
            int cx = ChunkPos.getX(key) << 4, cz = ChunkPos.getZ(key) << 4;
            for (Area a : list) {
                Vec3 mid = new Vec3(cx + 8, (a.sy() << 4) + 8, cz + 8);
                if (mid.distanceToSqr(r.cam) > max) continue;
                EspStyle.box(r, new AABB(cx, a.sy() << 4, cz, cx + 16, (a.sy() << 4) + 16, cz + 16), COLOR | 0xFF000000, true);
                r.label(mid, a.what(), COLOR | 0xFF000000);
            }
        });
    }

    @Override
    public int labelClass() {
        return LABEL_EVIDENCE;
    }
}
