package dev.vantage.core.chunk;

/** What the queue and the scan pipeline ask the rest of the service when they decide whether work is still wanted. */
public interface Rules {
    /** False for a module that is switched off (work for it is dropped). */
    boolean ownerOn(Object owner);

    boolean loaded(long chunkKey);

    /** The chunk's generation now, or {@link ChunkIndex#NONE}. */
    int generation(long chunkKey);

    /** The number of the current world. */
    int world();

    /** A job or a result handler threw, or a scanner failed again and again. The service never lets that reach the game. */
    void failed(Object owner, String kind, Throwable error);

    /** A scanner threw for one chunk: a problem to report (and count), not a reason to fault the owner. See {@link ScanPipeline#FAULT_AFTER}. */
    default void scanFailed(Object owner, String kind, long chunkKey, Throwable error) {}
}
