package dev.vantage.mixin;

import dev.vantage.module.render.NoRender;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vantage$boss(CallbackInfo ci) {
        if (NoRender.hide(NoRender.BOSS_BAR)) ci.cancel();
    }
}
