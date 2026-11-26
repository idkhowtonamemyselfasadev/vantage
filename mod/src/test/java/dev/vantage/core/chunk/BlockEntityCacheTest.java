package dev.vantage.core.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The block entity lists of the chunk service (BUGS.md B-181): a stand-in "chunk" is a plain list the test edits like a block update would. */
class BlockEntityCacheTest {
    final BlockEntityCache cache = new BlockEntityCache();
    final long chunk = ChunkIndex.key(4, -2);
    final BlockEntityInfo chest = new BlockEntityInfo(null, null, null), barrel = new BlockEntityInfo(null, null, null);
    final List<BlockEntityInfo> live = new ArrayList<>();
    int builds;

    List<BlockEntityInfo> ask(int world, int generation) {
        return cache.get(chunk, world, generation, () -> {
            builds++;
            return new ArrayList<>(live);
        });
    }

    @Test
    void aListIsBuiltOncePerWorldAndGeneration() {
        live.add(chest);
        assertSame(ask(1, 1), ask(1, 1));
        assertEquals(1, builds);
        ask(1, 2);
        ask(2, 2);
        assertEquals(3, builds);
    }

    @Test
    void aBlockEntityThatAppearsWithASingleUpdateIsSeenWithoutANewGeneration() {
        assertEquals(List.of(), ask(1, 1)); // the list is kept (empty)
        live.add(chest); // the game placed a chest; the data generation did not move
        cache.blockChanged(chunk, true, i -> false);
        assertEquals(List.of(chest), ask(1, 1));
    }

    @Test
    void aBlockEntityThatGoesAwayWithASingleUpdateIsGone() {
        live.add(chest);
        live.add(barrel);
        ask(1, 1);
        live.remove(chest); // broken: the new state (air) has no block entity, but the chest was listed
        cache.blockChanged(chunk, false, i -> i == chest);
        assertEquals(List.of(barrel), ask(1, 1));
    }

    @Test
    void anUpdateThatTouchesNoBlockEntityKeepsTheListAndAnUnaskedChunkCostsNothing() {
        live.add(chest);
        ask(1, 1);
        cache.blockChanged(chunk, false, i -> false); // stone placed somewhere
        ask(1, 1);
        assertEquals(1, builds);
        long other = ChunkIndex.key(9, 9);
        assertFalse(cache.has(other));
        cache.blockChanged(other, true, i -> true); // no list kept: nothing to do
        assertFalse(cache.has(other));
    }

    @Test
    void anUnloadedChunkLosesItsListAndAMissingChunkIsNotKept() {
        ask(1, 1);
        assertTrue(cache.has(chunk));
        cache.unloaded(chunk);
        assertFalse(cache.has(chunk));
        assertEquals(List.of(), cache.get(chunk, 1, 1, () -> null));
        assertFalse(cache.has(chunk));
    }
}
