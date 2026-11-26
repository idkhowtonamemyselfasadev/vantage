package dev.vantage.mixin;

import dev.vantage.module.cosmetic.TimeChanger;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Time Changer: the sky, the light and the moon read the day time through here. */
@Mixin(ClientLevel.ClientLevelData.class)
public class ClientLevelDataMixin {
    @Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true)
    private void vantage$time(CallbackInfoReturnable<Long> cir) {
        long t = TimeChanger.override();
        if (t >= 0) cir.setReturnValue(cir.getReturnValue() - cir.getReturnValue() % 24000L + t);
    }
}
