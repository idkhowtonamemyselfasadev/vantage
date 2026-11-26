package dev.vantage.core.chunk;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * The one queue of chunk work. Plain code, no game classes, so it is tested with a fake clock.
 * <ul>
 * <li>Submitting the same owner + kind + chunk again changes nothing in the order: the job and the generation it was submitted at
 * are replaced, the place in the queue is kept.</li>
 * <li>{@link #run} takes jobs until the tick's budget is used up, but always runs at least one, so the queue moves even when a
 * single job costs more than the whole budget. A job that returns "not finished" is continued next tick, before any new job.</li>
 * <li>A job is dropped, unrun, when its owner is off, its chunk is no longer loaded, the chunk's data is newer than at submit time,
 * or the world is another one.</li>
 * </ul>
 * Client thread only. Submitting allocates one small key; running allocates nothing.
 */
public final class WorkQueue {
    private record Key(Object owner, String kind, long chunk) {}

    private static final class Entry {
        final Key key;
        Job job;
        int generation, world;

        Entry(Key key, Job job, int generation, int world) {
            this.key = key;
            this.job = job;
            this.generation = generation;
            this.world = world;
        }
    }

    private final Map<Key, Entry> byKey = new HashMap<>();
    private final ArrayDeque<Entry> fresh = new ArrayDeque<>();
    /** Jobs that said "not finished", oldest first; they run before {@link #fresh}. */
    private ArrayDeque<Entry> carry = new ArrayDeque<>();
    private ArrayDeque<Entry> next = new ArrayDeque<>();
    private long runs, dropped, deferrals;
    private long dropOwnerOff, dropUnloaded, dropStale, dropCleared;
    private int peak;

    public void submit(Object owner, String kind, long chunk, Job job, int generation, int world) {
        Key k = new Key(owner, kind, chunk);
        Entry e = byKey.get(k);
        if (e != null) {
            e.job = job;
            e.generation = generation;
            e.world = world;
            return;
        }
        e = new Entry(k, job, generation, world);
        byKey.put(k, e);
        fresh.addLast(e);
        if (byKey.size() > peak) peak = byKey.size();
    }

    /** The most jobs the queue held at once since the start or the last {@link #resetPeak}. */
    public int peak() {
        return peak;
    }

    /** Starts a new window for {@link #peak}: from now on it counts from what is waiting this moment. */
    public void resetPeak() {
        peak = byKey.size();
    }

    /** Jobs waiting (new ones and ones to be continued). */
    public int length() {
        return byKey.size();
    }

    /** Job calls made since the start (a continued job counts once per call). */
    public long runs() {
        return runs;
    }

    /** Jobs the owner has waiting (new ones and ones to be continued). */
    public int pending(Object owner) {
        int n = 0;
        for (Key k : byKey.keySet()) if (k.owner == owner) n++;
        return n;
    }

    /** Ticks that ended with work still waiting because the budget was used up (the rig's "deferred" count). */
    public long deferrals() {
        return deferrals;
    }

    /** Jobs thrown away without running, since the start. */
    /** One line for the log at world leave: what ran and why jobs were dropped, so a re-queue bug shows as numbers. */
    public String summary() {
        return "work queue: runs " + runs + ", dropped " + dropped + " (owner off " + dropOwnerOff + ", unloaded " + dropUnloaded + ", stale " + dropStale + ", cleared " + dropCleared + "), deferrals " + deferrals + ", peak " + peak();
    }

    public long dropped() {
        return dropped;
    }

    /** Everything waiting is dropped (a world change). */
    public void clear() {
        dropped += byKey.size();
        dropCleared += byKey.size();
        byKey.clear();
        fresh.clear();
        carry.clear();
        next.clear();
    }

    /** The owner's waiting jobs are thrown away (counted as dropped); other owners' jobs keep their place. Returns how many were dropped. */
    public int cancel(Object owner) {
        int before = byKey.size();
        byKey.values().removeIf(e -> e.key.owner == owner);
        fresh.removeIf(e -> e.key.owner == owner);
        carry.removeIf(e -> e.key.owner == owner);
        next.removeIf(e -> e.key.owner == owner);
        int n = before - byKey.size();
        dropped += n;
        dropOwnerOff += n;
        return n;
    }

    /** Runs this tick's share of the queue; returns how many jobs were called. */
    public int run(Budget budget, Rules rules) {
        int ran = 0;
        while (ran == 0 || budget.hasTimeLeft()) {
            Entry e = carry.pollFirst();
            if (e == null) e = fresh.pollFirst();
            if (e == null) break;
            byKey.remove(e.key, e);
            if (!rules.ownerOn(e.key.owner) || !rules.loaded(e.key.chunk) || e.world != rules.world() || e.generation < rules.generation(e.key.chunk)) {
                dropped++;
                if (!rules.ownerOn(e.key.owner)) dropOwnerOff++;
                else if (!rules.loaded(e.key.chunk)) dropUnloaded++;
                else if (e.world != rules.world()) dropCleared++;
                else dropStale++;
                continue;
            }
            ran++;
            runs++;
            boolean done = true;
            budget.begin();
            try {
                done = e.job.run(budget);
            } catch (Throwable t) { // the module system's boundary: a faulty job must not stop the queue or reach the game
                rules.failed(e.key.owner, e.key.kind, t);
            } finally {
                budget.end();
            }
            // not finished: continue next tick, unless the same work was submitted again meanwhile (then the new submit stands)
            if (!done && byKey.putIfAbsent(e.key, e) == null) next.addLast(e);
        }
        if (ran > 0 && !budget.hasTimeLeft() && !(fresh.isEmpty() && carry.isEmpty() && next.isEmpty())) deferrals++;
        for (var it = carry.descendingIterator(); it.hasNext();) next.addFirst(it.next()); // carried jobs that did not get their turn stay in front of the new ones
        carry.clear();
        ArrayDeque<Entry> t = carry;
        carry = next;
        next = t;
        return ran;
    }
}
