package dev.vantage.core.event;

import net.minecraft.network.protocol.Packet;

/**
 * A packet from the server is about to be handled, on the client thread (the game has already moved it there from the network
 * thread). Read only: a listener cannot stop or change the packet.
 * One object is reused for every firing (it would otherwise be allocated for every packet); a listener must not keep it.
 */
public final class PacketInEvent {
    public static final PacketInEvent INSTANCE = new PacketInEvent();
    public static final Events.Channel<PacketInEvent> CHANNEL = Events.channel(PacketInEvent.class);
    public Packet<?> packet;

    private PacketInEvent() {}

    public static void fire(Packet<?> packet) {
        if (CHANNEL.size() == 0) return; // nobody listens: most sessions, so the hook costs one field read per packet
        INSTANCE.packet = packet;
        CHANNEL.fire(INSTANCE);
    }
}
