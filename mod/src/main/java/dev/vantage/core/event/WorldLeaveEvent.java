package dev.vantage.core.event;

/**
 * The world is gone (disconnected or closed). Fired after the client dropped it.
 * One object is reused for every firing (it would otherwise be allocated every leave); a listener must not keep it.
 */
public final class WorldLeaveEvent {
    public static final WorldLeaveEvent INSTANCE = new WorldLeaveEvent();
    public static final Events.Channel<WorldLeaveEvent> CHANNEL = Events.channel(WorldLeaveEvent.class);

    private WorldLeaveEvent() {}

    public static void fire() {
        CHANNEL.fire(INSTANCE);
    }
}
