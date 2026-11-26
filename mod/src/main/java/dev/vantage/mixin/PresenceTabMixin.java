package dev.vantage.mixin;

import dev.vantage.core.Presence;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Vantage mark in front of a tab-list name when that player runs the client, and the friend star before friends. */
@Mixin(PlayerTabOverlay.class)
public class PresenceTabMixin {
    @Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true)
    private void vantage$badge(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component c = dev.vantage.module.client.FriendsModule.decorate(info.getProfile().name(), Presence.decorate(info.getProfile().id(), cir.getReturnValue()));
        if (c != cir.getReturnValue()) { cir.setReturnValue(c); Presence.tabHits++; }
        Component hidden = dev.vantage.core.Streamer.scrub(cir.getReturnValue()); // streamer mode: names become stand-ins
        if (hidden != cir.getReturnValue()) cir.setReturnValue(hidden);
    }
}
