package dev.vantage.core.event;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The player is in a world where there was none before (joined a server or opened a single-player world).
 * One object is reused for every firing (it would otherwise be allocated every join); a listener must not keep it.
 */
public final class WorldJoinEvent {
    public static final WorldJoinEvent INSTANCE = new WorldJoinEvent();
    public static final Events.Channel<WorldJoinEvent> CHANNEL = Events.channel(WorldJoinEvent.class);
    public ClientLevel level;

    private WorldJoinEvent() {}

    public static void fire(ClientLevel level) {
        INSTANCE.level = level;
        CHANNEL.fire(INSTANCE);
    }
}
