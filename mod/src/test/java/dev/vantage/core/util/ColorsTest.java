package dev.vantage.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ColorsTest {
    @Test
    void withAlphaReplacesAndClamps() {
        assertEquals(0x803366CC, Colors.withAlpha(0xFF3366CC, 0x80));
        assertEquals(0xFF3366CC, Colors.withAlpha(0x003366CC, 300)); // 300 no longer spills into red
        assertEquals(0x003366CC, Colors.withAlpha(0xFF3366CC, -5));
        assertEquals(0xFF3366CC, Colors.opaque(0x103366CC));
    }

    @Test
    void fadeMultipliesAlphaRoundedAndClamped() {
        assertEquals(0x803366CC, Colors.fade(0xFF3366CC, 0.5f)); // 127.5 rounds to 128
        assertEquals(0xFF3366CC, Colors.fade(0xFF3366CC, 1f));
        assertEquals(0x003366CC, Colors.fade(0xFF3366CC, 0f));
        assertEquals(0xFF3366CC, Colors.fade(0xFF3366CC, 2f));
        assertEquals(0x003366CC, Colors.fade(0xFF3366CC, -1f));
        assertEquals(0x403366CC, Colors.fade(0x803366CC, 0.5f));
    }

    @Test
    void mixIsLinearPerChannelWithAlphaAndRounded() {
        int a = 0xFF000000, b = 0x00FFFFFF;
        assertEquals(a, Colors.mix(a, b, 0f));
        assertEquals(b, Colors.mix(a, b, 1f));
        assertEquals(0x80808080, Colors.mix(a, b, 0.5f)); // 127.5 rounds up in every channel, alpha too
        assertEquals(0x20202020 + 0x00000000, Colors.mix(0x00000000, 0x40404040, 0.5f));
        assertEquals(0xFF0A0A0A, Colors.mix(0xFF000000, 0xFF0A0A0A, 1.0));
        assertEquals(0xFF030303, Colors.mix(0xFF000000, 0xFF0A0A0A, 0.25)); // 2.5 rounds to 3
    }

    @Test
    void mixClampsTOutsideZeroToOne() {
        assertEquals(0xFF102030, Colors.mix(0xFF102030, 0xFFFFFFFF, -3f));
        assertEquals(0xFFFFFFFF, Colors.mix(0xFF102030, 0xFFFFFFFF, 7f));
        assertEquals(0xFF102030, Colors.mix(0xFF102030, 0xFFFFFFFF, Double.NEGATIVE_INFINITY));
    }

    @Test
    void scaleKeepsAlphaCapsAndNeverGoesNegative() {
        assertEquals(0x80204060, Colors.scale(0x80102030, 2f));
        assertEquals(0xFFFF0000, Colors.scale(0xFF900000, 10f));
        assertEquals(0xFF000000, Colors.scale(0xFF102030, -1f));
        assertEquals(0xFF081018, Colors.scale(0xFF102030, 0.5f));
    }

    @Test
    void lightenAndDarkenMoveTowardWhiteAndBlackAndKeepAlpha() {
        assertEquals(0x80FFFFFF, Colors.lighten(0x80102030, 1f));
        assertEquals(0x80102030, Colors.lighten(0x80102030, 0f));
        assertEquals(0x80889098, Colors.lighten(0x80102030, 0.5f)); // 135.5, 143.5, 151.5 round up
        assertEquals(0x80000000, Colors.darken(0x80102030, 1f));
        assertEquals(0x80102030, Colors.darken(0x80102030, 0f));
        assertEquals(0xFF081018, Colors.darken(0xFF102030, 0.5f));
        assertEquals(0xFF102030, Colors.darken(0xFF102030, -4f));
    }

    @Test
    void hsvOfPrimariesGreyAndBlack() {
        assertArrayEquals(new float[] {0f, 1f, 1f}, Colors.toHsv(0xFFFF0000), 1e-6f);
        assertArrayEquals(new float[] {1f / 3, 1f, 1f}, Colors.toHsv(0xFF00FF00), 1e-6f);
        assertArrayEquals(new float[] {2f / 3, 1f, 1f}, Colors.toHsv(0xFF0000FF), 1e-6f);
        assertArrayEquals(new float[] {0f, 0f, 128 / 255f}, Colors.toHsv(0xFF808080), 1e-6f);
        assertArrayEquals(new float[] {0f, 0f, 0f}, Colors.toHsv(0xFF000000), 1e-6f);
        assertEquals(0xFFFF0000, Colors.fromHsv(0f, 1f, 1f, 255));
        assertEquals(0x4000FF00, Colors.fromHsv(1f / 3, 1f, 1f, 0x40));
    }

    @Test
    void hsvRoundTripsAFewColours() {
        for (int c : new int[] {0xFF3366CC, 0xFFCC9933, 0xFF123456, 0xFFFFFFFF, 0xFF010101, 0xFFE91E63}) {
            float[] h = Colors.toHsv(c);
            int back = Colors.fromHsv(h[0], h[1], h[2], 255);
            assertEquals(c, back, "round trip of " + Integer.toHexString(c));
        }
    }

    @Test
    void hexWritesCapitalsWithAlpha() {
        assertEquals("#FF3366CC", Colors.hex(0xFF3366CC));
        assertEquals("#00000001", Colors.hex(1));
        assertEquals("#FFFFFFFF", Colors.hex(-1));
    }

    @Test
    void parseTakesWithAndWithoutHashAndAlpha() {
        assertEquals(0xFF3366CC, Colors.parse("#FF3366CC"));
        assertEquals(0xFF3366CC, Colors.parse("FF3366CC"));
        assertEquals(0x803366CC, Colors.parse("#803366cc"));
        assertEquals(0xFF3366CC, Colors.parse("#3366CC")); // six digits: opaque
        assertEquals(0xFF3366CC, Colors.parse("3366cc"));
        assertEquals(0x000000FF, Colors.parse("#FF")); // short: a plain number
        assertEquals(0xFFFFFFFF, Colors.parse("#FFFFFFFF"));
        assertEquals(0x00000000, Colors.parse("0"));
        assertThrows(NumberFormatException.class, () -> Colors.parse(""));
        assertThrows(NumberFormatException.class, () -> Colors.parse("#"));
        assertThrows(NumberFormatException.class, () -> Colors.parse("#GG3366"));
        assertThrows(NumberFormatException.class, () -> Colors.parse("#123456789"));
        assertEquals(0x80FF3366, Colors.parse(Colors.hex(0x80FF3366)));
    }
}
