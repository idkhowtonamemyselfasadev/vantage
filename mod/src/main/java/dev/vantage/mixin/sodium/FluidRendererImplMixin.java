package dev.vantage.mixin.sodium;

import dev.vantage.module.render.Xray;
import net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FluidRendererImpl.class, remap = false)
public class FluidRendererImplMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void vantage$skipFluid(CallbackInfo ci) {
        if (Xray.active()) ci.cancel();
    }
}
