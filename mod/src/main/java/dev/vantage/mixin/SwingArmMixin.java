package dev.vantage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vantage.module.cosmetic.SwingAnimation;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swing Animation: vanilla's arm swing transform is replaced by the chosen style. */
@Mixin(ItemInHandRenderer.class)
public class SwingArmMixin {
    @Inject(method = "swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V", at = @At("HEAD"), cancellable = true)
    private void vantage$swing(float progress, PoseStack pose, int side, HumanoidArm arm, CallbackInfo ci) {
        SwingAnimation s = SwingAnimation.active();
        if (s == null || !s.applies(arm)) return;
        s.swing(progress, pose, side, arm);
        ci.cancel();
    }
}
