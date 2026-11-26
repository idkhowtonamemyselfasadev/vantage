package dev.vantage.module.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.chunk.Chunks;
import dev.vantage.render.R3;
import dev.vantage.setting.BlockListSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/** Highlights every block of the chosen types in loaded chunks. */
public class Search extends Module {
    public final BlockListSetting blocks;
    public final ModeSetting colorMode = add(new ModeSetting("Color mode", "Block's own map colour or one colour", "Block", "Block", "Custom"));
    public final ColorSetting color = add(new ColorSetting("Color", "Custom colour", 0xFF78A8A8)).visibleIf(() -> colorMode.is("Custom"));
    public final ModeSetting mode = add(new ModeSetting("Mode", "How to draw", "Both", "Outline", "Fill", "Both"));
    public final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines from the crosshair", false));
    public final NumberSetting max = add(new NumberSetting("Max boxes", "Nearest ones only", 400, 50, 3000, 50));
    public final NumberSetting range = add(new NumberSetting("Range", "Max distance in blocks (0 = all loaded)", 0, 0, 512, 16));

    private final Map<Long, List<BlockPos>> found = new HashMap<>();
    protected List<BlockPos> nearest = List.of();
    /** Fake ores seen on this server: the last scan only kept exposed blocks. */
    private boolean strict;
    private int timer;
    private long lastPc = Long.MIN_VALUE;
    /** One prepared outline per colour group; the groups are rebuilt each frame (a block's map colour is read from the world), the outline only when its blocks differ. */
    private final R3.OutlineCache outlines = new R3.OutlineCache();

    public Search() {
        this("Search", "Highlight chosen blocks anywhere in loaded chunks",
                List.of("diamond_ore", "deepslate_diamond_ore", "ancient_debris", "spawner", "end_portal_frame"));
    }

    /** Block ESP reuses this with its own list, so two different block sets can be highlighted at once. */
    protected Search(String name, String description, List<String> defaults) {
        this(name, description, defaults, Category.RENDER);
    }

    protected Search(String name, String description, List<String> defaults, Category category) {
        super(name, description, category);
        blocks = new BlockListSetting("Blocks", "What to highlight", defaults).onChange(this::rescanAll);
        settings.add(0, blocks);
    }

    @Override
    public String info() {
        int n = 0;
        for (List<BlockPos> l : found.values()) n += l.size();
        return String.valueOf(n);
    }

    @Override
    protected void onEnable() {
        rescanAll();
    }

    @Override
    protected void onDisable() {
        found.clear();
        nearest = List.of();
    }

    @Override
    public void onLeaveWorld() {
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    /** What the scan found belongs to the old world; subclasses add what else they remember. */
    protected void forgetWorld() {
        onDisable();
    }

    protected void rescanAll() {
        found.clear();
        Chunks.cancel(this); // the old scan's jobs would put their results next to the new ones
        if (!on() || mc.level == null || mc.player == null) return;
        int r = scanChunks();
        ChunkPos pc = mc.player.chunkPosition();
        // nearest chunks first
        List<long[]> order = new ArrayList<>();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) order.add(new long[] {ChunkPos.asLong(pc.x + x, pc.z + z), x * x + z * z});
        order.sort(Comparator.comparingLong(a -> a[1]));
        for (long[] o : order) enqueue(o[0]);
    }

    /** How many chunks around the player get scanned. Block ESP lowers it with its own setting. */
    protected int scanChunks() {
        return mc.options.getEffectiveRenderDistance() + 1;
    }

    /**
     * One scan job per chunk in the chunk service: inside the shared budget, a chunk waits once (a burst of block changes that comes
     * while the chunk waits changes nothing), dropped when the chunk unloads or has newer data than the job was queued at. A chunk
     * outside the scan range when its turn comes is left alone; {@link #rescanAll} queues it again when the range grows.
     */
    private void enqueue(long key) {
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        if (Chunks.generation(cx, cz) < 1) return;
        Chunks.submit(this, "scan", cx, cz, budget -> {
            if (mc.level == null || mc.player == null) return true;
            ChunkPos me = mc.player.chunkPosition();
            if (Math.max(Math.abs(cx - me.x), Math.abs(cz - me.z)) > scanChunks()) return true;
            if (mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c) scan(c);
            return true;
        });
    }

