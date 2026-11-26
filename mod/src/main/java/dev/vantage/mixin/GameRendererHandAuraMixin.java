package dev.vantage.mixin;

import dev.vantage.module.cosmetic.HandAura;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hand Aura runs right after the first-person hands are drawn, while their depth is the only depth in the buffer. */
@Mixin(GameRenderer.class)
public class GameRendererHandAuraMixin {
    @Inject(method = "renderItemInHand(FZLorg/joml/Matrix4f;)V", at = @At("TAIL"))
    private void vantage$aura(float partial, boolean sleeping, Matrix4f projection, CallbackInfo ci) {
        HandAura.frame();
    }
}
