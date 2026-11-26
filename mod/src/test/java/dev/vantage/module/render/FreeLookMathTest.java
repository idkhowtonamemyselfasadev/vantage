package dev.vantage.module.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Free Look's plain arithmetic: wrap, clamp, mouse turn and the orbit position. */
class FreeLookMathTest {
    private static final double EPS = 1e-9;

    @Test
    void wrapKeepsAnglesInHalfOpenRange() {
        assertEquals(0f, FreeLookMath.wrapDegrees(0f));
        assertEquals(-180f, FreeLookMath.wrapDegrees(180f));
        assertEquals(-180f, FreeLookMath.wrapDegrees(-180f));
        assertEquals(10f, FreeLookMath.wrapDegrees(370f), 1e-4);
        assertEquals(-10f, FreeLookMath.wrapDegrees(-370f), 1e-4);
        assertEquals(170f, FreeLookMath.wrapDegrees(-190f), 1e-4);
        assertEquals(0f, FreeLookMath.wrapDegrees(720f), 1e-4);
        for (float a = -1000; a < 1000; a += 13.7f) {
            float w = FreeLookMath.wrapDegrees(a);
            assertTrue(w >= -180f && w < 180f, "wrap " + a + " -> " + w);
        }
    }

    @Test
    void pitchIsClampedToStraightUpAndDown() {
        assertEquals(90f, FreeLookMath.clampPitch(120f));
        assertEquals(-90f, FreeLookMath.clampPitch(-500f));
        assertEquals(12.5f, FreeLookMath.clampPitch(12.5f));
        assertEquals(90f, FreeLookMath.turnPitch(89f, 1000));
        assertEquals(-90f, FreeLookMath.turnPitch(-89f, -1000));
    }

    @Test
    void mouseTurnUsesVanillaFactor() {
        assertEquals(15f, FreeLookMath.turnYaw(0f, 100), 1e-4);
        assertEquals(-15f, FreeLookMath.turnYaw(0f, -100), 1e-4);
        assertEquals(-170f, FreeLookMath.turnYaw(175f, 100), 1e-3); // wraps past 180
        assertEquals(7.5f, FreeLookMath.turnPitch(0f, 50), 1e-4);
    }

    @Test
    void lookMatchesMinecraftConventions() {
        double[] s = FreeLookMath.look(0f, 0f); // yaw 0 faces +z
        assertEquals(0, s[0], EPS); assertEquals(0, s[1], EPS); assertEquals(1, s[2], EPS);
        double[] w = FreeLookMath.look(90f, 0f); // yaw 90 faces -x
        assertEquals(-1, w[0], EPS); assertEquals(0, w[2], EPS);
        double[] down = FreeLookMath.look(0f, 90f); // pitch 90 looks down
        assertEquals(-1, down[1], EPS);
        for (float y = -180; y < 180; y += 37) for (float p = -90; p <= 90; p += 29) {
            double[] l = FreeLookMath.look(y, p);
            assertEquals(1.0, Math.sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]), 1e-9);
        }
    }

    @Test
    void orbitSitsBehindThePivot() {
        double[] a = FreeLookMath.orbit(10, 64, -3, 0f, 0f, 4); // looking +z: camera 4 blocks toward -z
        assertEquals(10, a[0], EPS); assertEquals(64, a[1], EPS); assertEquals(-7, a[2], EPS);
        double[] b = FreeLookMath.orbit(0, 0, 0, 180f, 0f, 4); // looking -z: camera at +z
        assertEquals(0, b[0], 1e-9); assertEquals(4, b[2], 1e-9);
        double[] c = FreeLookMath.orbit(0, 0, 0, 0f, 90f, 4); // looking straight down: camera straight above
        assertEquals(4, c[1], 1e-9); assertEquals(0, c[2], 1e-9);
        double[] z = FreeLookMath.orbit(1, 2, 3, 33f, -20f, 0); // distance 0 is the pivot
        assertEquals(1, z[0], EPS); assertEquals(2, z[1], EPS); assertEquals(3, z[2], EPS);
        for (float y = -180; y < 180; y += 41) for (float p = -90; p <= 90; p += 31) {
            double[] o = FreeLookMath.orbit(5, 6, 7, y, p, 4);
            double d = Math.sqrt(Math.pow(o[0] - 5, 2) + Math.pow(o[1] - 6, 2) + Math.pow(o[2] - 7, 2));
            assertEquals(4.0, d, 1e-9);
        }
    }
}
