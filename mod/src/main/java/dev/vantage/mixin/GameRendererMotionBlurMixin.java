package dev.vantage.mixin;

import dev.vantage.module.cosmetic.MotionBlur;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Motion Blur, Hands = Blur too: runs right after the hands are flushed (renderHandsWithItems ends with renderAllFeatures), before
 *  screen effects and the HUD. Priority 1100 = applied after GameRendererHandAuraMixin, so at the same TAIL it runs after the aura. */
@Mixin(value = GameRenderer.class, priority = 1100)
public class GameRendererMotionBlurMixin {
    @Inject(method = "renderItemInHand(FZLorg/joml/Matrix4f;)V", at = @At("TAIL"))
    private void vantage$blurHands(float partial, boolean sleeping, Matrix4f view, CallbackInfo ci) {
        MotionBlur.afterHands();
    }
}
