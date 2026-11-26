package dev.vantage.core.notify;

import java.util.function.IntFunction;

import dev.vantage.core.event.Events;

/**
 * The one call for telling the user something. {@code Notify.info|success|warning|error(owner, text)}, optionally with a key and a
 * cooldown, goes through the {@link Limiter} (so a noisy module cannot flood the screen) and then to the {@link Sink}.
 * The owner is a module or a core service ({@link #CORE} for core code that has none of its own).
 * <ul>
 * <li>Without a key the text is the key, so two different events (a text with a player name or a chunk in it) are both shown and the
 * same one is not repeated. Give a key where the text changes but the event is the same.</li>
 * <li>{@link #once} is for "found a new X": shown once per key until the world is left, not dropped by the 5-lines-in-10-s rule,
 * and merged into one summary line when more than 5 arrive within a second.</li>
 * <li>{@link #reply} is the direct path for answers to something the user just typed: no limiting, no merging, only the look.</li>
 * <li>A {@link Sound} is played only when the line is actually shown.</li>
 * </ul>
 * Not every thread is the client thread (config and stores report from their own), so everything here is safe to call from any of them.
 */
public final class Notify {
    /** Ready-made owner for core code that has none (notices about saved data, ...). */
    public static final Events.Owner CORE = new Events.Owner() {
        @Override public String ownerName() { return "Vantage"; }
        @Override public int ownerRank() { return 0; }
    };

    private static final Limiter LIMITER = new Limiter(() -> System.nanoTime() / 1_000_000L);
    private static volatile Sink sink = new ChatSink();

    private Notify() {}

    /** Phase 3: replacing this one object routes levels to an on-screen display instead of the chat box. */
    public static void sink(Sink s) {
        sink = s;
    }

    public static void info(Events.Owner owner, String text) { post(owner, Level.INFO, null, -1, text, null); }
    public static void info(Events.Owner owner, String key, String text) { post(owner, Level.INFO, key, -1, text, null); }
    public static void info(Events.Owner owner, String key, long cooldownMs, String text) { post(owner, Level.INFO, key, cooldownMs, text, null); }

    public static void success(Events.Owner owner, String text) { post(owner, Level.SUCCESS, null, -1, text, null); }
    public static void success(Events.Owner owner, String key, String text) { post(owner, Level.SUCCESS, key, -1, text, null); }
    public static void success(Events.Owner owner, String key, long cooldownMs, String text) { post(owner, Level.SUCCESS, key, cooldownMs, text, null); }

    public static void warning(Events.Owner owner, String text) { post(owner, Level.WARNING, null, -1, text, null); }
    public static void warning(Events.Owner owner, String key, String text) { post(owner, Level.WARNING, key, -1, text, null); }
    public static void warning(Events.Owner owner, String key, long cooldownMs, String text) { post(owner, Level.WARNING, key, cooldownMs, text, null); }

    public static void error(Events.Owner owner, String text) { post(owner, Level.ERROR, null, -1, text, null); }
    public static void error(Events.Owner owner, String key, String text) { post(owner, Level.ERROR, key, -1, text, null); }
    public static void error(Events.Owner owner, String key, long cooldownMs, String text) { post(owner, Level.ERROR, key, cooldownMs, text, null); }

    /** The general form: any level, key (null = the text), cooldown in ms (negative = 3000), optional sound. */
    public static void post(Events.Owner owner, Level level, String key, long cooldownMs, String text, Sound sound) {
        Sink s = sink;
        if (!s.ready()) return;
        Limiter.Line line = LIMITER.offer(owner, level, key, cooldownMs, text);
        if (line != null) s.show(line.level(), line.text(), sound);
    }

    /**
     * Tells the user once per key until the world is left (the key is remembered however long ago it was; a notification with a plain
     * cooldown would come again). It is not subject to the 5-lines-in-10-s drop, so a big first scan can announce every new find, but
     * more than 5 within a second are not shown one by one: when the owner has been quiet for a second one line says how many more
     * there were ("&lt;owner&gt;: n more"). The key must say what the line is about (a chunk, a position), not the whole text.
     */
    public static void once(Events.Owner owner, Level level, String key, String text) {
        once(owner, level, key, text, null);
    }

    /** {@link #once(Events.Owner, Level, String, String)} with the wording of the summary line, given the number of lines it stands for. */
    public static void once(Events.Owner owner, Level level, String key, String text, IntFunction<String> summary) {
        Sink s = sink;
        if (!s.ready()) return;
        Limiter.Line line = LIMITER.offerOnce(owner, level, key, text, summary);
        if (line != null) s.show(line.level(), line.text(), null);
    }

    /** An answer to something the user just typed: shown as it is, never limited or merged. */
    public static void reply(Level level, String text) {
        sink.show(level, text, null);
    }

    /** Called every client tick: shows the "held back" lines and merged errors that are due. */
    public static void tick() {
        Sink s = sink;
        if (!s.ready()) return;
        for (Limiter.Line line : LIMITER.tick()) s.show(line.level(), line.text(), null);
    }

    /** A world was left: the limiter state of every owner is dropped. */
    public static void worldLeft() {
        LIMITER.reset();
    }
}
