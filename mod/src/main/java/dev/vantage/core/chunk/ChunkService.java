package dev.vantage.core.chunk;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The chunk data service without the game: the index, the queue, the budget, the world counter and the worker pipeline, joined.
 * {@link Chunks} wires one of these to the event bus; the tests build their own with a fake clock and a hand-driven executor.
 * Client thread only, except what {@link ScanPipeline} documents.
 */
public final class ChunkService {
    /** Told when a queued job or a result handler threw, or a scanner failed. */
    @FunctionalInterface
    public interface Failures {
        void failed(Object owner, String kind, Throwable error);

        /** A scanner threw for one chunk (see {@link ScanPipeline}): report it. The default says nothing. */
        default void scanFailed(Object owner, String kind, long chunkKey, Throwable error) {}
    }

    private final Budget budget;
    private final ServiceStats stats = new ServiceStats();
    private final ChunkIndex index = new ChunkIndex();
    private final WorkQueue queue = new WorkQueue();
    private final WorldCounter world = new WorldCounter();
    private final ScanPipeline scans;
    private final Rules rules;
    /** The world object the index belongs to (compared by identity); null while there is none. */
    private Object level;
    private long lastTickNs;

    public ChunkService(LongSupplier clock, Supplier<Executor> workers, Predicate<Object> ownerOn, Failures failures) {
        budget = new Budget(clock);
        scans = new ScanPipeline(workers, world, stats);
        rules = new Rules() {
            @Override
            public boolean ownerOn(Object owner) {
                return ownerOn.test(owner);
            }

            @Override
            public boolean loaded(long chunkKey) {
                return index.isLoaded(chunkKey);
            }

            @Override
            public int generation(long chunkKey) {
                return index.generation(chunkKey);
            }

            @Override
            public int world() {
                return world.current();
            }

            @Override
            public void failed(Object owner, String kind, Throwable error) {
                failures.failed(owner, kind, error);
            }

            @Override
            public void scanFailed(Object owner, String kind, long chunkKey, Throwable error) {
                failures.scanFailed(owner, kind, chunkKey, error);
            }
        };
    }

    public Budget budget() {
        return budget;
    }

    public ChunkIndex index() {
        return index;
    }

    public WorkQueue queue() {
        return queue;
    }

    public WorldCounter world() {
        return world;
    }

    public ScanPipeline scans() {
        return scans;
    }

    /** The measurement numbers (waits, worker time); see {@link ServiceStats}. */
    public ServiceStats stats() {
        return stats;
    }

    public long lastTickNs() {
        return lastTickNs;
    }

    // ---- what the game tells us -------------------------------------------------------------------------------------------------

    /**
     * Every chunk event names the world it belongs to. A world that is not the one the index was built for means the old world is
     * gone, whether or not its end was announced yet: the game tells us about a changed world at the end of the tick, after the
     * chunks of the new world may already have arrived (BUGS.md B-180). The index and the queue are started again at that moment.
     */
    private void seen(Object current) {
        if (current == level) return;
        reset();
        level = current;
    }

    public void chunkLoaded(Object chunkWorld, int cx, int cz, long gameTime, long wallMs) {
        seen(chunkWorld);
        index.load(cx, cz, gameTime, wallMs);
    }

    public void chunkData(Object chunkWorld, int cx, int cz, long gameTime, long wallMs) {
        seen(chunkWorld);
        index.data(cx, cz, gameTime, wallMs);
    }

    /** Queued work for the chunk is dropped when its turn comes, because the index no longer lists the chunk. */
    public void chunkUnloaded(int cx, int cz) {
        index.unload(cx, cz);
    }

    /** The world was joined, left or replaced; {@code current} is the world the client has now (null when none). */
    public void worldEvent(Object current) {
        if (current == null && queue.runs() > 0) dev.vantage.core.Vantage.LOG.info(queue.summary());
        seen(current);
    }

    /** Index, queue, waiting snapshots' results: all dropped, and the world counter moves on so that late results are recognised. */
    public void reset() {
        world.next();
        index.clear();
        queue.clear();
        scans.clear();
    }

    // ---- what modules ask for ----------------------------------------------------------------------------------------------------

