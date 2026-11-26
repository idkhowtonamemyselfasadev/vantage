package dev.vantage.mixin;

import dev.vantage.module.render.NoRender;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void vantage$crosshair(CallbackInfo ci) {
        if (dev.vantage.module.cosmetic.Crosshair.replacesVanilla()) ci.cancel();
    }

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void vantage$vignette(CallbackInfo ci) {
        if (NoRender.hide(NoRender.VIGNETTE)) ci.cancel();
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void vantage$portal(CallbackInfo ci) {
        if (NoRender.hide(NoRender.PORTAL)) ci.cancel();
    }

    @Inject(method = "renderConfusionOverlay", at = @At("HEAD"), cancellable = true)
    private void vantage$nausea(CallbackInfo ci) {
        if (NoRender.hide(NoRender.PORTAL)) ci.cancel();
    }

    @Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
    private void vantage$pumpkin(CallbackInfo ci) {
        if (NoRender.hide(NoRender.PUMPKIN)) ci.cancel();
    }

    @Inject(method = "renderScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void vantage$scoreboard(CallbackInfo ci) {
        if (NoRender.hide(NoRender.SCOREBOARD)) ci.cancel();
    }
}
