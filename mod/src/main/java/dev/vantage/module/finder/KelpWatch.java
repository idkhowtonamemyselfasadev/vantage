package dev.vantage.module.finder;

import java.util.HashMap;
import java.util.Map;

/**
 * Kelp grows while somebody keeps its chunk ticking, and that somebody is often the user: a minute beside fresh ocean pushes more plants
 * past worldgen height. Growth that happened in a chunk which already held kelp, between two looks while it stayed loaded and within the
 * user's own simulation distance, is the user's own and is taken out of the count. What was grown at first sight, what grew while the
 * chunk was not loaded, what grows out of the user's reach and kelp in a chunk that had none (somebody planted it) all still count.
 * With a {@link Memory} the user's own share outlives the session (a restart, a relog at the same place); the rest is pure logic on
 * chunk keys and counts.
 */
public final class KelpWatch {
    private static final class Rec { int grown, own; boolean kelp, held; }
    private final Map<Long, Rec> recs = new HashMap<>();

    /** Where the user's own grown plants per chunk are kept between sessions. */
    public interface Memory {
        int own(long chunk);
        void keep(long chunk, int own);
        Memory NONE = new Memory() {
            @Override public int own(long chunk) { return 0; }
            @Override public void keep(long chunk, int own) {}
        };
    }

    private final Memory memory;

    public KelpWatch() { this(Memory.NONE); }

    public KelpWatch(Memory memory) { this.memory = memory; }

    /**
     * One look at a loaded chunk. grown: plants past worldgen height now; kelp: the chunk holds any kelp at all; near: it is within the
     * user's simulation distance. Returns the grown plants that may count as a sign of somebody else.
     */
    public int count(long chunk, int grown, boolean kelp, boolean near) {
        Rec r = recs.get(chunk);
        if (r == null) {
            recs.put(chunk, r = new Rec());
            r.own = memory.own(chunk); // grown under the user's nose in an earlier session
        } else if (r.held && r.kelp && near && grown > r.grown) r.own += grown - r.grown;
        r.own = Math.min(r.own, grown);
        if (r.own != memory.own(chunk)) memory.keep(chunk, r.own);
        r.grown = grown;
        r.kelp = kelp;
        r.held = true;
        return grown - r.own;
    }

    /** The chunk left the client: nothing that grows until it is back is the user's doing. */
    public void unloaded(long chunk) {
        Rec r = recs.get(chunk);
        if (r != null) r.held = false;
    }

    /** Nothing is held any more (the finder was off or left the world): what grows until a chunk is seen again is not the user's doing. */
    public void unloadAll() {
        for (Rec r : recs.values()) r.held = false;
    }

    /** Another world: the session's records go, the memory stays with its owner. */
    public void clear() { recs.clear(); }
}
