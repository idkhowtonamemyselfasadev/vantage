package dev.vantage.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.vantage.module.render.Fullbright;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/** Fullbright: the lightmap reads a huge gamma instead of the slider value. */
@Mixin(LightTexture.class)
public class LightTextureMixin {
    @ModifyExpressionValue(method = "updateLightTexture",
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 0))
    private Object vantage$gamma(Object gamma) {
        return Fullbright.active() ? (Object) Fullbright.gamma() : gamma;
    }

    /**
     * Freecam flying through the ground: blocks with light level 0 (everything buried) stay near black even at the huge Fullbright
     * gamma, so a base inside the rock was hard to see. The lightmap's night vision factor (4th float of the uniform block)
     * scales the colour up to full white whatever the light level, which is what "lit up while flying" has to mean.
     */
    @ModifyArg(method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 3), index = 0)
    private float vantage$freecamNightVision(float factor) {
        return dev.vantage.module.render.Freecam.bright() ? 1f : factor;
    }
}
