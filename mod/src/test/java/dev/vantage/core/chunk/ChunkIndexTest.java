package dev.vantage.core.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkIndexTest {
    final ChunkIndex idx = new ChunkIndex();

    @Test
    void aNewChunkIsKnownAfterItsLoadAndIsAtGenerationOneAfterItsData() {
        idx.load(3, -4, 100, 5_000);
        assertTrue(idx.isLoaded(3, -4));
        assertEquals(0, idx.generation(ChunkIndex.key(3, -4)));
        assertEquals(1, idx.data(3, -4, 100, 5_001));
        assertEquals(1, idx.generation(ChunkIndex.key(3, -4)));
        assertEquals(1, idx.count());
    }

    @Test
    void aResentChunkAndABurstEachRaiseTheGenerationAndKeepTheArrivalTimes() {
        idx.load(0, 0, 100, 5_000);
        idx.data(0, 0, 100, 5_000);
        idx.load(0, 0, 160, 8_000); // the server sends the chunk again: load, then data
        assertEquals(2 - 1, idx.generation(ChunkIndex.key(0, 0))); // a load alone changes nothing
        assertEquals(2, idx.data(0, 0, 160, 8_000));
        assertEquals(3, idx.data(0, 0, 170, 8_500)); // a burst of block changes
        assertEquals(100, idx.loadedAt(0, 0));
        assertEquals(5_000, idx.loadedWallMs(0, 0));
    }

    @Test
    void dataForAChunkThatWasNeverAnnouncedAddsIt() {
        assertEquals(1, idx.data(7, 7, 9, 9));
        assertTrue(idx.isLoaded(7, 7));
        assertEquals(9, idx.loadedAt(7, 7));
    }

    @Test
    void unloadRemovesTheChunkAndALaterLoadStartsAtGenerationZeroAgain() {
        idx.load(1, 1, 10, 10);
        idx.data(1, 1, 10, 10);
        idx.unload(1, 1);
        assertFalse(idx.isLoaded(1, 1));
        assertEquals(ChunkIndex.NONE, idx.generation(ChunkIndex.key(1, 1)));
        assertEquals(-1, idx.loadedAt(1, 1));
        idx.load(1, 1, 50, 50);
        assertEquals(0, idx.generation(ChunkIndex.key(1, 1)));
        assertEquals(50, idx.loadedAt(1, 1));
    }

    @Test
    void forEachVisitsOnlyChunksWithDataAndForEachLoadedVisitsEveryChunk() {
        idx.load(0, 0, 1, 1);
        idx.data(0, 0, 1, 1);
        idx.load(-1, 2, 2, 2); // announced, no data yet: generation 0
        List<String> data = new ArrayList<>(), loaded = new ArrayList<>();
        idx.forEach((cx, cz, gt, wall, gen) -> data.add(cx + "," + cz + ":" + gt + ":" + gen));
        idx.forEachLoaded((cx, cz, gt, wall, gen) -> loaded.add(cx + "," + cz + ":" + gt + ":" + gen));
        assertEquals(List.of("0,0:1:1"), data);
        assertEquals(List.of("-1,2:2:0", "0,0:1:1"), loaded.stream().sorted().toList());
        idx.data(-1, 2, 3, 3); // its data came: now it is visited by both
        data.clear();
        idx.forEach((cx, cz, gt, wall, gen) -> data.add(cx + "," + cz));
        assertEquals(List.of("-1,2", "0,0"), data.stream().sorted().toList());
    }

    @Test
    void clearEmptiesTheIndex() {
        idx.load(0, 0, 1, 1);
        idx.data(0, 0, 1, 1);
        idx.clear();
        assertEquals(0, idx.count());
        assertFalse(idx.isLoaded(0, 0));
        idx.forEachLoaded((cx, cz, gt, wall, gen) -> org.junit.jupiter.api.Assertions.fail("nothing is loaded"));
    }

    @Test
    void theKeyRoundTripsNegativeCoordinates() {
        long k = ChunkIndex.key(-123456, 98765);
        assertEquals(-123456, ChunkIndex.keyX(k));
        assertEquals(98765, ChunkIndex.keyZ(k));
        assertEquals((-5 & 4294967295L) | ((9 & 4294967295L) << 32), ChunkIndex.key(-5, 9)); // vanilla ChunkPos.asLong, written out: its class cannot start in a unit test
    }
}
