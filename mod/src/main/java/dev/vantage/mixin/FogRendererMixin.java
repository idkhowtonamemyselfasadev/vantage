package dev.vantage.mixin;

import java.nio.ByteBuffer;

import dev.vantage.module.render.NoRender;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Render "Fog": the world fog buffer gets the same far-away values vanilla uses for "no fog". */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Shadow
    private void updateBuffer(ByteBuffer b, int i, Vector4f color, float envStart, float envEnd, float rdStart, float rdEnd, float sky, float cloud) {}

    /** Night vision stretches the Nether's dark sky colour to full brightness, which fills the view with red. */
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void vantage$sky(CallbackInfoReturnable<Vector4f> cir) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null && level.dimensionType().hasCeiling() && NoRender.hide(NoRender.FOG)) {
            cir.getReturnValue().set(0.08f, 0.02f, 0.02f, 1f);
        }
    }

    @Redirect(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void vantage$fog(FogRenderer self, ByteBuffer b, int i, Vector4f color, float envStart, float envEnd,
                             float rdStart, float rdEnd, float sky, float cloud) {
        if (NoRender.hide(NoRender.FOG)) {
            float far = Float.MAX_VALUE;
            updateBuffer(b, i, color, far, far, far, far, sky, cloud);
        } else {
            updateBuffer(b, i, color, envStart, envEnd, rdStart, rdEnd, sky, cloud);
        }
    }
}
