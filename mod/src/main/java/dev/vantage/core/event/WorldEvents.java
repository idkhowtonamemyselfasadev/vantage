package dev.vantage.core.event;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Turns "the client's world object changed" into exactly one of the three world events. Kept apart from VantageClient so a unit test can
 * check the decision without a game.
 */
public final class WorldEvents {
    private WorldEvents() {}

    /** No world to a world fires the join, a world to none the leave, a world to another world the dimension change. */
    public static void fire(boolean hadWorld, boolean hasWorld, ClientLevel level) {
        if (!hasWorld) WorldLeaveEvent.fire();
        else if (!hadWorld) WorldJoinEvent.fire(level);
        else DimensionChangeEvent.fire(level);
    }
}
