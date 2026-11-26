package dev.vantage.core.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.vantage.core.Vantage;

/**
 * The one writer thread ("Vantage storage": daemon, lowest priority) that every store writes through, and the list of all stores.
 *
 * The client thread takes the snapshot of what is to be saved; serialising it to text and writing it to disk happen here, one job
 * after the other, so two saves never overlap and a later one always lands after an earlier one. {@link #tick()} (called every
 * client tick) hands over what is due; {@link #flush()} hands over everything and waits until it is on disk.
 *
 * In the in-game check rig (system property {@code vantage.ui}) every change is written and waited for at once, because the
 * checks read the files right after changing something and must not race with the writer thread. Real play never does that.
 */
public final class Stores {
    private static final Map<Store, Boolean> ALL = Collections.synchronizedMap(new WeakHashMap<>());
    private static final boolean SYNCHRONOUS = System.getProperty("vantage.ui") != null;
    static final long FLUSH_WAIT_SECONDS = 60;
    /** The text of the newest save of each file that has not reached the disk yet: what a load of that file reads (see {@link Store#read}). */
    private static final Map<Path, Store.Memo> PENDING = new ConcurrentHashMap<>();
    /** Per file, how many journal jobs are queued or running (see {@link Store#waitFor}). */
    private static final Map<Path, Integer> WRITING = new ConcurrentHashMap<>();
    private static ThreadPoolExecutor writer;
    private static final List<Writable> WRITABLES = new CopyOnWriteArrayList<>();

    /** Something that is not a store but writes through the same thread, on the same rhythm: the rolling log files ({@code core/log}). */
    public interface Writable {
        /** Client tick: hand over what is due (at least a second after the last hand-over). */
        void tick();

        /** Hand over everything pending; {@link Stores#flush()} then waits for the writer. */
        void flushRequest();
    }

    /** Adds a log file to the ticks and flushes. Files are few (a handful per server visited), so they are kept for the session. */
    public static void register(Writable w) {
        WRITABLES.add(w);
    }

    /** The writer thread as an executor, for classes that do their own batching. */
    public static Executor writerExecutor() {
        return Stores::submit;
    }

    /** True in the in-game check rig, where everything is written at once. */
    public static boolean synchronousWrites() {
        return SYNCHRONOUS;
    }

    private Stores() {}

    /**
     * A chunk set that follows its scope by itself: it loads the file of the place the player is in when that place changes (join,
     * dimension change, a module that was off is switched on), hands the old one to the writer when it is left, and has nothing loaded
     * and writes nothing while there is no world. The module declares it once and never calls load. Chain {@code activeWhen},
     * {@code onLoad} and {@code adopt} on the result (see {@link ChunkSetStore}); the file is {@code Scope.path(scope, name, ".chunks")}.
     */
    public static ChunkSetStore chunkSet(String name, Scope scope) {
        return ChunkSetStore.scoped(name, scope, dev.vantage.core.Config::dir, Scope::current, System::currentTimeMillis);
    }

    static void register(Store s) {
        ALL.put(s, Boolean.TRUE);
    }

    static boolean synchronous() {
        return SYNCHRONOUS;
    }

    static void pending(Path file, Store.Memo text) {
        PENDING.put(file.toAbsolutePath().normalize(), text);
    }

    static Store.Memo pending(Path file) {
        return PENDING.get(file.toAbsolutePath().normalize());
    }

    /** The save of {@code file} that had this text is on disk: it is no longer pending, unless a newer save replaced it meanwhile. */
    static void written(Path file, Store.Memo text) {
        PENDING.remove(file.toAbsolutePath().normalize(), text);
    }

    static void writing(Path file, int delta) {
        WRITING.merge(file.toAbsolutePath().normalize(), delta, (a, b) -> a + b == 0 ? null : a + b);
    }

    static boolean isWriting(Path file) {
        return WRITING.containsKey(file.toAbsolutePath().normalize());
    }

    private static synchronized ThreadPoolExecutor writer() {
        if (writer == null) {
            writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "Vantage storage");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        return writer;
    }

    /** Runs a job on the writer thread, after everything handed over before it. */
    static Future<?> submit(Runnable job) {
        return writer().submit(job);
    }

    private static List<Store> all() {
        synchronized (ALL) {
            return new ArrayList<>(ALL.keySet());
        }
    }

    /** Hands over every store whose save is due (changed, and the last one at least a second ago). Cheap when nothing changed. Client tick. */
    public static void tick() {
        for (Store s : all()) {
            s.followScope();
            s.tick();
        }
        for (Writable w : WRITABLES) w.tick();
    }

    /**
     * Hands over every changed store and waits until the writer has put everything on disk. For leaving a world and for game exit.
     * Returns false when the writer did not finish within a minute (it is logged).
     */
    public static boolean flush() {
        for (Store s : all()) s.flushRequest();
        for (Writable w : WRITABLES) w.flushRequest();
        return awaitWriter();
    }

    /** Waits until the jobs handed over so far are done. */
    static boolean awaitWriter() {
        Future<?> barrier = submit(() -> {});
        try {
            barrier.get(FLUSH_WAIT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Vantage.LOG.warn("Waiting for the storage writer was interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            Vantage.LOG.warn("The storage writer did not finish", e);
        }
        return false;
    }
}
