package dev.vantage.mixin;

import dev.vantage.core.Presence;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Vantage mark in front of a player's name tag when they run the client, and the friend star before friends. */
@Mixin(EntityRenderer.class)
public class PresenceNameTagMixin {
    @Inject(method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true)
    private void vantage$badge(Entity entity, CallbackInfoReturnable<Component> cir) {
        if (!(entity instanceof Player p) || cir.getReturnValue() == null) return;
        Component c = dev.vantage.module.client.FriendsModule.decorate(p.getGameProfile().name(), Presence.decorate(p.getUUID(), cir.getReturnValue()));
        if (c != cir.getReturnValue()) { cir.setReturnValue(c); Presence.tagHits++; }
        Component hidden = dev.vantage.core.Streamer.scrub(cir.getReturnValue()); // streamer mode: names become stand-ins
        if (hidden != cir.getReturnValue()) cir.setReturnValue(hidden);
    }
}
