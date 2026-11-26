package dev.vantage.mixin;

import dev.vantage.core.event.PacketHandledEvent;
import dev.vantage.core.event.PacketInEvent;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires {@link PacketInEvent} before and {@link PacketHandledEvent} after a server packet is handled. Target: the small record
 * the game queues for each packet that has to run on the main thread (PacketProcessor.ListenerAndPacket.handle, 1.21.11). Why
 * here: it is the one place every server packet passes through on the client thread. The integrated server uses the same class on
 * its own thread with a server-side listener, which the type check leaves out.
 *
 * The server sends some packets in a bundle (an entity together with its data and equipment). The game handles a bundle's packets
 * one after the other without queueing them again, so they are announced from here: all of them before the bundle is handled, all
 * of them after.
 */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public class PacketInMixin {
    @Shadow @Final private PacketListener listener;
    @Shadow @Final private Packet<?> packet;

    @Inject(method = "handle", at = @At("HEAD"))
    private void vantage$packetIn(CallbackInfo ci) {
        if (!(listener instanceof ClientGamePacketListener) || PacketInEvent.CHANNEL.size() == 0) return;
        if (packet instanceof ClientboundBundlePacket bundle) for (Packet<?> sub : bundle.subPackets()) PacketInEvent.fire(sub);
        else PacketInEvent.fire(packet);
    }

    @Inject(method = "handle", at = @At("TAIL"))
    private void vantage$packetHandled(CallbackInfo ci) {
        if (!(listener instanceof ClientGamePacketListener) || PacketHandledEvent.CHANNEL.size() == 0) return;
        if (packet instanceof ClientboundBundlePacket bundle) for (Packet<?> sub : bundle.subPackets()) PacketHandledEvent.fire(sub);
        else PacketHandledEvent.fire(packet);
    }
}
