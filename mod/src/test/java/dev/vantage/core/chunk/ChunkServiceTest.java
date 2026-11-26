package dev.vantage.core.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

/** The service without the game: a fake clock, fake worlds (plain objects) and a worker "pool" that the test runs by hand. */
class ChunkServiceTest {
    final long[] now = {0};
    final Set<Object> off = new HashSet<>();
    final List<Throwable> failures = new ArrayList<>();
    /** The workers: nothing runs until the test says so. */
    final ArrayDeque<Runnable> workerTasks = new ArrayDeque<>();
    final Executor workers = workerTasks::add;
    /** Scans that failed for one chunk and were only reported (owner + chunk + message). */
    final List<String> reported = new ArrayList<>();
    final ChunkService svc = new ChunkService(() -> now[0], () -> workers, o -> !off.contains(o), new ChunkService.Failures() {
        @Override
        public void failed(Object owner, String kind, Throwable error) {
            failures.add(error);
        }

        @Override
        public void scanFailed(Object owner, String kind, long chunkKey, Throwable error) {
            reported.add(owner + " " + ChunkIndex.keyX(chunkKey) + "," + ChunkIndex.keyZ(chunkKey) + " " + error.getMessage());
        }
    });
    final Object world1 = new Object(), world2 = new Object();

    void arrive(Object w, int cx, int cz) {
        svc.chunkLoaded(w, cx, cz, 10, 1000);
        svc.chunkData(w, cx, cz, 10, 1000);
    }

    void tick() {
        svc.tick(2_000_000);
    }

    void runWorkers() {
        while (!workerTasks.isEmpty()) workerTasks.poll().run();
    }

    @Test
    void theIndexFollowsLoadAndUnload() {
        arrive(world1, 0, 0);
        arrive(world1, 1, 0);
        svc.chunkUnloaded(0, 0);
        assertEquals(1, svc.index().count());
        assertFalse(svc.index().isLoaded(0, 0));
        assertEquals(1, svc.index().generation(ChunkIndex.key(1, 0)));
    }

    @Test
    void leavingOrChangingTheWorldEmptiesTheIndexTheQueueAndMovesTheWorldCounter() {
        arrive(world1, 0, 0);
        svc.submit("a", "k", 0, 0, b -> true);
        int w = svc.world().current();
        svc.worldEvent(null); // left
        assertEquals(0, svc.index().count());
        assertEquals(0, svc.queue().length());
        assertNotEquals(w, svc.world().current());
        arrive(world2, 0, 0);
        int w2 = svc.world().current();
        svc.worldEvent(world2); // joined: the index already belongs to this world, nothing is thrown away
        assertEquals(w2, svc.world().current());
        assertEquals(1, svc.index().count());
        svc.worldEvent(world1); // dimension change: the world the client has is not the one the index was built for
        assertEquals(0, svc.index().count());
        assertNotEquals(w2, svc.world().current());
    }

    @Test
    void chunksOfTheNewWorldThatArriveBeforeTheWorldEventAreKept() {
        arrive(world1, 0, 0);
        arrive(world2, 5, 5); // B-180: the respawn packet and its chunks come before the end of the tick, which announces the change
        assertFalse(svc.index().isLoaded(0, 0));
        assertTrue(svc.index().isLoaded(5, 5));
        svc.worldEvent(world2);
        assertTrue(svc.index().isLoaded(5, 5));
    }

    @Test
    void aJobQueuedBeforeAWorldChangeNeverRuns() {
        arrive(world1, 0, 0);
        List<String> ran = new ArrayList<>();
        svc.submit("a", "k", 0, 0, b -> ran.add("old") || true);
        arrive(world2, 0, 0); // the same coordinates in the new world
        tick();
        assertTrue(ran.isEmpty());
        assertEquals(1, svc.dropped());
    }

    @Test
    void aJobForAChunkThatGotNewDataIsDroppedUntilItIsSubmittedAgain() {
        arrive(world1, 0, 0);
        List<String> ran = new ArrayList<>();
        svc.submit("a", "k", 0, 0, b -> ran.add("v1") || true);
        svc.chunkData(world1, 0, 0, 11, 1100);
        tick();
        assertTrue(ran.isEmpty());
        svc.submit("a", "k", 0, 0, b -> ran.add("v2") || true);
        tick();
        assertEquals(List.of("v2"), ran);
    }

