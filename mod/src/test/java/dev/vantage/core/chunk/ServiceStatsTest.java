package dev.vantage.core.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

/** The numbers the step 5 measurement reads: waits from queueing to result, worker time and runs, the queue's peak. */
class ServiceStatsTest {
    final long[] now = {0};
    final ArrayDeque<Runnable> workerTasks = new ArrayDeque<>();
    final Executor workers = workerTasks::add;
    final ChunkService svc = new ChunkService(() -> now[0], () -> workers, o -> true, (o, k, e) -> {});
    final Object world = new Object();

    void arrive(int cx, int cz) {
        svc.chunkLoaded(world, cx, cz, 10, 1000);
        svc.chunkData(world, cx, cz, 10, 1000);
    }

    @Test
    void aJobsWaitRunsFromQueueingToItsFinish() {
        arrive(0, 0);
        svc.submit("a", "k", 0, 0, b -> true);
        now[0] += 5_000_000; // waits 5 ms in the queue
        svc.tick(2_000_000);
        assertEquals(1, svc.stats().waits());
        assertEquals(5.0, svc.stats().waitMeanMs(), 1e-9);
        assertEquals(5.0, svc.stats().waitMaxMs(), 1e-9);
    }

    @Test
    void aScansWaitEndsWhenItsResultReachesTheClientThread() {
        arrive(0, 0);
        List<String> got = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "snap", s -> s + "!", got::add);
        now[0] += 1_000_000;
        svc.tick(2_000_000); // snapshot taken, handed to a worker: not a result yet
        assertEquals(0, svc.stats().waits());
        while (!workerTasks.isEmpty()) workerTasks.poll().run();
        now[0] += 3_000_000;
        svc.tick(2_000_000); // result delivered
        assertEquals(List.of("snap!"), got);
        assertEquals(1, svc.stats().waits());
        assertEquals(4.0, svc.stats().waitMaxMs(), 1e-9);
    }

    @Test
    void aSnapshotsCostIsTheClientThreadTimeItsSupplierTook() {
        arrive(0, 0);
        svc.scan("a", "k", 0, 0, () -> { now[0] += 700_000; return "snap"; }, s -> s, r -> {});
        svc.tick(2_000_000);
        assertEquals(1, svc.stats().snapshots());
        assertEquals(0.7, svc.stats().snapshotMs(), 1e-9);
        svc.stats().reset();
        assertEquals(0, svc.stats().snapshots());
    }

    @Test
    void workerTimeAndRunsCountScannersThatRanEvenWhenDropped() {
        arrive(0, 0);
        svc.scan("a", "k", 0, 0, () -> "snap", s -> { try { Thread.sleep(2); } catch (InterruptedException e) { throw new IllegalStateException(e); } return s; }, r -> {});
        svc.tick(2_000_000);
        svc.chunkUnloaded(0, 0); // the result will be dropped at delivery
        while (!workerTasks.isEmpty()) workerTasks.poll().run();
        assertEquals(1, svc.stats().workerRuns());
        assertTrue(svc.stats().workerMs() >= 1.5, "worker ms " + svc.stats().workerMs());
        svc.stats().reset();
        assertEquals(0, svc.stats().workerRuns());
        assertEquals(0.0, svc.stats().workerMs());
    }

    @Test
    void thePeakIsTheLongestTheQueueGotAndStartsAgainOnReset() {
        for (int i = 0; i < 3; i++) arrive(i, 0);
        for (int i = 0; i < 3; i++) svc.submit("a", "k", i, 0, b -> true);
        assertEquals(3, svc.queue().peak());
        svc.tick(2_000_000);
        assertEquals(0, svc.queue().length());
        assertEquals(3, svc.queue().peak());
        svc.queue().resetPeak();
        assertEquals(0, svc.queue().peak());
        svc.submit("a", "k", 0, 0, b -> true);
        assertEquals(1, svc.queue().peak());
    }
}
