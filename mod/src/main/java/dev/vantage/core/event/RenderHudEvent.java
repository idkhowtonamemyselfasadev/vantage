package dev.vantage.core.event;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The HUD is being drawn. Listeners with a higher priority draw first.
 * One object is reused for every firing (it would otherwise be allocated every frame); a listener must not keep it.
 */
public final class RenderHudEvent {
    public static final RenderHudEvent INSTANCE = new RenderHudEvent();
    public static final Events.Channel<RenderHudEvent> CHANNEL = Events.channel(RenderHudEvent.class);
    public GuiGraphics g;
    public DeltaTracker delta;
    /** Ui.depth() when this frame's HUD started: a listener that throws may leave Ui.begin() calls open, which scale everything drawn after it. */
    private int mark;

    static {
        // whichever HUD listener failed (a module's onRender2D, a hudPanel draw, anything else), its open scales are closed before the next one draws
        CHANNEL.afterFault = () -> dev.vantage.core.Ui.unwind(INSTANCE.g, INSTANCE.mark);
    }

    private RenderHudEvent() {}

    public static void fire(GuiGraphics g, DeltaTracker delta) {
        INSTANCE.g = g;
        INSTANCE.delta = delta;
        INSTANCE.mark = dev.vantage.core.Ui.depth();
        CHANNEL.fire(INSTANCE);
        if (dev.vantage.core.Ui.depth() != INSTANCE.mark) dev.vantage.core.Ui.unwind(g, INSTANCE.mark); // a listener that returned early with a scale open
    }
}
