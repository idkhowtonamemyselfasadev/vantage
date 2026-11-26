package dev.vantage.core.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one way the game reaches client code: a hook fires an event, listeners receive it.
 *
 * How it is built, and why:
 * <ul>
 * <li>Every event class has a {@link Channel} holding one array of the listeners that are live right now. Firing reads that array
 * once and loops over it with an index, so firing allocates nothing; adding or removing a listener builds a new array (rare).</li>
 * <li>A listener belongs to an {@link Owner} through a {@link Group}. Code never adds a single listener to the bus: it declares
 * listeners in its group and the group is attached or detached as a whole. That is what makes a leak impossible to write: a module
 * that is switched off has its whole group detached, whatever it declared.</li>
 * <li>Order is fixed and does not depend on when something was switched on: higher priority first, then the owner's rank, then the
 * order in which the owner declared its listeners.</li>
 * <li>A listener that throws never reaches the game: the error goes to the {@link FaultSink} with the owner, and the loop carries on
 * with the next listener.</li>
 * </ul>
 * All of this is called on the client thread. Attach and detach are synchronised so that a mistake elsewhere cannot corrupt the
 * arrays, but events are meant to be fired from one thread.
 */
public final class Events {
    private Events() {}

    /** Receives one kind of event. */
    @FunctionalInterface
    public interface Handler<E> {
        void on(E event) throws Exception;
    }

    /** Whoever owns listeners: a module, or a core service. */
    public interface Owner {
        /** For messages and the log. */
        String ownerName();

        /** Position among all owners; decides the order of listeners with the same priority. */
        int ownerRank();
    }

    /** Told when a listener threw. The module system sets this at start-up; until then errors are rethrown so that tests see them. */
    @FunctionalInterface
    public interface FaultSink {
        void fault(Owner owner, Class<?> eventType, Throwable error);
    }

    private static final Map<Class<?>, Channel<?>> CHANNELS = new IdentityHashMap<>();
    private static volatile FaultSink sink;

    public static void faultSink(FaultSink s) {
        sink = s;
    }

    /** The channel of an event class, created on first use. */
    @SuppressWarnings("unchecked") // the map only ever stores Channel<E> under the key E.class
    public static <E> Channel<E> channel(Class<E> type) {
        synchronized (CHANNELS) {
            return (Channel<E>) CHANNELS.computeIfAbsent(type, t -> new Channel<>(type));
        }
    }

    /** Fires an event through its channel. Hooks that fire every tick or frame keep the channel in a constant and call it directly. */
    @SuppressWarnings("unchecked") // an event object is always fired through the channel of its own class
    public static <E> void fire(E event) {
        Channel<E> c;
        synchronized (CHANNELS) {
            c = (Channel<E>) CHANNELS.get(event.getClass());
        }
        if (c != null) c.fire(event);
    }

    /** Live listeners of one owner, over all channels (0 after its group was detached). For tests and the developer overlay. */
    public static int liveCount(Owner owner) {
        int n = 0;
        synchronized (CHANNELS) {
            for (Channel<?> c : CHANNELS.values()) for (Entry<?> e : c.live) if (e.owner == owner) n++;
        }
        return n;
    }

    /** Live listeners in total. */
    public static int liveCount() {
        int n = 0;
        synchronized (CHANNELS) {
            for (Channel<?> c : CHANNELS.values()) n += c.live.length;
        }
        return n;
    }

    private static final class Entry<E> {
        final Class<E> type;
        final Owner owner;
        final Handler<? super E> handler;
        final int priority, index;
        /** False from the moment the listener is detached, so a pass that is already running skips it. */
        volatile boolean attached;

        Entry(Class<E> type, Owner owner, Handler<? super E> handler, int priority, int index) {
            this.type = type; this.owner = owner; this.handler = handler; this.priority = priority; this.index = index;
        }
    }

    private static final Comparator<Entry<?>> ORDER = Comparator.<Entry<?>>comparingInt(e -> -e.priority).thenComparingInt(e -> e.owner.ownerRank()).thenComparingInt(e -> e.index);

    /** The listeners of one event class. */
    public static final class Channel<E> {
        private static final Entry<?>[] NONE = new Entry<?>[0];
        private final Class<E> type;
        private volatile Entry<?>[] live = NONE;
        /** Runs right after a listener of this channel threw, before the next one: the channel's owner puts back what the failed one left open. */
        public volatile Runnable afterFault;

        private Channel(Class<E> type) {
            this.type = type;
        }

        public Class<E> type() {
            return type;
        }

        /** Hands the event to every live listener, in order. Allocates nothing. */
        @SuppressWarnings("unchecked") // entries of this channel were created for E
        public void fire(E event) {
            if (dev.vantage.core.Terms.off()) return; // the terms gate: while it is up no listener hears anything
            Entry<?>[] a = live;
            for (int i = 0; i < a.length; i++) {
                Entry<E> e = (Entry<E>) a[i];
                if (!e.attached) continue;
                try {
                    e.handler.on(event);
                } catch (Throwable t) {
                    Runnable af = afterFault;
                    if (af != null) { try { af.run(); } catch (Throwable ignored) { /* cleanup must never hide the fault */ } }
                    FaultSink s = sink;
                    if (s == null) throw t instanceof RuntimeException r ? r : new IllegalStateException("listener of " + e.owner.ownerName() + " failed", t);
                    s.fault(e.owner, type, t);
                }
            }
        }

        public int size() {
            return live.length;
        }

        private synchronized void add(Entry<E> e) {
            Entry<?>[] b = Arrays.copyOf(live, live.length + 1);
            b[b.length - 1] = e;
            Arrays.sort(b, ORDER);
            e.attached = true;
            live = b;
        }

        private synchronized void remove(Entry<E> e) {
            e.attached = false;
            Entry<?>[] a = live;
            int at = -1;
            for (int i = 0; i < a.length; i++) if (a[i] == e) at = i;
            if (at < 0) return;
            if (a.length == 1) { live = NONE; return; }
            Entry<?>[] b = new Entry<?>[a.length - 1];
            System.arraycopy(a, 0, b, 0, at);
            System.arraycopy(a, at + 1, b, at, a.length - at - 1);
            live = b;
        }
    }

    /** Everything one owner listens to. Declared once, attached and detached as a whole. */
    public static final class Group {
        private final Owner owner;
        private final List<Entry<?>> declared = new ArrayList<>();
        private boolean attached;

        public Group(Owner owner) {
            this.owner = owner;
        }

        public <E> Group listen(Class<E> type, Handler<? super E> handler) {
            return listen(type, 0, handler);
        }

        /** Higher priority runs earlier. Most listeners use 0. */
        public synchronized <E> Group listen(Class<E> type, int priority, Handler<? super E> handler) {
            Entry<E> e = new Entry<>(type, owner, handler, priority, declared.size());
            declared.add(e);
            if (attached) channel(type).add(e);
            return this;
        }

        public synchronized void attach() {
            if (attached) return;
            attached = true;
            for (Entry<?> e : declared) add(e);
        }

        public synchronized void detach() {
            if (!attached) return;
            attached = false;
            for (Entry<?> e : declared) remove(e);
        }

        public synchronized boolean attached() {
            return attached;
        }

        public synchronized int declared() {
            return declared.size();
        }

        private static <E> void add(Entry<E> e) {
            channel(e.type).add(e);
        }

        private static <E> void remove(Entry<E> e) {
            channel(e.type).remove(e);
        }
    }
}
