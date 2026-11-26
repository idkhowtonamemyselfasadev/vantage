package dev.vantage.core.chunk;

/**
 * Numbers the worlds of a session. Work that was queued or computed in one world carries the number it saw, and is thrown away when
 * the number has moved on: that is how a late result from a worker thread is recognised. Read from worker threads, written on the
 * client thread only.
 */
public final class WorldCounter {
    private volatile int current;

    public int current() {
        return current;
    }

    /** A new world: everything numbered before is stale. */
    public int next() {
        return ++current;
    }
}
