package dev.vantage.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChunkMathTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void blockToChunkFloorsForNegatives() {
        assertEquals(0, ChunkMath.chunk(0));
        assertEquals(0, ChunkMath.chunk(15));
        assertEquals(1, ChunkMath.chunk(16));
        assertEquals(-1, ChunkMath.chunk(-1));
        assertEquals(-1, ChunkMath.chunk(-16));
        assertEquals(-2, ChunkMath.chunk(-17));
    }

    @Test
    void doubleToChunkFloorsFirstWhichIsB188() {
        assertEquals(-1, ChunkMath.chunk(-0.5));
        assertEquals(-1, ChunkMath.chunk(-1.0));
        assertEquals(-1, ChunkMath.chunk(-16.0));
        assertEquals(-2, ChunkMath.chunk(-16.5)); // (int) -16.5 >> 4 is -1: the bug
        assertEquals(-2, ChunkMath.chunk(-17.0));
        assertEquals(0, ChunkMath.chunk(0.0));
        assertEquals(0, ChunkMath.chunk(15.99));
        assertEquals(1, ChunkMath.chunk(16.0));
    }

    @Test
    void blockBoundsOfAChunk() {
        assertEquals(0, ChunkMath.minBlock(0));
        assertEquals(15, ChunkMath.maxBlock(0));
        assertEquals(8, ChunkMath.centre(0));
        assertEquals(-16, ChunkMath.minBlock(-1));
        assertEquals(-1, ChunkMath.maxBlock(-1));
        assertEquals(-8, ChunkMath.centre(-1));
        assertEquals(new ChunkPos(-3, 5).getMiddleBlockX(), ChunkMath.centre(-3));
        assertEquals(new ChunkPos(-3, 5).getMiddleBlockZ(), ChunkMath.centre(5));
    }

    @Test
    void keyRoundTripsNegativeCoordinatesAndIsTheVanillaLayout() {
        long k = ChunkMath.key(-123456, -98765);
        assertEquals(-123456, ChunkMath.keyX(k));
        assertEquals(-98765, ChunkMath.keyZ(k));
        assertEquals(ChunkPos.asLong(-5, 9), ChunkMath.key(-5, 9));
        assertEquals(ChunkMath.key(-1, -2), ChunkMath.keyAt(-0.5, -16.5));
        assertEquals(ChunkMath.key(0, 0), ChunkMath.keyAt(15.9, 0.0));
    }

    @Test
    void chunkDistanceIsTheLargerAxis() {
        assertEquals(0, ChunkMath.chunkDistance(2, 2, 2, 2));
        assertEquals(5, ChunkMath.chunkDistance(-2, 0, 3, 1));
        assertEquals(4, ChunkMath.chunkDistance(0, 1, 1, -3));
    }

    @Test
    void distanceToAChunkIsZeroInsideAndMeasuredToTheEdgeOrCorner() {
        assertEquals(0, ChunkMath.distanceToChunk(8, 8, 0, 0), 1e-9);
        assertEquals(0, ChunkMath.distanceToChunk(16, 16, 0, 0), 1e-9); // on the far edge
        assertEquals(4, ChunkMath.distanceToChunk(20, 8, 0, 0), 1e-9);
        assertEquals(5, ChunkMath.distanceToChunk(19, 20, 0, 0), 1e-9); // 3 and 4 past the corner
        assertEquals(5, ChunkMath.distanceToChunk(-3, -4, 0, 0), 1e-9);
        assertEquals(25, ChunkMath.distanceToChunkSq(-3, -4, 0, 0), 1e-9);
        assertEquals(0.5, ChunkMath.distanceToChunk(-16.5, -20, -1, -2) , 1e-9); // chunk (-1,-2) spans x -16..0, z -32..-16
    }
}
