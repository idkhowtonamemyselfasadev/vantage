package dev.vantage.core.chunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The block entity lists {@link Chunks#blockEntities} has built, one per chunk. A list is valid for one world and one data generation
 * of its chunk, and ends early when a single block update touches a block entity of that chunk: the data generation only moves when
 * the chunk's data arrives, a block update changes the world without it (BUGS.md B-181). Plain code, no game access; client thread only.
 */
final class BlockEntityCache {
    private record Cached(int world, int generation, List<BlockEntityInfo> list) {}

    private final Map<Long, Cached> chunks = new HashMap<>();

    /** The cached list when it was built for this world and generation, else {@code build}'s answer, which is kept (a null answer means "no chunk" and is not kept). */
    List<BlockEntityInfo> get(long chunk, int world, int generation, Supplier<List<BlockEntityInfo>> build) {
        Cached c = chunks.get(chunk);
        if (c != null && c.world == world && c.generation == generation) return c.list;
        List<BlockEntityInfo> built = build.get();
        if (built == null) return List.of();
        List<BlockEntityInfo> list = List.copyOf(built);
        chunks.put(chunk, new Cached(world, generation, list));
        return list;
    }

    /** Whether a list is kept for the chunk (a block update in a chunk nobody asked about costs one lookup, no more). */
    boolean has(long chunk) {
        return chunks.containsKey(chunk);
    }

    void unloaded(long chunk) {
        chunks.remove(chunk);
    }

    /**
     * One block of the chunk changed. The kept list is wrong when the new state has a block entity (one appeared, or its state
     * changed) or when the block was a listed block entity before (it went away, or changed state), so it is dropped and built again
     * by the next call.
     *
     * @param wasListed true for the record that stands for the changed block
     */
    void blockChanged(long chunk, boolean newStateHasBlockEntity, Predicate<BlockEntityInfo> wasListed) {
        Cached c = chunks.get(chunk);
        if (c == null) return;
        if (newStateHasBlockEntity || c.list.stream().anyMatch(wasListed)) chunks.remove(chunk);
    }
}
