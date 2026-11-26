package dev.vantage.core.event;

/**
 * A chunk left the client world.
 * One object is reused for every firing (it would otherwise be allocated every chunk); a listener must not keep it.
 */
public final class ChunkUnloadEvent {
    public static final ChunkUnloadEvent INSTANCE = new ChunkUnloadEvent();
    public static final Events.Channel<ChunkUnloadEvent> CHANNEL = Events.channel(ChunkUnloadEvent.class);
    public int cx;
    public int cz;

    private ChunkUnloadEvent() {}

    public static void fire(int cx, int cz) {
        INSTANCE.cx = cx;
        INSTANCE.cz = cz;
        CHANNEL.fire(INSTANCE);
    }
}
