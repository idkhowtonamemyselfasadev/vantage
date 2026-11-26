package dev.vantage.module.finder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Config;
import dev.vantage.core.Module;
import dev.vantage.core.chunk.Chunks;
import dev.vantage.core.store.JsonStore;
import dev.vantage.core.util.Dims;
import dev.vantage.module.render.EspStyle;
import dev.vantage.render.R3;
import dev.vantage.setting.ActionSetting;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Counts storage blocks in every chunk the client receives; a chunk with lots of them is a stash. */
public class StashFinder extends Module {
    public record Find(String dim, int x, int y, int z, int chests, int barrels, int shulkers, int other, long time, Integer pct, String why) {
        public int total() { return chests + barrels + shulkers + other; }
        public String detail() {
            List<String> p = new ArrayList<>();
            if (chests > 0) p.add(chests + " chests");
            if (barrels > 0) p.add(barrels + " barrels");
            if (shulkers > 0) p.add(shulkers + " shulkers");
            if (other > 0) p.add(other + " other");
            return String.join(", ", p);
        }
    }

    public final NumberSetting threshold = add(new NumberSetting("Min storage", "Storage blocks in one chunk (or one pile across a chunk border) before it counts as a stash", 12, 2, 100, 1));
    public final BoolSetting chests = add(new BoolSetting("Chests", "Count chests and trapped chests", true));
    public final BoolSetting barrels = add(new BoolSetting("Barrels", "Count barrels", true));
    public final BoolSetting shulkers = add(new BoolSetting("Shulkers", "Count shulker boxes (each counts double)", true));
    public final BoolSetting hoppers = add(new BoolSetting("Hoppers etc.", "Count hoppers, droppers and dispensers", false));
    public final BoolSetting enderChests = add(new BoolSetting("Ender chests", "Count ender chests", false));
    public final BoolSetting onlyStashes = add(new BoolSetting("Only real stashes", "Skip farm output, sorters, grinders, shops and base storage. Old finds are judged again when you pass them", true));
    public final BoolSetting notify = add(new BoolSetting("Chat alert", "Message + sound when a new stash is found", true));
    public final BoolSetting waypoint = add(new BoolSetting("Auto waypoint", "Drop a waypoint on each new stash", false));
    public final BoolSetting show = add(new BoolSetting("Show in world", "Column + label over every stash", true));
    public final ColorSetting color = add(new ColorSetting("Color", "Marker colour", 0xFFAD879B));

    private final Map<String, Find> finds = new LinkedHashMap<>();
    private final Map<String, Integer> strikes = new java.util.HashMap<>();

    private boolean nearPlayer(LevelChunk chunk) {
        if (mc.player == null) return false;
        double dx = chunk.getPos().getMiddleBlockX() - mc.player.getX(), dz = chunk.getPos().getMiddleBlockZ() - mc.player.getZ();
        return dx * dx + dz * dz <= 48 * 48;
    }

    private static void dropWaypoint(Find f) {
        var wp = Waypoints.get();
        for (var w : new java.util.ArrayList<>(wp.list())) if (w.name != null && w.name.equals("Stash " + f.total()) && w.x == f.x() && w.y == f.y() && w.z == f.z()) wp.remove(w);
    }
    private String loadedFor = "";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public StashFinder() {
        super("Stash Finder", "Pings chunks packed with chests, barrels and shulkers", Category.FINDER);
        add(new ActionSetting("List finds", "Print the last 10 stashes in chat", () -> {
            List<Find> l = new ArrayList<>(finds.values());
            if (l.isEmpty()) Chat.info("no stashes found yet");
            for (int i = Math.max(0, l.size() - 10); i < l.size(); i++) {
                Find f = l.get(i);
                Chat.info(Chat.coords(f.x(), f.y(), f.z()) + "  " + f.detail() + "  " + f.dim().replace("minecraft:", ""));
            }
        }));
        add(new ActionSetting("Clear finds", "Forget every stash on this server", () -> { finds.clear(); save(); Chat.info("stashes cleared"); }));
    }

