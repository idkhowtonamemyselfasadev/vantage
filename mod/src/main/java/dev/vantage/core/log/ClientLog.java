package dev.vantage.core.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code Vantage.LOG} is: the game's logger of the same name, plus a copy of every line in the client's own log file. It has the
 * method names of an SLF4J logger that the client uses, so the call sites did not change. The game's log gets exactly what it got
 * before; the file line has time, level and source {@code Vantage}.
 */
public final class ClientLog extends Leveled {
    private static final Object[] NONE = new Object[0];
    private final Logger game = LoggerFactory.getLogger("Vantage");

    @Override
    void emit(LogLevel level, String format, Object[] args) {
        switch (level) {
            case INFO -> game.info(format, args);
            case WARN -> game.warn(format, args);
            case ERROR -> game.error(format, args);
            default -> game.debug(format, args);
        }
        Log.hub().line(level, "Vantage", format, args == null ? NONE : args, false);
    }
}
