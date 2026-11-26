package dev.vantage.core.chunk;

import java.util.function.LongSupplier;

/**
 * The time account of one tick: everything that scans chunks spends what it used and asks whether there is time left. The limit is
 * a request to start no more work, not an interrupt: a job that has started runs to its end or to its own check of
 * {@link #hasTimeLeft()}. The clock is injected so that tests need no real time. Client thread only.
 */
public final class Budget {
    /** The default for everyone: 2.0 ms of chunk work per tick (a 60 fps frame is 16.7 ms). */
    public static final long DEFAULT_NS = 2_000_000L;

    private final LongSupplier clock;
    private long limitNs = DEFAULT_NS;
    private long usedNs;
    /** Start of the job that is running right now, or -1: its time counts as used while it runs, so it can ask whether to carry on. */
    private long jobStart = -1;

    public Budget(LongSupplier clock) {
        this.clock = clock;
    }

    /** The clock the budget was given, in nanoseconds. */
    public long now() {
        return clock.getAsLong();
    }

    /** A new tick starts: nothing is used yet. */
    public void newTick() {
        usedNs = 0;
    }

    /** The queue is about to call a job. */
    public void begin() {
        jobStart = clock.getAsLong();
    }

    /** The job returned: what it took is spent. */
    public void end() {
        usedNs += clock.getAsLong() - jobStart;
        jobStart = -1;
    }

    /** Adds time used by something that is not a queued job (a module's own scan loop, applying a result). */
    public void spend(long ns) {
        usedNs += ns;
    }

    /** Nanoseconds used so far this tick, including the part of the running job that is already behind it. */
    public long used() {
        return jobStart < 0 ? usedNs : usedNs + (clock.getAsLong() - jobStart);
    }

    public long limit() {
        return limitNs;
    }

    public void limit(long ns) {
        limitNs = ns;
    }

    /** Nanoseconds left in this tick's budget (zero or less when it is used up). */
    public long timeLeft() {
        return limitNs - used();
    }

    public boolean hasTimeLeft() {
        return used() < limitNs;
    }
}
