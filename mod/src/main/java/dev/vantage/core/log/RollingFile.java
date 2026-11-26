package dev.vantage.core.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * An append-only text file that never grows without limit. When the next record would take the file past {@code maxBytes} it is
 * renamed to {@code name.1} (older ones shift to {@code .2} ... {@code .keep}, the one that would become {@code .keep+1} is deleted)
 * and a new file starts.
 *
 * Appends are cheap and may come from any thread: the text is put in a pending list. The actual write runs on the {@code writer}
 * executor (in the client that is the storage writer thread), in batches: {@link #tick()} hands the pending text over when at least
 * a second has passed since the last write, so a burst of appends is one write and one {@code force}; {@link #flushRequest()} hands it over at
 * once (leaving a world, exit). With {@code synchronous} every append is written on the spot (the in-game check rig, whose checks read the
 * file right after an event). This class knows no game classes; the clock and the executor are injected so it can be tested on a temp folder.
 *
 * A record is one {@link #append} call (it may hold several lines, such as a stack trace) and is never split between two files. A record
 * larger than the limit goes alone into a fresh file, which then exceeds the limit by that one record. A write that fails is reported
 * once per file (through {@code problem}) and its text is dropped: a log must not throw into the caller or fill the memory.
 */
public final class RollingFile {
    static final long INTERVAL_MS = 1000;
    /** More than this many records waiting for the writer are dropped and counted (a stuck disk must not eat the memory). */
    static final int MAX_PENDING = 50_000;

    private final Path file;
    private final long maxBytes;
    private final int keep;
    private final LongSupplier clock;
    private final Executor writer;
    private final boolean synchronous;
    private final Consumer<String> problem;
    private final Object lock = new Object();
    private List<String> pending = new ArrayList<>();
    private long lastHandOver = Long.MIN_VALUE / 2;
    private int dropped;
    private final AtomicInteger writes = new AtomicInteger();
    private volatile boolean failureReported;
    /** Only the writer thread (or the caller in synchronous mode, under {@code lock}) touches these. */
    private long size = -1;
    private final Object ioLock = new Object();

    public RollingFile(Path file, long maxBytes, int keep, LongSupplier clock, Executor writer, boolean synchronous, Consumer<String> problem) {
        if (maxBytes < 1 || keep < 0) throw new IllegalArgumentException("bad limits");
        this.file = file;
        this.maxBytes = maxBytes;
        this.keep = keep;
        this.clock = clock;
        this.writer = writer;
        this.synchronous = synchronous;
        this.problem = problem;
    }

    public Path file() {
        return file;
    }

    /** How many writes reached {@link #file} so far (a batch is one write). */
    public int writes() {
        return writes.get();
    }

    /** Adds one record (a newline is added). Never throws, never blocks on the disk unless the file is synchronous. */
    public void append(String record) {
        synchronized (lock) {
            if (pending.size() >= MAX_PENDING) {
                dropped++;
                return;
            }
            pending.add(record);
        }
        if (synchronous) flushRequest();
    }

    /** Hands the pending text to the writer when the last hand-over was at least a second ago. Cheap when nothing is waiting. */
    public void tick() {
        synchronized (lock) {
            if (pending.isEmpty() || clock.getAsLong() - lastHandOver < INTERVAL_MS) return;
        }
        flushRequest();
    }

    /** Hands everything pending to the writer now. The caller waits for the writer (Stores.flush) when it needs the text on disk. */
    public void flushRequest() {
        List<String> batch;
        synchronized (lock) {
            if (pending.isEmpty()) return;
            batch = pending;
            pending = new ArrayList<>();
            lastHandOver = clock.getAsLong();
        }
        if (synchronous) write(batch);
        else writer.execute(() -> write(batch));
    }

    /** Records dropped because too many were waiting. */
    public int dropped() {
        synchronized (lock) {
            return dropped;
        }
    }

    private void write(List<String> batch) {
        synchronized (ioLock) {
            try {
                Path dir = file.getParent();
                if (dir != null) Files.createDirectories(dir);
                if (size < 0) size = Files.exists(file) ? Files.size(file) : 0;
                StringBuilder run = new StringBuilder();
                long runBytes = 0;
                for (String record : batch) {
                    String text = record + "\n";
                    long n = text.getBytes(StandardCharsets.UTF_8).length;
                    if (size + runBytes > 0 && size + runBytes + n > maxBytes) {
                        put(run, runBytes);
                        run.setLength(0);
                        runBytes = 0;
                        rotate();
                    }
                    run.append(text);
                    runBytes += n;
                }
                put(run, runBytes);
                writes.incrementAndGet();
            } catch (IOException | RuntimeException e) {
                size = -1; // unknown after a failure: measured again at the next write
                if (!failureReported) {
                    failureReported = true;
                    problem.accept("Could not write " + file + ": " + e);
                }
            }
        }
    }

    private void put(StringBuilder text, long bytes) throws IOException {
        if (bytes == 0) return;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(false);
        }
        size += bytes;
    }

    private Path numbered(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }

    private void rotate() throws IOException {
        if (keep == 0) {
            Files.deleteIfExists(file);
        } else {
            Files.deleteIfExists(numbered(keep));
            for (int i = keep - 1; i >= 1; i--) if (Files.exists(numbered(i))) Files.move(numbered(i), numbered(i + 1));
            if (Files.exists(file)) Files.move(file, numbered(1));
        }
        size = 0;
    }
}
