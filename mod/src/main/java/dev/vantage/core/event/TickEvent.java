package dev.vantage.core.event;

/**
 * The end of a client tick while a world and a player exist.
 * One object is reused for every firing (it would otherwise be allocated every tick); a listener must not keep it.
 */
public final class TickEvent {
    public static final TickEvent INSTANCE = new TickEvent();
    public static final Events.Channel<TickEvent> CHANNEL = Events.channel(TickEvent.class);

    private TickEvent() {}

    public static void fire() {
        CHANNEL.fire(INSTANCE);
    }
}
