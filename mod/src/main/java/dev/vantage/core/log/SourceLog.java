package dev.vantage.core.log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The logger of one source: a module (source = its id) or a core system ({@link Log#core}). Same {@code info}, {@code warn},
 * {@code error} shapes as SLF4J; lines go to the game's log (marked with the source) and to the client's own file.
 *
 * {@link #debug} is for tracing: it does nothing, and does not call its supplier, unless debug was switched on for this source with
 * the debug command; the check is one volatile read, so it is safe on a per-frame path. {@link #once} is for something wrong that
 * can repeat every frame: the first time a key is seen it is logged, later times are only counted and the counts are written at exit.
 */
public final class SourceLog extends Leveled {
    private static final int MAX_KEYS = 200;
    private static final String OTHER = "(other keys)";

    private final LogHub hub;
    private final String source;
    volatile boolean debugOn;
    private final ConcurrentHashMap<String, AtomicLong> seen = new ConcurrentHashMap<>();

    SourceLog(LogHub hub, String source) {
        this.hub = hub;
        this.source = source;
    }

    public String source() {
        return source;
    }

    public boolean debugging() {
        return debugOn;
    }

    @Override
    void emit(LogLevel level, String format, Object[] args) {
        hub.line(level, source, format, args, true);
    }

    /** Traces. Nothing is formatted or written while debug is off for this source. */
    public void debug(Supplier<String> message) {
        if (!debugOn) return;
        hub.line(LogLevel.DEBUG, source, message.get(), new Object[0], false);
    }

    /** Logs a warning the first time {@code key} is seen from this source; later times only count. The supplier runs only the first time. */
    public void once(String key, Supplier<String> message) {
        once(key, null, message);
    }

    /** Like {@link #once(String, Supplier)}, with the exception whose stack trace is written the first time. */
    public void once(String key, Throwable error, Supplier<String> message) {
        AtomicLong n = seen.get(key);
        if (n == null) {
            if (seen.size() >= MAX_KEYS) key = OTHER; // a key built from changing data must not grow the map for ever
            n = seen.computeIfAbsent(key, k -> new AtomicLong());
        }
        if (n.incrementAndGet() != 1) return;
        hub.line(LogLevel.WARN, source, message.get(), error == null ? new Object[0] : new Object[] {error}, true);
    }

    /** The repeats since the first time for every key, then reset; for the lines written at exit. */
    java.util.Map<String, Long> takeRepeats() {
        java.util.Map<String, Long> out = new java.util.TreeMap<>();
        for (var e : seen.entrySet()) {
            long n = e.getValue().getAndUpdate(x -> 1); // the key stays known (counts as seen once), so it is not reported as new again
            if (n > 1) out.put(e.getKey(), n - 1);
        }
        return out;
    }
}
