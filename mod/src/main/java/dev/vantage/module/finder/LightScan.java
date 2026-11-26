package dev.vantage.module.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Toasts;
import dev.vantage.core.util.Cooldown;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Block light the visible blocks can't explain. Light never enters a solid opaque block, so an opaque block that doesn't glow
 * and still has block light above 0 is not what the server says it is: something hidden sits there, or a lit cave was filled in.
 * On a server that hides nothing this finds nothing. The peak level hints at the source.
 */
public class LightScan extends Module {
    private static final int COLOR = 0xFFF0C040;
    private static final int PER_TICK = 6;
    private static final long CONFIRM_MS = 3000;
    /**
     * A job whose chunk is not complete yet (light can arrive before the chunk does, or for a chunk the client dropped) is looked at
     * again once per tick, at most this many ticks (5 seconds at 20 per second), and then given up on, so nothing retries for ever.
     */
    static final int MAX_TRIES = 100;

    public final NumberSetting minLight = add(new NumberSetting("Min light", "Ignore weaker mismatches. 4 skips hidden magma (level 3), which is all over the Nether", 4, 1, 15, 1));
    public final BoolSetting skipLava = add(new BoolSetting("Skip level 15", "Mostly hidden lava, unless you want beacons and lanterns too", false));
    public final BoolSetting toast = add(new BoolSetting("Toast", "Card for each new section", true));
    public final NumberSetting range = add(new NumberSetting("Range", "Draw finds this close", 128, 32, 512, 16));
    public final BoolSetting shell = add(new BoolSetting("Glow shell", "Below Y 0: every lit cell brighter than its sky light, drawn as the shell of the glow. Torches in a buried base show as a glowing bubble in the rock", false));
    public final NumberSetting shellMin = add(new NumberSetting("Shell min light", "Faintest glow the shell keeps", 5, 1, 15, 1)).visibleIf(shell::on);
    public final NumberSetting shellRange = add(new NumberSetting("Shell range", "Chunks around you the shell covers", 8, 2, 16, 1)).visibleIf(shell::on);

    /** lining: the peak touches amethyst block or budding amethyst, so the light is a crystal anti-xray turned into rock. */
    public record Find(BlockPos peak, int level, int cells, boolean lining) {
        /** Glow lichen is level 7 and sits as single cells on cave walls; lava is 15 in big pools. Those are caves, not players. */
        public boolean natural() {
            return !amethyst() && ((level == 7 && cells <= 2) || (level == 15 && cells >= 12) || level < 4);
        }
        /** Buds glow 1, 2 and 4, clusters 5, and only next to the geode's lining. */
        public boolean amethyst() {
            return lining && (level == 1 || level == 2 || level == 4 || level == 5);
        }
        public String what() {
            return amethyst() ? (level == 5 ? "hidden amethyst cluster" : "hidden amethyst bud") : guess(level);
        }
    }
    private record Job(int cx, int cz, int sy, DataLayer light, int tries) {}
    private record Pending(Find find, long due) {}

    private final Map<Long, Find> finds = new HashMap<>(); // by section
    private final Map<Long, Pending> waiting = new HashMap<>(); // seen once, recheck before believing it
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    /** Jobs waiting for their chunk to be complete; they go back into {@link #jobs} at the start of the next tick. */
    private final ArrayDeque<Job> parked = new ArrayDeque<>();
    private final Cooldown toastGate = Cooldown.wall(3000);

