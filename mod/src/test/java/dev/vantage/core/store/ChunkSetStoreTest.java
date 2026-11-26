package dev.vantage.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import dev.vantage.core.Notices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkSetStoreTest {
    @TempDir
    Path dir;

    private final long[] now = {0};

    @BeforeEach
    void clearNotices() {
        Notices.clear("Saved data");
    }

    private ChunkSetStore store(Path f) {
        return new ChunkSetStore("sky", () -> f, () -> now[0]);
    }

    private static Set<Long> contentsOf(ChunkSetStore s) {
        Set<Long> out = new HashSet<>();
        s.forEach((x, z) -> out.add(ChunkSetStore.pack(x, z)));
        return out;
    }

    @Test
    void keysIsASnapshotOfEveryChunkAsPackedKeys() {
        ChunkSetStore s = store(dir.resolve("keys.vcs"));
        assertEquals(0, s.keys().length);
        s.add(3, -4);
        s.add(-1, 0);
        s.add(100000, 100000);
        s.add(-40, 1000); // another region
        long[] keys = s.keys();
        assertEquals(4, keys.length);
        assertEquals(contentsOf(s), java.util.Arrays.stream(keys).boxed().collect(java.util.stream.Collectors.toSet()));
        assertTrue(s.contains(keys[0])); // a key is what contains(long) takes
        s.remove(3, -4);
        s.add(7, 7);
        assertEquals(4, keys.length); // a copy: the store changing later does not change it
        assertTrue(java.util.Arrays.stream(keys).anyMatch(k -> k == ChunkSetStore.pack(3, -4)));
        assertEquals(4, s.keys().length);
    }

    @Test
    void aRoundTripKeepsEveryChunk() {
        Path f = dir.resolve("sky.vcs");
        ChunkSetStore s = store(f);
        Random r = new Random(7);
        Set<Long> want = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            int x = r.nextInt(400) - 200;
            int z = r.nextInt(400) - 200;
            s.add(x, z);
            want.add(ChunkSetStore.pack(x, z));
        }
        s.flush();
        ChunkSetStore again = store(f);
        assertTrue(again.load());
        assertEquals(want.size(), again.size());
        assertEquals(want, contentsOf(again));
    }

    @Test
    void negativeCoordinatesAndRegionBordersWork() {
        ChunkSetStore s = store(dir.resolve("a.vcs"));
        int[] edges = {-33, -32, -31, -1, 0, 1, 30, 31, 32, 33, 63, 64, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int x : edges) for (int z : edges) assertTrue(s.add(x, z), x + "," + z);
        assertEquals(edges.length * edges.length, s.size());
        for (int x : edges) for (int z : edges) assertTrue(s.contains(x, z), x + "," + z);
        assertFalse(s.contains(2, 2));
        assertFalse(s.contains(-2, -2));
        assertFalse(s.add(-1, -1), "adding again changes nothing");
        assertTrue(s.contains(ChunkSetStore.pack(-33, 31)));
        assertTrue(s.remove(-1, -32));
        assertFalse(s.contains(-1, -32));
        assertTrue(s.contains(-1, -33));
        s.flush();
        ChunkSetStore again = store(dir.resolve("a.vcs"));
        again.load();
        assertEquals(s.size(), again.size());
        assertTrue(again.contains(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void behavesLikeAPlainSetOver50000RandomOperations() {
        ChunkSetStore s = store(dir.resolve("a.vcs"));
        Set<Long> plain = new HashSet<>();
        Random r = new Random(20261120);
        for (int i = 0; i < 50_000; i++) {
            int x = r.nextInt(161) - 80;
            int z = r.nextInt(161) - 80;
            long key = ChunkSetStore.pack(x, z);
            switch (r.nextInt(3)) {
                case 0 -> assertEquals(plain.add(key), s.add(x, z));
                case 1 -> assertEquals(plain.remove(key), s.remove(x, z));
                default -> assertEquals(plain.contains(key), s.contains(x, z));
            }
            if (i % 5000 == 0) assertEquals(plain.size(), s.size());
        }
        assertEquals(plain.size(), s.size());
        assertEquals(plain, contentsOf(s));
        s.flush();
        ChunkSetStore again = store(dir.resolve("a.vcs"));
        assertTrue(again.load());
        assertEquals(plain, contentsOf(again));
    }

    @Test
    void emptyRegionsAreDropped() {
        ChunkSetStore s = store(dir.resolve("a.vcs"));
        s.add(100, 100);
        assertEquals(1, s.regionCount());
        s.remove(100, 100);
        assertEquals(0, s.regionCount());
        assertEquals(0, s.size());
        s.add(1, 1);
        s.clear();
        assertEquals(0, s.size());
        assertFalse(s.contains(1, 1));
    }

    @Test
    void aDamagedFileFallsBackToTheBackupAndReports() throws IOException {
        Path f = dir.resolve("a.vcs");
        ChunkSetStore s = store(f);
        s.add(1, 1);
        s.flush();
        s.add(2, 2);
        s.flush();
        String text = Files.readString(f);
        Files.writeString(f, text.substring(0, text.length() - 30)); // cut off
        ChunkSetStore again = store(f);
        assertTrue(again.load());
        assertEquals(1, again.size(), "the backup holds the first save");
        assertTrue(again.contains(1, 1));
        assertEquals(1, Notices.texts("Saved data").size());
    }

    @Test
    void aDamagedFileWithoutBackupStartsEmptyAndIsNotOverwrittenWhileUnchanged() throws IOException {
        Path f = dir.resolve("a.vcs");
        Files.writeString(f, "VCHUNKS 1 5 0\nAAAA\n");
        ChunkSetStore s = store(f);
        assertFalse(s.load());
        assertEquals(0, s.size());
        assertEquals(1, Notices.texts("Saved data").size());
        Stores.flush();
        assertEquals("VCHUNKS 1 5 0\nAAAA\n", Files.readString(f));
        s.add(3, 3);
        s.flush();
        assertTrue(Files.readString(f).startsWith("VCHUNKS 1 1 "));
    }

    @Test
    void manyChangesMakeFewWrites() {
        ChunkSetStore s = store(dir.resolve("a.vcs"));
        for (int i = 0; i < 5000; i++) s.add(i, -i);
        Stores.tick();
        Stores.awaitWriter();
        assertEquals(1, s.saves());
        for (int i = 5000; i < 10000; i++) {
            s.add(i, -i);
            Stores.tick();
        }
        Stores.awaitWriter();
        assertEquals(1, s.saves());
        now[0] += 1000;
        Stores.tick();
        Stores.awaitWriter();
        assertEquals(2, s.saves());
    }

    @Test
    void twoQuickSavesLeaveTheLaterContentAndFlushWaitsForTheDisk() {
        Path f = dir.resolve("a.vcs");
        ChunkSetStore s = store(f);
        s.add(1, 1);
        Stores.tick();
        s.add(2, 2);
        now[0] += 1000;
        Stores.tick();
        assertTrue(Stores.flush());
        ChunkSetStore again = store(f);
        again.load();
        assertEquals(2, again.size());
    }

    @Test
    void theDumpIsReadable() {
        ChunkSetStore s = store(dir.resolve("a.vcs"));
        s.add(-40, 5);
        s.add(-41, 5);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        s.dump(new PrintStream(bytes));
        String text = bytes.toString();
        assertTrue(text.contains("2 chunks in 1 regions"), text);
        assertTrue(text.contains("x -41..-40"), text);
    }
}
