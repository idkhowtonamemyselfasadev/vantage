package dev.vantage.core.log;

import java.nio.file.Path;
import java.time.ZoneId;

import dev.vantage.core.store.Stores;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's logging: the one {@link LogHub} (file {@code config/vantage/logs/vantage.log}, 2 MB per file, 5 old files kept), the
 * core loggers and the exit hook. Modules use {@code Module.log()}; core code uses {@link #core}.
 */
public final class Log {
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final int KEEP = 5;
    /** Core sources the debug command knows before their code has run (a source is also known as soon as {@link #core} was called with it). */
    private static final String[] CORE_SOURCES = {"chunks", "config", "store", "commands", "leaks", "updater", "presence", "perf"};

    private static final Logger GAME = LoggerFactory.getLogger("Vantage");
    private static final LogHub HUB = new LogHub(Log::file, System::currentTimeMillis, ZoneId.systemDefault(), (level, text, error) -> {
        switch (level) {
            case INFO -> GAME.info(text, error);
            case WARN -> GAME.warn(text, error);
            case ERROR -> GAME.error(text, error);
            default -> GAME.debug(text, error);
        }
    });
    private static RollingFile file;
    private static boolean fileFailed;

    static {
        for (String s : CORE_SOURCES) HUB.source(s);
    }

    private Log() {}

    public static LogHub hub() {
        return HUB;
    }

    /** The logger of a core system, e.g. {@code Log.core("chunks")}. The name is what {@code debug <name> on} takes. */
    public static SourceLog core(String name) {
        return HUB.source(name);
    }

    private static synchronized RollingFile file() {
        if (file == null && !fileFailed) {
            try {
                Path p = FabricLoader.getInstance().getConfigDir().resolve("vantage").resolve("logs").resolve("vantage.log");
                file = DataLogs.open(p, MAX_BYTES, KEEP);
            } catch (RuntimeException | LinkageError e) {
                fileFailed = true; // no game around (unit tests; Config is not touched here so its class is not poisoned for them): lines still reach the game's log
            }
        }
        return file;
    }

    /** At exit: writes the "happened N more times" lines of the once keys. The caller flushes the writer afterwards (Stores.flush). */
    public static void writeRepeatCounts() {
        HUB.writeRepeatCounts();
    }

    /** Hands pending lines to the writer (for callers that cannot wait). */
    public static void flushRequest() {
        if (file != null) file.flushRequest();
    }

    static boolean synchronous() {
        return Stores.synchronousWrites();
    }
}
