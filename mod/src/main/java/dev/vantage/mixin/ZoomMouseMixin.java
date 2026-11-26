package dev.vantage.mixin;

import dev.vantage.module.cosmetic.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Zoom: the mouse slows down by the zoom, and the wheel changes the zoom while the key is held. */
@Mixin(MouseHandler.class)
public class ZoomMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"))
    private void vantage$slow(double d, CallbackInfo ci) {
        Zoom z = Zoom.active();
        if (z == null) return;
        double s = z.sensitivity();
        if (s != 1) { accumulatedDX *= s; accumulatedDY *= s; }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void vantage$wheel(long window, double x, double y, CallbackInfo ci) {
        Zoom z = Zoom.active();
        if (z == null || Minecraft.getInstance().screen != null) return;
        if (z.wheel(y)) ci.cancel();
    }
}
