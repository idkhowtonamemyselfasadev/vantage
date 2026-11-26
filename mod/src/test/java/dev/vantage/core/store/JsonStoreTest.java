package dev.vantage.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vantage.core.Notices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonStoreTest {
    @TempDir
    Path dir;

    private final long[] now = {0};
    private int value;
    private int seenVersion;
    private int loadedValue;

    @BeforeEach
    void clearNotices() {
        Notices.clear("Saved data");
    }

    private JsonStore store(Path file, int version) {
        return new JsonStore("test", () -> file, version, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("value", value);
            return o;
        }, (root, v) -> {
            seenVersion = v;
            loadedValue = root.getAsJsonObject().get("value").getAsInt();
        }, () -> now[0]);
    }

    private void save(JsonStore s, int v) {
        value = v;
        s.markDirty();
        s.flush();
    }

    @Test
    void aSavedStoreLoadsBack() {
        Path f = dir.resolve("a.json");
        save(store(f, 1), 42);
        JsonStore again = store(f, 1);
        assertTrue(again.load());
        assertEquals(42, loadedValue);
        assertTrue(Notices.texts("Saved data").isEmpty());
    }

    @Test
    void aMissingFileIsNotAProblemAndLoadsNothing() {
        assertFalse(store(dir.resolve("none.json"), 1).load());
        assertTrue(Notices.texts("Saved data").isEmpty());
    }

    @Test
    void aDamagedFileFallsBackToTheBackupAndSaysSo() throws IOException {
        Path f = dir.resolve("a.json");
        JsonStore s = store(f, 1);
        save(s, 1);
        save(s, 2);
        Files.writeString(f, "{\"value\": 2, \"cut");
        assertTrue(store(f, 1).load());
        assertEquals(1, loadedValue, "the backup is the save before the last one");
        assertEquals(1, Notices.texts("Saved data").size());
        assertTrue(Notices.texts("Saved data").get(0).contains("last good copy"));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("a.json.bad-")), "the damaged file is kept");
        }
    }

    @Test
    void aDamagedFileWithoutBackupStartsEmptyAndIsNotOverwrittenByAnUnchangedStore() throws IOException {
        Path f = dir.resolve("a.json");
        Files.writeString(f, "not json at all");
        JsonStore s = store(f, 1);
        assertFalse(s.load());
        assertEquals(1, Notices.texts("Saved data").size());
        assertTrue(Notices.texts("Saved data").get(0).contains("no usable backup"));
        Stores.flush();
        now[0] += 5000;
        Stores.tick();
        Stores.awaitWriter();
        assertEquals("not json at all", Files.readString(f), "nothing changed, so nothing is written");
        save(s, 7);
        assertTrue(Files.readString(f).contains("\"value\":7"));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("a.json.bad-")), "the damaged text was copied aside");
        }
    }

    @Test
    void anEmptyFileCountsAsDamaged() throws IOException {
        Path f = dir.resolve("a.json");
        Files.writeString(f, "");
        assertFalse(store(f, 1).load());
        assertEquals(1, Notices.texts("Saved data").size());
    }

    @Test
    void readGetsTheVersionTheFileHad() throws IOException {
        Path f = dir.resolve("a.json");
        Files.writeString(f, "{\"v\":3,\"value\":5}");
        assertTrue(store(f, 4).load());
        assertEquals(3, seenVersion);
        assertEquals(5, loadedValue);
        Files.writeString(f, "{\"value\":6}");
        store(f, 4).load();
        assertEquals(1, seenVersion, "a file without a version is version 1");
        save(store(f, 4), 9);
        assertEquals(4, JsonParser.parseString(Files.readString(f)).getAsJsonObject().get("v").getAsInt());
    }

    @Test
    void anUnversionedArrayIsWrittenAsItIs() throws IOException {
        Path f = dir.resolve("arr.json");
        JsonArray a = new JsonArray();
        a.add(5);
        a.add(6);
        JsonStore s = new JsonStore("arr", () -> f, 2, () -> a, (root, v) -> seenVersion = v + root.getAsJsonArray().size() * 100, () -> now[0]).unversioned();
        s.markDirty();
        s.flush();
        assertEquals("[5,6]", Files.readString(f));
        assertTrue(s.load());
        assertEquals(201, seenVersion);
    }

    @Test
    void manyChangesInASecondMakeOneWrite() {
        Path f = dir.resolve("a.json");
        JsonStore s = store(f, 1);
        for (int i = 0; i < 1000; i++) {
            value = i;
            s.markDirty();
        }
        Stores.tick();
        Stores.awaitWriter();
        assertEquals(1, s.saves());
        for (int i = 0; i < 1000; i++) {
            value = 1000 + i;
            s.markDirty();
            now[0] += 0; // the clock does not move
            Stores.tick();
        }
        Stores.awaitWriter();
        assertEquals(1, s.saves(), "less than a second later: still waiting");
        now[0] += 1000;
        Stores.tick();
        Stores.awaitWriter();
        assertEquals(2, s.saves());
        assertTrue(store(f, 1).load());
        assertEquals(1999, loadedValue, "the write has the latest state");
    }

    @Test
    void twoQuickSavesLeaveTheLaterContent() {
        Path f = dir.resolve("a.json");
        JsonStore s = store(f, 1);
        for (int round = 0; round < 50; round++) {
            value = round;
            s.markDirty();
            now[0] += 1000;
            Stores.tick();
        }
        Stores.flush();
        assertTrue(store(f, 1).load());
        assertEquals(49, loadedValue);
    }

    @Test
    void flushReturnsOnlyWhenTheFileIsOnDisk() {
        Path f = dir.resolve("deep/er/a.json");
        JsonStore s = store(f, 1);
        value = 11;
        s.markDirty();
        assertFalse(Files.exists(f), "nothing is written until the tick or the flush");
        assertTrue(Stores.flush());
        assertTrue(Files.exists(f));
        assertFalse(s.dirty());
        assertEquals(1, s.saves());
    }

    @Test
    void aFailedWriteIsTriedAgainAtTheNextRequest() throws IOException {
        Path f = dir.resolve("a.json");
        Files.createDirectory(dir.resolve("a.json.tmp")); // the temp file cannot be made
        JsonStore s = store(f, 1);
        value = 3;
        s.markDirty();
        s.flush();
        assertEquals(0, s.saves());
        assertTrue(s.dirty(), "still wants to be saved");
        assertFalse(Files.exists(f));
        Files.delete(dir.resolve("a.json.tmp"));
        now[0] += 1000;
        Stores.tick();
        Stores.awaitWriter();
        assertEquals(1, s.saves());
        assertTrue(Files.exists(f));
    }

    @Test
    void theWritersNameAndKindAreAsPromised() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        Thread[] where = new Thread[1];
        Stores.submit(() -> {
            where[0] = Thread.currentThread();
            seen.incrementAndGet();
        }).get();
        assertEquals("Vantage storage", where[0].getName());
        assertTrue(where[0].isDaemon());
        assertEquals(Thread.MIN_PRIORITY, where[0].getPriority());
    }

    @Test
    void aStoreKeepsSavingToTheFileItLoadedFrom() {
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");
        Path[] current = {a};
        JsonStore s = new JsonStore("t", () -> current[0], 1, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("value", value);
            return o;
        }, (r, v) -> loadedValue = r.getAsJsonObject().get("value").getAsInt(), () -> now[0]);
        s.load();
        value = 5;
        s.markDirty();
        current[0] = b; // the server changed before the save was due
        Stores.flush();
        assertTrue(Files.exists(a));
        assertFalse(Files.exists(b));
    }

    @Test
    void aLoadAfterAnUnsavedChangeDoesNotLoseTheChange() {
        Path f = dir.resolve("a.json");
        JsonStore s = store(f, 1);
        save(s, 1);
        s.load();
        value = 8; // changed, not saved yet
        s.markDirty();
        assertTrue(s.load(), "a reload (a dimension change) writes the change first, then reads");
        assertEquals(8, loadedValue);
    }
}
