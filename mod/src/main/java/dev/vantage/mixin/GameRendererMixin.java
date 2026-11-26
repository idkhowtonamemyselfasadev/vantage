package dev.vantage.mixin;

import dev.vantage.module.render.NoRender;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void vantage$hurtCam(CallbackInfo ci) {
        if (NoRender.hide(NoRender.HURT_CAM)) ci.cancel();
    }

    /** Freecam: nothing is targeted, so no block outline floats where the frozen player happens to look. */
    @Inject(method = "pick", at = @At("TAIL"))
    private void vantage$noTarget(float partial, CallbackInfo ci) {
        if (dev.vantage.module.render.Freecam.active() == null) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.crosshairPickEntity = null;
        mc.hitResult = net.minecraft.world.phys.BlockHitResult.miss(mc.player.position(), net.minecraft.core.Direction.UP, mc.player.blockPosition());
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void vantage$hand(CallbackInfo ci) {
        var f = dev.vantage.module.render.Freecam.active();
        if (f != null && f.hideHand.on()) ci.cancel();
    }
}
