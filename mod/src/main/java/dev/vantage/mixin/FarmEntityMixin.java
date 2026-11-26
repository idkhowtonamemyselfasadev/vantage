package dev.vantage.mixin;

import dev.vantage.module.donut.FarmOptimizer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Farm optimizer: items, orbs, falling blocks and TNT never reach the entity renderers. */
@Mixin(EntityRenderDispatcher.class)
public class FarmEntityMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void vantage$hide(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (FarmOptimizer.hides(entity)) cir.setReturnValue(false);
    }
}
