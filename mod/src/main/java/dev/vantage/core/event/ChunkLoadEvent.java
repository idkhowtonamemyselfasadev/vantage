package dev.vantage.core.event;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A chunk became part of the client world.
 * One object is reused for every firing (it would otherwise be allocated every chunk); a listener must not keep it.
 */
public final class ChunkLoadEvent {
    public static final ChunkLoadEvent INSTANCE = new ChunkLoadEvent();
    public static final Events.Channel<ChunkLoadEvent> CHANNEL = Events.channel(ChunkLoadEvent.class);
    public LevelChunk chunk;

    private ChunkLoadEvent() {}

    public static void fire(LevelChunk chunk) {
        INSTANCE.chunk = chunk;
        CHANNEL.fire(INSTANCE);
    }
}
