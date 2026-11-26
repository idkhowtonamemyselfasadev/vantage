package dev.vantage.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import com.google.gson.JsonObject;
import dev.vantage.core.Notices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordStoreTest {
    record Rec(String id, int score, String note) {}

    @TempDir
    Path dir;

    private final long[] now = {0};

    @BeforeEach
    void clearNotices() {
        Notices.clear("Saved data");
    }

    private static JsonObject toJson(Rec r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id());
        o.addProperty("score", r.score());
        o.addProperty("note", r.note());
        return o;
    }

    private static Rec fromJson(JsonObject o) {
        return new Rec(o.get("id").getAsString(), o.get("score").getAsInt(), o.get("note").getAsString());
    }

    private RecordStore<Rec> store(long foldBytes) {
        return new RecordStore<>("finds", () -> dir.resolve("finds.json"), foldBytes, RecordStoreTest::toJson, RecordStoreTest::fromJson, Rec::id, () -> now[0]);
    }

    private Path base() {
        return dir.resolve("finds.json");
    }

    private Path journal() {
        return dir.resolve("finds.json.journal");
    }

    /** Hands the changes over and waits, without folding. */
    private void appendOnly(RecordStore<Rec> s) {
        now[0] += 1000;
        Stores.tick();
        Stores.awaitWriter();
    }

    private static List<String> readLines(Path p) {
        try {
            return Files.readAllLines(p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private RecordStore<Rec> reload() {
        RecordStore<Rec> s = store(1 << 30);
        s.load();
        return s;
    }

    @Test
    void aRoundTripThroughTheBaseFile() {
        RecordStore<Rec> s = store(1 << 30);
        for (int i = 0; i < 100; i++) s.add(new Rec("r" + i, i, "note " + i));
        s.flush();
        assertTrue(Files.exists(base()));
        RecordStore<Rec> again = store(1 << 30);
        assertTrue(again.load());
        assertEquals(100, again.size());
        assertEquals(new Rec("r42", 42, "note 42"), again.get("r42"));
        assertEquals("r0", again.all().get(0).id(), "the order is kept");
    }

    @Test
    void theJournalIsReplayedWithUpdatesAndRemoves() {
        RecordStore<Rec> s = store(1 << 30);
        s.add(new Rec("a", 1, "x"));
        s.add(new Rec("b", 2, "y"));
        s.add(new Rec("c", 3, "z"));
        s.update(new Rec("a", 10, "x2"));
        s.remove("b");
        assertNull(s.remove("nope"));
        appendOnly(s);
        assertFalse(Files.exists(base()), "nothing was folded");
        assertEquals(5, readLines(journal()).size());
        RecordStore<Rec> again = reload();
        assertEquals(2, again.size());
        assertEquals(new Rec("a", 10, "x2"), again.get("a"));
        assertNull(again.get("b"));
        assertNotNull(again.get("c"));
        assertTrue(Notices.texts("Saved data").isEmpty());
    }

    @Test
    void updateAndRemoveOfRecordsInTheBaseFile() {
        RecordStore<Rec> s = store(1 << 30);
        s.add(new Rec("a", 1, "x"));
        s.add(new Rec("b", 2, "y"));
        s.flush(); // folded
        s.update(new Rec("a", 5, "new"));
        s.remove("b");
        appendOnly(s);
        RecordStore<Rec> again = reload();
        assertEquals(1, again.size());
        assertEquals(5, again.get("a").score());
    }

    @Test
    void aCutOffLastLineIsDroppedAndTheNextAppendIsClean() throws IOException {
        RecordStore<Rec> s = store(1 << 30);
        s.add(new Rec("a", 1, "x"));
        s.add(new Rec("b", 2, "y"));
        appendOnly(s);
        Files.write(journal(), "{\"op\":\"a\",\"r\":{\"id\":\"c\",\"sco".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        RecordStore<Rec> again = store(1 << 30);
        again.load();
        assertEquals(2, again.size());
        assertTrue(Notices.texts("Saved data").isEmpty(), "a cut-off end is a crash, not damage the user can do anything about");
        again.add(new Rec("d", 4, "w"));
        appendOnly(again);
        RecordStore<Rec> third = reload();
        assertEquals(3, third.size());
        assertNotNull(third.get("d"));
        assertTrue(Notices.texts("Saved data").isEmpty());
    }

    @Test
    void aLastLineWithoutItsNewlineIsKept() throws IOException {
        RecordStore<Rec> s = store(1 << 30);
        s.add(new Rec("a", 1, "x"));
        appendOnly(s);
        Files.write(journal(), "{\"op\":\"a\",\"r\":{\"id\":\"c\",\"score\":3,\"note\":\"\"}}".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        RecordStore<Rec> again = store(1 << 30);
        again.load();
        assertEquals(2, again.size());
        again.add(new Rec("d", 4, "w"));
        appendOnly(again);
        assertEquals(3, reload().size());
    }

    @Test
    void anUnreadableMiddleLineIsReportedOnceAndSkipped() throws IOException {
        RecordStore<Rec> s = store(1 << 30);
        s.add(new Rec("a", 1, "x"));
        s.add(new Rec("b", 2, "y"));
        s.add(new Rec("c", 3, "z"));
        appendOnly(s);
        List<String> lines = Files.readAllLines(journal());
        lines.set(1, "}}} garbage {{{");
        Files.write(journal(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        RecordStore<Rec> again = store(1 << 30);
        again.load();
        assertEquals(2, again.size());
        assertNotNull(again.get("a"));
        assertNull(again.get("b"));
        assertNotNull(again.get("c"));
        List<String> notices = Notices.texts("Saved data");
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("line 2"), notices.get(0));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".unread-")), "a copy of the journal is kept");
        }
    }

    @Test
    void theJournalIsFoldedWhenItPassesItsSizeAndAtFlush() {
        RecordStore<Rec> s = store(200);
        for (int i = 0; i < 10; i++) s.add(new Rec("r" + i, i, "a note that makes the line longer"));
        appendOnly(s);
        assertTrue(Files.exists(base()), "the size limit was passed");
        assertFalse(Files.exists(journal()), "the folded journal is out of the way");
        assertTrue(Files.exists(dir.resolve("finds.json.journal.old")));
        assertEquals(10, reload().size());
        RecordStore<Rec> big = store(1 << 30);
        big.load();
        big.add(new Rec("late", 1, "x"));
        appendOnly(big);
        assertTrue(Files.exists(journal()));
        big.flush();
        assertFalse(Files.exists(journal()), "flush folds what is in the journal");
        assertEquals(11, reload().size());
    }

    @Test
    void aFlushWithNothingChangedWritesNothing() {
        RecordStore<Rec> s = store(1 << 30);
        s.add(new Rec("a", 1, "x"));
        s.flush();
        int saves = s.saves();
        s.flush();
        assertEquals(saves, s.saves());
    }

    @Test
    void aFoldThatDiesBeforeTheRenameLeavesTheOldBaseAndTheJournal() throws IOException {
        RecordStore<Rec> s = store(1 << 30);
        s.add(new Rec("a", 1, "x"));
        s.flush();
        String oldBase = Files.readString(base());
        s.add(new Rec("b", 2, "y"));
        appendOnly(s);
        String oldJournal = Files.readString(journal());
        Files.createDirectory(dir.resolve("finds.json.tmp")); // the new base cannot be written
        s.flush();
        assertEquals(oldBase, Files.readString(base()));
        assertEquals(oldJournal, Files.readString(journal()));
        RecordStore<Rec> again = reload();
        assertEquals(2, again.size());
        Files.delete(dir.resolve("finds.json.tmp"));
        now[0] += 1000;
        Stores.tick();
        Stores.awaitWriter();
        assertFalse(Files.exists(journal()), "the retry folded it");
        assertEquals(2, reload().size());
    }

    @Test
    void aDamagedBaseIsRebuiltFromTheBackupAndTheFoldedJournal() throws IOException {
        RecordStore<Rec> s = store(1 << 30);
        s.add(new Rec("a", 1, "x"));
        s.add(new Rec("b", 2, "y"));
        s.flush();
        s.add(new Rec("c", 3, "z"));
        s.update(new Rec("a", 9, "x9"));
        s.flush();
        Files.writeString(base(), "{\"v\":1,\"records\":[{\"id\":\"a\",\"sc");
        RecordStore<Rec> again = reload();
        assertEquals(3, again.size());
        assertEquals(9, again.get("a").score());
        List<String> notices = Notices.texts("Saved data");
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("last good copy"));
    }

    @Test
    void aDamagedBaseWithNoBackupStartsEmptyAndIsNotOverwrittenWhileUnchanged() throws IOException {
        Files.writeString(base(), "garbage");
        RecordStore<Rec> s = store(1 << 30);
        assertFalse(s.load());
        assertEquals(0, s.size());
        assertEquals(1, Notices.texts("Saved data").size());
        Stores.flush();
        now[0] += 5000;
        Stores.tick();
        Stores.awaitWriter();
        assertEquals("garbage", Files.readString(base()));
        s.add(new Rec("a", 1, "x"));
        s.flush();
        assertEquals(1, reload().size());
    }

    @Test
    void manyChangesInASecondMakeOneAppend() {
        RecordStore<Rec> s = store(1 << 30);
        for (int i = 0; i < 500; i++) s.add(new Rec("r" + i, i, ""));
        for (int i = 0; i < 500; i++) {
            now[0] += 0;
            Stores.tick();
        }
        Stores.awaitWriter();
        assertEquals(1, s.saves());
        assertEquals(500, reload().size());
    }

    @Test
    void aBadRecordInTheBaseIsSkippedAndReported() throws IOException {
        Files.writeString(base(), "{\"v\":1,\"records\":[{\"id\":\"a\",\"score\":1,\"note\":\"\"},{\"id\":\"b\"},{\"id\":\"c\",\"score\":3,\"note\":\"\"}]}");
        RecordStore<Rec> s = store(1 << 30);
        assertTrue(s.load());
        assertEquals(2, s.size());
        assertEquals(1, Notices.texts("Saved data").size());
    }
}
