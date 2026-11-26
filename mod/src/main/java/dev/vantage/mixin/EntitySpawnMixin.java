package dev.vantage.mixin;

import dev.vantage.core.event.EntitySpawnEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires {@link EntitySpawnEvent}. Target: ClientLevel.addEntity, the one way an entity enters the client world (1.21.11). */
@Mixin(ClientLevel.class)
public class EntitySpawnMixin {
    @Inject(method = "addEntity", at = @At("TAIL"))
    private void vantage$entitySpawn(Entity entity, CallbackInfo ci) {
        EntitySpawnEvent.fire(entity);
    }
}
