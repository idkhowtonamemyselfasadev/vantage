package dev.vantage.mixin;

import dev.vantage.module.cosmetic.SwingAnimation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Swing Animation: the tempo shortens or lengthens the client's copy of your own swing. */
@Mixin(LivingEntity.class)
public class SwingTempoMixin {
    @Inject(method = "getCurrentSwingDuration()I", at = @At("RETURN"), cancellable = true)
    private void vantage$tempo(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this != Minecraft.getInstance().player) return;
        SwingAnimation s = SwingAnimation.active();
        if (s != null) cir.setReturnValue(s.ticks(cir.getReturnValue()));
    }
}
