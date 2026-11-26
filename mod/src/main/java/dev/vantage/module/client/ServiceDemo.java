package dev.vantage.module.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Ui;
import dev.vantage.core.chunk.BlockEntityInfo;
import dev.vantage.core.chunk.Chunks;
import dev.vantage.core.command.Args;
import dev.vantage.core.command.Command;
import dev.vantage.core.notify.Level;
import dev.vantage.core.notify.Notify;
import dev.vantage.core.store.ChunkSetStore;
import dev.vantage.core.store.Scope;
import dev.vantage.core.store.Stores;
import dev.vantage.core.util.ChunkMath;
import dev.vantage.core.util.Colors;
import dev.vantage.core.util.Cooldown;
import dev.vantage.core.util.Dims;
import dev.vantage.render.R3;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.IntSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Developer module and reference for module authors: it marks the chunks around you that hold a block of a chosen kind and remembers
 * them per server and dimension. It touches every core service once, each call with a comment saying why it is used that way
 * (ARCHITECTURE.md 19.5); copy the pattern, not the feature. Hidden unless -Dvantage.dev=true (or the check rig, -Dvantage.ui).
 */
public class ServiceDemo extends Module {
    private static final String ID = "service-demo";

    public final ModeSetting mode = add(new ModeSetting("Look for", "The kind of block that marks a chunk", "Chest", ServiceDemoMath.MODES.toArray(new String[0])));
    public final IntSetting radius = add(new IntSetting("Radius", "Chunks around you that are looked at", 4, 1, 32));
    public final ColorSetting color = add(new ColorSetting("Colour", "Fill and border of a marked chunk", 0xFF3DA9FC));
    public final BoolSetting atFeet = add(new BoolSetting("At feet", "Draw at your feet; off draws at the height below", true));
    public final IntSetting height = add(new IntSetting("Height", "Block height of the tiles", 64, -64, 320)).visibleIf(() -> !atFeet.on());
    public final BoolSetting hud = add(new BoolSetting("Show HUD", "The count and the nearest marked chunk", true));
    /** Developer switch for the in-game check: makes the scan of the chunk you stand in, or of every chunk, throw. Never saved. */
    public final ModeSetting failure = add(new ModeSetting("Failure test", "Developer: make scans throw", "Off", "Off", "One chunk", "Every chunk"));

    // storage service: declared once. A bit-map file per server and dimension, loaded for the place the player is in by the service itself (join, dimension change, module switched on) and written by the writer thread.
    // adopt: where an earlier client version kept this data; it is copied into the new file once and left where it is. onLoad: a new set arrived, so the drawn tiles and the scan position start over.
    private final ChunkSetStore store = Stores.chunkSet(ID, Scope.DIMENSION).activeWhen(this::activeInWorld).adopt("service-demo/{server}/{dim}.chunks", ChunkSetStore::readFile).onLoad(had -> {
        setChanged = true;
        lastCx = Integer.MIN_VALUE;
        log().info("loaded {} chunks for {} (file {})", this.store.size(), Dims.id(mc.level), had ? "read" : "new"); // logging: info for something a user may need when reading the log
    });
    private final Map<Long, Integer> scanned = new HashMap<>(); // chunk -> data generation already handed to the chunk service, so a border crossing does not rescan
    private final Cooldown hudGate = Cooldown.wall(250); // utilities: the nearest-chunk search runs at most four times a second, not every frame
    private long[] keys = new long[0];
    private R3.TileSet tiles;
    private boolean setChanged;
    private int lastCx = Integer.MIN_VALUE, lastCz;
    private String nearestText = "";

