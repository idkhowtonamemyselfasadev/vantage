package dev.vantage.core.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

/**
 * The state of the client's logging, free of game classes so that it is unit tested: the file lines go to, the clock, the
 * per-source loggers, which sources have debug on, and the "once" counts. {@link Log} holds the one real instance.
 *
 * A line in the file: {@code 2026-11-20 14:03:22.123 WARN  [storage-esp] message}, followed by the stack trace when there is an exception.
 */
public final class LogHub {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /** Where a line of a {@link SourceLog} is also shown (the game's own log in the client). */
    public interface Echo {
        void line(LogLevel level, String text, Throwable error);
    }

    private final Supplier<RollingFile> file;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Echo echo;
    private final ConcurrentHashMap<String, SourceLog> sources = new ConcurrentHashMap<>();

    /** @param file the client's own log file, or null while there is none (the lines still reach the echo) */
    public LogHub(Supplier<RollingFile> file, LongSupplier clock, ZoneId zone, Echo echo) {
        this.file = file;
        this.clock = clock;
        this.zone = zone;
        this.echo = echo;
    }

    /** The logger of a source (a module id or a core name); the same object every time. */
    public SourceLog source(String name) {
        SourceLog s = sources.get(name);
        return s != null ? s : sources.computeIfAbsent(name, n -> new SourceLog(this, n));
    }

    /** Every source that has a logger, sorted: what the debug command can switch. */
    public List<String> sourceNames() {
        return new ArrayList<>(new TreeSet<>(sources.keySet()));
    }

    public boolean knows(String source) {
        return sources.containsKey(source);
    }

    public void setDebug(String source, boolean on) {
        source(source).debugOn = on;
    }

    public boolean debugOn(String source) {
        SourceLog s = sources.get(source);
        return s != null && s.debugOn;
    }

    /** The sources that have debug on, sorted. */
    public List<String> debugSources() {
        List<String> out = new ArrayList<>();
        for (SourceLog s : sources.values()) if (s.debugOn) out.add(s.source());
        out.sort(null);
        return out;
    }

    /** Debug off everywhere. Returns how many sources were on. */
    public int allDebugOff() {
        int n = 0;
        for (SourceLog s : sources.values()) {
            if (s.debugOn) n++;
            s.debugOn = false;
        }
        return n;
    }

    /** Formats and writes a line. {@code echo}: also hand it to the echo (the game's log); false for debug lines and for callers that log to the game themselves. */
    void line(LogLevel level, String source, String format, Object[] args, boolean echoToo) {
        FormattingTuple t = MessageFormatter.arrayFormat(format, args);
        record(level, source, t.getMessage(), t.getThrowable());
        if (echoToo && echo != null) echo.line(level, "[" + source + "] " + t.getMessage(), t.getThrowable());
    }

    /** Writes a finished message to the file only. Never throws. */
    public void record(LogLevel level, String source, String message, Throwable error) {
        RollingFile f = file.get();
        if (f == null) return;
        StringBuilder sb = new StringBuilder(64 + (message == null ? 4 : message.length()));
        sb.append(TIME.format(Instant.ofEpochMilli(clock.getAsLong()).atZone(zone))).append(' ');
        String name = level.name();
        sb.append(name).append(" ".repeat(Math.max(1, 6 - name.length()))).append('[').append(source).append("] ").append(message);
        if (error != null) {
            StringWriter w = new StringWriter();
            error.printStackTrace(new PrintWriter(w));
            sb.append('\n').append(w.toString().stripTrailing());
        }
        f.append(sb.toString());
    }

    /**
     * Writes "key happened N more times" for every once-key that repeated since the last call, and starts counting again. For the
     * end of a session. Returns the lines (also appended to the file).
     */
    public List<String> writeRepeatCounts() {
        List<String> out = new ArrayList<>();
        Map<String, SourceLog> sorted = new TreeMap<>(sources);
        for (SourceLog s : sorted.values()) {
            for (var e : s.takeRepeats().entrySet()) {
                String text = e.getKey() + " happened " + String.format(Locale.ROOT, "%,d", e.getValue()) + " more times";
                record(LogLevel.INFO, s.source(), text, null);
                out.add("[" + s.source() + "] " + text);
            }
        }
        return out;
    }
}