    @Override
    protected void onEnable() {
        // scan what is already loaded
        if (mc.level == null || mc.player == null) return;
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos pc = mc.player.chunkPosition();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) if (Chunks.generation(pc.x + x, pc.z + z) >= 1) enqueue(pc.x + x, pc.z + z);
    }

    @Override
    public void onJoinWorld() {
        forgetWorld();
    }

    @Override
    public void onLeaveWorld() {
        strikes.clear();
        forgetWorld();
    }

    @Override
    public void onDimensionChange() {
        forgetWorld();
    }

    private void forgetWorld() {
        loadedFor = "";
        waiting.clear(); told.clear(); added.clear();
    }

    /**
     * One scan job per chunk in the chunk service (it runs inside the shared budget, in the order queued, and drops the job when the
     * chunk unloads, changes or the world is left). The job is queued from the data event: the load event comes first, at a generation
     * the data then replaces, so work queued there would be out of date at once. {@code onChunkLoad} queues too, only for callers that
     * announce a chunk themselves (the rig); the service queues a chunk once, so the pair costs nothing.
     */
    private void enqueue(int cx, int cz) {
        Chunks.submit(this, "scan", cx, cz, budget -> {
            if (mc.level != null && mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c) scan(c, false);
            return true;
        });
    }

    @Override
    public void onChunkLoad(LevelChunk chunk) {
        enqueue(chunk.getPos().x, chunk.getPos().z);
    }

    @Override
    public void onChunkData(LevelChunk chunk) {
        enqueue(chunk.getPos().x, chunk.getPos().z); // every data packet, not only the first (queued once per chunk; a scan dropped as stale after newer data gets queued again). First data, as the load event was before
    }

    /** Chunks where a container appeared or changed by a block update: {ticks since the last one, ticks since the first}. */
    private final Map<Long, int[]> added = new java.util.HashMap<>();
    static final int ADD_QUIET_TICKS = 20, ADD_MAX_TICKS = 100;
    /** An alert for blocks placed while the chunk is held waits this long, so Base Finder has scored the same blocks before a number is quoted. */
    static final int ADD_ALERT_TICKS = 40;

    /** The containers this module can count; a block update that puts one into a chunk the client holds never re-sends the chunk. */
    public static boolean storageBlock(BlockState state) {
        var b = state.getBlock();
        return b instanceof net.minecraft.world.level.block.ChestBlock || b instanceof net.minecraft.world.level.block.BarrelBlock || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                || b instanceof net.minecraft.world.level.block.HopperBlock || b instanceof net.minecraft.world.level.block.DispenserBlock || b instanceof net.minecraft.world.level.block.EnderChestBlock;
    }

    @Override
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        if (!storageBlock(state)) return;
        added.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> new int[2])[0] = 0;
    }

    /** Scans the chunks with new containers once the building pauses (or after a while, if it never does). */
    private void tickAdded() {
        for (var it = added.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            int[] t = e.getValue();
            if (++t[0] < ADD_QUIET_TICKS && ++t[1] < ADD_MAX_TICKS) continue;
            it.remove();
            int cx = ChunkPos.getX(e.getKey()), cz = ChunkPos.getZ(e.getKey());
            Chunks.submit(this, "added", cx, cz, budget -> {
                if (mc.level != null && mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c) scan(c, true);
                return true;
            });
        }
    }

    @Override
    public void onTick() {
        String key = Config.serverKey();
        if (!key.equals(loadedFor)) load(key);
        tickAdded();
        tickAlerts();
    }

    /** Lowest ground in the chunk's corners and middle, so one hill doesn't make a surface chest look buried. */
    private int ground(LevelChunk chunk) {
        int ox = chunk.getPos().getMinBlockX(), oz = chunk.getPos().getMinBlockZ(), low = Integer.MAX_VALUE;
        for (int[] o : new int[][] {{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}}) low = Math.min(low, mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox + o[0], oz + o[1]));
        return low;
    }

    /** addOnly: the scan was caused by block updates. It may add a stash or raise its count, never drop or lower one: only chunk data does that. */
    private void scan(LevelChunk chunk, boolean addOnly) {
        scan(chunk, addOnly, true);
    }

    private void scan(LevelChunk chunk, boolean addOnly, boolean mayQueue) {
        if (BaseFinder.endCity(chunk)) return;
        int ch = 0, ba = 0, sh = 0, ot = 0, hiddenHere = 0;
        long sx = 0, sy = 0, sz = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            boolean hit = true;
            if (be instanceof ChestBlockEntity && chests.on()) ch++;
            else if (be instanceof BarrelBlockEntity && barrels.on()) ba++;
            else if (be instanceof ShulkerBoxBlockEntity && shulkers.on()) sh++;
            else if (hoppers.on() && (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity)) ot++;
            else if (enderChests.on() && be instanceof EnderChestBlockEntity) ot++;
            else hit = false;
            if (hit) {
                BlockPos p = be.getBlockPos();
                sx += p.getX(); sy += p.getY(); sz += p.getZ();
            }
        }
        // chests the server only admits to in the chunk data: vanilla drops these, so they'd never reach the loop above
        for (var h : dev.vantage.core.HiddenStorage.in(chunk.getPos().x, chunk.getPos().z)) {
            if (chunk.getBlockEntity(h.pos()) != null) continue;
            var t = h.type();
            boolean hit = true;
            if ((t == net.minecraft.world.level.block.entity.BlockEntityType.CHEST || t == net.minecraft.world.level.block.entity.BlockEntityType.TRAPPED_CHEST) && chests.on()) ch++;
            else if (t == net.minecraft.world.level.block.entity.BlockEntityType.BARREL && barrels.on()) ba++;
            else if (t == net.minecraft.world.level.block.entity.BlockEntityType.SHULKER_BOX && shulkers.on()) sh++;
            else hit = false;
            if (hit) { sx += h.pos().getX(); sy += h.pos().getY(); sz += h.pos().getZ(); hiddenHere++; }
        }
        // containers that are part of a pile lying across a chunk border, with too few on either side to count there
        List<BlockEntity> over = new ArrayList<>(), overEntities = new ArrayList<>();
        if (ch + ba + sh * 2 + ot < threshold.i() && ch + ba + sh > 0) {
            var containers = ChunkPile.in(mc.level, this::counted);
            java.util.function.ToIntFunction<BlockPos> weight = p -> mc.level.getBlockEntity(p) instanceof ShulkerBoxBlockEntity ? 2 : 1;
            long here = chunk.getPos().toLong();
            for (var pile : ChunkPile.crossing(here, containers)) {
                Long owner = ChunkPile.owner(pile, containers, weight, threshold.i());
                if (owner == null) continue;
                if (owner != here) { if (mayQueue) splitFor(owner, addOnly); continue; }
                for (BlockPos p : pile.outside(here)) {
                    BlockEntity be = mc.level.getBlockEntity(p);
                    if (be == null) continue;
                    over.add(be);
                    if (be instanceof ShulkerBoxBlockEntity) sh++; else if (be instanceof BarrelBlockEntity) ba++; else ch++;
                }
                for (long k : pile.byChunk().keySet()) {
                    if (k != here && mc.level.getChunkSource().getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), ChunkStatus.FULL, false) instanceof LevelChunk c) {
                        for (BlockEntity be : c.getBlockEntities().values()) if (!ChunkPile.container(be)) overEntities.add(be);
                    }
                }
            }
        }
        int n = ch + ba + sh + ot;
        int own = n - over.size(); // the marker sits among this chunk's own containers, so the stash keeps its chunk
        String dim = Dims.id(mc.level);
        String id = dim + ":" + chunk.getPos().x + ":" + chunk.getPos().z;
        Find old = finds.get(id);
        if (n == 0 || ch + ba + sh * 2 + ot < threshold.i()) {
            if (addOnly) return;
            // emptied or broken: two scans in a row below the threshold drop the old find (one thin packet must not delete a real stash)
            // only near the player: a far chunk can arrive with its containers hidden by the server, which is not an emptied stash
            if (old != null && nearPlayer(chunk) && strikes.merge(id, 1, Integer::sum) >= 2) { strikes.remove(id); finds.remove(id); dropWaypoint(old); save(); }
            return;
        }
        StashLogic.Verdict v;
        if (over.isEmpty()) v = StashLogic.judge(mc.level, chunk, ground(chunk), hiddenHere);
        else {
            List<BlockEntity> pile = new ArrayList<>(chunk.getBlockEntities().values());
            pile.addAll(over);
            pile.addAll(overEntities);
            v = StashLogic.judge(mc.level, pile, ground(chunk), hiddenHere, true);
        }
        if (onlyStashes.on() && (v == null || !v.kind().equals(StashLogic.STASH))) {
            if (addOnly) return;
            if (old != null) { finds.remove(id); dropWaypoint(old); save(); } // came from before the check existed
            return;
        }
        Find f = new Find(dim, (int) (sx / own), (int) (sy / own), (int) (sz / own), ch, ba, sh, ot, System.currentTimeMillis(), v == null ? null : v.pct(), v == null ? null : String.join(", ", v.why()));
        if (addOnly && old != null && f.total() <= old.total()) return;
        boolean lower = old != null && old.total() > f.total();
        if (!lower) strikes.remove(id); // "in a row": any good scan that is not lower clears the strike
        if (old != null && old.pct() != null && old.total() >= f.total() && !(lower && strikes.merge(id, 1, Integer::sum) >= 2)) return;
        strikes.remove(id);
        finds.put(id, f);
        save();
        if (old != null) { // only ping the first time; a lower count moves the waypoint's name
            if (lower && waypoint.on()) { dropWaypoint(old); Waypoints.get().add("Stash " + f.total(), f.x(), f.y(), f.z(), color.argb()); }
            return;
        }
        dev.vantage.module.cosmetic.FindEvents.marked("Stash", f.detail() == null ? "" : f.detail(), f.x(), f.y(), f.z());
        if (notify.on()) waiting.put(id, new int[] {0, addOnly || !over.isEmpty() ? ADD_ALERT_TICKS : 0}); // the alert waits for Base Finder's row number (see tickAlerts)
        if (waypoint.on()) Waypoints.get().add("Stash " + f.total(), f.x(), f.y(), f.z(), color.argb());
    }

    /** Would the switches count this container? */
    private boolean counted(BlockEntity be) {
        return be instanceof ChestBlockEntity ? chests.on() : be instanceof BarrelBlockEntity ? barrels.on() : be instanceof ShulkerBoxBlockEntity && shulkers.on();
    }

    /** A chunk next door holds most of a split pile: it is looked at again, now that this part of the pile is here. It never queues a third. */
    private void splitFor(long owner, boolean addOnly) {
        int cx = ChunkPos.getX(owner), cz = ChunkPos.getZ(owner);
        Chunks.submit(this, "split", cx, cz, budget -> {
            if (mc.level != null && mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c) scan(c, addOnly, false);
            return true;
        });
    }

    /** Alerts waiting for Base Finder to score the chunk: find id -> {ticks waited, least ticks to wait}. */
    private final Map<String, int[]> waiting = new java.util.HashMap<>();
    /** Per alerted stash: {percent the player was last told (-1 = none), updates sent, ticks since the last check}. */
    private final Map<String, int[]> told = new java.util.HashMap<>();
    static final int ALERT_WAIT_TICKS = 200, UPDATE_COOLDOWN_TICKS = 200, MAX_UPDATES = 3;

    /** Fire the alert now? Yes once the panel has a number, when Base Finder is off (there will be no row), or after the wait ran out. */
    public static boolean alertReady(Integer panel, boolean baseFinderOn, int waited) {
        return panel != null || !baseFinderOn || waited >= ALERT_WAIT_TICKS;
    }

    /** Send a short correction? Only when the row moved more than a point from what the alert said, at most MAX_UPDATES times. */
    public static boolean alertStale(Integer told, Integer panel, int updates) {
        return told != null && panel != null && Math.abs(told - panel) > 1 && updates < MAX_UPDATES;
    }

    /** The follow-up an alert without a percent ("checking") promised: due as soon as the panel has a row. It is not one of the corrections. */
    public static boolean followUpDue(Integer told, Integer panel) {
        return told == null && panel != null;
    }

    /** The percent the alert for the stash in this chunk last told the player, or null. */
    public Integer toldPctAt(int cx, int cz) {
        int[] t = told.get(Dims.id(mc.level) + ":" + cx + ":" + cz);
        return t == null || t[0] < 0 ? null : t[0];
    }

    private void sendAlert(Find f, String lead) {
        boolean bfOn = baseFinderOn();
        Integer pct = alertPct(pctAt(f.x() >> 4, f.z() >> 4), f.pct(), bfOn);
        int dist = (int) Math.sqrt(mc.player.distanceToSqr(f.x(), f.y(), f.z()));
        Chat.alert(Component.literal(lead).withColor(color.argb())
                .append(Component.literal((pct == null ? "" : pct + "%  ") + Chat.coords(f.x(), f.y(), f.z()) + "  " + f.detail() + "  " + dist + "m" + (f.why() == null ? "" : "  " + f.why()) + alertTail(pct, bfOn)).withColor(0xFFD3DCD4)), 1.4f);
        String id = f.dim() + ":" + (f.x() >> 4) + ":" + (f.z() >> 4);
        int[] t = told.computeIfAbsent(id, k -> new int[] {-1, 0, 0});
        t[0] = pct == null ? -1 : pct;
        t[2] = 0;
    }

    private void tickAlerts() {
        if (mc.player == null || mc.level == null) return;
        boolean bfOn = baseFinderOn();
        for (var it = waiting.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Find f = finds.get(e.getKey());
            if (f == null) { it.remove(); continue; }
            e.getValue()[0]++;
            if (e.getValue()[0] >= e.getValue()[1] && alertReady(pctAt(f.x() >> 4, f.z() >> 4), bfOn, e.getValue()[0])) { it.remove(); sendAlert(f, "STASH "); }
        }
        for (var e : told.entrySet()) {
            int[] t = e.getValue();
            Find f = finds.get(e.getKey());
            if (f == null) continue;
            if (t[0] < 0) { // the alert said "checking": the row's number goes out as soon as there is one
                if (followUpDue(null, pctAt(f.x() >> 4, f.z() >> 4))) sendAlert(f, "STASH now ");
                continue;
            }
            if (++t[2] < UPDATE_COOLDOWN_TICKS) continue;
            t[2] = 0;
            if (alertStale(t[0], pctAt(f.x() >> 4, f.z() >> 4), t[1])) { t[1]++; sendAlert(f, "STASH now "); }
        }
    }

    private boolean baseFinderOn() {
        BaseFinder bf = dev.vantage.core.Modules.get(BaseFinder.class);
        return bf != null && bf.on();
    }

    private Integer shownPct(Find f) {
        return alertPct(pctAt(f.x() >> 4, f.z() >> 4), f.pct(), baseFinderOn());
    }

    /**
     * The percent a STASH alert or label may show: the panel row's. With Base Finder on and no row yet there is none, because Stash Finder's own
     * pile score is a different number the row would contradict. Only with Base Finder off (no row will come) is the own score shown.
     */
    public static Integer alertPct(Integer panel, Integer own, boolean baseFinderOn) {
        return panel != null ? panel : baseFinderOn ? null : own;
    }

    /** What ends an alert that has no percent because the row is still to come. */
    public static String alertTail(Integer shown, boolean baseFinderOn) {
        return shown == null && baseFinderOn ? "  (checking)" : "";
    }

    /** The one number for a stash: Base Finder's percentage for the chunk when it has one, else none (Stash Finder's own pile score only picks Stash or Storage). */
    public Integer pctAt(int cx, int cz) {
        BaseFinder bf = dev.vantage.core.Modules.get(BaseFinder.class);
        return bf != null && bf.on() ? bf.shownPctAt(cx, cz) : null;
    }

    /** Finds in a chunk of this dimension, for the test rig. */
    /** Chunks waiting to be scanned. */
    public int pending() {
        return Chunks.pending(this);
    }

    public int inChunk(int cx, int cz) {
        return finds.containsKey(Dims.id(mc.level) + ":" + cx + ":" + cz) ? 1 : 0;
    }

    @Override
    public String info() {
        return finds.isEmpty() ? null : String.valueOf(finds.size());
    }

    @Override
    public void onRender3D(R3 r) {
        if (!show.on()) return;
        String dim = Dims.id(mc.level);
        int minY = mc.level.getMinY(), maxY = mc.level.getMaxY();
        int c = color.argb();
        for (Find f : finds.values()) {
            if (!f.dim().equals(dim)) continue;
            double cx = (f.x() >> 4 << 4) + 8, cz = (f.z() >> 4 << 4) + 8;
            if (Math.abs(cx - r.cam.x) > 1024 || Math.abs(cz - r.cam.z) > 1024) continue;
            r.column(new AABB(cx - 2, minY, cz - 2, cx + 2, maxY, cz + 2), c | 0xFF000000, EspStyle.WEIGHT, 0, cx, cz);
            r.kind("stash");
            EspStyle.box(r, new AABB(f.x(), f.y(), f.z(), f.x() + 1, f.y() + 1, f.z() + 1), c, true);
            Vec3 lp = new Vec3(f.x() + 0.5, f.y() + 2, f.z() + 0.5);
            if (lp.distanceTo(r.cam) < 512) r.label(lp, "STASH " + (shownPct(f) == null ? "" : shownPct(f) + "%  ") + f.detail(), c);
        }
    }

    private Path file(String key) {
        return Config.dir().resolve("stashes").resolve(key + ".json");
    }

    /** Pretty object "dim:cx:cz" -> find, with a v field. On JsonStore: written on the storage thread, a damaged file is never written over. The finds are immutable records, so the tree is a true snapshot. */
    private final JsonStore store = new JsonStore("stashes", () -> loadedFor.isEmpty() ? null : file(loadedFor), 1, () -> GSON.toJsonTree(finds), (root, v) -> {
        Map<String, Find> m = GSON.fromJson(root, new TypeToken<LinkedHashMap<String, Find>>() {}.getType());
        finds.clear();
        if (m != null) finds.putAll(m);
    }).pretty();

    private void load(String key) {
        loadedFor = key; // from here the store's path is this server's file; an unsaved change of the old one is handed over first
        if (!store.load()) finds.clear();
    }

    private void save() {
        if (loadedFor.isEmpty()) return;
        store.markDirty();
    }

    @Override
    public int labelClass() {
        return LABEL_SUMMARY;
    }

    @Override
    public boolean groupsBoxes() {
        return true;
    }
}
