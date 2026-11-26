package dev.vantage.mixin;

import dev.vantage.module.render.HandView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hand View: your own arm animation only, other players keep theirs. */
@Mixin(LivingEntity.class)
public class HandSwingMixin {
    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
    private void vantage$swing(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this == Minecraft.getInstance().player) cir.setReturnValue(HandView.swingTicks(cir.getReturnValue()));
    }
}