    public LightScan() {
        super("Light Scan", "Hidden light sources, from light data the blocks can't explain", Category.FINDER);
        // light data arrives with a chunk and in its own packet (the hook used to be two lines in a mixin)
        listen(dev.vantage.core.event.PacketHandledEvent.class, e -> {
            if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket p) onLight(p.getX(), p.getZ(), p.getLightData());
            else if (e.packet instanceof net.minecraft.network.protocol.game.ClientboundLightUpdatePacket p) onLight(p.getX(), p.getZ(), p.getLightData());
        });
    }

    @Override
    public String info() {
        return String.valueOf(finds.size());
    }

    public List<Find> finds() {
        return new ArrayList<>(finds.values());
    }

    public int pending() {
        return jobs.size() + parked.size();
    }

    /** What usually glows at this level. A hint only. */
    private boolean touchesLining(BlockPos p) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockState n = mc.level.getBlockState(p.relative(d));
            if (n.is(net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK) || n.is(net.minecraft.world.level.block.Blocks.BUDDING_AMETHYST)) return true;
        }
        return false;
    }

    static String guess(int l) {
        return switch (l) {
            case 15 -> "lava, lantern, beacon";
            case 14 -> "torch, end rod";
            case 13 -> "lit furnace";
            case 12, 11 -> "portal, candles";
            case 10 -> "soul torch, crying obsidian";
            case 9, 8 -> "redstone ore, candles";
            case 7 -> "ender chest, table, lichen";
            case 6, 5, 4, 3, 2 -> "amethyst, magma";
            default -> "faint source";
        };
    }

    /** Raw light from a packet. Index 0 is the section below the world. */
    public void onLight(int cx, int cz, ClientboundLightUpdatePacketData data) {
        if (mc.level == null) return;
        // a section that went dark comes in the empty mask, with no array to scan
        BitSet empty = data.getEmptyBlockYMask();
        for (int i = empty.nextSetBit(0); i >= 0; i = empty.nextSetBit(i + 1)) {
            long key = SectionPos.asLong(cx, mc.level.getMinSectionY() - 1 + i, cz);
            finds.remove(key);
            waiting.remove(key);
        }
        BitSet mask = data.getBlockYMask();
        List<byte[]> arrays = data.getBlockUpdates();
        int n = 0;
        for (int i = mask.nextSetBit(0); i >= 0 && n < arrays.size(); i = mask.nextSetBit(i + 1), n++) {
            jobs.add(new Job(cx, cz, mc.level.getMinSectionY() - 1 + i, new DataLayer(arrays.get(n).clone()), 0));
        }
    }

    /** Returns false when the chunk is not complete yet and the job should be tried again later. */
    private boolean scan(Job j) {
        if (!(mc.level.getChunkSource().getChunk(j.cx(), j.cz(), ChunkStatus.FULL, false) instanceof LevelChunk chunk)) return false;
        int idx = mc.level.getSectionIndexFromSectionY(j.sy());
        if (idx < 0 || idx >= chunk.getSections().length) return true;
        LevelChunkSection sec = chunk.getSections()[idx];
        long key = SectionPos.asLong(j.cx(), j.sy(), j.cz());
        int peak = 0, cells = 0, px = 0, py = 0, pz = 0;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int l = j.light().get(x, y, z);
            if (l < minLight.i()) continue;
            BlockState st = sec.getBlockState(x, y, z);
            if (st.getLightEmission() != 0 || !st.canOcclude() || st.getLightBlock() < 15) continue;
            cells++;
            if (l > peak) { peak = l; px = x; py = y; pz = z; }
        }
        if (cells == 0 || (skipLava.on() && peak == 15)) { finds.remove(key); waiting.remove(key); return true; }
        BlockPos at = new BlockPos((j.cx() << 4) + px, (j.sy() << 4) + py, (j.cz() << 4) + pz);
        waiting.put(key, new Pending(new Find(at, peak, cells, touchesLining(at)), System.currentTimeMillis() + CONFIRM_MS));
        return true;
    }

    /**
     * Lava turning to stone can reach us before the light update that goes with it, so a first sighting proves nothing. Real
     * hidden sources keep their light in the client's engine; stale ones get relit away by the block update.
     */
    private void confirm(long key, Find f) {
        BlockState st = mc.level.getBlockState(f.peak());
        if (mc.level.getBrightness(LightLayer.BLOCK, f.peak()) == 0 || st.getLightEmission() != 0 || !st.canOcclude()) { finds.remove(key); return; }
        boolean fresh = finds.put(key, f) == null;
        if (fresh) dev.vantage.core.LeakLog.add("LIGHT", f.peak().getX(), f.peak().getY(), f.peak().getZ(), "level " + f.level() + " cells " + f.cells() + (f.natural() ? " natural" : "") + (f.amethyst() ? " amethyst" : ""));
        if (fresh && toast.on() && !f.natural() && toastGate.tryUse()) {
            BlockPos at = f.peak();
            Toasts.ok("Light Scan", (f.amethyst() ? "Hidden amethyst" : "Light " + f.level() + " inside solid block") + " at " + at.getX() + " " + at.getY() + " " + at.getZ());
        }
    }

    @Override
    public void onTick() {
        if (mc.level != null && mc.player != null) tickShell();
        jobs.addAll(parked);
        parked.clear();
        for (int i = 0; i < PER_TICK && !jobs.isEmpty() && dev.vantage.core.ScanBudget.go(i); i++) {
            long b0 = System.nanoTime();
            Job j = jobs.poll();
            if (!scan(j) && j.tries() < MAX_TRIES) parked.add(new Job(j.cx(), j.cz(), j.sy(), j.light(), j.tries() + 1));
            dev.vantage.core.ScanBudget.spend(System.nanoTime() - b0);
        }
        long now = System.currentTimeMillis();
        for (var it = waiting.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (now < e.getValue().due()) continue;
            it.remove();
            confirm(e.getKey(), e.getValue().find());
        }
    }

    @Override
    protected void onEnable() {
        reset();
        if (mc.level == null || mc.player == null) return;
        // chunks from before the switch: the light engine still holds what the server sent
        var engine = mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos pc = mc.player.chunkPosition();
        for (int x = pc.x - r; x <= pc.x + r; x++) for (int z = pc.z - r; z <= pc.z + r; z++) {
            if (mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) continue;
            for (int sy = mc.level.getMinSectionY(); sy <= mc.level.getMaxSectionY(); sy++) {
                DataLayer d = engine.getDataLayerData(SectionPos.of(x, sy, z));
                if (d != null && !d.isEmpty()) jobs.add(new Job(x, z, sy, d.copy(), 0));
            }
        }
    }

    private void reset() {
        finds.clear();
        waiting.clear();
        jobs.clear();
        parked.clear();
        shellByChunk.clear();
        shellDraw = List.of();
        shellOrder = List.of();
    }

    @Override public void onDisable() { reset(); }
    @Override
    public void onLeaveWorld() {
        reset();
    }

    @Override
    public void onDimensionChange() {
        reset();
    }

    @Override
    public void onChunkUnload(int cx, int cz) {
        finds.keySet().removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz);
        waiting.keySet().removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz);
        parked.removeIf(j -> j.cx() == cx && j.cz() == cz);
    }

    @Override
    public void onRender3D(R3 r) {
        double max = range.get() * range.get();
        for (Find f : finds.values()) {
            Vec3 mid = f.peak().getCenter();
            if (mid.distanceToSqr(r.cam) > max) continue;
            int c = f.natural() ? 0x80F0C040 : COLOR;
            EspStyle.box(r, new AABB(f.peak()), c, true);
            r.label(mid.add(0, 0.8, 0), "light " + f.level() + "  " + (f.natural() ? "probably " : "") + f.what(), f.amethyst() ? 0xFFC882FF : c);
        }
        drawShell(r);
    }

    // ---- glow shell: the light itself, below Y 0 -----------------------------------------------------------------------------

    private static final int SHELL_CHUNKS_PER_PASS = 16, SHELL_CAP = 60_000, SHELL_DRAW = 2500;
    private final Map<Long, int[]> shellLevel = new HashMap<>(); // block pos -> {level, chunk index marker}
    private final Map<Long, long[]> shellByChunk = new HashMap<>();
    private List<long[]> shellDraw = List.of(); // {pos, level}
    private List<Long> shellOrder = List.of();
    private int shellCursor;

    public int shellSize() {
        return shellDraw.size();
    }

    public List<Long> shellCells() {
        List<Long> out = new ArrayList<>(shellDraw.size());
        for (long[] h : shellDraw) out.add(h[0]);
        return out;
    }

    /** Lit cells below Y 0 of one chunk: block light at least the minimum and above the sky light there. */
    private long[] collectShell(int cx, int cz) {
        var block = mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        var sky = mc.level.getLightEngine().getLayerListener(LightLayer.SKY);
        List<Long> out = new ArrayList<>();
        int min = shellMin.i();
        for (int sy = mc.level.getMinSectionY(); sy < 0; sy++) {
            DataLayer b = block.getDataLayerData(SectionPos.of(cx, sy, cz));
            if (b == null || b.isEmpty()) continue;
            DataLayer s = sky.getDataLayerData(SectionPos.of(cx, sy, cz));
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                int bl = b.get(x, y, z);
                if (bl < min) continue;
                if (s != null && bl <= s.get(x, y, z)) continue;
                out.add(BlockPos.asLong((cx << 4) + x, (sy << 4) + y, (cz << 4) + z) ^ ((long) bl << 60));
            }
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static long posOf(long packed) { return packed & ~(0xFL << 60); }
    private static int levelOf(long packed) { return (int) ((packed >>> 60) & 0xF); }

    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Weak local peaks (min+1, min+2 with nothing brighter around) are lichen and magma: their patch goes. Then only the shell stays. */
    static List<long[]> shellOf(Map<Long, Integer> map, int min, int cap) {
        java.util.Set<Long> drop = new java.util.HashSet<>();
        for (var e : map.entrySet()) {
            int l = e.getValue();
            if ((l != min + 1 && l != min + 2) || drop.contains(e.getKey())) continue;
            long p = e.getKey();
            boolean peak = true;
            for (int dx = -1; dx <= 1 && peak; dx++) for (int dy = -1; dy <= 1 && peak; dy++) for (int dz = -1; dz <= 1; dz++) {
                Integer o = map.get(BlockPos.offset(p, dx, dy, dz));
                if (o != null && o > l) { peak = false; break; }
            }
            if (!peak) continue;
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(p); drop.add(p);
            while (!q.isEmpty()) {
                long c = q.poll();
                for (int[] d : SIX) { long n = BlockPos.offset(c, d[0], d[1], d[2]); if (map.containsKey(n) && drop.add(n)) q.add(n); }
            }
        }
        List<long[]> shell = new ArrayList<>();
        for (var e : map.entrySet()) {
            if (drop.contains(e.getKey())) continue;
            boolean edge = false;
            for (int[] d : SIX) { long n = BlockPos.offset(e.getKey(), d[0], d[1], d[2]); if (!map.containsKey(n) || drop.contains(n)) { edge = true; break; } }
            if (edge) { shell.add(new long[] {e.getKey(), e.getValue()}); if (shell.size() >= cap) break; }
        }
        return shell;
    }

    private void tickShell() {
        if (!shell.on()) { if (!shellByChunk.isEmpty()) { shellByChunk.clear(); shellDraw = List.of(); } return; }
        if (mc.level.getGameTime() % 20 != 0 && !shellOrder.isEmpty() && shellCursor < shellOrder.size()) {
            // a few chunks a tick, nearest first, so one pass spreads over about a second
            for (int n = 0; n < SHELL_CHUNKS_PER_PASS / 4 && shellCursor < shellOrder.size(); n++, shellCursor++) {
                long k = shellOrder.get(shellCursor);
                if (!mc.level.getChunkSource().hasChunk(ChunkPos.getX(k), ChunkPos.getZ(k))) { shellByChunk.remove(k); continue; }
                shellByChunk.put(k, collectShell(ChunkPos.getX(k), ChunkPos.getZ(k)));
            }
            return;
        }
        if (mc.level.getGameTime() % 20 != 0) return;
        // a pass is done: rebuild the shell, then start the next pass
        Map<Long, Integer> map = new HashMap<>();
        int total = 0;
        for (long[] arr : shellByChunk.values()) {
            for (long packed : arr) { map.put(posOf(packed), levelOf(packed)); if (++total >= SHELL_CAP) break; }
            if (total >= SHELL_CAP) break;
        }
        shellDraw = shellOf(map, shellMin.i(), SHELL_DRAW * 4);
        int r = shellRange.i();
        ChunkPos me = mc.player.chunkPosition();
        List<Long> order = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) order.add(ChunkPos.asLong(me.x + dx, me.z + dz));
        order.sort(java.util.Comparator.comparingInt(k -> { int dx = ChunkPos.getX(k) - me.x, dz = ChunkPos.getZ(k) - me.z; return dx * dx + dz * dz; }));
        shellByChunk.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - me.x), Math.abs(ChunkPos.getZ(k) - me.z)) > r);
        shellOrder = order;
        shellCursor = 0;
    }

    private void drawShell(R3 r) {
        if (!shell.on() || shellDraw.isEmpty()) return;
        Vec3 cam = r.cam;
        List<long[]> near = shellDraw;
        if (near.size() > SHELL_DRAW) {
            near = new ArrayList<>(shellDraw);
            near.sort(java.util.Comparator.comparingDouble(h -> Vec3.atCenterOf(BlockPos.of(h[0])).distanceToSqr(cam)));
            near = near.subList(0, SHELL_DRAW);
        }
        for (long[] h : near) {
            BlockPos p = BlockPos.of(h[0]);
            float t = h[1] / 15f;
            int rr = (int) (0xFF + (255 - 0xFF) * t), gg = (int) (0xCC + (255 - 0xCC) * t), bb = (int) (0x00 + 255 * t);
            int a = Math.round(0x60 * (0.5f + 0.5f * t));
            r.fill(new AABB(p), a << 24 | rr << 16 | gg << 8 | bb, true);
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
