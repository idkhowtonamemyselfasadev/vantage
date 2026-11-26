package dev.vantage.mixin;

import dev.vantage.module.render.NoRender;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blindness and Darkness fog. */
@Mixin(MobEffectFogEnvironment.class)
public class MobEffectFogEnvironmentMixin {
    @Inject(method = "isApplicable", at = @At("HEAD"), cancellable = true)
    private void vantage$blind(CallbackInfoReturnable<Boolean> cir) {
        if (NoRender.hide(NoRender.BLINDNESS)) cir.setReturnValue(false);
    }
}
