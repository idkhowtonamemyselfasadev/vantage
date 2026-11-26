package dev.vantage.core.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

/** {@code pending(owner)} is "not done": queued, on a worker, or waiting for its result to be applied. */
class ScanPendingTest {
    final ArrayDeque<Runnable> workerTasks = new ArrayDeque<>();
    final Executor workers = workerTasks::add;
    final ChunkService svc = new ChunkService(() -> 0L, () -> workers, o -> true, (o, k, e) -> {});
    final Object world = new Object();

    void arrive(int cx) {
        svc.chunkLoaded(world, cx, 0, 1, 1);
        svc.chunkData(world, cx, 0, 1, 1);
    }

    void runWorkers() {
        while (!workerTasks.isEmpty()) workerTasks.poll().run();
    }

    @Test
    void aScanCountsFromQueueingUntilItsResultIsApplied() {
        arrive(0);
        svc.scan("a", "k", 0, 0, () -> "s", s -> s, r -> {});
        assertEquals(1, svc.pending("a")); // queued
        svc.tick(2_000_000);
        assertEquals(1, svc.pending("a")); // snapshot taken, on a worker
        runWorkers();
        assertEquals(1, svc.pending("a")); // result computed, not applied
        svc.tick(2_000_000);
        assertEquals(0, svc.pending("a"));
        assertEquals(0, svc.pending("b"));
    }

    @Test
    void severalScansAndOtherOwnersAreCountedApart() {
        arrive(0);
        arrive(1);
        svc.scan("a", "k", 0, 0, () -> "s", s -> s, r -> {});
        svc.scan("a", "k", 1, 0, () -> "s", s -> s, r -> {});
        svc.scan("b", "k", 1, 0, () -> "s", s -> s, r -> {});
        svc.tick(2_000_000);
        assertEquals(2, svc.pending("a"));
        assertEquals(1, svc.pending("b"));
        runWorkers();
        svc.tick(2_000_000);
        assertEquals(0, svc.pending("a") + svc.pending("b"));
    }

    @Test
    void aResultThatIsDroppedStopsCounting() {
        arrive(0);
        svc.scan("a", "k", 0, 0, () -> "s", s -> s, r -> {});
        svc.tick(2_000_000);
        svc.chunkUnloaded(0, 0);
        runWorkers();
        svc.tick(2_000_000);
        assertEquals(0, svc.pending("a"));
    }

    @Test
    void aFailedScanStopsCountingToo() {
        arrive(0);
        svc.scan("a", "k", 0, 0, () -> "s", s -> { throw new IllegalStateException("x"); }, r -> {});
        svc.tick(2_000_000);
        runWorkers();
        svc.tick(2_000_000);
        assertEquals(0, svc.pending("a"));
    }

    @Test
    void cancelEndsTheCountAtOnceAndALateResultDoesNotMakeItNegative() {
        arrive(0);
        arrive(1);
        svc.scan("a", "k", 0, 0, () -> "s", s -> s, r -> {});
        svc.tick(2_000_000); // on a worker
        svc.cancel("a");
        assertEquals(0, svc.pending("a"));
        svc.scan("a", "k", 1, 0, () -> "s", s -> s, r -> {}); // new work after the cancel counts
        svc.tick(2_000_000);
        assertEquals(1, svc.pending("a"));
        runWorkers(); // the old result arrives (dropped), the new one waits
        svc.tick(2_000_000);
        assertEquals(0, svc.pending("a"));
    }

    @Test
    void aNewWorldForgetsEverythingInFlight() {
        arrive(0);
        svc.scan("a", "k", 0, 0, () -> "s", s -> s, r -> {});
        svc.tick(2_000_000);
        svc.worldEvent(new Object());
        assertEquals(0, svc.pending("a"));
        runWorkers();
        svc.tick(2_000_000);
        assertEquals(0, svc.pending("a"));
    }
}
