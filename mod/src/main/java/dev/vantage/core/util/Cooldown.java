package dev.vantage.core.util;

import java.util.function.LongSupplier;

/**
 * "Has this much time passed since the last time". The clock is injected so tests drive it by hand; the two factories pick the
 * unit: {@link #wall} counts real milliseconds (keeps running while a single-player game is paused), {@link #ticks} counts game
 * ticks of the clock you give it (stands still while paused). Not thread safe: one thread owns a cooldown.
 *
 * <p>The first {@link #tryUse} always passes. If the clock goes backwards (a new world restarts the tick counter, the system time
 * is set back) the cooldown counts as passed and starts again from the new time, so it can never stay locked.
 */
public final class Cooldown {
    private final long period;
    private final LongSupplier clock;
    private boolean used;
    private long last;

    /** A cooldown of {@code period} units of the given clock. */
    public Cooldown(long period, LongSupplier clock) {
        this.period = period;
        this.clock = clock;
    }

    /** Cooldown of {@code ms} real milliseconds on {@link System#currentTimeMillis}. */
    public static Cooldown wall(long ms) {
        return new Cooldown(ms, System::currentTimeMillis);
    }

    /** Cooldown of {@code ms} real milliseconds on the given millisecond clock (for tests). */
    public static Cooldown wall(long ms, LongSupplier millis) {
        return new Cooldown(ms, millis);
    }

    /** Cooldown of {@code ticks} game ticks on the given tick counter (for example {@code level::getGameTime}). */
    public static Cooldown ticks(long ticks, LongSupplier gameTime) {
        return new Cooldown(ticks, gameTime);
    }

    /** True if the action may run now (first call, period passed, or the clock went back); does not start the period. */
    public boolean ready() {
        if (!used) return true;
        long now = clock.getAsLong();
        return now < last || now - last >= period;
    }

    /** If ready, starts the period and returns true; otherwise false and nothing changes. */
    public boolean tryUse() {
        if (!ready()) return false;
        used = true;
        last = clock.getAsLong();
        return true;
    }

    /** Units of the clock left until the next pass; 0 when ready. */
    public long remaining() {
        if (!ready()) return period - (clock.getAsLong() - last);
        return 0;
    }

    /** Forgets the last use: the next {@link #tryUse} passes. */
    public void reset() {
        used = false;
    }
}
