package dev.vantage.core.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The position maths of pinned HUD elements: anchor and offset to a place and back. */
class HudLayoutTest {
    private static final float W = 1280, H = 720, EW = 100, EH = 40;

    @Test
    void everyAnchorGivesItsPlaceAndComesBackAsItself() {
        for (Anchor a : Anchor.values()) {
            float[] at = HudLayout.position(a, 10, 6, EW, EH, W, H);
            HudLayout.Pin back = HudLayout.pinFor(at[0], at[1], EW, EH, W, H);
            assertEquals(a, back.anchor(), a.key);
            assertEquals(10, back.dx(), 0.001, a.key);
            assertEquals(6, back.dy(), 0.001, a.key);
        }
    }

    @Test
    void anOffsetIsTheDistanceInwardFromTheAnchor() {
        assertEquals(10, HudLayout.position(Anchor.TOP_LEFT, 10, 6, EW, EH, W, H)[0], 0.001);
        assertEquals(6, HudLayout.position(Anchor.TOP_LEFT, 10, 6, EW, EH, W, H)[1], 0.001);
        assertEquals(W - EW - 10, HudLayout.position(Anchor.BOTTOM_RIGHT, 10, 6, EW, EH, W, H)[0], 0.001);
        assertEquals(H - EH - 6, HudLayout.position(Anchor.BOTTOM_RIGHT, 10, 6, EW, EH, W, H)[1], 0.001);
        float[] mid = HudLayout.position(Anchor.CENTER, 0, 0, EW, EH, W, H);
        assertEquals((W - EW) / 2, mid[0], 0.001);
        assertEquals((H - EH) / 2, mid[1], 0.001);
        assertEquals((W - EW) / 2 + 20, HudLayout.position(Anchor.TOP, 20, 3, EW, EH, W, H)[0], 0.001, "centre offsets run right");
    }

    @Test
    void aDroppedPieceTakesTheNearestAnchor() {
        assertEquals(Anchor.TOP_LEFT, HudLayout.pinFor(5, 5, EW, EH, W, H).anchor());
        assertEquals(Anchor.TOP_RIGHT, HudLayout.pinFor(W - EW - 5, 5, EW, EH, W, H).anchor());
        assertEquals(Anchor.BOTTOM_LEFT, HudLayout.pinFor(5, H - EH - 5, EW, EH, W, H).anchor());
        assertEquals(Anchor.BOTTOM_RIGHT, HudLayout.pinFor(W - EW - 5, H - EH - 5, EW, EH, W, H).anchor());
        assertEquals(Anchor.CENTER, HudLayout.pinFor((W - EW) / 2 + 30, (H - EH) / 2 - 20, EW, EH, W, H).anchor());
        assertEquals(Anchor.TOP, HudLayout.pinFor((W - EW) / 2, 4, EW, EH, W, H).anchor());
        assertEquals(Anchor.LEFT, HudLayout.pinFor(4, (H - EH) / 2, EW, EH, W, H).anchor());
        assertEquals(Anchor.RIGHT, HudLayout.pinFor(W - EW - 4, (H - EH) / 2, EW, EH, W, H).anchor());
        assertEquals(Anchor.BOTTOM, HudLayout.pinFor((W - EW) / 2, H - EH - 4, EW, EH, W, H).anchor());
    }

    @Test
    void aPieceBottomRightKeepsItsDistanceWhenTheScreenChanges() {
        HudLayout.Pin pin = HudLayout.pinFor(W - EW - 12, H - EH - 8, EW, EH, W, H);
        for (float[] screen : new float[][] {{1680, 720}, {960, 720}, {1280, 450}, {800, 450}}) {
            float[] at = HudLayout.position(pin.anchor(), pin.dx(), pin.dy(), EW, EH, screen[0], screen[1]);
            assertEquals(12, screen[0] - EW - at[0], 0.001, "distance from the right edge on " + screen[0] + "x" + screen[1]);
            assertEquals(8, screen[1] - EH - at[1], 0.001, "distance from the bottom edge on " + screen[0] + "x" + screen[1]);
        }
    }

    @Test
    void aPieceIsClampedIntoTheScreen() {
        float[] at = HudLayout.position(Anchor.BOTTOM_RIGHT, -50, -50, EW, EH, W, H);
        assertEquals(W - EW, at[0], 0.001);
        assertEquals(H - EH, at[1], 0.001);
        at = HudLayout.position(Anchor.TOP_LEFT, -30, 9999, EW, EH, W, H);
        assertEquals(0, at[0], 0.001);
        assertEquals(H - EH, at[1], 0.001);
        assertEquals(0, HudLayout.position(Anchor.RIGHT, 5, 5, 2000, 900, W, H)[0], 0.001, "a piece larger than the screen sits at 0");
    }
}
