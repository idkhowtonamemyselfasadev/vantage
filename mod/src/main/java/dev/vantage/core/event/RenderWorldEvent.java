package dev.vantage.core.event;

import dev.vantage.render.R3;

/**
 * The world has been drawn; listeners add their shapes to the frame's {@link R3}, which is flushed once afterwards.
 * One object is reused for every firing (it would otherwise be allocated every frame); a listener must not keep it.
 */
public final class RenderWorldEvent {
    public static final RenderWorldEvent INSTANCE = new RenderWorldEvent();
    public static final Events.Channel<RenderWorldEvent> CHANNEL = Events.channel(RenderWorldEvent.class);
    public R3 r;

    private RenderWorldEvent() {}

    public static void fire(R3 r) {
        INSTANCE.r = r;
        CHANNEL.fire(INSTANCE);
    }
}
