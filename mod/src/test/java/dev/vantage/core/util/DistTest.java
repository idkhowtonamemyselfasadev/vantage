package dev.vantage.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DistTest {
    @Test
    void twoDimensionalDistanceAndSquare() {
        assertEquals(5, Dist.dist2(3, 4), 1e-12);
        assertEquals(25, Dist.dist2Sq(-3, 4), 1e-12);
        assertEquals(5, Dist.dist2(-1, -1, 2, 3), 1e-12);
        assertEquals(0, Dist.dist2(0, 0), 0);
    }

    @Test
    void threeDimensionalDistanceAndSquare() {
        assertEquals(3, Dist.dist3(1, 2, 2), 1e-12);
        assertEquals(9, Dist.dist3Sq(-1, 2, -2), 1e-12);
        assertEquals(3, Dist.dist3(0, 0, 0, -1, -2, 2), 1e-12);
    }

    @Test
    void distanceToABoxFromInsideFaceEdgeAndCorner() {
        // box from (0,0,0) to (2,2,2)
        assertEquals(0, Dist.toBox(1, 1, 1, 0, 0, 0, 2, 2, 2), 0);
        assertEquals(0, Dist.toBox(2, 1, 1, 0, 0, 0, 2, 2, 2), 0); // on a face
        assertEquals(3, Dist.toBox(5, 1, 1, 0, 0, 0, 2, 2, 2), 1e-12); // beside a face
        assertEquals(5, Dist.toBox(5, 6, 1, 0, 0, 0, 2, 2, 2), 1e-12); // beside an edge: 3 and 4
        assertEquals(3, Dist.toBox(-1, -2, 4, 0, 0, 0, 2, 2, 2), 1e-12); // past a corner: 1, 2, 2
        assertEquals(9, Dist.toBoxSq(-1, -2, 4, 0, 0, 0, 2, 2, 2), 1e-12);
        assertEquals(1, Dist.toBox(-5, -4, -5, -6, -6, -6, -4, -5, -4), 1e-12); // negative box, one block above it
    }

    @Test
    void bearingFollowsTheYawConvention() {
        assertEquals(0, Dist.bearing(0, 5), 1e-9); // +z is south
        assertEquals(90, Dist.bearing(-5, 0), 1e-9); // -x is west
        assertEquals(-90, Dist.bearing(5, 0), 1e-9); // +x is east
        assertEquals(180, Math.abs(Dist.bearing(0, -5)), 1e-9);
        assertEquals(-45, Dist.bearing(1, 1), 1e-9);
        assertEquals(0, Dist.bearing(0, 0), 1e-9);
        assertEquals(135, Dist.bearing(10, 10, 5, 5), 1e-9);
        assertEquals(90, Dist.bearing(1, 1, 0, 1), 1e-9);
    }

    @Test
    void angleDiffIsTheShortestTurn() {
        assertEquals(20, Dist.angleDiff(170, -170), 1e-9);
        assertEquals(-20, Dist.angleDiff(-170, 170), 1e-9);
        assertEquals(0, Dist.angleDiff(30, 30), 1e-9);
        assertEquals(10, Dist.angleDiff(0, 370), 1e-9);
    }
}
