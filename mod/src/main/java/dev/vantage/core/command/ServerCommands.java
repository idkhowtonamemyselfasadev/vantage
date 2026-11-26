package dev.vantage.core.command;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import dev.vantage.core.event.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place a module sends a command to the server. It refuses while no world is joined, keeps at least 250 ms between two
 * commands (later ones wait in a queue and go out in order from {@link #tick}), takes a leading slash off, and logs each command at
 * debug level with the module that sent it. The first command after a quiet moment goes out at once, in the same tick.
 * The queue is dropped when the world is left ({@link #clear}).
 */
public final class ServerCommands {
    /** The shortest time between two commands. */
    public static final long MIN_GAP_MS = 250;
    private static final Logger LOG = LoggerFactory.getLogger("vantage.servercommands");

    private record Waiting(Events.Owner owner, String text) {}

    private final LongSupplier clock;
    private final Consumer<String> wire;
    private final BooleanSupplier joined;
    private final ArrayDeque<Waiting> queue = new ArrayDeque<>();
    private long lastSent = Long.MIN_VALUE / 2;

    /** Tests build their own with a fake clock; the game uses {@link #install}. */
    public ServerCommands(LongSupplier clockMillis, Consumer<String> wire, BooleanSupplier joined) {
        this.clock = clockMillis;
        this.wire = wire;
        this.joined = joined;
    }

    private static ServerCommands main = new ServerCommands(System::currentTimeMillis, t -> {}, () -> false);

    /** Called once at start: how a command reaches the server and when a world counts as joined. */
    public static void install(Consumer<String> wire, BooleanSupplier joined) {
        main = new ServerCommands(System::currentTimeMillis, wire, joined);
    }

    /** True when the command was sent or queued; false when it was refused (no world, or nothing left after the slash). */
    public static boolean send(Events.Owner owner, String text) {
        return main.sendNow(owner, text);
    }

    public static void tick() {
        main.drain();
    }

    public static void clear() {
        main.drop();
    }

    public boolean sendNow(Events.Owner owner, String text) {
        String t = text == null ? "" : text.strip();
        while (t.startsWith("/")) t = t.substring(1).stripLeading();
        if (t.isEmpty() || !joined.getAsBoolean()) return false;
        if (queue.isEmpty() && clock.getAsLong() - lastSent >= MIN_GAP_MS) {
            out(new Waiting(owner, t));
        } else {
            queue.add(new Waiting(owner, t));
        }
        return true;
    }

    /** Sends the next waiting command when the gap has passed. At most one per call: the gap is longer than a tick. */
    public void drain() {
        if (queue.isEmpty()) return;
        if (!joined.getAsBoolean()) {
            queue.clear();
            return;
        }
        if (clock.getAsLong() - lastSent >= MIN_GAP_MS) out(queue.poll());
    }

    public void drop() {
        queue.clear();
    }

    public int waiting() {
        return queue.size();
    }

    private void out(Waiting w) {
        lastSent = clock.getAsLong();
        LOG.debug("{} sends /{}", w.owner == null ? "?" : w.owner.ownerName(), w.text);
        wire.accept(w.text);
    }
}
