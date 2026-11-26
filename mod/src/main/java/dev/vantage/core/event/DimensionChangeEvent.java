package dev.vantage.core.event;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The player moved to another dimension of the same session: the old world object was replaced by a new one.
 * One object is reused for every firing (it would otherwise be allocated every dimension change); a listener must not keep it.
 */
public final class DimensionChangeEvent {
    public static final DimensionChangeEvent INSTANCE = new DimensionChangeEvent();
    public static final Events.Channel<DimensionChangeEvent> CHANNEL = Events.channel(DimensionChangeEvent.class);
    public ClientLevel level;

    private DimensionChangeEvent() {}

    public static void fire(ClientLevel level) {
        INSTANCE.level = level;
        CHANNEL.fire(INSTANCE);
    }
}
