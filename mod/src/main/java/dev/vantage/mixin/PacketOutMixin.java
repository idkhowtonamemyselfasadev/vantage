package dev.vantage.mixin;

import dev.vantage.core.event.PacketOutEvent;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires {@link PacketOutEvent}. Target: Connection.send(Packet, ChannelFutureListener, boolean), the method every other send
 * overload ends in (1.21.11). The integrated server's connections are the same class; they send client-bound packets and are
 * left out by the direction check.
 */
@Mixin(Connection.class)
public abstract class PacketOutMixin {
    @Shadow public abstract PacketFlow getSending();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void vantage$packetOut(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (getSending() != PacketFlow.SERVERBOUND) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.isSameThread()) PacketOutEvent.fire(packet);
    }
}
