package dev.vantage.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plan's numbers for the storage service (task 3.4): 100,000 and 500,000 chunks in a ChunkSetStore, 100,000 and 500,000 records
 * of about 150 bytes of JSON in a RecordStore. Too slow for the normal test run: {@code ./gradlew test -Pstorebench --offline}.
 * Every timing is of the real operation on the full data and is followed by an assertion on what it did, so a run that did nothing
 * fails. The numbers are printed on lines that start with {@code STOREBENCH}.
 */
@Tag("storebench")
class StoreBenchmarkTest {
    @TempDir
    Path dir;

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private static void line(String what, String value) {
        System.out.println("STOREBENCH " + what + ": " + value);
    }

    private static long usedHeap() {
        for (int i = 0; i < 3; i++) System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @Test
    void chunkSet100k() throws IOException {
        chunkSet(100_000);
    }

    @Test
    void chunkSet500k() throws IOException {
        chunkSet(500_000);
    }

    @Test
    void records100k() throws IOException {
        records(100_000);
    }

    @Test
    void records500k() throws IOException {
        records(500_000);
    }

    private void chunkSet(int n) throws IOException {
        String tag = "ChunkSetStore " + n;
        Path f = dir.resolve("chunks-" + n + ".vcs");
        int side = (int) Math.ceil(Math.sqrt(n));
        int origin = -side / 2; // the area spans negative and positive coordinates
        ChunkSetStore s = new ChunkSetStore("bench", () -> f);

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) s.add(origin + i % side, origin + i / side);
        long add = System.nanoTime() - t0;
        assertEquals(n, s.size());
        line(tag + " add all", String.format("%.1f ms (%d chunks in %d regions)", ms(add), s.size(), s.regionCount()));

        s.markDirty();
        t0 = System.nanoTime();
        s.flush();
        long save = System.nanoTime() - t0;
        assertTrue(Files.isRegularFile(f));
        assertFalse(s.dirty());
        line(tag + " file size", Files.size(f) + " bytes");
        line(tag + " save (snapshot + write + fsync + backup, to disk)", String.format("%.1f ms", ms(save)));

        ChunkSetStore again = new ChunkSetStore("bench", () -> f);
        t0 = System.nanoTime();
        boolean loaded = again.load();
        long load = System.nanoTime() - t0;
        assertTrue(loaded);
        assertEquals(n, again.size());
        line(tag + " load", String.format("%.1f ms", ms(load)));

        Random r = new Random(5);
        int[] xs = new int[1_000_000];
        int[] zs = new int[1_000_000];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = origin - 50 + r.nextInt(side + 100);
            zs[i] = origin - 50 + r.nextInt(side + 100);
        }
        int hits = 0;
        t0 = System.nanoTime();
        for (int i = 0; i < xs.length; i++) if (again.contains(xs[i], zs[i])) hits++;
        long contains = System.nanoTime() - t0;
        int expected = 0;
        for (int i = 0; i < xs.length; i++) {
            int cx = xs[i] - origin;
            int cz = zs[i] - origin;
            if (cx >= 0 && cx < side && cz >= 0 && cz < side && cz * side + cx < n) expected++;
        }
        assertEquals(expected, hits);
        assertTrue(hits > 0);
        line(tag + " 1,000,000 contains", String.format("%.1f ms (%d were in the set)", ms(contains), hits));
    }

    private record Find(String id, int x, int y, int z, String type, int score, long time, String note) {}

    private static JsonObject toJson(Find r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id());
        o.addProperty("x", r.x());
        o.addProperty("y", r.y());
        o.addProperty("z", r.z());
        o.addProperty("type", r.type());
        o.addProperty("score", r.score());
        o.addProperty("time", r.time());
        o.addProperty("note", r.note());
        return o;
    }

    private static Find fromJson(JsonObject o) {
        return new Find(o.get("id").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("type").getAsString(),
                o.get("score").getAsInt(), o.get("time").getAsLong(), o.get("note").getAsString());
    }

    private static Find find(int i) {
        Random r = new Random(i);
        String[] types = {"stash", "farm", "base", "spawner", "portal"};
        return new Find("f" + i, r.nextInt(60000) - 30000, r.nextInt(100) - 40, r.nextInt(60000) - 30000, types[i % types.length], r.nextInt(100),
                1_700_000_000_000L + i * 1000L, "note for entry number " + i + " with some words");
    }

    private void records(int n) throws IOException {
        String tag = "RecordStore " + n;
        Path f = dir.resolve("records-" + n + ".json");
        long avg = toJson(find(n / 2)).toString().length();
        assertTrue(avg > 120 && avg < 190, "a record is about 150 bytes of JSON, this one is " + avg);
        RecordStore<Find> s = new RecordStore<>("bench", () -> f, 64L << 20, StoreBenchmarkTest::toJson, StoreBenchmarkTest::fromJson, Find::id);

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) s.add(find(i));
        long add = System.nanoTime() - t0;
        assertEquals(n, s.size());
        line(tag + " add all (memory + journal lines queued; record built each time)", String.format("%.1f ms (one record is %d bytes of JSON)", ms(add), avg));

        t0 = System.nanoTime();
        s.flush(); // appends the journal and folds it into the base file
        long firstFlush = System.nanoTime() - t0;
        Path journal = f.resolveSibling(f.getFileName() + ".journal");
        assertTrue(Files.isRegularFile(f));
        assertFalse(Files.exists(journal), "the journal was folded");
        line(tag + " flush after adding all (append journal + first fold)", String.format("%.1f ms", ms(firstFlush)));
        line(tag + " base file size after a fold", Files.size(f) + " bytes");

        s = null;
        long heapBefore = usedHeap();
        RecordStore<Find> again = new RecordStore<>("bench", () -> f, 64L << 20, StoreBenchmarkTest::toJson, StoreBenchmarkTest::fromJson, Find::id);
        t0 = System.nanoTime();
        boolean loaded = again.load();
        long load = System.nanoTime() - t0;
        assertTrue(loaded);
        assertEquals(n, again.size());
        assertEquals(find(n - 1), again.get("f" + (n - 1)));
        line(tag + " load (base file read and parsed)", String.format("%.1f ms", ms(load)));
        long heapAfter = usedHeap();
        assertEquals(n, again.size()); // keeps the store alive over the measurement
        line(tag + " heap used by the loaded store (approximate: after System.gc(), whole JVM)", String.format("%.0f MB", (heapAfter - heapBefore) / 1048576.0));

        Find extra = find(n + 1);
        t0 = System.nanoTime();
        again.add(extra);
        long addCall = System.nanoTime() - t0;
        t0 = System.nanoTime();
        Stores.tick(); // hands the one line to the writer: append + fsync of the journal
        Stores.awaitWriter();
        long toDisk = System.nanoTime() - t0;
        assertEquals(1, Files.readAllLines(journal).size());
        assertEquals(n + 1, again.size());
        line(tag + " ONE add after load: add call", String.format("%.3f ms", ms(addCall)));
        line(tag + " ONE add after load: until the line is on disk (journal append + fsync)", String.format("%.1f ms", ms(toDisk)));

        t0 = System.nanoTime();
        again.flush(); // a fold of all n + 1 records
        long fold = System.nanoTime() - t0;
        assertFalse(Files.exists(journal));
        RecordStore<Find> check = new RecordStore<>("bench", () -> f, 64L << 20, StoreBenchmarkTest::toJson, StoreBenchmarkTest::fromJson, Find::id);
        check.load();
        assertEquals(n + 1, check.size());
        line(tag + " fold of the journal into the base file (" + (n + 1) + " records; snapshot + serialise + fsync + backup + rename)", String.format("%.1f ms", ms(fold)));
    }
}
