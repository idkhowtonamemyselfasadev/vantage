package dev.vantage.core.event;

import net.minecraft.network.protocol.Packet;

/**
 * The client is about to send a packet to the server. Fired only for sends that happen on the client thread (nearly all of
 * them; the few replies the network thread sends by itself are skipped rather than handed to listeners on the wrong thread).
 * Read only. One object is reused for every firing; a listener must not keep it.
 */
public final class PacketOutEvent {
    public static final PacketOutEvent INSTANCE = new PacketOutEvent();
    public static final Events.Channel<PacketOutEvent> CHANNEL = Events.channel(PacketOutEvent.class);
    public Packet<?> packet;

    private PacketOutEvent() {}

    public static void fire(Packet<?> packet) {
        if (CHANNEL.size() == 0) return;
        INSTANCE.packet = packet;
        CHANNEL.fire(INSTANCE);
    }
}
