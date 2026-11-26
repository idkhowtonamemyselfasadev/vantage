package dev.vantage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vantage.module.donut.FarmOptimizer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Farm optimizer: the two moving ticks of a piston are skipped, the block just appears where it ends up. */
@Mixin(PistonHeadRenderer.class)
public class FarmPistonMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void vantage$skip(PistonHeadRenderState state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera, CallbackInfo ci) {
        if (FarmOptimizer.hidesPistonMotion()) { FarmOptimizer.pistonsSkipped++; ci.cancel(); }
    }
}
