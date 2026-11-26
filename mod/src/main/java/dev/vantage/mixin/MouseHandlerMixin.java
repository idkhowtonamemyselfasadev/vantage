package dev.vantage.mixin;

import dev.vantage.module.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: the wheel sets the flying speed instead of the hotbar slot. Screens keep their own scrolling. */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void vantage$speed(long window, double x, double y, CallbackInfo ci) {
        Freecam f = Freecam.active();
        if (f == null || Minecraft.getInstance().screen != null) return;
        if (y != 0) f.scroll(y);
        ci.cancel();
    }
}