    @Test
    void aSnapshotScanDeliversOnALaterTickOnTheClientSide() {
        arrive(world1, 0, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "snapshot", s -> s + " scanned", r -> clientSide.add(r));
        assertEquals(0, svc.scans().snapshotsWaiting());
        tick(); // the queue takes the snapshot and hands it to a worker
        assertEquals(1, svc.scans().snapshotsWaiting());
        assertTrue(clientSide.isEmpty());
        runWorkers(); // the worker computes the result
        assertEquals(0, svc.scans().snapshotsWaiting());
        assertEquals(1, svc.scans().resultsWaiting());
        assertTrue(clientSide.isEmpty()); // nothing is applied until the client thread's next tick
        tick();
        assertEquals(List.of("snapshot scanned"), clientSide);
        assertEquals(0, svc.scans().resultsWaiting());
        assertEquals(1, svc.scans().delivered());
    }

    @Test
    void aResultIsIgnoredWhenTheChunkGotNewDataWhileTheWorkerWasBusy() {
        arrive(world1, 0, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "s", s -> s, r -> clientSide.add(r));
        tick();
        runWorkers();
        svc.chunkData(world1, 0, 0, 12, 1200); // the data changed after the snapshot
        tick();
        assertTrue(clientSide.isEmpty());
        assertEquals(1, svc.scans().dropped());
    }

    @Test
    void aResultIsIgnoredWhenTheChunkUnloadedOrTheOwnerWasSwitchedOff() {
        arrive(world1, 0, 0);
        arrive(world1, 1, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "gone", s -> s, clientSide::add);
        svc.scan("b", "k", 1, 0, () -> "off", s -> s, clientSide::add);
        tick();
        runWorkers();
        svc.chunkUnloaded(0, 0);
        off.add("b");
        tick();
        assertTrue(clientSide.isEmpty());
        assertEquals(2, svc.scans().dropped());
    }

    @Test
    void theWorldCounterMakesALateResultIgnoredEvenForTheSameCoordinates() {
        arrive(world1, 0, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "old", s -> s, clientSide::add);
        tick();
        svc.worldEvent(null);
        arrive(world2, 0, 0); // chunk 0,0 of the new world has generation 1 too, as the old one had
        runWorkers(); // the worker finishes after the change: it sees the new number and throws the work away
        tick();
        assertTrue(clientSide.isEmpty());
        assertEquals(0, svc.scans().resultsWaiting());
    }

    @Test
    void aResultThatWasAlreadyWaitingWhenTheWorldChangedIsDropped() {
        arrive(world1, 0, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "old", s -> s, clientSide::add);
        tick();
        runWorkers();
        assertEquals(1, svc.scans().resultsWaiting());
        svc.worldEvent(null);
        assertEquals(0, svc.scans().resultsWaiting());
        arrive(world2, 0, 0);
        tick();
        assertTrue(clientSide.isEmpty());
    }

    @Test
    void aSnapshotOfAMissingChunkEndsTheScanQuietly() {
        arrive(world1, 0, 0);
        svc.scan("a", "k", 0, 0, () -> null, s -> s, r -> {});
        tick();
        assertTrue(workerTasks.isEmpty());
        assertEquals(0, svc.queue().length());
    }

