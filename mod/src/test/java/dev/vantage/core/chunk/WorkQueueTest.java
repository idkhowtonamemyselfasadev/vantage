package dev.vantage.core.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class WorkQueueTest {
    final long[] now = {0};
    final Budget budget = new Budget(() -> now[0]);
    final WorkQueue q = new WorkQueue();
    final TestRules rules = new TestRules().load(0, 0).load(1, 0).load(2, 0);
    final List<String> ran = new ArrayList<>();

    /** A job that takes {@code ms} of fake time and is finished. */
    Job job(String name, double ms) {
        return b -> {
            ran.add(name);
            now[0] += (long) (ms * 1e6);
            return true;
        };
    }

    void submit(Object owner, String kind, int cx, String name, double ms) {
        q.submit(owner, kind, ChunkIndex.key(cx, 0), job(name, ms), rules.generation, rules.world);
    }

    void tick(double limitMs) {
        budget.limit((long) (limitMs * 1e6));
        budget.newTick();
        q.run(budget, rules);
    }

    @Test
    void jobsRunInTheOrderTheyWereSubmitted() {
        submit("a", "k", 0, "first", 0.1);
        submit("a", "k", 1, "second", 0.1);
        submit("b", "k", 0, "third", 0.1);
        tick(2);
        assertEquals(List.of("first", "second", "third"), ran);
        assertEquals(0, q.length());
        assertEquals(3, q.runs());
    }

    @Test
    void theSameOwnerKindAndChunkIsQueuedOnceAndKeepsItsPlace() {
        submit("a", "k", 0, "old", 0.1);
        submit("a", "k", 1, "other", 0.1);
        submit("a", "k", 0, "new", 0.1); // replaces the job, keeps the first place
        assertEquals(2, q.length());
        tick(2);
        assertEquals(List.of("new", "other"), ran);
    }

    @Test
    void differentOwnerOrKindOrChunkAreNotDuplicates() {
        submit("a", "k", 0, "1", 0);
        submit("b", "k", 0, "2", 0);
        submit("a", "j", 0, "3", 0);
        submit("a", "k", 1, "4", 0);
        assertEquals(4, q.length());
    }

    @Test
    void theBudgetStopsTheTicksWorkAndTheRestWaitsForTheNextTick() {
        for (int i = 0; i < 5; i++) submit("o" + i, "k", 0, "j" + i, 1.0);
        tick(2.5); // 1 ms each: the third job starts at 2 ms (under 2.5) and ends at 3 ms, so the fourth does not start
        assertEquals(List.of("j0", "j1", "j2"), ran);
        assertEquals(2, q.length());
        tick(2.5);
        assertEquals(5, ran.size());
        assertEquals(0, q.length());
    }

    @Test
    void aTickAlwaysRunsOneJobEvenWhenItAloneIsOverTheBudget() {
        submit("a", "k", 0, "huge", 50);
        submit("b", "k", 0, "next", 1);
        tick(2);
        assertEquals(List.of("huge"), ran);
        tick(2);
        assertEquals(List.of("huge", "next"), ran);
    }

    @Test
    void aZeroBudgetStillMovesTheQueueOneJobAtATime() {
        for (int i = 0; i < 4; i++) submit("o" + i, "k", 0, "j" + i, 0.5);
        for (int t = 1; t <= 4; t++) {
            tick(0);
            assertEquals(t, ran.size());
        }
    }

    @Test
    void aNotFinishedJobContinuesFirstOnTheNextTickBeforeNewJobs() {
        int[] calls = {0};
        q.submit("slow", "k", ChunkIndex.key(0, 0), b -> {
            ran.add("slow" + calls[0]);
            now[0] += 3_000_000;
            return ++calls[0] >= 3;
        }, 0, 0);
        submit("fast", "k", 1, "fast", 0.1);
        tick(2);
        assertEquals(List.of("slow0"), ran); // over budget after one call: fast waits
        assertEquals(2, q.length());
        submit("late", "k", 2, "late", 0.1); // submitted after: must also wait behind the continued job
        tick(2);
        assertEquals(List.of("slow0", "slow1"), ran);
        tick(2);
        assertEquals(List.of("slow0", "slow1", "slow2"), ran); // finished now
        tick(2);
        assertEquals(List.of("slow0", "slow1", "slow2", "fast", "late"), ran);
    }

    @Test
    void aNotFinishedJobIsNotCalledTwiceInOneTickEvenWithTimeLeft() {
        int[] calls = {0};
        q.submit("a", "k", ChunkIndex.key(0, 0), b -> ++calls[0] >= 2, 0, 0);
        tick(100);
        assertEquals(1, calls[0]);
        assertEquals(1, q.length());
        tick(100);
        assertEquals(2, calls[0]);
        assertEquals(0, q.length());
    }

    @Test
    void submittingAgainWhileAJobIsUnfinishedReplacesItAndRestarts() {
        q.submit("a", "k", ChunkIndex.key(0, 0), b -> false, 0, 0);
        tick(2);
        assertEquals(1, q.length());
        submit("a", "k", 0, "restart", 0.1);
        assertEquals(1, q.length());
        tick(2);
        assertEquals(List.of("restart"), ran);
        assertEquals(0, q.length());
    }

    @Test
    void aJobThatSubmitsItselfWhileRunningIsQueuedAgain() {
        q.submit("a", "k", ChunkIndex.key(0, 0), b -> {
            ran.add("once");
            submit("a", "k", 0, "again", 0.1);
            return true;
        }, 0, 0);
        tick(2);
        assertEquals(List.of("once", "again"), ran);
    }

    @Test
    void jobsOfAnOwnerThatIsSwitchedOffAreDropped() {
        submit("a", "k", 0, "a", 0.1);
        submit("b", "k", 0, "b", 0.1);
        rules.off.add("a");
        tick(2);
        assertEquals(List.of("b"), ran);
        assertEquals(1, q.dropped());
    }

    @Test
    void jobsOfAChunkThatIsNoLongerLoadedAreDropped() {
        submit("a", "k", 1, "gone", 0.1);
        submit("a", "k", 0, "kept", 0.1);
        rules.loaded.remove(ChunkIndex.key(1, 0));
        tick(2);
        assertEquals(List.of("kept"), ran);
        assertEquals(1, q.dropped());
    }

    @Test
    void jobsFromAnOlderGenerationAreDroppedButNewerOnesRun() {
        submit("a", "k", 0, "stale", 0.1);
        rules.generation = 1; // the chunk's data was re-sent
        submit("b", "k", 0, "fresh", 0.1);
        tick(2);
        assertEquals(List.of("fresh"), ran);
        assertEquals(1, q.dropped());
    }

    @Test
    void submittingAgainAfterNewDataBringsTheJobUpToTheNewGeneration() {
        submit("a", "k", 0, "old", 0.1);
        rules.generation = 2;
        submit("a", "k", 0, "again", 0.1); // same key: replaced, now at generation 2
        tick(2);
        assertEquals(List.of("again"), ran);
        assertEquals(0, q.dropped());
    }

    @Test
    void jobsFromAnotherWorldAreDropped() {
        submit("a", "k", 0, "old world", 0.1);
        rules.world = 1;
        tick(2);
        assertTrue(ran.isEmpty());
        assertEquals(1, q.dropped());
    }

    @Test
    void clearDropsEverythingAndCountsIt() {
        submit("a", "k", 0, "1", 0);
        submit("b", "k", 0, "2", 0);
        q.clear();
        assertEquals(0, q.length());
        assertEquals(2, q.dropped());
        tick(2);
        assertTrue(ran.isEmpty());
    }

    @Test
    void aJobThatThrowsIsReportedAndTheQueueCarriesOn() {
        q.submit("a", "k", ChunkIndex.key(0, 0), b -> {
            throw new IllegalStateException("boom");
        }, 0, 0);
        submit("b", "k", 0, "after", 0.1);
        tick(2);
        assertEquals(List.of("after"), ran);
        assertEquals("boom", rules.failure.getMessage());
        assertEquals(0, q.length());
    }

    @Test
    void aLongJobCanCutItselfIntoPiecesWithTheBudget() {
        int[] steps = {0};
        q.submit("a", "k", ChunkIndex.key(0, 0), b -> {
            while (steps[0] < 5 && b.hasTimeLeft()) {
                steps[0]++;
                now[0] += 1_000_000; // one step takes 1 ms
            }
            return steps[0] >= 5;
        }, 0, 0);
        tick(2.5);
        assertEquals(3, steps[0]); // the third step starts at 2 ms, under 2.5, and ends at 3 ms
        assertEquals(1, q.length());
        tick(2.5);
        assertEquals(5, steps[0]);
        assertEquals(0, q.length());
    }

    @Test
    void cancelDropsOneOwnersWaitingJobsIncludingOnesToBeContinuedAndKeepsTheOthers() {
        submit("a", "k", 0, "a0", 0.1);
        submit("b", "k", 0, "b0", 0.1);
        submit("a", "k", 1, "a1", 0.1);
        q.submit("a", "long", ChunkIndex.key(2, 0), b -> {
            ran.add("a-long");
            return false; // not finished: it waits for the next tick
        }, rules.generation, rules.world);
        tick(0.0001); // the budget lets exactly one job run (a0); the rest wait
        assertEquals(List.of("a0"), ran);
        assertEquals(2, q.cancel("a")); // a1 and a-long
        assertEquals(1, q.length());
        assertEquals(2, q.dropped());
        tick(2);
        assertEquals(List.of("a0", "b0"), ran);
        submit("a", "k", 1, "a1-again", 0.1); // work queued after the cancel is not affected
        tick(2);
        assertEquals(List.of("a0", "b0", "a1-again"), ran);
    }

    @Test
    void pendingCountsOneOwnersJobsAndDeferralsCountTicksCutShortByTheBudget() {
        submit("a", "k", 0, "1", 1);
        submit("a", "k", 1, "2", 1);
        submit("b", "k", 2, "3", 1);
        assertEquals(2, q.pending("a"));
        assertEquals(1, q.pending("b"));
        tick(1.5); // runs 1 and 2 (the second starts with time left), then the budget is gone with job 3 waiting
        assertEquals(1, q.deferrals());
        assertEquals(1, q.pending("b"));
        tick(5); // the rest fits: not a deferral
        assertEquals(1, q.deferrals());
        assertEquals(0, q.pending("b"));
    }
}
