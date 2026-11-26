package dev.vantage.core.util;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

/**
 * Chunk arithmetic. The one thing this adds over the game is the {@code double} form: a chunk of a coordinate with a fraction is
 * floor-then-shift. Casting to {@code int} first truncates toward zero and names the wrong chunk for negative coordinates (B-188:
 * -16.5 casts to -16, which is chunk -1, but the block is -17 and the chunk is -2). Keys are vanilla {@link ChunkPos#asLong}
 * (x in the low 32 bits, z in the high 32). Do not feed this class the other key layouts the code base has (R3, BaseLogic).
 */
public final class ChunkMath {
    /** Chunk of a block coordinate: floor division by 16, so block -1 and -16 are chunk -1, block -17 is chunk -2. */
    public static int chunk(int block) {
        return SectionPos.blockToSectionCoord(block);
    }

    /** Chunk of a world coordinate with a fraction: floor first, then divide by 16 (-0.5 is chunk -1, 15.99 is chunk 0). */
    public static int chunk(double coord) {
        return SectionPos.blockToSectionCoord(Mth.floor(coord));
    }

    /** First (lowest) block coordinate of a chunk on one axis: chunk * 16. */
    public static int minBlock(int chunk) {
        return SectionPos.sectionToBlockCoord(chunk);
    }

    /** Last (highest) block coordinate of a chunk on one axis: chunk * 16 + 15. */
    public static int maxBlock(int chunk) {
        return SectionPos.sectionToBlockCoord(chunk, 15);
    }

    /** Block coordinate of the middle of a chunk on one axis: chunk * 16 + 8 (the same as vanilla {@code getMiddleBlockX}). */
    public static int centre(int chunk) {
        return SectionPos.sectionToBlockCoord(chunk, 8);
    }

    /** The key of chunk (cx, cz), vanilla layout. */
    public static long key(int cx, int cz) {
        return ChunkPos.asLong(cx, cz);
    }

    /** The key of the chunk that holds the world point (x, z); the coordinates may have fractions. */
    public static long keyAt(double x, double z) {
        return ChunkPos.asLong(chunk(x), chunk(z));
    }

    /** Chunk x of a key made by {@link #key}. */
    public static int keyX(long key) {
        return ChunkPos.getX(key);
    }

    /** Chunk z of a key made by {@link #key}. */
    public static int keyZ(long key) {
        return ChunkPos.getZ(key);
    }

    /** Chessboard distance between two chunks in chunks: the larger of the x and z differences, never negative. */
    public static int chunkDistance(int ax, int az, int bx, int bz) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz));
    }

    /** Horizontal distance in blocks from the point (x, z) to the nearest point of chunk (cx, cz); 0 inside the chunk. */
    public static double distanceToChunk(double x, double z, int cx, int cz) {
        return Math.sqrt(distanceToChunkSq(x, z, cx, cz));
    }

    /** Squared horizontal distance in blocks from (x, z) to the chunk's square (it spans minBlock to maxBlock + 1); 0 inside. */
    public static double distanceToChunkSq(double x, double z, int cx, int cz) {
        double dx = gap(x, minBlock(cx), minBlock(cx) + 16.0), dz = gap(z, minBlock(cz), minBlock(cz) + 16.0);
        return dx * dx + dz * dz;
    }

    private static double gap(double v, double lo, double hi) {
        return v < lo ? lo - v : v > hi ? v - hi : 0;
    }

    private ChunkMath() {}
}