    @Test
    void aScannerThatThrowsForOneChunkIsReportedOnTheClientThreadAndDoesNotFaultTheOwner() {
        arrive(world1, 0, 0);
        List<Object> results = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "s", s -> {
            throw new IllegalStateException("scanner");
        }, results::add);
        tick();
        runWorkers();
        assertTrue(reported.isEmpty()); // not on the worker
        tick();
        assertEquals(List.of("a 0,0 scanner"), reported);
        assertTrue(failures.isEmpty()); // one bad chunk is no reason to fault the module
        assertTrue(results.isEmpty()); // the result handler is not called for a failure
        assertEquals(1, svc.scans().failed());
    }

    private void failScans(String owner, int count, List<Throwable> seen) {
        for (int i = 0; i < count; i++) {
            arrive(world1, i, 5);
            svc.scan(owner, "k", i, 5, () -> "s", s -> {
                throw new IllegalStateException("boom");
            }, r -> {}, seen == null ? null : seen::add);
        }
        tick();
        runWorkers();
        tick();
    }

    @Test
    void aFailureCallbackHearsTheExceptionAndOneThatThrowsItselfFaultsTheOwner() {
        List<Throwable> seen = new ArrayList<>();
        failScans("a", 3, seen);
        assertEquals(3, seen.size());
        assertEquals("boom", seen.get(0).getMessage());
        assertTrue(failures.isEmpty());
        arrive(world1, 9, 9);
        svc.scan("a", "k", 9, 9, () -> "s", s -> {
            throw new IllegalStateException("boom");
        }, r -> {}, t -> {
            throw new IllegalArgumentException("the module's own callback");
        });
        tick();
        runWorkers();
        tick();
        assertEquals(1, failures.size()); // module code that throws is a module error, as before
        assertEquals("the module's own callback", failures.get(0).getMessage());
    }

    @Test
    void aScannerThatFailsForEveryChunkFaultsTheOwnerOnceAfterTwentyInARow() {
        failScans("a", ScanPipeline.FAULT_AFTER - 1, null);
        assertEquals(ScanPipeline.FAULT_AFTER - 1, reported.size());
        assertTrue(failures.isEmpty());
        arrive(world1, 50, 5);
        svc.scan("a", "k", 50, 5, () -> "s", s -> {
            throw new IllegalStateException("last");
        }, r -> {});
        tick();
        runWorkers();
        tick();
        assertEquals(1, failures.size());
        assertEquals("last", failures.get(0).getMessage());
        assertEquals(ScanPipeline.FAULT_AFTER, reported.size()); // every one was still reported
    }

    @Test
    void aSuccessBetweenFailuresStartsTheCountAgainAndOwnersAreCountedApart() {
        failScans("a", ScanPipeline.FAULT_AFTER - 1, null);
        arrive(world1, 60, 5);
        svc.scan("a", "k", 60, 5, () -> "s", s -> s, r -> {}); // one chunk that works
        tick();
        runWorkers();
        tick();
        for (int i = 0; i < ScanPipeline.FAULT_AFTER - 1; i++) {
            arrive(world1, 100 + i, 6);
            svc.scan("a", "k", 100 + i, 6, () -> "s", s -> {
                throw new IllegalStateException("again");
            }, r -> {});
            svc.scan("b", "k", 100 + i, 6, () -> "s", s -> {
                throw new IllegalStateException("other owner");
            }, r -> {});
        }
        tick();
        runWorkers();
        tick();
        assertTrue(failures.isEmpty()); // 19 + success + 19 for "a", 19 for "b": nobody reached 20 in a row
    }

    @Test
    void aBlockEntityScanHandsTheWorkerTheRecordsAndCopiesNoSection() {
        arrive(world1, 0, 0);
        List<BlockEntityInfo> source = new ArrayList<>(List.of(new BlockEntityInfo(null, null, null)));
        int[] listed = {0};
        List<String> clientSide = new ArrayList<>();
        svc.scanBlockEntities("a", "k", 0, 0, key -> {
            listed[0]++;
            return List.copyOf(source);
        }, be -> be.cx() + "," + be.cz() + " gen " + be.generation() + " n " + be.blockEntities().size(), clientSide::add);
        assertEquals(0, listed[0]); // queued, nothing read yet
        tick(); // the job reads the list on the client thread, inside the budget, and hands it over
        assertEquals(1, listed[0]);
        assertEquals(1, svc.scans().snapshotsWaiting());
        source.clear(); // the game changes the live data after the hand-over: the worker keeps what it was given
        runWorkers();
        assertTrue(clientSide.isEmpty());
        tick();
        assertEquals(List.of("0,0 gen 1 n 1"), clientSide);
    }

    @Test
    void aBlockEntityScanOfAMissingChunkEndsQuietlyAndOfAChangedChunkIsDropped() {
        arrive(world1, 0, 0);
        arrive(world1, 1, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scanBlockEntities("a", "k", 0, 0, key -> null, be -> "none", clientSide::add);
        svc.scanBlockEntities("a", "k", 1, 0, key -> List.of(), be -> "stale", clientSide::add);
        tick();
        assertEquals(1, svc.scans().snapshotsWaiting()); // only the chunk that exists went to a worker
        runWorkers();
        svc.chunkData(world1, 1, 0, 12, 1200);
        tick();
        assertTrue(clientSide.isEmpty());
    }

    @Test
    void cancelLeavesOtherOwnersAndTheirPlainJobsAlone() {
        arrive(world1, 0, 0);
        arrive(world1, 1, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "a scan", s -> s, clientSide::add);
        svc.submit("a", "plain", 0, 0, b -> clientSide.add("a job") || true);
        svc.scan("b", "k", 1, 0, () -> "b scan", s -> s, clientSide::add);
        svc.submit("b", "plain", 1, 0, b -> clientSide.add("b job") || true);
        svc.cancel("a");
        assertEquals(2, svc.queue().length()); // only b's two are left
        assertEquals(2, svc.queue().dropped());
        tick();
        runWorkers();
        tick();
        assertEquals(List.of("b job", "b scan"), clientSide.stream().sorted().toList());
    }

    @Test
    void aJobCancelledWhileQueuedNeverRunsAndWhatTheOwnerStartsAfterwardsDoes() {
        arrive(world1, 0, 0);
        arrive(world1, 1, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "before", s -> s, clientSide::add);
        svc.cancel("a"); // dropped while still queued
        assertEquals(0, svc.queue().length());
        svc.scan("a", "k", 1, 0, () -> "after", s -> s, clientSide::add);
        tick();
        runWorkers();
        tick();
        assertEquals(List.of("after"), clientSide);
    }

    @Test
    void aScanThatWasOnAWorkerWhenTheOwnerWasCancelledNeverDelivers() {
        arrive(world1, 0, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "running", s -> s, clientSide::add);
        tick(); // the snapshot is on a worker
        svc.cancel("a");
        runWorkers(); // it finishes after the cancel
        tick();
        assertTrue(clientSide.isEmpty());
        svc.scan("a", "k", 0, 0, () -> "again", s -> s, clientSide::add); // the same owner may scan again
        tick();
        runWorkers();
        tick();
        assertEquals(List.of("again"), clientSide);
    }

    @Test
    void aResultAlreadyWaitingWhenTheOwnerIsCancelledIsDropped() {
        arrive(world1, 0, 0);
        List<String> clientSide = new ArrayList<>();
        svc.scan("a", "k", 0, 0, () -> "waiting", s -> s, clientSide::add);
        tick();
        runWorkers();
        assertEquals(1, svc.scans().resultsWaiting());
        svc.cancel("a");
        assertEquals(0, svc.scans().resultsWaiting());
        tick();
        assertTrue(clientSide.isEmpty());
    }

    @Test
    void theSnapshotIsTakenInsideTheBudgetOnePerTickWhenEachIsExpensive() {
        for (int i = 0; i < 3; i++) arrive(world1, i, 0);
        int[] taken = {0};
        for (int i = 0; i < 3; i++) {
            svc.scan("a", "k", i, 0, () -> {
                now[0] += 3_000_000; // a copy that costs more than the whole 2 ms budget
                taken[0]++;
                return "s";
            }, s -> s, r -> {});
        }
        tick();
        assertEquals(1, taken[0]);
        tick();
        assertEquals(2, taken[0]);
    }

    @Test
    void theLastTickTimeAndTheCountersAreKept() {
        arrive(world1, 0, 0);
        svc.submit("a", "k", 0, 0, b -> {
            now[0] += 1_500_000;
            return true;
        });
        tick();
        assertEquals(1_500_000, svc.lastTickNs());
        assertEquals(1, svc.queue().runs());
    }
}
