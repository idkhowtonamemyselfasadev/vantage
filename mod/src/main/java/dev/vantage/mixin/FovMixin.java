package dev.vantage.mixin;

import dev.vantage.module.cosmetic.CustomFov;
import dev.vantage.module.cosmetic.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom FOV and Zoom: the world's field of view after vanilla worked it out. The hand pass asks with useOptions = false and is left alone. */
@Mixin(GameRenderer.class)
public class FovMixin {
    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("RETURN"), cancellable = true)
    private void vantage$fov(Camera camera, float partial, boolean useOptions, CallbackInfoReturnable<Float> cir) {
        if (!useOptions) return;
        float f = cir.getReturnValue();
        CustomFov c = CustomFov.active();
        if (c != null) f = c.apply(f);
        Zoom z = Zoom.active();
        if (z != null) f = z.apply(f);
        cir.setReturnValue(f);
    }
}
