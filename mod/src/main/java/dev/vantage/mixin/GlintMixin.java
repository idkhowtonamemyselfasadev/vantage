package dev.vantage.mixin;

import dev.vantage.module.cosmetic.CustomGlint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom Glint: vanilla's scrolling glint matrix, with our speed and pattern size folded in. */
@Mixin(TextureTransform.class)
public class GlintMixin {
    @Inject(method = "setupGlintTexturing(F)Lorg/joml/Matrix4f;", at = @At("HEAD"), cancellable = true)
    private static void vantage$glint(float scale, CallbackInfoReturnable<Matrix4f> cir) {
        CustomGlint g = CustomGlint.active();
        if (g == null) return;
        long l = (long) (Util.getMillis() * Minecraft.getInstance().options.glintSpeed().get() * 8.0 * g.speed.get());
        float f = (l % 110000L) / 110000f, h = (l % 30000L) / 30000f;
        CustomGlint.applied++;
        cir.setReturnValue(new Matrix4f().translation(-f, h, 0).rotateZ(0.17453292f).scale(scale / g.pattern.f()));
    }
}
