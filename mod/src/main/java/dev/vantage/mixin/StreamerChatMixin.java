package dev.vantage.mixin;

import dev.vantage.core.Streamer;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Streamer mode: names and the server address in a chat line become stand-ins as the line is added (display only). */
@Mixin(ChatComponent.class)
public class StreamerChatMixin {
    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component vantage$streamer(Component message) {
        return Streamer.scrub(message);
    }
}
