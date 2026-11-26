package dev.vantage.core.log;

/**
 * The call shapes of an SLF4J logger that the client uses ({@code {}} placeholders with one, two or many arguments, and a trailing
 * Throwable), spelled out once so that {@code Vantage.LOG} and the per-source loggers accept exactly the same calls and the 136 existing
 * call sites did not have to change. Everything ends in {@link #emit}.
 */
abstract class Leveled {
    abstract void emit(LogLevel level, String format, Object[] args);

    private static final Object[] NONE = new Object[0];

    public final void info(String msg) { emit(LogLevel.INFO, msg, NONE); }
    public final void info(String format, Object a) { emit(LogLevel.INFO, format, new Object[] {a}); }
    public final void info(String format, Object a, Object b) { emit(LogLevel.INFO, format, new Object[] {a, b}); }
    public final void info(String format, Object... args) { emit(LogLevel.INFO, format, args); }
    public final void info(String msg, Throwable t) { emit(LogLevel.INFO, msg, new Object[] {t}); }

    public final void warn(String msg) { emit(LogLevel.WARN, msg, NONE); }
    public final void warn(String format, Object a) { emit(LogLevel.WARN, format, new Object[] {a}); }
    public final void warn(String format, Object a, Object b) { emit(LogLevel.WARN, format, new Object[] {a, b}); }
    public final void warn(String format, Object... args) { emit(LogLevel.WARN, format, args); }
    public final void warn(String msg, Throwable t) { emit(LogLevel.WARN, msg, new Object[] {t}); }

    public final void error(String msg) { emit(LogLevel.ERROR, msg, NONE); }
    public final void error(String format, Object a) { emit(LogLevel.ERROR, format, new Object[] {a}); }
    public final void error(String format, Object a, Object b) { emit(LogLevel.ERROR, format, new Object[] {a, b}); }
    public final void error(String format, Object... args) { emit(LogLevel.ERROR, format, args); }
    public final void error(String msg, Throwable t) { emit(LogLevel.ERROR, msg, new Object[] {t}); }
}
