package dev.vantage.mixin.voxy;

import dev.vantage.module.render.Freecam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Underground in Freecam the real chunks are hollow or hidden, so Voxy's blocky far terrain shows through and its
 * giant blocks can swallow the camera. It is skipped for those frames. Voxy is optional and its internals change: a miss here must not stop the game.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public class VoxyRenderSystemMixin {
    @Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true, require = 0)
    private void vantage$baseView(CallbackInfo ci) {
        if (Freecam.hideLod()) ci.cancel();
    }
}
