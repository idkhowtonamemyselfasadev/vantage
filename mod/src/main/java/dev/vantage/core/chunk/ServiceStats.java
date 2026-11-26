package dev.vantage.core.chunk;

/**
 * Numbers for the measurement of the chunk service (PERFORMANCE.md, task 3.3 step 5): how long work waited from the moment it was
 * queued to its result (a job that finished, or a scan result that reached the client thread), how much time the workers spent in
 * scanners, how many scans they ran and the longest the queue ever got. Waits are written on the client thread; the two worker
 * numbers are atomic because workers add to them. {@link #reset} starts a new window.
 */
public final class ServiceStats {
    private long waits, waitSumNs, waitMaxNs, snapshots, snapshotNs;
    private final java.util.concurrent.atomic.AtomicLong workerNs = new java.util.concurrent.atomic.AtomicLong(), workerRuns = new java.util.concurrent.atomic.AtomicLong();

    /** A job finished or a scan result arrived {@code ns} after it was queued. Client thread. */
    void waited(long ns) {
        waits++;
        waitSumNs += ns;
        if (ns > waitMaxNs) waitMaxNs = ns;
    }

    /** A snapshot was taken (the copy of the chunk's sections) and took {@code ns} of client thread time. Client thread. */
    void snapshotted(long ns) {
        snapshots++;
        snapshotNs += ns;
    }

    /** Snapshots taken since the last reset. */
    public long snapshots() {
        return snapshots;
    }

    /** Client thread time all snapshots took together, in milliseconds: the price of handing a scan to a worker. */
    public double snapshotMs() {
        return snapshotNs / 1e6;
    }

    /** A scanner ran for {@code ns} on a worker. Any thread. */
    void worked(long ns) {
        workerNs.addAndGet(ns);
        workerRuns.incrementAndGet();
    }

    /** Results (jobs finished, scans delivered) counted in the wait numbers. */
    public long waits() {
        return waits;
    }

    public double waitMeanMs() {
        return waits == 0 ? 0 : waitSumNs / 1e6 / waits;
    }

    public double waitMaxMs() {
        return waitMaxNs / 1e6;
    }

    /** Time all scanners together ran on workers, in milliseconds. */
    public double workerMs() {
        return workerNs.get() / 1e6;
    }

    /** Scanner calls made on workers (also those whose result was dropped later). */
    public long workerRuns() {
        return workerRuns.get();
    }

    public void reset() {
        waits = 0;
        waitSumNs = 0;
        waitMaxNs = 0;
        snapshots = 0;
        snapshotNs = 0;
        workerNs.set(0);
        workerRuns.set(0);
    }
}
