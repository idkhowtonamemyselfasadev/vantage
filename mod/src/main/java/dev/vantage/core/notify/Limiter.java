package dev.vantage.core.notify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

import dev.vantage.core.event.Events;

/**
 * The rate limiting of {@link Notify}, with no game classes and an injectable clock (milliseconds) so that it is unit tested.
 * <ul>
 * <li>The same key from the same owner is shown at most once per cooldown (default 3000 ms). A call without a key uses its text.</li>
 * <li>An owner gets at most 5 lines in any 10 s. What exceeds that is counted; when the owner has made no call for 2 s, {@link #tick}
 * returns one line "&lt;owner&gt;: n more messages held back".</li>
 * <li>Errors are never dropped. A repeated error inside its cooldown is merged; {@link #tick} (or the next call of that key after the
 * cooldown) returns the line with "(xN)", N being the number of times it happened since the line that was shown. Errors do not count
 * against the 5 lines and are not held back by them.</li>
 * </ul>
 * <li>{@link #offerOnce}: a key from an owner is shown once until {@link #reset} (the world is left), however long it has been, and is
 * not held back by the 5-in-10-s rule. When more than 5 such lines arrive within a second the rest are not shown one by one but
 * counted, and once the owner has been quiet for a second {@link #tick} returns one summary line.</li>
 * </ul>
 * State is per owner (compared by identity) and per key. {@link #reset} drops all of it (the client calls it when a world is left).
 * Quiet state is removed in {@link #tick}, so a module that sends text with coordinates in it does not grow the maps for ever.
 */
public final class Limiter {
    public static final long DEFAULT_COOLDOWN_MS = 3000, WINDOW_MS = 10_000, QUIET_MS = 2000, ONCE_WINDOW_MS = 1000;
    /** Most distinct once-keys remembered per owner; further new keys are counted into the summary instead of shown, so the set cannot grow for ever. */
    public static final int MAX_ONCE_KEYS = 10_000;
    public static final int MAX_LINES = 5;

    /** A line that may be shown now. */
    public record Line(Events.Owner owner, Level level, String text) {}

    private static final class KeyState {
        long shownAt, cooldown;
        int pending;
        String text;
        Level level;
    }

    private static final class OwnerState {
        final Map<String, KeyState> keys = new HashMap<>();
        final ArrayDeque<Long> shown = new ArrayDeque<>();
        int held;
        long lastCall;
        /** {@link #offerOnce}: keys already told, the times of the lines shown in the last second, what was held back and how to word it. */
        final Set<String> once = new HashSet<>();
        final ArrayDeque<Long> onceShown = new ArrayDeque<>();
        int onceHeld;
        long lastOnce;
        Level onceLevel;
        IntFunction<String> onceSummary;
    }

    private final LongSupplier clock;
    private final Map<Events.Owner, OwnerState> owners = new IdentityHashMap<>();

    public Limiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * One notification. Returns the line to show now, or null when it is dropped (a repeat inside its cooldown, or over the 5 lines)
     * or merged (an error repeated inside its cooldown). A negative cooldown means the default.
     */
    public synchronized Line offer(Events.Owner owner, Level level, String key, long cooldownMs, String text) {
        long now = clock.getAsLong();
        String k = key == null ? text : key;
        long cooldown = cooldownMs < 0 ? DEFAULT_COOLDOWN_MS : cooldownMs;
        OwnerState os = owners.computeIfAbsent(owner, o -> new OwnerState());
        KeyState ks = os.keys.get(k);
        if (level == Level.ERROR) {
            if (ks != null && now - ks.shownAt < ks.cooldown) {
                ks.pending++;
                ks.text = text;
                ks.level = level;
                return null;
            }
            String shown = ks != null && ks.pending > 0 ? text + " (x" + (ks.pending + 1) + ")" : text;
            remember(os, k, ks, now, cooldown, text, level);
            return new Line(owner, level, shown);
        }
        os.lastCall = now;
        if (ks != null && now - ks.shownAt < ks.cooldown) return null;
        while (!os.shown.isEmpty() && now - os.shown.peekFirst() >= WINDOW_MS) os.shown.pollFirst();
        if (os.shown.size() >= MAX_LINES) {
            os.held++;
            return null;
        }
        os.shown.addLast(now);
        remember(os, k, ks, now, cooldown, text, level);
        return new Line(owner, level, text);
    }

