package dev.vantage.mixin;

import dev.vantage.module.render.NoRender;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void vantage$fire(CallbackInfo ci) {
        if (NoRender.hide(NoRender.FIRE)) ci.cancel();
    }

    @Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
    private static void vantage$water(CallbackInfo ci) {
        if (NoRender.hide(NoRender.WATER)) ci.cancel();
    }

    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
    private static void vantage$inWall(CallbackInfo ci) {
        if (NoRender.hide(NoRender.IN_WALL)) ci.cancel();
    }

    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void vantage$totem(CallbackInfo ci) {
        if (NoRender.hide(NoRender.TOTEM)) ci.cancel();
    }
}
