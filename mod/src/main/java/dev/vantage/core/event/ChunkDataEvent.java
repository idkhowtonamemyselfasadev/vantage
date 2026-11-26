package dev.vantage.core.event;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Chunk data arrived from the server (fired after {@link ChunkLoadEvent} for a new chunk, and again when the server re-sent the chunk or a burst of block changes rewrote it).
 * One object is reused for every firing (it would otherwise be allocated every chunk); a listener must not keep it.
 */
public final class ChunkDataEvent {
    public static final ChunkDataEvent INSTANCE = new ChunkDataEvent();
    public static final Events.Channel<ChunkDataEvent> CHANNEL = Events.channel(ChunkDataEvent.class);
    public LevelChunk chunk;

    private ChunkDataEvent() {}

    public static void fire(LevelChunk chunk) {
        INSTANCE.chunk = chunk;
        CHANNEL.fire(INSTANCE);
    }
}
