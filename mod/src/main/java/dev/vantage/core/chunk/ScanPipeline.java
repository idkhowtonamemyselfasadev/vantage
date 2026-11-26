package dev.vantage.core.chunk;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The way to and from the worker threads. A snapshot (an immutable copy of what a scan reads, taken on the client thread) goes to a
 * worker; the worker runs the scanner, a pure function of the snapshot, and puts the result on a concurrent queue; the client thread
 * collects results in {@link #deliver}, once per tick, and only hands one on when its chunk still has the generation the snapshot
 * was taken at, the world is still the same and the owner is still on. A worker therefore never touches a live chunk, a module's
 * collections or anything the client thread changes. Nothing is logged on the worker.
 *
 * <p>A scanner that throws for one chunk is a problem of that chunk, not of the owner: {@link #deliver} reports it through
 * {@link Rules#scanFailed} and hands it to the scan's own failure callback if it has one. Only {@link #FAULT_AFTER} failures in a row for
 * the same owner, with no successful result in between, are taken as "this scanner is broken" and go to {@link Rules#failed}, which
 * faults the owner as an uncaught error always did.
 */
public final class ScanPipeline {
    /** Consecutive failed scans of one owner after which the owner is faulted. */
    public static final int FAULT_AFTER = 20;

    private record Result(Object owner, String kind, long chunk, int generation, int world, int epoch, Runnable apply, Throwable error, Consumer<Throwable> onFailed) {}

    private final Supplier<Executor> executor;
    private final WorldCounter world;
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final AtomicInteger waiting = new AtomicInteger(), ready = new AtomicInteger();
    /** Failed scans in a row per owner (reset by a result that was delivered without an error). Client thread only. */
    private final Map<Object, Integer> streak = new IdentityHashMap<>();
    /** How often each owner's scans were cancelled: a result carries the number it was started under, and is dropped when the owner's number has moved on. Client thread only. */
    private final Map<Object, Integer> epochs = new IdentityHashMap<>();
    /** Scans per owner that were handed to a worker and whose result has not been applied or dropped yet. Client thread only. */
    private final Map<Object, Integer> inFlight = new IdentityHashMap<>();
    private long delivered, dropped, failed;
    private final ServiceStats stats;

    /** The executor is asked for when the first scan starts, so a game that never scans never starts a thread. */
    public ScanPipeline(Supplier<Executor> executor, WorldCounter world) {
        this(executor, world, new ServiceStats());
    }

    public ScanPipeline(Supplier<Executor> executor, WorldCounter world, ServiceStats stats) {
        this.executor = executor;
        this.world = world;
        this.stats = stats;
    }

    /** Snapshots handed to workers that no worker has started on yet. */
    public int snapshotsWaiting() {
        return Math.max(0, waiting.get());
    }

    /** Results computed and not yet applied or dropped. */
    public int resultsWaiting() {
        return Math.max(0, ready.get());
    }

    /** Results handed to their module since the start. */
    public long delivered() {
        return delivered;
    }

    /** Results thrown away at delivery (chunk changed or gone, world changed, owner off). */
    public long dropped() {
        return dropped;
    }

    /** Scans that threw (and were reported), since the start. */
    public long failed() {
        return failed;
    }

    public <S, R> void start(Object owner, String kind, long chunk, int generation, S snapshot, Function<S, R> scanner, Consumer<R> onResult) {
        start(owner, kind, chunk, generation, snapshot, scanner, onResult, null);
    }

    /**
     * Hands a snapshot to a worker. Called on the client thread, after the snapshot was taken. {@code onFailed} (may be null) is called
     * on the client thread with the exception when the scanner threw and the result is still wanted.
     */
    /** Scans of the owner that are on a worker or whose result waits to be applied. */
    public int inFlight(Object owner) {
        return inFlight.getOrDefault(owner, 0);
    }

    public <S, R> void start(Object owner, String kind, long chunk, int generation, S snapshot, Function<S, R> scanner, Consumer<R> onResult, Consumer<Throwable> onFailed) {
        inFlight.merge(owner, 1, Integer::sum);
        int w = world.current();
        int epoch = epochs.getOrDefault(owner, 0);
        waiting.incrementAndGet();
        executor.get().execute(() -> {
            waiting.decrementAndGet();
            if (w != world.current()) return; // the world was left while this waited: nobody wants the result
            Result r;
            long t0 = System.nanoTime();
            try {
                R value = scanner.apply(snapshot);
                r = new Result(owner, kind, chunk, generation, w, epoch, () -> onResult.accept(value), null, onFailed);
            } catch (Throwable t) { // worker boundary: the error is handed to the client thread, which reports it
                r = new Result(owner, kind, chunk, generation, w, epoch, null, t, onFailed);
            }
            stats.worked(System.nanoTime() - t0);
            ready.incrementAndGet();
            results.add(r);
        });
    }

    /** Applies finished results on the client thread while there is time (at least one). Returns how many were applied. */
    public int deliver(Budget budget, Rules rules, ChunkIndex index) {
        int done = 0;
        while (done == 0 || budget.hasTimeLeft()) {
            Result r = results.poll();
            if (r == null) break;
            ready.decrementAndGet();
            if (r.world == rules.world() && r.epoch == epochs.getOrDefault(r.owner, 0)) inFlight.computeIfPresent(r.owner, (o, n) -> n <= 1 ? null : n - 1); // a result of an old world or a cancelled epoch was already forgotten by clear() or cancel()
            if (r.world != rules.world() || r.epoch != epochs.getOrDefault(r.owner, 0) || !rules.ownerOn(r.owner) || index.generation(r.chunk) != r.generation) {
                dropped++;
                continue;
            }
            done++;
            delivered++;
            long t0 = budget.now();
            if (r.error != null) {
                scanFailed(r, rules);
            } else {
                streak.remove(r.owner);
                try {
                    r.apply.run();
                } catch (Throwable t) { // the module system's boundary: a faulty result handler must not reach the game
                    rules.failed(r.owner, r.kind, t);
                }
            }
            budget.spend(budget.now() - t0);
        }
        return done;
    }

    /** One scanner failure: report it, tell the scan's callback, and fault the owner only when it keeps happening. */
    private void scanFailed(Result r, Rules rules) {
        failed++;
        int n = streak.merge(r.owner, 1, Integer::sum);
        try {
            rules.scanFailed(r.owner, r.kind, r.chunk, r.error);
            if (r.onFailed != null) r.onFailed.accept(r.error);
        } catch (Throwable t) { // the module system's boundary again: the report or the module's own callback threw
            rules.failed(r.owner, r.kind, t);
        }
        if (n >= FAULT_AFTER) {
            streak.remove(r.owner);
            rules.failed(r.owner, r.kind, r.error);
        }
    }

    /**
     * Everything the owner has handed to workers so far is cancelled: results already waiting are dropped now, and results of work
     * that is still running are dropped when they arrive (their epoch is old). Scans the owner starts after this are unaffected.
     */
    public void cancel(Object owner) {
        epochs.merge(owner, 1, Integer::sum);
        streak.remove(owner);
        inFlight.remove(owner);
        for (var it = results.iterator(); it.hasNext();) {
            if (it.next().owner == owner) {
                it.remove();
                ready.decrementAndGet();
                dropped++;
            }
        }
    }

    /** Throws away every result that is waiting (a world change). Work that is still on a worker is recognised by its world number. */
    public void clear() {
        streak.clear();
        inFlight.clear();
        while (results.poll() != null) {
            ready.decrementAndGet();
            dropped++;
        }
    }
}