    /** The chunk's data arrived, was sent again, or a burst of block changes rewrote it (a server filling or un-filling a slab): one scan, whichever it was. */
    @Override
    public void onChunkData(LevelChunk chunk) {
        enqueue(chunk.getPos().toLong());
    }

    @Override
    public void onChunkUnload(int cx, int cz) {
        found.remove(ChunkPos.asLong(cx, cz));
    }

    @Override
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        List<BlockPos> l = found.get(key);
        boolean want = blocks.contains(state.getBlock());
        if (want) {
            if (l == null) found.put(key, l = new ArrayList<>());
            if (!l.contains(pos)) l.add(pos.immutable());
        } else if (l != null) {
            l.remove(pos);
        }
    }

    @Override
    public void onTick() {
        if (dev.vantage.core.FakeOres.detected() != strict) {
            strict = dev.vantage.core.FakeOres.detected();
            rescanAll();
        }
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z, reach = scanChunks();
        long pcKey = ChunkPos.asLong(pcx, pcz);
        if (pcKey != lastPc) {
            lastPc = pcKey;
            // a shortened scan range follows the player: chunks that were too far when they loaded may be inside it now
            if (reach < mc.options.getEffectiveRenderDistance() + 1) rescanAll();
        }
        if (--timer <= 0) {
            timer = 10;
            List<BlockPos> all = new ArrayList<>();
            for (List<BlockPos> l : found.values()) all.addAll(l);
            var eye = mc.player.blockPosition();
            if (range.get() > 0) {
                double r2 = range.get() * range.get();
                all.removeIf(p -> p.distSqr(eye) > r2);
            }
            all = filter(all);
            if (all.size() > max.i()) {
                all.sort(Comparator.comparingDouble(p -> p.distSqr(eye)));
                all = new ArrayList<>(all.subList(0, max.i()));
            }
            nearest = all;
        }
    }

    /** A finder's own say on which hits count, after the range and before the box limit. Everything by default. */
    protected List<BlockPos> filter(List<BlockPos> all) {
        return all;
    }

    private void scan(LevelChunk chunk) {
        Set<Block> want = blocks.blocks();
        List<BlockPos> out = new ArrayList<>();
        if (!want.isEmpty()) {
            LevelChunkSection[] secs = chunk.getSections();
            int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
            for (int si = 0; si < secs.length; si++) {
                LevelChunkSection s = secs[si];
                if (s.hasOnlyAir()) continue;
                dev.vantage.core.FakeOres.look(s);
                if (!s.getStates().maybeHas(st -> want.contains(st.getBlock()))) continue;
                int by = mc.level.getSectionYFromSectionIndex(si) << 4;
                for (int i = 0; i < 4096; i++) {
                    int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
                    if (want.contains(s.getBlockState(x, y, z).getBlock())) out.add(new BlockPos(bx + x, by + y, bz + z));
                }
            }
        }
        if (dev.vantage.core.FakeOres.detected()) out.removeIf(p -> !dev.vantage.core.FakeOres.exposed(p));
        if (out.isEmpty()) found.remove(chunk.getPos().toLong());
        else found.put(chunk.getPos().toLong(), out);
    }

    @Override
    public void onRender3D(R3 r) {
        boolean custom = colorMode.is("Custom");
        // one outline per colour group: touching blocks of one kind become one vein shape
        java.util.Map<Integer, List<BlockPos>> byColor = new java.util.LinkedHashMap<>();
        for (BlockPos p : nearest) {
            BlockState s = mc.level.getBlockState(p);
            int c = custom ? color.argb() : 0xFF000000 | s.getMapColor(mc.level, p).col;
            if (!custom && (c & 0xFFFFFF) == 0) c = 0xFFD3DCD4;
            byColor.computeIfAbsent(c, k -> new ArrayList<>()).add(p);
        }
        for (var e : byColor.entrySet()) {
            int c = e.getKey();
            r.outline(outlines.get(c, e.getValue()), c, 1.5f, true, mode.is("Outline") ? 0 : 45, !mode.is("Fill"));
            if (tracers.on()) for (BlockPos p : e.getValue()) r.tracer(new AABB(p).getCenter(), c, 1.1f);
        }
        outlines.endFrame();
    }
}
