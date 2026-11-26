package dev.vantage.mixin;

import dev.vantage.module.cosmetic.JumpCircles;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Jump Circles: your own jump, the moment it starts. */
@Mixin(LivingEntity.class)
public class JumpMixin {
    @Inject(method = "jumpFromGround()V", at = @At("HEAD"))
    private void vantage$jump(CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        JumpCircles j = JumpCircles.active();
        if (j != null) j.jumped((LivingEntity) (Object) this);
    }
}
