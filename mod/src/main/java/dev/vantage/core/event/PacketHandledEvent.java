package dev.vantage.core.event;

import net.minecraft.network.protocol.Packet;

/**
 * The game has just finished handling a packet from the server, on the client thread: what the packet said is now part of the
 * client world. Most listeners want this one; {@link PacketInEvent} is for the few that must see the world as it was before
 * (an entity that the packet removes, an item that it picks up).
 * One object is reused for every firing; a listener must not keep it.
 */
public final class PacketHandledEvent {
    public static final PacketHandledEvent INSTANCE = new PacketHandledEvent();
    public static final Events.Channel<PacketHandledEvent> CHANNEL = Events.channel(PacketHandledEvent.class);
    public Packet<?> packet;

    private PacketHandledEvent() {}

    public static void fire(Packet<?> packet) {
        if (CHANNEL.size() == 0) return;
        INSTANCE.packet = packet;
        CHANNEL.fire(INSTANCE);
    }
}
