package dev.vantage.core.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import dev.vantage.core.Notices;
import dev.vantage.core.Vantage;
import dev.vantage.core.io.SafeFile;

/**
 * What every store shares: the dirty flag, the rule "at most one save a second", the hand-over to the writer thread and the rule
 * for a write that failed (logged once, tried again at the next request). A store that has been changed is saved by
 * {@link Stores#tick()}; {@link Stores#flush()} saves it at once.
 */
abstract class Store {
    static final long INTERVAL_MS = 1000;

    /** What the writer thread does for one save. It must not touch anything the client thread still changes. */
    interface Job {
        void run() throws IOException;
    }

    final String name;
    final Supplier<Path> path;
    private final LongSupplier clock;
    private volatile boolean dirty;
    private volatile long lastRequest = Long.MIN_VALUE / 2;
    private volatile boolean failureLogged;
    private final AtomicInteger saves = new AtomicInteger();
    /** The newest save of each file that has not run yet. Per file: a store that moves to another file (a scope changing) must not lose the save of the one it left. */
    private final Map<Path, Job> latest = new ConcurrentHashMap<>();

    Store(String name, Supplier<Path> path, LongSupplier clock) {
        this.name = name;
        this.path = path;
        this.clock = clock;
        Stores.register(this);
    }

    /** Says that something changed. Cheap, any thread. The save happens at the next {@link Stores#tick()} that is at least a second after the last one. */
    public void markDirty() {
        dirty = true;
        if (Stores.synchronous()) flush();
    }

    public boolean dirty() {
        return dirty;
    }

    /** How many saves have reached the disk (for tests and the benchmark). */
    public int saves() {
        return saves.get();
    }

    /** Saves now if anything changed, and waits until it is on disk. */
    public void flush() {
        flushRequest();
        Stores.awaitWriter();
    }

    final void tick() {
        if (dirty && clock.getAsLong() - lastRequest >= INTERVAL_MS) requestNow();
    }

    void flushRequest() {
        if (needsRequest()) requestNow();
    }

    boolean needsRequest() {
        return dirty;
    }

    /** Takes the snapshot (on the calling thread) and hands it over. Takes no lock of its own, so a store's write function may take its owner's lock. */
    final void requestNow() {
        lastRequest = clock.getAsLong();
        dirty = false;
        request();
    }

    /** Subclass: take the snapshot and call {@link #submitLatest} or {@link #submitInOrder}; or set {@link #markDirty} again when there is no path yet. */
    abstract void request();

    /** Text that is built once, by whichever thread needs it first: the writer, or a load on the client thread that finds the write still queued. */
    static final class Memo {
        private final Supplier<String> make;
        private String text;

        Memo(Supplier<String> make) {
            this.make = make;
        }

        synchronized String get() {
            if (text == null) text = make.get();
            return text;
        }
    }

    /**
     * For whole-file stores: queues the write of one file. A newer snapshot replaces one that has not been written yet, so the later one
     * wins. Until the write has reached the disk the text is also kept in {@link Stores}'s pending map, so a {@link #read} of the same
     * file, by this store or another, gets it from memory instead of an older file (see {@link #read}). A write that fails leaves its
     * text there: the session keeps seeing what was saved last, and the store asks for another save ({@link #retry}).
     */
    final void submitFile(Path file, Supplier<String> text, Predicate<String> readable) {
        Memo memo = new Memo(text);
        Stores.pending(file, memo);
        latest.put(file.toAbsolutePath().normalize(), () -> {
            SafeFile.write(file, memo.get(), readable);
            Stores.written(file, memo);
        });
        Stores.submit(() -> {
            Job j = latest.remove(file.toAbsolutePath().normalize());
            if (j != null) run(j);
        });
    }

    /** For stores whose jobs add up (the journal): every job runs, in the order handed over. {@code file} is what the jobs write, for {@link #waitFor}. */
    final void submitInOrder(Path file, Job job) {
        Stores.writing(file, +1);
        Stores.submit(() -> {
            try {
                run(job);
            } finally {
                Stores.writing(file, -1);
            }
        });
    }

    /**
     * What a load reads of {@code file}: when a save of that file is still queued or running, that save's text (the in-memory state it
     * will put on disk), else the file as {@link SafeFile#read} sees it. A load therefore never waits for the writer thread and never
     * reads an older file than the one just saved. Whole-file stores only.
     */
    final SafeFile.Read read(Path file, Predicate<String> readable) {
        Memo m = Stores.pending(file);
        if (m != null) {
            String text = m.get();
            if (readable.test(text)) return new SafeFile.Read(text, false, null);
        }
        return SafeFile.read(file, readable);
    }

    /**
     * Before a load: what the owner changed and has not yet handed over is handed over now (a snapshot on this thread, cheap), because
     * the load replaces it. Changes go to the file the store is bound to, which is the file they belong to even when the path supplier
     * already answers for another server. Nothing waits for the writer: the write goes on in the background.
     */
    final void handOver() {
        if (dirty()) requestNow();
    }

    /**
     * For a store that cannot serve a queued write from memory (the journal): waits for the writer, but only when it still has a job for
     * this very file, and at most {@link Stores#FLUSH_WAIT_SECONDS} seconds. A load of another file does not wait at all.
     */
    final void waitFor(Path file) {
        if (Stores.isWriting(file)) Stores.awaitWriter();
    }

    private void run(Job job) {
        try {
            job.run();
            saves.incrementAndGet();
            failureLogged = false;
        } catch (IOException | RuntimeException e) {
            if (!failureLogged) Vantage.LOG.warn("Could not save {} (tried again at the next request)", name, e);
            failureLogged = true;
            retry();
        }
    }

    /** A write failed: the data is still only in memory, so ask for another save. */
    void retry() {
        dirty = true;
    }

    /** Tells the user what a read found out about the file: damaged (and what was used instead), or missing with a backup. */
    final void report(Path p, SafeFile.Read r) {
        String file = p.getFileName().toString();
        String kept = r.badCopy() == null ? "" : " The damaged file was kept as " + r.badCopy().getFileName() + ".";
        if (r.damaged()) {
            Notices.add("Saved data", r.ok() ? file + " was damaged, so the last good copy was loaded." + kept
                    : file + " was damaged and there was no usable backup, so it starts empty." + kept + " It is not changed until you change something.");
        } else if (r.fromBackup()) {
            Notices.add("Saved data", file + " was missing, so the backup copy was loaded.");
        }
    }

    /** A store bound to a {@link Scope} looks whether the place changed and loads or lets go accordingly. Called every client tick by {@link Stores#tick()}. */
    void followScope() {}

    /** Forgets unsaved changes: they belonged to a place that has been left, or to no place at all. */
    final void clearDirty() {
        dirty = false;
    }

    /** No usable place to save yet (no world): keep the change for later. */
    final void stayDirty() {
        dirty = true;
    }
}
