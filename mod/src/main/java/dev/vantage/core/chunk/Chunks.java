package dev.vantage.core.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.vantage.core.Module;
import dev.vantage.core.Perf;
import dev.vantage.core.ScanBudget;
import dev.vantage.core.event.BlockUpdateEvent;
import dev.vantage.core.event.ChunkDataEvent;
import dev.vantage.core.event.ChunkLoadEvent;
import dev.vantage.core.event.ChunkUnloadEvent;
import dev.vantage.core.event.DimensionChangeEvent;
import dev.vantage.core.event.Events;
import dev.vantage.core.event.TickEvent;
import dev.vantage.core.event.WorldJoinEvent;
import dev.vantage.core.event.WorldLeaveEvent;
import dev.vantage.core.log.Log;
import dev.vantage.core.log.SourceLog;
import dev.vantage.core.util.ChunkMath;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The chunk data service every module shares (ARCHITECTURE.md section 17): which chunks are loaded and since when, one work queue
 * with a per-tick time budget, block entities per chunk, and scanning off the client thread. Static, like the other core services.
 * It listens as a system owner before every module, so a module's {@code onChunkData} already finds the new generation here.
 *
 * <p>Scan on {@code onChunkData}, not on {@code onChunkLoad}: the load event comes first (generation 0), the data event second
 * (generation 1, and a higher one each time the data is re-sent or rewritten by a burst), and work queued at generation 0 is
 * dropped when the data arrives. All of this is called on the client thread; {@link #scan} workers only ever see a snapshot.
 */
public final class Chunks implements Events.Owner {
    private static final Chunks OWNER = new Chunks();
    private static final Events.Group GROUP = new Events.Group(OWNER);
    private static final ChunkService SERVICE = new ChunkService(System::nanoTime, Chunks::workers, Chunks::ownerOn, new Faults());
    /** Block entity lists, built once per chunk generation and dropped by a block update that touches one. */
    private static final BlockEntityCache BLOCK_ENTITIES = new BlockEntityCache();
    private static Executor pool;

    private Chunks() {}

    @Override
    public String ownerName() {
        return "chunk service";
    }

    /** Before every module (modules start at rank 1), and with a higher priority than any of their chunk listeners. */
    @Override
    public int ownerRank() {
        return 0;
    }

    private static final int FIRST = 100;

    /** Called once at start-up, next to {@code CoreTaps.init()}. */
    public static void init() {
        if (GROUP.declared() > 0) return;
        GROUP.listen(ChunkLoadEvent.class, FIRST, e -> SERVICE.chunkLoaded(e.chunk.getLevel(), e.chunk.getPos().x, e.chunk.getPos().z, e.chunk.getLevel().getGameTime(), System.currentTimeMillis()));
        GROUP.listen(ChunkDataEvent.class, FIRST, e -> SERVICE.chunkData(e.chunk.getLevel(), e.chunk.getPos().x, e.chunk.getPos().z, e.chunk.getLevel().getGameTime(), System.currentTimeMillis()));
        GROUP.listen(ChunkUnloadEvent.class, FIRST, e -> {
            SERVICE.chunkUnloaded(e.cx, e.cz);
            BLOCK_ENTITIES.unloaded(ChunkIndex.key(e.cx, e.cz));
        });
        GROUP.listen(BlockUpdateEvent.class, FIRST, e -> { // the event comes after the game changed the block, so the live chunk already has the new block entity (or none)
            long key = ChunkIndex.key(ChunkMath.chunk(e.pos.getX()), ChunkMath.chunk(e.pos.getZ()));
            if (BLOCK_ENTITIES.has(key)) BLOCK_ENTITIES.blockChanged(key, e.state.hasBlockEntity(), info -> info.pos().equals(e.pos));
        });
        GROUP.listen(WorldJoinEvent.class, FIRST, e -> SERVICE.worldEvent(Minecraft.getInstance().level));
        GROUP.listen(WorldLeaveEvent.class, FIRST, e -> SERVICE.worldEvent(Minecraft.getInstance().level));
        GROUP.listen(DimensionChangeEvent.class, FIRST, e -> SERVICE.worldEvent(Minecraft.getInstance().level));
        GROUP.listen(TickEvent.class, FIRST, e -> {
            long t0 = System.nanoTime();
            long late = SERVICE.queue().deferrals();
            SERVICE.tick(ScanBudget.limitNs());
            ScanBudget.deferred += SERVICE.queue().deferrals() - late; // the rig counts a tick that put queued work off for want of budget
            Perf.add("chunk queue", System.nanoTime() - t0);
        });
        GROUP.attach();
    }

    // ---- the index ---------------------------------------------------------------------------------------------------------------

    public static boolean isLoaded(int cx, int cz) {
        return SERVICE.index().isLoaded(cx, cz);
    }

    /** The game time the chunk first arrived, or -1 when it is not loaded. */
    public static long loadedAt(int cx, int cz) {
        return SERVICE.index().loadedAt(cx, cz);
    }

    /** The wall clock (epoch ms) the chunk first arrived, or -1 when it is not loaded. */
    public static long loadedWallMs(int cx, int cz) {
        return SERVICE.index().loadedWallMs(cx, cz);
    }

    /** How often the chunk's data arrived (new = 1, then one more for every re-send or burst), 0 when only the load came, -1 when not loaded. */
    public static int generation(int cx, int cz) {
        return SERVICE.index().generation(ChunkIndex.key(cx, cz));
    }

    public static int count() {
        return SERVICE.index().count();
    }

    /**
     * Calls the visitor for every chunk that has data (generation 1 or more): the chunks there is something to scan in. A chunk that
     * was only announced and whose data has not come is not visited, so a caller needs no {@code generation < 1} test of its own;
     * every caller so far wanted exactly this. Do not load or unload chunks from inside it.
     */
    public static void forEach(ChunkIndex.Visitor v) {
        SERVICE.index().forEach(v);
    }

    /** Calls the visitor for every loaded chunk, also those at generation 0 (the load event came, the data did not). Same rule: do not load or unload from inside it. */
    public static void forEachLoaded(ChunkIndex.Visitor v) {
        SERVICE.index().forEachLoaded(v);
    }

    // ---- the queue, block entities and scans ----------------------------------------------------------------------------

    /**
     * Queues chunk work for the owner (a module, or any object that stands for a service). The same owner + kind + chunk is queued once.
     * Dropped when the owner is off, the chunk unloaded or got newer data than it had now, or the world changed.
     */
    public static void submit(Object owner, String kind, int cx, int cz, Job job) {
        SERVICE.submit(owner, kind, cx, cz, job);
    }

    /**
     * The block entities of a loaded chunk as small immutable records, built on the client thread once per data generation and shared by
     * every caller. Empty when the chunk is not loaded. The list is built again after a block update that adds, removes or changes a
     * block entity of the chunk, so it is right after a single update too, not only after the chunk's data was re-sent.
     */
    public static List<BlockEntityInfo> blockEntities(int cx, int cz) {
        long key = ChunkIndex.key(cx, cz);
        int gen = SERVICE.index().generation(key);
        if (gen == ChunkIndex.NONE) return List.of();
        return BLOCK_ENTITIES.get(key, SERVICE.world().current(), gen, () -> {
            LevelChunk chunk = liveChunk(cx, cz);
            if (chunk == null) return null;
            List<BlockEntityInfo> list = new ArrayList<>(chunk.getBlockEntities().size());
            for (var be : chunk.getBlockEntities().values()) list.add(new BlockEntityInfo(be.getBlockPos(), be.getType(), be.getBlockState()));
            return list;
        });
    }

    /**
     * Scans a chunk off the client thread. On the client thread, inside the budget, the chunk is copied into a {@link ChunkSnapshot};
     * the {@code scanner} runs on a worker thread and may touch nothing but the snapshot; {@code onResult} runs on the client thread on
     * a later tick, and only if the chunk still has the snapshot's generation, the world is the same and the owner is still on.
     *
     * <p>A scanner that throws is a problem of that chunk, not of the module: it is written once to the owner's log (per exception
     * class, later ones are counted, see {@link #scansFailed}) and {@code onResult} is not called. Only when the same owner's scans fail
     * {@link ScanPipeline#FAULT_AFTER} times in a row is the module faulted. To react to a failed chunk, use the overload with
     * {@code onFailed}; a module that does not care writes nothing.
     */
    public static <R> void scan(Object owner, String kind, int cx, int cz, Function<ChunkSnapshot, R> scanner, Consumer<R> onResult) {
        scan(owner, kind, cx, cz, scanner, onResult, null);
    }

    /** {@link #scan(Object, String, int, int, Function, Consumer)} that also tells {@code onFailed} (client thread) when the scanner threw for this chunk. */
    public static <R> void scan(Object owner, String kind, int cx, int cz, Function<ChunkSnapshot, R> scanner, Consumer<R> onResult, Consumer<Throwable> onFailed) {
        SERVICE.scan(owner, kind, cx, cz, () -> snapshot(cx, cz), scanner, onResult, onFailed);
    }

    /**
     * Like {@link #scan}, for a scanner that needs only what is cheap to hand over: the chunk position, its generation and the block
     * entities (position, type, state) as immutable records. No section is copied, so the job costs a list lookup instead of a copy of
     * the whole column. It is a separate method with its own input type, not a flag on {@code scan}, so a scanner cannot ask for blocks
     * by mistake: it has no section array to read. Everything else (queue, budget, delivery rules) is the same as for {@code scan}.
     */
    public static <R> void scanBlockEntities(Object owner, String kind, int cx, int cz, Function<ChunkBlockEntities, R> scanner, Consumer<R> onResult) {
        scanBlockEntities(owner, kind, cx, cz, scanner, onResult, null);
    }

    /** {@link #scanBlockEntities(Object, String, int, int, Function, Consumer)} that also tells {@code onFailed} (client thread) when the scanner threw for this chunk. */
    public static <R> void scanBlockEntities(Object owner, String kind, int cx, int cz, Function<ChunkBlockEntities, R> scanner, Consumer<R> onResult, Consumer<Throwable> onFailed) {
        SERVICE.scanBlockEntities(owner, kind, cx, cz, key -> liveChunk(cx, cz) == null ? null : blockEntities(cx, cz), scanner, onResult, onFailed);
    }

    /**
     * Cancels everything the owner has in the service: queued jobs are dropped, and results of its scans that are running or waiting are
     * not delivered. Called automatically when a module is switched off (or faulted); the world being left drops everything for every
     * owner on its own. A module calls it itself to forget work in flight when it throws its state away (a "clear" command); work it
     * queues after the call is unaffected.
     */
    public static void cancel(Object owner) {
        SERVICE.cancel(owner);
    }

    private static ChunkSnapshot snapshot(int cx, int cz) {
        LevelChunk chunk = liveChunk(cx, cz);
        if (chunk == null) return null;
        LevelChunkSection[] live = chunk.getSections();
        LevelChunkSection[] copy = new LevelChunkSection[live.length];
        for (int i = 0; i < live.length; i++) copy[i] = live[i].copy();
        return new ChunkSnapshot(cx, cz, SERVICE.index().generation(ChunkIndex.key(cx, cz)), chunk.getMinSectionY(), copy, blockEntities(cx, cz));
    }

    private static LevelChunk liveChunk(int cx, int cz) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk c ? c : null;
    }

    // ---- developer numbers (task 4.3 shows them in an overlay) ----------------------------------------------------------

    /** Jobs waiting in the queue. */
    public static int queueLength() {
        return SERVICE.queue().length();
    }

    /** Job calls made this session. */
    public static long jobsRun() {
        return SERVICE.queue().runs();
    }

    /** Work of the owner that is not finished: jobs waiting in the queue, and scans on a worker or whose result has not been applied yet. */
    public static int pending(Object owner) {
        return SERVICE.pending(owner);
    }

    /** Jobs and scan results thrown away this session (owner off, chunk gone or newer, world changed). */
    public static long jobsDropped() {
        return SERVICE.dropped();
    }

    /** Milliseconds the service itself used in its last tick (applying results and running the queue). */
    public static double lastTickMs() {
        return SERVICE.lastTickNs() / 1e6;
    }

    /** Scans whose scanner threw, this session (each was reported to its owner's log, once per exception class). */
    public static long scansFailed() {
        return SERVICE.scans().failed();
    }

    /** Snapshots waiting for a worker thread. */
    public static int snapshotsWaiting() {
        return SERVICE.scans().snapshotsWaiting();
    }

    /** Scan results waiting to be applied on the client thread. */
    public static int resultsWaiting() {
        return SERVICE.scans().resultsWaiting();
    }

    /** The measurement numbers of the service: waits, worker time (task 3.3 step 5). */
    public static ServiceStats stats() {
        return SERVICE.stats();
    }

    /** The most jobs the queue held at once since the last {@link #resetPeak}. */
    public static int peakQueue() {
        return SERVICE.queue().peak();
    }

    public static void resetPeak() {
        SERVICE.queue().resetPeak();
    }

    /** The time account of this tick, shared with {@link ScanBudget}. */
    public static Budget budget() {
        return SERVICE.budget();
    }

    // ---- plumbing ------------------------------------------------------------------------------------------------------------

    private static boolean ownerOn(Object owner) {
        return !(owner instanceof Module m) || m.on();
    }

    /** At most two daemon workers at the lowest priority, started with the first scan and gone again after 30 idle seconds. */
    private static synchronized Executor workers() {
        if (pool == null) {
            ThreadPoolExecutor p = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "Vantage chunk scan");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            p.allowCoreThreadTimeOut(true);
            pool = p;
        }
        return pool;
    }

    /** The service's two answers to a failure: fault the module (an uncaught error in a job or handler, or a scanner broken for every chunk), or report one chunk's failed scan. */
    private static final class Faults implements ChunkService.Failures {
        @Override
        public void failed(Object owner, String kind, Throwable error) {
            Chunks.failed(owner, kind, error);
        }

        @Override
        public void scanFailed(Object owner, String kind, long chunkKey, Throwable error) {
            String name = owner instanceof Events.Owner o ? o.ownerName() : String.valueOf(owner);
            SourceLog log = owner instanceof Module m ? m.log() : Log.core("chunks");
            // once per owner (the module's own logger; core owners share one, so the name is in the key), kind and exception class: later ones only count
            log.once("scan-failed " + name + " " + kind + " " + error.getClass().getName(), error,
                    () -> "scan '" + kind + "' of " + name + " failed for chunk " + ChunkIndex.keyX(chunkKey) + ", " + ChunkIndex.keyZ(chunkKey) + " (" + error + "); later failures of this kind are only counted");
        }
    }

    /** What a faulty job costs its module is decided by the module system: the error is thrown inside a listener of the owner, which the event bus hands to it. */
    private static void failed(Object owner, String kind, Throwable error) {
        if (owner instanceof Module m) {
            Events.Group g = new Events.Group(m).listen(JobFailed.class, e -> {
                throw new IllegalStateException("chunk work '" + e.kind + "' failed", e.error);
            });
            g.attach();
            try {
                Events.fire(new JobFailed(kind, error));
            } finally {
                g.detach();
            }
        } else {
            Log.core("chunks").once("job-failed " + kind, error, () -> "chunk work '" + kind + "' of " + owner + " failed (later failures of this kind are only counted)");
        }
    }

    private record JobFailed(String kind, Throwable error) {}
}
