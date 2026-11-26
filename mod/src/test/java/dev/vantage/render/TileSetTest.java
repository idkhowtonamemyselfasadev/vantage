package dev.vantage.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Preparing chunk tiles for drawing: neighbours merge into rectangles, only the outside border is kept, nothing is lost or doubled. */
class TileSetTest {
    /** ChunkPos touches the game's registries when it is first used, so they are started once (no game window needed). */
    @BeforeAll
    static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static long[] keys(int... xz) {
        long[] k = new long[xz.length / 2];
        for (int i = 0; i < k.length; i++) k[i] = ChunkPos.asLong(xz[i * 2], xz[i * 2 + 1]);
        return k;
    }

    /** The area the rectangles cover, in tiles: must equal the number of tiles, or something overlaps or is missing. */
    private static int area(R3.TileSet s) {
        int a = 0;
        for (int i = 0; i < s.rects.length; i += 4) a += (s.rects[i + 2] - s.rects[i]) * (s.rects[i + 3] - s.rects[i + 1]);
        return a;
    }

    private static int borderLength(R3.TileSet s) {
        int a = 0;
        for (int i = 0; i < s.border.length; i += 4) a += Math.abs(s.border[i + 2] - s.border[i]) + Math.abs(s.border[i + 3] - s.border[i + 1]);
        return a;
    }

    @Test
    void oneTileIsOneRectangleWithFourSides() {
        R3.TileSet s = R3.tileSet(keys(3, -2), 1);
        assertEquals(1, s.tiles);
        assertEquals(1, s.rectangles());
        assertEquals(4, s.borderSegments());
        assertEquals(1, area(s));
        assertEquals(4, borderLength(s));
        assertEquals(3, s.rects[0]);
        assertEquals(-2, s.rects[1]);
    }

    @Test
    void aSmallBlockMergesIntoOneRectangleAndItsBorderIsOnlyTheOutside() {
        // 4 x 3 tiles
        long[] k = new long[12];
        int n = 0;
        for (int x = 0; x < 4; x++) for (int z = 0; z < 3; z++) k[n++] = ChunkPos.asLong(x, z);
        R3.TileSet s = R3.tileSet(k, n);
        assertEquals(12, s.tiles);
        assertEquals(1, s.rectangles());
        assertEquals(12, area(s));
        assertEquals(4, s.borderSegments(), "four sides, each one segment");
        assertEquals(14, borderLength(s), "the perimeter of 4 x 3");
    }

    @Test
    void tenThousandTilesBecomeAFewHundredRectangles() {
        long[] k = new long[10_000];
        int n = 0;
        for (int x = -50; x < 50; x++) for (int z = -50; z < 50; z++) k[n++] = ChunkPos.asLong(x, z);
        R3.TileSet s = R3.tileSet(k, n);
        assertEquals(10_000, s.tiles);
        assertEquals(10_000, area(s));
        // 100 x 100 in pieces of at most 8 x 8: 13 x 13 pieces
        assertEquals(169, s.rectangles());
        assertEquals(400, borderLength(s), "only the outside of the square");
        assertTrue(s.borderSegments() <= 4 * 13, "border segments " + s.borderSegments());
    }

    @Test
    void anLShapeAHoleAndNegativeCoordinatesKeepExactAreaAndBorder() {
        // an L: three in a row and two more going down from the first; then a ring of 8 around a hole, far away at negative coordinates
        R3.TileSet l = R3.tileSet(keys(0, 0, 1, 0, 2, 0, 0, 1, 0, 2), 5);
        assertEquals(5, area(l));
        assertEquals(12, borderLength(l));
        long[] ring = keys(-10, -10, -9, -10, -8, -10, -10, -9, -8, -9, -10, -8, -9, -8, -8, -8);
        R3.TileSet r = R3.tileSet(ring, ring.length);
        assertEquals(8, r.tiles);
        assertEquals(8, area(r));
        assertEquals(16, borderLength(r), "12 outside plus 4 around the hole");
    }

    @Test
    void repeatedKeysCountOnceAndTheCountArgumentLimitsWhatIsRead() {
        R3.TileSet s = R3.tileSet(keys(1, 1, 1, 1, 2, 1, 1, 1), 4);
        assertEquals(2, s.tiles);
        assertEquals(2, area(s));
        R3.TileSet first = R3.tileSet(keys(1, 1, 5, 5, 9, 9), 1);
        assertEquals(1, first.tiles);
        R3.TileSet none = R3.tileSet(new long[0], 0);
        assertEquals(0, none.tiles);
        assertEquals(0, none.rectangles());
    }

    @Test
    void tilesThatOnlyTouchAtACornerStaySeparate() {
        R3.TileSet s = R3.tileSet(keys(0, 0, 1, 1), 2);
        assertEquals(2, s.rectangles());
        assertEquals(8, borderLength(s));
    }

    @Test
    void theFlagHelpersAreWhatTheDocsSay() {
        assertEquals(0x80123456, R3.opacity(0x123456, 0.5));
        assertEquals(1f, R3.fadeAt(64));
        assertEquals(0.45f, R3.fadeAt(256), 1e-4f);
        assertTrue(R3.fadeAt(160) < 1f && R3.fadeAt(160) > 0.45f);
    }
}
