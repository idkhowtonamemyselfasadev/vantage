package dev.vantage.core.chunk;

import java.util.List;

/**
 * What {@link Chunks#scanBlockEntities} hands a worker: the chunk's position, the data generation at the moment it was taken and the
 * block entities as immutable records. Nothing in it is a live game object and no section was copied, so taking it costs one list
 * lookup on the client thread. Use {@link Chunks#scan} (and its {@link ChunkSnapshot}) only when the scanner has to read blocks.
 */
public record ChunkBlockEntities(int cx, int cz, int generation, List<BlockEntityInfo> blockEntities) {
    public ChunkBlockEntities {
        blockEntities = List.copyOf(blockEntities); // already immutable when it comes from the service: copyOf then returns the same list
    }
}
