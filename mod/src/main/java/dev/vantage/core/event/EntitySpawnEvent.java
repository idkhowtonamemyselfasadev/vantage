package dev.vantage.core.event;

import net.minecraft.world.entity.Entity;

/**
 * An entity was added to the client world (it came into view, or it was just created).
 * One object is reused for every firing; a listener must not keep it.
 */
public final class EntitySpawnEvent {
    public static final EntitySpawnEvent INSTANCE = new EntitySpawnEvent();
    public static final Events.Channel<EntitySpawnEvent> CHANNEL = Events.channel(EntitySpawnEvent.class);
    public Entity entity;

    private EntitySpawnEvent() {}

    public static void fire(Entity entity) {
        if (CHANNEL.size() == 0) return;
        INSTANCE.entity = entity;
        CHANNEL.fire(INSTANCE);
    }
}
