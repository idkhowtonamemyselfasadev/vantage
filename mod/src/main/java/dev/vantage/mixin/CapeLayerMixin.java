package dev.vantage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vantage.module.cosmetic.HitColor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hit Colour, armour too: the cape flashes with the player as well. */
@Mixin(CapeLayer.class)
public class CapeLayerMixin {
    @Inject(method = "submit", at = @At("HEAD"))
    private void vantage$begin(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float a, float b, CallbackInfo ci) {
        HitColor.wearerOverlay = HitColor.armourFlash() ? LivingEntityRenderer.getOverlayCoords(state, 0) : -1;
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void vantage$end(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float a, float b, CallbackInfo ci) {
        HitColor.wearerOverlay = -1;
    }

    @Redirect(method = "submit", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/texture/OverlayTexture;NO_OVERLAY:I"), expect = 1)
    private int vantage$overlay() {
        return HitColor.overlayFor(OverlayTexture.NO_OVERLAY);
    }
}