    public ServiceDemo() {
        super("Service Demo", "Developer: marks chunks with a chosen block and remembers them (reference for module authors)", Category.CLIENT);
        developer(); // hidden: locked like a beta, but by -Dvantage.dev instead of -Dvantage.beta
        failure.visibleIf(() -> false);
        failure.storedIf(() -> false);
        // HUD service: declared once, so the element is listed (and movable) while the module is off; it is drawn only while the module is on, no screen is open and the user has not hidden it. The layer does the slot and Ui.begin/end.
        hudPanel(ID, "Service Demo", "Top Left").when(() -> hud.on() && mc.player != null).size(wh -> {
            refreshSet();
            if (hudGate.tryUse()) { // utilities: Cooldown, not a hand-written timestamp
                var hits = ServiceDemoMath.nearest(keys, keys.length, mc.player.getX(), mc.player.getZ(), 1);
                nearestText = hits.isEmpty() ? "nothing found yet" : ServiceDemoMath.where(hits.get(0));
            }
            wh[0] = Math.max(Ui.width(ServiceDemoMath.countLine(store.size()), 1f, true), Ui.width(nearestText, 0.9f, false)) + 20;
            wh[1] = 34;
        }).draw((g, x, y, w, h) -> {
            Ui.panel(g, x, y, w, h);
            Ui.text(g, ServiceDemoMath.countLine(store.size()), x + 10, y + 7, Ui.TEXT, 1f, true);
            Ui.text(g, nearestText, x + 10, y + 20, Ui.SUB, 0.9f, false);
        });
        command(Command.named("demo", "Service Demo: count the marked chunks, list the nearest five, or forget them") // command service: one declaration; it exists while the module is not locked, and while the module is off the dispatcher answers instead of running it (usableWhileOff() would opt out)
                .arg(Args.oneOf("action", "count, list or clear", List.of("count", "list", "clear")).optional())
                .runs((a, r) -> {
                    String action = a.has(0) ? a.get(0) : "count";
                    refreshSet();
                    switch (action) {
                        case "list" -> {
                            var hits = ServiceDemoMath.nearest(keys, keys.length, mc.player.getX(), mc.player.getZ(), 5);
                            if (hits.isEmpty()) r.info("Demo: nothing found yet");
                            for (int i = 0; i < hits.size(); i++) r.info(ServiceDemoMath.listLine(i + 1, hits.get(i))); // reply: the answer to the typed command, not a notification
                        }
                        case "clear" -> {
                            int n = store.size();
                            Chunks.cancel(this); // chunk service: scans still queued or running would find their chunk again right after the clear; after this call new work is unaffected
                            store.clear();
                            setChanged = true;
                            Notify.info(this, "clear", "Demo: forgot " + n + " chunks in this dimension"); // notify service: a state change the user caused, level info
                        }
                        default -> r.info(ServiceDemoMath.countLine(store.size()));
                    }
                }));
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------------------

    @Override
    protected void onEnable() {
        lastCx = Integer.MIN_VALUE; // everything is looked at again (onDisable forgot what was handed to the chunk service)
        setChanged = true;
        warnRadius();
    }

    @Override
    protected void onDisable() {
        scanned.clear(); // the chunk service cancels our queued work and running scans when we are switched off, so what we thought was handed over is gone
    }

    @Override
    public void onJoinWorld() { // lifecycle: runs also while the module is off, so nothing of the last world is kept
        dropWorld();
    }

    @Override
    public void onLeaveWorld() {
        dropWorld(); // the store lets go of its set by itself when the world is gone; Stores.flush on leaving has written it
    }

    @Override
    public void onDimensionChange() {
        dropWorld(); // the store file is per dimension: the service hands the old one to the writer and loads the new one on its own
    }

    private void dropWorld() {
        scanned.clear();
        keys = new long[0];
        tiles = null;
        setChanged = true; // the store may already have loaded the new place (its callback ran first): rebuild from whatever it holds now
        lastCx = Integer.MIN_VALUE;
    }

    @Override
    protected void onSettingChanged(Setting<?> s) {
        if (!activeInWorld()) return; // it also fires at config load, before there is a world and while the module is off
        if (s == mode) { store.clear(); setChanged = true; scanned.clear(); lastCx = Integer.MIN_VALUE; } // the set answered another question: forget it and look again
        if (s == radius) { lastCx = Integer.MIN_VALUE; warnRadius(); }
    }

    private void warnRadius() {
        int sent = mc.options.getEffectiveRenderDistance();
        if (radius.i() > sent) Notify.warning(this, "radius", "Demo: radius " + radius.i() + " is more than the " + sent + " chunks that are loaded"); // notify service: warning = works, but not as asked
    }

    // ---- events and the chunk service -----------------------------------------------------------------------------------------

    @Override
    public void onChunkData(LevelChunk chunk) { // event bus: the base class subscribes this hook while the module is on; it is the one "chunk is here" signal
        scan(chunk.getPos().x, chunk.getPos().z, Chunks.generation(chunk.getPos().x, chunk.getPos().z));
    }

    @Override
    public void onBlockUpdate(BlockPos pos, BlockState state) { // event bus: the chunk service's list is right after a single update, so the chunk is judged by what it holds now (a placed or a broken block)
        int cx = ChunkMath.chunk(pos.getX()), cz = ChunkMath.chunk(pos.getZ());
        if (inRadius(cx, cz) && (store.contains(cx, cz) || ServiceDemoMath.matches(mode.get(), BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()))) judge(cx, cz, holds(Chunks.blockEntities(cx, cz), mode.get()));
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        int cx = ChunkMath.chunk(mc.player.getX()), cz = ChunkMath.chunk(mc.player.getZ()); // utilities: floor-then-shift, right for negative coordinates
        if (cx == lastCx && cz == lastCz) return;
        lastCx = cx;
        lastCz = cz;
        Chunks.forEach((x, z, t, w, g) -> scan(x, z, g)); // chunk service: the chunks that have data (never one that is only announced); the player moved, so chunks that came into the radius are queued
    }

    private boolean inRadius(int cx, int cz) {
        return mc.player != null && ChunkMath.chunkDistance(cx, cz, ChunkMath.chunk(mc.player.getX()), ChunkMath.chunk(mc.player.getZ())) <= radius.i();
    }

    private void scan(int cx, int cz, int generation) {
        long key = ChunkMath.key(cx, cz);
        if (!inRadius(cx, cz) || scanned.getOrDefault(key, -1) == generation) return; // already handed over at this generation (a border crossing does not rescan)
        scanned.put(key, generation);
        String kind = mode.get();
        // the failure switch is read here, on the client thread: the scanner below runs on a worker and may use nothing that changes
        boolean boom = failure.is("Every chunk") || failure.is("One chunk") && cx == ChunkMath.chunk(mc.player.getX()) && cz == ChunkMath.chunk(mc.player.getZ());
        // chunk service: scanBlockEntities() = a queued, de-duplicated, budgeted job that hands a worker thread the chunk's block entity records (no section is copied: this scanner never reads blocks),
        // and the result comes back on the client thread only if the chunk still has this generation. A scanner that reads blocks uses Chunks.scan() and its ChunkSnapshot instead.
        // A scanner that throws is a problem of that chunk: the service logs it once in our log and the module goes on. The last argument is optional, for a module that wants to tell the user.
        Chunks.scanBlockEntities(this, "scan", cx, cz, snap -> {
            if (boom) throw new IllegalStateException("deliberate failure");
            boolean has = holds(snap.blockEntities(), kind);
            log().debug(() -> "job chunk " + cx + ", " + cz + (has ? " has " : " has no ") + kind + " (worker " + Thread.currentThread().getName() + ")"); // logging: debug per job, free while off; log() is safe on a worker thread
            return has;
        }, has -> judge(cx, cz, has), error -> Notify.error(this, "scan failed", "Demo: could not look at chunk " + cx + ", " + cz)); // notify service: errors are merged, never dropped
    }

    private static boolean holds(List<BlockEntityInfo> blockEntities, String kind) {
        for (BlockEntityInfo be : blockEntities) if (ServiceDemoMath.matches(kind, BuiltInRegistries.BLOCK.getKey(be.state().getBlock()).getPath())) return true;
        return false;
    }

    /** The chunk holds a marked block now (or no longer): it joins the set, or leaves it when its last marked block is gone. */
    private void judge(int cx, int cz, boolean has) {
        // notify service: once per chunk until the world is left, however often it is found again; past five lines within a second (a big first scan) the rest become one summary. The key names the chunk, so nothing here de-duplicates
        if (has) Notify.once(this, Level.SUCCESS, "found " + cx + "," + cz, "Demo: found a " + mode.get().toLowerCase() + " in chunk " + cx + ", " + cz, n -> "Demo: " + n + " more new chunk" + (n == 1 ? "" : "s") + " found");
        if (has ? store.add(cx, cz) : store.remove(cx, cz)) { // storage service: true when the set changed
            setChanged = true;
            hudGate.reset();
        }
    }

    // ---- drawing and HUD ------------------------------------------------------------------------------------------------------

    /** Rebuilds the key array and the prepared tile set; only when the set changed. */
    private void refreshSet() {
        if (!setChanged) return;
        setChanged = false;
        keys = store.keys(); // storage service: a snapshot as packed keys, the layout R3 wants
        tiles = R3.tileSet(keys, keys.length); // drawing service: merges neighbours into rectangles once, so a frame writes a few quads instead of one call per chunk
    }

    @Override
    public void onRender3D(R3 r) {
        refreshSet();
        if (tiles == null || mc.player == null) return;
        int c = color.argb();
        r.tiles(tiles, atFeet.on() ? mc.player.getY() + 0.02 : height.i(), Colors.withAlpha(c, 60), Colors.lighten(c, 0.35f), 2f, 0); // drawing: one call per frame for the whole set; colours from Colors
    }

    /** The two lines of the HUD element: the count, then distance and direction of the nearest chunk. */
    public String hudText() {
        return ServiceDemoMath.countLine(store.size()) + "\n" + nearestText;
    }
}
