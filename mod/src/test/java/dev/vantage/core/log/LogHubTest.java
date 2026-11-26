package dev.vantage.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogHubTest {
    @TempDir
    Path dir;

    private final long[] now = {1_700_000_000_000L}; // 2023-11-14 22:13:20 UTC
    private final List<String> echoed = new ArrayList<>();
    private LogHub hub;
    private Path file;

    @BeforeEach
    void setUp() {
        file = dir.resolve("vantage.log");
        RollingFile f = new RollingFile(file, 100_000, 2, () -> now[0], Runnable::run, true, m -> {});
        hub = new LogHub(() -> f, () -> now[0], ZoneOffset.UTC, (level, text, error) -> echoed.add(level + " " + text + (error == null ? "" : " !" + error.getMessage())));
    }

    private List<String> lines() throws IOException {
        return Files.exists(file) ? Files.readAllLines(file) : List.of();
    }

    @Test
    void aLineHasTimeLevelAndSource() throws IOException {
        hub.source("radar").warn("something odd");
        assertEquals(List.of("2023-11-14 22:13:20.000 WARN  [radar] something odd"), lines());
        now[0] += 5;
        hub.source("radar").info("fine");
        hub.source("core").error("bad");
        assertEquals("2023-11-14 22:13:20.005 INFO  [radar] fine", lines().get(1));
        assertEquals("2023-11-14 22:13:20.005 ERROR [core] bad", lines().get(2));
    }

    @Test
    void placeholderShapesInUse() throws IOException {
        SourceLog l = hub.source("x");
        l.info("no placeholder");
        l.info("one {}", "a");
        l.info("two {} {}", 1, 2.5);
        l.warn("many {} {} {} {}", "a", "b", "c", "d");
        l.warn("literal {} stays when there is no argument");
        l.error("{} failed in {}", "Radar", "onTick", new IllegalStateException("boom"));
        List<String> lines = lines();
        assertTrue(lines.get(0).endsWith("] no placeholder"));
        assertTrue(lines.get(1).endsWith("] one a"));
        assertTrue(lines.get(2).endsWith("] two 1 2.5"));
        assertTrue(lines.get(3).endsWith("] many a b c d"));
        assertTrue(lines.get(4).endsWith("] literal {} stays when there is no argument"));
        assertTrue(lines.get(5).endsWith("] Radar failed in onTick"), lines.get(5));
        assertEquals("java.lang.IllegalStateException: boom", lines.get(6));
        assertTrue(lines.get(7).trim().startsWith("at "));
    }

    @Test
    void aTrailingThrowableAloneIsTheErrorNotAnArgument() throws IOException {
        hub.source("x").warn("could not save", new java.io.IOException("disk full"));
        List<String> lines = lines();
        assertTrue(lines.get(0).endsWith("] could not save"));
        assertEquals("java.io.IOException: disk full", lines.get(1));
    }

    @Test
    void theEchoGetsTheSameTextMarkedWithTheSource() {
        hub.source("radar").warn("Too {} to {}", "slow", "draw", new RuntimeException("why"));
        assertEquals(List.of("WARN [radar] Too slow to draw !why"), echoed);
    }

    @Test
    void debugDoesNotCallTheSupplierWhenOff() throws IOException {
        AtomicInteger called = new AtomicInteger();
        SourceLog l = hub.source("radar");
        l.debug(() -> "n=" + called.incrementAndGet());
        assertEquals(0, called.get());
        assertTrue(lines().isEmpty());
        assertFalse(l.debugging());
    }

    @Test
    void debugWritesWhenOnAndStopsWhenOff() throws IOException {
        AtomicInteger called = new AtomicInteger();
        SourceLog l = hub.source("radar");
        hub.setDebug("radar", true);
        l.debug(() -> "seen " + called.incrementAndGet());
        assertEquals(1, called.get());
        assertTrue(lines().get(0).contains("DEBUG [radar] seen 1"));
        assertTrue(echoed.isEmpty(), "debug lines go to the client's file only");
        hub.setDebug("radar", false);
        l.debug(() -> "seen " + called.incrementAndGet());
        assertEquals(1, called.get());
        assertEquals(1, lines().size());
    }

    @Test
    void debugIsPerSource() throws IOException {
        SourceLog a = hub.source("a"), b = hub.source("b");
        hub.setDebug("a", true);
        a.debug(() -> "from a");
        b.debug(() -> "from b");
        assertEquals(1, lines().size());
        assertTrue(lines().get(0).contains("[a] from a"));
        assertEquals(List.of("a"), hub.debugSources());
        hub.setDebug("b", true);
        assertEquals(List.of("a", "b"), hub.debugSources());
        assertTrue(hub.debugOn("b"));
    }

    @Test
    void debugOffClearsAll() {
        hub.setDebug("a", true);
        hub.setDebug("b", true);
        hub.source("c");
        assertEquals(2, hub.allDebugOff());
        assertTrue(hub.debugSources().isEmpty());
        assertFalse(hub.debugOn("a"));
        assertEquals(0, hub.allDebugOff());
    }

    @Test
    void debugCanBeSwitchedBeforeTheLoggerExistsAndTheLoggerStaysTheSame() {
        hub.setDebug("late", true);
        SourceLog l = hub.source("late");
        assertTrue(l.debugging());
        assertSame(l, hub.source("late"));
        assertNotSame(l, hub.source("other"));
        assertTrue(hub.knows("late"));
        assertFalse(hub.knows("nothing"));
    }

    @Test
    void onceLogsTheFirstTimeAndCountsTheRest() throws IOException {
        AtomicInteger built = new AtomicInteger();
        SourceLog l = hub.source("loading");
        for (int i = 0; i < 1235; i++) l.once("render", () -> "failed " + built.incrementAndGet());
        assertEquals(1, built.get(), "the message is only built the first time");
        assertEquals(1, lines().size());
        assertTrue(lines().get(0).contains("WARN  [loading] failed 1"));
        assertEquals(List.of("[loading] render happened 1,234 more times"), hub.writeRepeatCounts());
        assertTrue(lines().get(1).endsWith("[loading] render happened 1,234 more times"));
        assertTrue(hub.writeRepeatCounts().isEmpty(), "counted once, then reset");
        l.once("render", () -> "again");
        assertEquals(2, lines().size(), "a key that was reported stays known");
    }

    @Test
    void onceKeysAreIndependentAndPerSource() throws IOException {
        hub.source("a").once("k", () -> "a-k");
        hub.source("a").once("j", () -> "a-j");
        hub.source("b").once("k", () -> "b-k");
        hub.source("a").once("k", () -> "no");
        assertEquals(3, lines().size());
        assertEquals(List.of("[a] k happened 1 more times"), hub.writeRepeatCounts());
    }

    @Test
    void onceKeepsTheStackTraceOfTheFirstTime() throws IOException {
        hub.source("x").once("k", new IllegalArgumentException("first"), () -> "problem");
        hub.source("x").once("k", new IllegalArgumentException("second"), () -> "problem");
        List<String> lines = lines();
        assertEquals("java.lang.IllegalArgumentException: first", lines.get(1));
        assertEquals(1, lines.stream().filter(s -> s.contains("[x] problem")).count());
        assertTrue(lines.stream().noneMatch(s -> s.contains("second")));
    }

    @Test
    void manyDifferentKeysDoNotGrowWithoutLimit() throws IOException {
        SourceLog l = hub.source("x");
        for (int i = 0; i < 1000; i++) { int n = i; l.once("key " + i, () -> "k" + n); }
        long written = lines().size();
        assertTrue(written <= 201, "200 keys and the shared one at most, got " + written);
    }

    @Test
    void noFileMeansNoWriteButNoFailure() {
        LogHub bare = new LogHub(() -> null, () -> now[0], ZoneOffset.UTC, null);
        bare.source("x").warn("nobody listens {}", 1);
        bare.setDebug("x", true);
        bare.source("x").debug(() -> "fine");
        bare.source("x").once("k", () -> "fine");
    }
}
