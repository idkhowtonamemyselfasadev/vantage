package dev.vantage.module.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Fast XP's delay rule: the counter is only ever lowered, never raised, and Delay 4 is vanilla. */
class FastXpMathTest {
    @Test
    void vanillaDelayChangesNothing() {
        // vanilla sets 4, counts down once a tick: 3, 2, 1, 0 are what handleKeybinds sees
        for (int c = 0; c <= 4; c++) assertEquals(Math.min(c, 3), FastXpMath.cap(c, 4));
        assertEquals(3, FastXpMath.cap(4, 4)); // (the check never sees 4 in vanilla: the decrement ran first)
    }

    @Test
    void lowDelaysCapTheCounter() {
        assertEquals(0, FastXpMath.cap(3, 1));
        assertEquals(0, FastXpMath.cap(3, 0));
        assertEquals(1, FastXpMath.cap(3, 2));
        assertEquals(2, FastXpMath.cap(3, 3));
    }

    @Test
    void neverRaisesTheCounter() {
        for (int d = 0; d <= 4; d++) for (int c = 0; c <= 4; c++) assertEquals(true, FastXpMath.cap(c, d) <= c);
        assertEquals(0, FastXpMath.cap(0, 4));
        assertEquals(1, FastXpMath.cap(1, 3));
    }

    @Test
    void outOfRangeDelaysAreClamped() {
        assertEquals(0, FastXpMath.cap(3, -5));
        assertEquals(3, FastXpMath.cap(9, 99));
    }

    @Test
    void throwCountsFollowTheInterval() {
        assertEquals(5, FastXpMath.throwsIn(20, 4));
        assertEquals(7, FastXpMath.throwsIn(20, 3));
        assertEquals(10, FastXpMath.throwsIn(20, 2));
        assertEquals(20, FastXpMath.throwsIn(20, 1));
        assertEquals(20, FastXpMath.throwsIn(20, 0));
        assertEquals(0, FastXpMath.throwsIn(0, 1));
    }
}
