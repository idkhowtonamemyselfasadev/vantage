package dev.vantage.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class CooldownTest {
    final AtomicLong now = new AtomicLong(1_000);

    @Test
    void theFirstCallPassesAndARepeatInsideThePeriodDoesNot() {
        Cooldown c = Cooldown.wall(3_000, now::get);
        assertTrue(c.tryUse());
        assertFalse(c.tryUse());
        now.addAndGet(2_999);
        assertFalse(c.tryUse());
        assertFalse(c.ready());
        assertEquals(1, c.remaining());
    }

    @Test
    void itPassesAfterThePeriodAndStartsAgain() {
        Cooldown c = Cooldown.wall(3_000, now::get);
        assertTrue(c.tryUse());
        now.addAndGet(3_000);
        assertTrue(c.ready());
        assertEquals(0, c.remaining());
        assertTrue(c.tryUse());
        assertFalse(c.tryUse());
        now.addAndGet(100);
        assertEquals(2_900, c.remaining());
    }

    @Test
    void readyDoesNotStartThePeriod() {
        Cooldown c = Cooldown.wall(500, now::get);
        assertTrue(c.ready());
        assertTrue(c.ready());
        assertTrue(c.tryUse());
    }

    @Test
    void resetMakesTheNextCallPass() {
        Cooldown c = Cooldown.wall(3_000, now::get);
        c.tryUse();
        assertFalse(c.ready());
        c.reset();
        assertTrue(c.tryUse());
        assertFalse(c.tryUse());
    }

    @Test
    void aClockGoingBackwardsDoesNotLockItForever() {
        Cooldown c = Cooldown.wall(3_000, now::get);
        assertTrue(c.tryUse());
        now.set(10); // system time set back, or the tick counter restarted in a new world
        assertTrue(c.ready());
        assertTrue(c.tryUse());
        assertFalse(c.tryUse());
        now.set(3_010);
        assertTrue(c.tryUse());
    }

    @Test
    void theGameTickFormCountsTicksOfItsOwnClock() {
        AtomicLong ticks = new AtomicLong(100);
        Cooldown c = Cooldown.ticks(40, ticks::get);
        assertTrue(c.tryUse());
        ticks.set(139);
        assertFalse(c.tryUse());
        ticks.set(140);
        assertTrue(c.tryUse());
        assertEquals(40, c.remaining());
    }

    @Test
    void zeroPeriodAlwaysPasses() {
        Cooldown c = Cooldown.wall(0, now::get);
        assertTrue(c.tryUse());
        assertTrue(c.tryUse());
    }

    @Test
    void theRealWallClockFactoryWorks() {
        Cooldown c = Cooldown.wall(60_000);
        assertTrue(c.tryUse());
        assertFalse(c.tryUse());
    }
}
