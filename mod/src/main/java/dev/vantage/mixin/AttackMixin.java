package dev.vantage.mixin;

import dev.vantage.module.cosmetic.HitParticles;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hit Particles, Damage Numbers, Kill Effect: your own attack click on an entity, read as it goes out. Nothing about the attack changes. */
@Mixin(MultiPlayerGameMode.class)
public class AttackMixin {
    @Inject(method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void vantage$hit(Player player, Entity target, CallbackInfo ci) {
        HitParticles h = HitParticles.active();
        if (h != null) h.burst(target);
        dev.vantage.module.cosmetic.HitTracker.hit(target);
    }
}
