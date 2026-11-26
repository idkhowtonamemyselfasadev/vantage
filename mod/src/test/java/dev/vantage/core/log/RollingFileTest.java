package dev.vantage.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollingFileTest {
    @TempDir
    Path dir;

    private final long[] now = {1_000_000};
    private final List<Runnable> queued = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private RollingFile file(String name, long max, int keep, boolean sync) {
        return new RollingFile(dir.resolve(name), max, keep, () -> now[0], queued::add, sync, problems::add);
    }

    private void runWriter() {
        List<Runnable> jobs = new ArrayList<>(queued);
        queued.clear();
        jobs.forEach(Runnable::run);
    }

    private String read(String name) throws IOException {
        return Files.readString(dir.resolve(name));
    }

    @Test
    void appendsArriveInOrder() throws IOException {
        RollingFile f = file("a.log", 1000, 2, false);
        f.append("one");
        f.append("two");
        f.append("three");
        f.flushRequest();
        runWriter();
        assertEquals("one\ntwo\nthree\n", read("a.log"));
        f.append("four");
        f.flushRequest();
        runWriter();
        assertEquals("one\ntwo\nthree\nfour\n", read("a.log"));
    }

    @Test
    void nothingIsWrittenUntilHandedOver() {
        RollingFile f = file("a.log", 1000, 2, false);
        f.append("x");
        assertTrue(queued.isEmpty());
        assertFalse(Files.exists(dir.resolve("a.log")));
    }

    @Test
    void rotatesAtTheLimitAndDeletesTheOldest() throws IOException {
        RollingFile f = file("r.log", 20, 2, true); // 9 characters + newline = 10 bytes: two records per file
        for (int i = 1; i <= 7; i++) f.append("record-" + i + "..");
        // records are "record-N.." (10 chars + newline = 11 bytes): one fits in 20, a second would make 22
        assertEquals("record-7..\n", read("r.log"));
        assertEquals("record-6..\n", read("r.log.1"));
        assertEquals("record-5..\n", read("r.log.2"));
        assertFalse(Files.exists(dir.resolve("r.log.3")));
    }

    @Test
    void twoRecordsShareAFileWhenTheyFit() throws IOException {
        RollingFile f = file("p.log", 30, 3, true);
        for (int i = 1; i <= 5; i++) f.append("line-" + i); // 7 bytes each: four per file
        assertEquals("line-5\n", read("p.log"));
        assertEquals("line-1\nline-2\nline-3\nline-4\n", read("p.log.1"));
        assertFalse(Files.exists(dir.resolve("p.log.2")));
    }

    @Test
    void rotationInsideOneBatchKeepsEverything() throws IOException {
        RollingFile f = file("b.log", 20, 5, false);
        for (int i = 1; i <= 5; i++) f.append("rec-" + i + "......"); // 11 bytes each
        f.flushRequest();
        runWriter();
        assertEquals("rec-5......\n", read("b.log"));
        assertEquals("rec-4......\n", read("b.log.1"));
        assertEquals("rec-1......\n", read("b.log.4"));
        assertEquals(1, f.writes());
    }

    @Test
    void anExistingFileCountsTowardsTheLimit() throws IOException {
        Files.writeString(dir.resolve("e.log"), "old old old old\n"); // 16 bytes
        RollingFile f = file("e.log", 20, 1, true);
        f.append("new line"); // 9 bytes would make 25
        assertEquals("new line\n", read("e.log"));
        assertEquals("old old old old\n", read("e.log.1"));
    }

    @Test
    void aRecordLargerThanTheLimitGoesAloneIntoAFreshFile() throws IOException {
        RollingFile f = file("big.log", 10, 2, true);
        f.append("tiny");
        f.append("this record is far larger than the limit");
        assertEquals("this record is far larger than the limit\n", read("big.log"));
        assertEquals("tiny\n", read("big.log.1"));
        f.append("next");
        assertEquals("next\n", read("big.log"));
        assertEquals("this record is far larger than the limit\n", read("big.log.1"));
        assertEquals("tiny\n", read("big.log.2"));
    }

    @Test
    void aRecordWithSeveralLinesIsNeverSplit() throws IOException {
        RollingFile f = file("m.log", 30, 1, true);
        f.append("first\nsecond\nthird"); // 19 bytes
        f.append("fourth\nfifth"); // 13 bytes: does not fit with the first
        assertEquals("fourth\nfifth\n", read("m.log"));
        assertEquals("first\nsecond\nthird\n", read("m.log.1"));
    }

    @Test
    void keepZeroDeletesTheOldFile() throws IOException {
        RollingFile f = file("z.log", 10, 0, true);
        f.append("aaaaaaaa");
        f.append("bbbbbbbb");
        assertEquals("bbbbbbbb\n", read("z.log"));
        assertFalse(Files.exists(dir.resolve("z.log.1")));
    }

    @Test
    void manyAppendsWithinASecondAreOneWrite() throws IOException {
        RollingFile f = file("w.log", 100_000, 1, false);
        f.append("first");
        f.tick(); // nothing was written before: handed over at once
        runWriter();
        assertEquals(1, f.writes());
        for (int i = 0; i < 500; i++) {
            f.append("burst " + i);
            now[0] += 1; // 500 ms in all
            f.tick();
        }
        assertTrue(queued.isEmpty(), "still inside the second");
        now[0] += 600;
        f.tick();
        assertEquals(1, queued.size());
        f.tick(); // nothing pending any more: no second job
        assertEquals(1, queued.size());
        runWriter();
        assertEquals(2, f.writes());
        assertEquals(501, read("w.log").split("\n").length);
    }

    @Test
    void flushHandsOverBeforeTheSecondIsUp() throws IOException {
        RollingFile f = file("f.log", 1000, 1, false);
        f.append("a");
        f.tick();
        runWriter();
        f.append("b");
        f.tick();
        assertTrue(queued.isEmpty());
        f.flushRequest();
        runWriter();
        assertEquals("a\nb\n", read("f.log"));
    }

    @Test
    void aFailingWriteDoesNotThrowAndIsReportedOnce() throws IOException {
        Files.writeString(dir.resolve("blocked"), "a file where the folder should be");
        RollingFile f = file("blocked/x.log", 1000, 1, true);
        f.append("one");
        f.append("two");
        f.append("three");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("x.log"));
    }

    @Test
    void aFailingWriteOnTheWriterThreadIsReportedOnceToo() throws IOException {
        Files.writeString(dir.resolve("blocked2"), "a file where the folder should be");
        RollingFile f = file("blocked2/x.log", 1000, 1, false);
        f.append("one");
        f.flushRequest();
        f.append("two");
        f.flushRequest();
        runWriter();
        assertEquals(1, problems.size());
        assertEquals(0, f.writes());
    }

    @Test
    void createsMissingFolders() throws IOException {
        RollingFile f = new RollingFile(dir.resolve("deep/er/x.log"), 1000, 1, () -> now[0], queued::add, true, problems::add);
        f.append("hi");
        assertEquals("hi\n", Files.readString(dir.resolve("deep/er/x.log")));
    }

    @Test
    void textIsStoredAsUtf8() throws IOException {
        RollingFile f = file("u.log", 1000, 1, true);
        f.append("grün ☃");
        assertEquals("grün ☃\n", read("u.log"));
    }
}
