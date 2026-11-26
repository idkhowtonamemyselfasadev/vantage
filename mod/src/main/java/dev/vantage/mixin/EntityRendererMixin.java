package dev.vantage.mixin;

import dev.vantage.module.render.EntityEsp;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entity ESP "Glow" mode: the vanilla outline effect, drawn only on this client. */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void vantage$glow(Entity entity, EntityRenderState state, float partial, CallbackInfo ci) {
        int c = EntityEsp.glowColor(entity);
        if (c != 0) state.outlineColor = 0xFF000000 | c;
    }
}
