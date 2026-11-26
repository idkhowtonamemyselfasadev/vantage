package dev.vantage.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CompassTest {
    @Test
    void theEightDirections() {
        assertEquals("S", Compass.name(0));
        assertEquals("SW", Compass.name(45));
        assertEquals("W", Compass.name(90));
        assertEquals("NW", Compass.name(135));
        assertEquals("N", Compass.name(180));
        assertEquals("N", Compass.name(-180));
        assertEquals("NE", Compass.name(-135));
        assertEquals("E", Compass.name(-90));
        assertEquals("SE", Compass.name(-45));
    }

    @Test
    void theEightBoundariesBelongToTheNextWordUpward() {
        assertEquals("S", Compass.name(22.4));
        assertEquals("SW", Compass.name(22.5));
        assertEquals("SW", Compass.name(67.4));
        assertEquals("W", Compass.name(67.5));
        assertEquals("W", Compass.name(112.4));
        assertEquals("NW", Compass.name(112.5));
        assertEquals("NW", Compass.name(157.4));
        assertEquals("N", Compass.name(157.5));
        assertEquals("NE", Compass.name(-157.4));
        assertEquals("N", Compass.name(-157.6));
        assertEquals("NE", Compass.name(-157.5)); // -157.5 rounds to -3, a half goes up
        assertEquals("S", Compass.name(-22.5));
        assertEquals("SE", Compass.name(-22.6));
        assertEquals("E", Compass.name(-67.6));
        assertEquals("SE", Compass.name(-67.5));
    }

    @Test
    void yawOutsideMinus180To180Wraps() {
        assertEquals("S", Compass.name(360));
        assertEquals("S", Compass.name(720));
        assertEquals("W", Compass.name(450));
        assertEquals("E", Compass.name(-450));
        assertEquals("N", Compass.name(540));
        assertEquals("N", Compass.name(-900));
        assertEquals("S", Compass.name(1e12 - 1e12 % 360));
        assertEquals("S", Compass.name(Double.NaN));
    }

    @Test
    void nameFromAnOffsetUsesTheBearing() {
        assertEquals("S", Compass.name(0, 10));
        assertEquals("N", Compass.name(0, -10));
        assertEquals("W", Compass.name(-10, 0));
        assertEquals("E", Compass.name(10, 0));
        assertEquals("NE", Compass.name(10, -10));
        assertEquals("SW", Compass.name(-10, 10));
        assertEquals("S", Compass.name(0, 0));
    }

    @Test
    void arrowAngleIsBearingMinusViewYawWrapped() {
        assertEquals(0, Compass.arrowAngle(30, 30), 1e-9);
        assertEquals(90, Compass.arrowAngle(90, 0), 1e-9);
        assertEquals(-90, Compass.arrowAngle(0, 90), 1e-9);
        assertEquals(-20, Compass.arrowAngle(170, -170), 1e-9);
        assertEquals(40, Compass.arrowAngle(-170, 150), 1e-9);
        assertEquals(10, Compass.arrowAngle(5, -365), 1e-9);
    }
}
