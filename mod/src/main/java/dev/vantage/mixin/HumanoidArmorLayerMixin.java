package dev.vantage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vantage.module.cosmetic.HitColor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hit Colour, armour too: while a humanoid's armour is submitted, remember the wearer's hurt overlay so the pieces flash with the body. */
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private void vantage$begin(PoseStack pose, SubmitNodeCollector collector, int light, HumanoidRenderState state, float a, float b, CallbackInfo ci) {
        HitColor.wearerOverlay = HitColor.armourFlash() ? LivingEntityRenderer.getOverlayCoords(state, 0) : -1;
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void vantage$end(PoseStack pose, SubmitNodeCollector collector, int light, HumanoidRenderState state, float a, float b, CallbackInfo ci) {
        HitColor.wearerOverlay = -1;
    }
}