    /**
     * A line that is shown once per key until {@link #reset}. Returns it, or null when the key was told before or the line is held back
     * for the summary (more than {@link #MAX_LINES} within {@link #ONCE_WINDOW_MS}). {@code summary} (null = "&lt;owner&gt;: n more")
     * words the summary line for n held lines; the one given by the latest held call is used.
     */
    public synchronized Line offerOnce(Events.Owner owner, Level level, String key, String text, IntFunction<String> summary) {
        long now = clock.getAsLong();
        OwnerState os = owners.computeIfAbsent(owner, o -> new OwnerState());
        if (os.once.contains(key)) return null;
        os.lastOnce = now;
        while (!os.onceShown.isEmpty() && now - os.onceShown.peekFirst() >= ONCE_WINDOW_MS) os.onceShown.pollFirst();
        if (os.once.size() >= MAX_ONCE_KEYS || os.onceShown.size() >= MAX_LINES) {
            if (os.once.size() < MAX_ONCE_KEYS) os.once.add(key); // held, but told: it is part of the summary and does not come again
            os.onceHeld++;
            os.onceLevel = level;
            os.onceSummary = summary;
            return null;
        }
        os.once.add(key);
        os.onceShown.addLast(now);
        return new Line(owner, level, text);
    }

    private static void remember(OwnerState os, String key, KeyState old, long now, long cooldown, String text, Level level) {
        KeyState ks = old != null ? old : new KeyState();
        ks.shownAt = now;
        ks.cooldown = cooldown;
        ks.pending = 0;
        ks.text = text;
        ks.level = level;
        if (old == null) os.keys.put(key, ks);
    }

    /** What is due now: held-back lines for owners that went quiet, merged errors whose cooldown is over. Also drops state that has nothing left to say. */
    public synchronized List<Line> tick() {
        if (owners.isEmpty()) return List.of();
        long now = clock.getAsLong();
        List<Line> out = null;
        for (Iterator<Map.Entry<Events.Owner, OwnerState>> oi = owners.entrySet().iterator(); oi.hasNext();) {
            Map.Entry<Events.Owner, OwnerState> oe = oi.next();
            Events.Owner owner = oe.getKey();
            OwnerState os = oe.getValue();
            if (os.held > 0 && now - os.lastCall >= QUIET_MS) {
                if (out == null) out = new ArrayList<>();
                out.add(new Line(owner, Level.INFO, owner.ownerName() + ": " + os.held + " more messages held back"));
                os.held = 0;
            }
            if (os.onceHeld > 0 && now - os.lastOnce >= ONCE_WINDOW_MS) {
                if (out == null) out = new ArrayList<>();
                out.add(new Line(owner, os.onceLevel, os.onceSummary != null ? os.onceSummary.apply(os.onceHeld) : owner.ownerName() + ": " + os.onceHeld + " more"));
                os.onceHeld = 0;
            }
            for (Iterator<KeyState> ki = os.keys.values().iterator(); ki.hasNext();) {
                KeyState ks = ki.next();
                if (now - ks.shownAt < ks.cooldown) continue;
                if (ks.pending > 0) {
                    if (out == null) out = new ArrayList<>();
                    out.add(new Line(owner, ks.level, ks.text + " (x" + (ks.pending + 1) + ")"));
                    ks.pending = 0;
                    ks.shownAt = now;
                } else ki.remove();
            }
            while (!os.shown.isEmpty() && now - os.shown.peekFirst() >= WINDOW_MS) os.shown.pollFirst();
            while (!os.onceShown.isEmpty() && now - os.onceShown.peekFirst() >= ONCE_WINDOW_MS) os.onceShown.pollFirst();
            if (os.keys.isEmpty() && os.shown.isEmpty() && os.held == 0 && os.once.isEmpty() && os.onceHeld == 0) oi.remove(); // the once-keys stay until reset()
        }
        return out == null ? List.of() : out;
    }

    /** Forgets everything (the world was left). */
    public synchronized void reset() {
        owners.clear();
    }
}
