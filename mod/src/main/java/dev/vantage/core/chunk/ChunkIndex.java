package dev.vantage.core.chunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Which chunks of the current world are loaded, since when, and a generation number per chunk that goes up each time its data
 * arrives (a new chunk, a re-sent chunk, a burst of block changes). A load alone makes the chunk known with generation 0, so a new
 * chunk is at generation 1 once its data event has come. A result computed from generation n is out of date as soon as the chunk
 * is at n + 1. Client thread only.
 */
public final class ChunkIndex {
    /** Nothing is known about this chunk. */
    public static final int NONE = -1;

    /** Called for every chunk by {@link #forEach} and {@link #forEachLoaded}. */
    @FunctionalInterface
    public interface Visitor {
        void visit(int cx, int cz, long gameTime, long wallMs, int generation);
    }

    private static final class Entry {
        final long gameTime, wallMs;
        int generation;

        Entry(long gameTime, long wallMs) {
            this.gameTime = gameTime;
            this.wallMs = wallMs;
        }
    }

    private final Map<Long, Entry> chunks = new HashMap<>();

    /** The key every part of the service uses for a chunk; the same layout as vanilla's ChunkPos.asLong. */
    public static long key(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    public static int keyX(long key) {
        return (int) key;
    }

    public static int keyZ(long key) {
        return (int) (key >>> 32);
    }

    /** The chunk became part of the world. A load of a chunk that is already known (a re-send) changes nothing. */
    public void load(int cx, int cz, long gameTime, long wallMs) {
        chunks.computeIfAbsent(key(cx, cz), k -> new Entry(gameTime, wallMs));
    }

    /** The chunk's data arrived (or was rewritten). Returns the new generation. A chunk that was never announced is added. */
    public int data(int cx, int cz, long gameTime, long wallMs) {
        Entry e = chunks.computeIfAbsent(key(cx, cz), k -> new Entry(gameTime, wallMs));
        return ++e.generation;
    }

    public void unload(int cx, int cz) {
        chunks.remove(key(cx, cz));
    }

    public void clear() {
        chunks.clear();
    }

    public boolean isLoaded(long key) {
        return chunks.containsKey(key);
    }

    public boolean isLoaded(int cx, int cz) {
        return isLoaded(key(cx, cz));
    }

    /** The chunk's generation, or {@link #NONE}. */
    public int generation(long key) {
        Entry e = chunks.get(key);
        return e == null ? NONE : e.generation;
    }

    /** The game time at which the chunk first arrived, or -1. */
    public long loadedAt(int cx, int cz) {
        Entry e = chunks.get(key(cx, cz));
        return e == null ? -1 : e.gameTime;
    }

    /** The wall clock (epoch milliseconds) at which the chunk first arrived, or -1. */
    public long loadedWallMs(int cx, int cz) {
        Entry e = chunks.get(key(cx, cz));
        return e == null ? -1 : e.wallMs;
    }

    public int count() {
        return chunks.size();
    }

    /**
     * Calls the visitor for every chunk that has data (generation 1 or more), in no particular order: the chunks a scan can read. A chunk
     * that is only loaded (generation 0: its data event has not come) is skipped; {@link #forEachLoaded} visits those too. The index must
     * not change during the walk.
     */
    public void forEach(Visitor v) {
        for (var e : chunks.entrySet()) if (e.getValue().generation >= 1) v.visit(keyX(e.getKey()), keyZ(e.getKey()), e.getValue().gameTime, e.getValue().wallMs, e.getValue().generation);
    }

    /** Calls the visitor for every loaded chunk, including those at generation 0, in no particular order. The index must not change during the walk. */
    public void forEachLoaded(Visitor v) {
        for (var e : chunks.entrySet()) v.visit(keyX(e.getKey()), keyZ(e.getKey()), e.getValue().gameTime, e.getValue().wallMs, e.getValue().generation);
    }
}