    public void submit(Object owner, String kind, int cx, int cz, Job job) {
        long t0 = budget.now();
        enqueue(owner, kind, cx, cz, b -> {
            boolean done = job.run(b);
            if (done) stats.waited(budget.now() - t0);
            return done;
        });
    }

    private void enqueue(Object owner, String kind, int cx, int cz, Job job) {
        long key = ChunkIndex.key(cx, cz);
        queue.submit(owner, kind, key, job, index.generation(key), world.current());
    }

    /**
     * Scans a chunk off the client thread. A queued job takes the snapshot on the client thread (inside the budget); a worker runs the
     * scanner on it; {@code onResult} runs on the client thread on a later tick if the chunk still has the generation the snapshot
     * had. A snapshot that returns null (the chunk is not there) ends the scan quietly.
     */
    public <S, R> void scan(Object owner, String kind, int cx, int cz, Supplier<S> snapshot, Function<S, R> scanner, Consumer<R> onResult) {
        scan(owner, kind, cx, cz, snapshot, scanner, onResult, null);
    }

    /** Like {@link #scan(Object, String, int, int, Supplier, Function, Consumer)}; {@code onFailed} (may be null) hears of a scanner that threw. */
    public <S, R> void scan(Object owner, String kind, int cx, int cz, Supplier<S> snapshot, Function<S, R> scanner, Consumer<R> onResult, Consumer<Throwable> onFailed) {
        long key = ChunkIndex.key(cx, cz);
        long t0 = budget.now();
        Consumer<R> timed = r -> { // the wait of a scan ends when its result reaches the client thread
            stats.waited(budget.now() - t0);
            onResult.accept(r);
        };
        enqueue(owner, kind, cx, cz, b -> {
            long c0 = budget.now();
            S s = snapshot.get();
            if (s != null) stats.snapshotted(budget.now() - c0);
            if (s != null) scans.start(owner, kind, key, index.generation(key), s, scanner, timed, onFailed);
            return true;
        });
    }

    /**
     * Scans a chunk's block entities off the client thread without copying any section: the queued job reads the list through
     * {@code entities} (null = the chunk is not there, which ends the scan quietly) and hands the worker a {@link ChunkBlockEntities}.
     * Delivery rules are those of {@link #scan}.
     */
    public <R> void scanBlockEntities(Object owner, String kind, int cx, int cz, LongFunction<List<BlockEntityInfo>> entities, Function<ChunkBlockEntities, R> scanner, Consumer<R> onResult) {
        scanBlockEntities(owner, kind, cx, cz, entities, scanner, onResult, null);
    }

    public <R> void scanBlockEntities(Object owner, String kind, int cx, int cz, LongFunction<List<BlockEntityInfo>> entities, Function<ChunkBlockEntities, R> scanner, Consumer<R> onResult,
            Consumer<Throwable> onFailed) {
        long key = ChunkIndex.key(cx, cz);
        scan(owner, kind, cx, cz, () -> {
            List<BlockEntityInfo> list = entities.apply(key);
            return list == null ? null : new ChunkBlockEntities(cx, cz, index.generation(key), list);
        }, scanner, onResult, onFailed);
    }

    /**
     * Drops the owner's queued jobs and makes the results of its scans that are already running or waiting not be delivered. Work the
     * owner queues afterwards is unaffected. Other owners are not touched.
     */
    public void cancel(Object owner) {
        queue.cancel(owner);
        scans.cancel(owner);
    }

    /** One tick: applies finished scan results, then runs the queue, both inside the budget. {@code limitNs} is this tick's budget. */
    public void tick(long limitNs) {
        long t0 = budget.now();
        budget.limit(limitNs);
        budget.newTick();
        scans.deliver(budget, rules, index);
        queue.run(budget, rules);
        lastTickNs = budget.now() - t0;
    }

    /** Everything of the owner that is not done: jobs in the queue, and scans on a worker or waiting for their result to be applied. */
    public int pending(Object owner) {
        return queue.pending(owner) + scans.inFlight(owner);
    }

    /** Jobs and results thrown away, together. */
    public long dropped() {
        return queue.dropped() + scans.dropped();
    }
}
