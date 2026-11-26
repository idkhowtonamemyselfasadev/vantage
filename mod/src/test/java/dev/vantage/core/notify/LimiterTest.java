package dev.vantage.core.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import dev.vantage.core.event.Events;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LimiterTest {
    record Who(String ownerName, int ownerRank) implements Events.Owner {}

    private long now;
    private Limiter limiter;
    private final Events.Owner a = new Who("Alpha", 1), b = new Who("Beta", 2);

    @BeforeEach
    void fresh() {
        now = 1_000_000;
        limiter = new Limiter(() -> now);
    }

    private String info(Events.Owner o, String key, String text) {
        Limiter.Line l = limiter.offer(o, Level.INFO, key, -1, text);
        return l == null ? null : l.text();
    }

    private List<String> tickTexts() {
        List<String> out = new ArrayList<>();
        for (Limiter.Line l : limiter.tick()) out.add(l.text());
        return out;
    }

    @Test
    void sameKeyIsShownOncePerCooldown() {
        assertEquals("hello", info(a, null, "hello"));
        now += 2999;
        assertNull(info(a, null, "hello"));
        now += 1;
        assertEquals("hello", info(a, null, "hello"));
    }

    @Test
    void aKeyCoversChangingText() {
        assertEquals("seen 1", info(a, "k", "seen 1"));
        assertNull(info(a, "k", "seen 2"));
    }

    @Test
    void aCallCanGiveItsOwnCooldown() {
        assertNotNull(limiter.offer(a, Level.INFO, "k", 500, "x"));
        now += 499;
        assertNull(limiter.offer(a, Level.INFO, "k", 500, "x"));
        now += 1;
        assertNotNull(limiter.offer(a, Level.INFO, "k", 500, "x"));
        assertNotNull(limiter.offer(a, Level.INFO, "z", 0, "y"));
        assertNotNull(limiter.offer(a, Level.INFO, "z", 0, "y")); // cooldown 0: never a repeat
    }

    @Test
    void twentyIdenticalCallsShowOne() {
        int shown = 0;
        for (int i = 0; i < 20; i++) {
            if (info(a, null, "same") != null) shown++;
            now += 100;
        }
        assertEquals(1, shown);
        now += 5000;
        assertEquals(List.of(), tickTexts()); // identical repeats are not "held back": they were never lines
    }

    @Test
    void sixthLineInTenSecondsIsHeldBack() {
        for (int i = 0; i < 5; i++) assertEquals("m" + i, info(a, null, "m" + i));
        for (int i = 5; i < 20; i++) assertNull(info(a, null, "m" + i));
        assertEquals(List.of(), tickTexts()); // not quiet yet
        now += 1999;
        assertEquals(List.of(), tickTexts());
        now += 1;
        assertEquals(List.of("Alpha: 15 more messages held back"), tickTexts());
        assertEquals(List.of(), tickTexts()); // once
    }

    @Test
    void windowSlidesAfterTenSeconds() {
        for (int i = 0; i < 5; i++) info(a, null, "m" + i);
        now += 9999;
        assertNull(info(a, null, "late"));
        now += 1; // the first five are now exactly 10 s old
        assertEquals("later", info(a, null, "later"));
    }

    @Test
    void quietTimeRestartsWithEveryCall() {
        for (int i = 0; i < 7; i++) info(a, null, "m" + i);
        now += 1500;
        info(a, null, "m7"); // held back too, and it is a call
        now += 1500;
        assertEquals(List.of(), tickTexts());
        now += 500;
        assertEquals(List.of("Alpha: 3 more messages held back"), tickTexts());
    }

    @Test
    void ownersAndKeysLimitOnlyThemselves() {
        for (int i = 0; i < 6; i++) info(a, null, "m" + i);
        assertEquals("m0", info(b, null, "m0")); // another owner, same text
        assertNull(info(a, "other", "fresh")); // a is over its own cap whatever b did
        assertEquals("one", info(b, "k1", "one"));
        assertEquals("two", info(b, "k2", "two"));
        assertNull(info(b, "k1", "one"));
    }

    @Test
    void errorInsideABurstIsShownAtOnce() {
        for (int i = 0; i < 12; i++) info(a, null, "m" + i);
        Limiter.Line e = limiter.offer(a, Level.ERROR, "boom", -1, "it broke");
        assertEquals("it broke", e.text());
        assertEquals(Level.ERROR, e.level());
    }

    @Test
    void errorsDoNotCountAgainstTheFiveLines() {
        for (int i = 0; i < 20; i++) assertNotNull(limiter.offer(a, Level.ERROR, "e" + i, -1, "err " + i));
        for (int i = 0; i < 5; i++) assertNotNull(info(a, null, "m" + i));
        assertNull(info(a, null, "m5"));
        now += 2000;
        assertEquals(List.of("Alpha: 1 more messages held back"), tickTexts());
    }

    @Test
    void repeatedErrorIsMergedAndShownWithCount() {
        assertEquals("bad", limiter.offer(a, Level.ERROR, "k", -1, "bad").text());
        for (int i = 0; i < 3; i++) {
            now += 100;
            assertNull(limiter.offer(a, Level.ERROR, "k", -1, "bad"));
        }
        assertEquals(List.of(), tickTexts());
        now += 3000;
        List<Limiter.Line> due = limiter.tick();
        assertEquals(1, due.size());
        assertEquals("bad (x4)", due.get(0).text());
        assertEquals(Level.ERROR, due.get(0).level());
        assertEquals(List.of(), tickTexts());
    }

    @Test
    void mergedErrorComesWithTheNextCallWhenNoTickRan() {
        limiter.offer(a, Level.ERROR, "k", -1, "bad");
        limiter.offer(a, Level.ERROR, "k", -1, "bad");
        limiter.offer(a, Level.ERROR, "k", -1, "bad");
        now += 3000;
        assertEquals("bad (x3)", limiter.offer(a, Level.ERROR, "k", -1, "bad").text());
    }

    @Test
    void singleErrorHasNoCount() {
        assertEquals("bad", limiter.offer(a, Level.ERROR, "k", -1, "bad").text());
        now += 10_000;
        assertEquals(List.of(), tickTexts());
        assertEquals("bad", limiter.offer(a, Level.ERROR, "k", -1, "bad").text());
    }

    @Test
    void stateIsDroppedWhenTheWorldIsLeft() {
        for (int i = 0; i < 8; i++) info(a, null, "m" + i);
        limiter.offer(a, Level.ERROR, "k", -1, "bad");
        limiter.offer(a, Level.ERROR, "k", -1, "bad");
        limiter.reset();
        now += 5000;
        assertEquals(List.of(), tickTexts()); // no held-back line, no merged error
        assertEquals("m0", info(a, null, "m0")); // a fresh budget and no cooldown
        assertEquals("bad", limiter.offer(a, Level.ERROR, "k", -1, "bad").text());
    }

    @Test
    void quietStateIsPruned() {
        info(a, null, "x");
        now += 20_000;
        limiter.tick();
        assertTrue(limiter.tick().isEmpty());
        assertEquals("x", info(a, null, "x"));
    }

    // ---- once per key until reset (B-201) ----

    private String once(Events.Owner o, String key, String text) {
        Limiter.Line l = limiter.offerOnce(o, Level.SUCCESS, key, text, null);
        return l == null ? null : l.text();
    }

    @Test
    void aOnceKeyIsShownOnceHoweverLongAgoAndAgainAfterAReset() {
        assertEquals("found 1", once(a, "c1", "found 1"));
        now += 3_600_000; // an hour: a plain cooldown would be over long ago
        assertNull(once(a, "c1", "found 1 again"));
        assertEquals("found 2", once(a, "c2", "found 2"));
        assertEquals("found 1", once(b, "c1", "found 1")); // another owner has its own keys
        limiter.reset(); // the world was left
        assertEquals("found 1", once(a, "c1", "found 1"));
    }

    @Test
    void onceLinesAreNotDroppedByTheFiveInTenSecondsRuleAndDoNotCountAgainstIt() {
        for (int i = 0; i < 5; i++) assertNotNull(info(a, "n" + i, "plain " + i)); // the plain budget is used up
        assertNull(info(a, "n5", "plain 5"));
        assertEquals("found", once(a, "c", "found")); // a once line still gets through
        now += 20_000;
        for (int i = 0; i < 5; i++) {
            now += 1_100; // spread out: no summary
            assertNotNull(once(a, "k" + i, "found " + i));
        }
        assertEquals("plain 6", info(a, "n6", "plain 6")); // and five once lines did not use up the plain budget
    }

    @Test
    void moreThanFiveWithinASecondAreMergedIntoOneSummaryAfterTheOwnerWentQuiet() {
        for (int i = 0; i < 8; i++) {
            String shown = once(a, "c" + i, "found " + i);
            if (i < 5) assertEquals("found " + i, shown);
            else assertNull(shown);
            now += 100; // 8 lines in 0.8 s
        }
        assertTrue(tickTexts().isEmpty()); // still busy: the summary waits
        now += 899; // the last call was 999 ms ago
        assertTrue(tickTexts().isEmpty());
        now += 2;
        assertEquals(List.of("Alpha: 3 more"), tickTexts());
        assertTrue(tickTexts().isEmpty()); // said once
        assertNull(once(a, "c7", "found 7")); // a held line counts as told, it does not come again later
    }

    @Test
    void theCallerCanWordTheSummaryAndItsLevelIsTheLevelOfTheHeldLines() {
        for (int i = 0; i < 7; i++) limiter.offerOnce(a, Level.SUCCESS, "c" + i, "found " + i, n -> "Demo: " + n + " new chunks found");
        now += 1_000;
        List<Limiter.Line> due = limiter.tick();
        assertEquals(1, due.size());
        assertEquals("Demo: 2 new chunks found", due.get(0).text());
        assertEquals(Level.SUCCESS, due.get(0).level());
    }

    @Test
    void aSlowTrickleIsNeverSummarisedAndTheOnceKeysOutliveQuietTicks() {
        for (int i = 0; i < 12; i++) {
            assertEquals("found " + i, once(a, "c" + i, "found " + i));
            now += 1_000; // one a second: the window is a second, so this never exceeds five
            assertTrue(tickTexts().isEmpty());
        }
        now += 600_000;
        tickTexts(); // quiet state is dropped, but not the once keys
        assertNull(once(a, "c0", "found 0"));
    }

    @Test
    void theRememberedKeysAreCappedAndTheOverflowCountsIntoTheSummary() {
        int shown = 0;
        for (int i = 0; i < Limiter.MAX_ONCE_KEYS + 50; i++) {
            if (once(a, "k" + i, "found " + i) != null) shown++;
            now += 300; // at most four lines inside a second
        }
        assertEquals(Limiter.MAX_ONCE_KEYS, shown);
        now += 2_000;
        assertEquals(List.of("Alpha: 50 more"), tickTexts());
    }
}
