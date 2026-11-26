package dev.vantage.mixin.sodium;

import dev.vantage.module.render.Xray;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractBlockRenderContext.class, remap = false)
public class AbstractBlockRenderContextMixin {
    @Shadow protected net.minecraft.world.level.BlockAndTintGetter level;
    @Shadow protected net.minecraft.core.BlockPos pos;

    /** Farm optimizer: a face next to hidden redstone has to draw, or there's a hole where the repeater was. */
    private boolean vantage$besideHidden(Direction face) {
        return face != null && level != null && dev.vantage.module.donut.FarmOptimizer.hidesBlock(level.getBlockState(pos.relative(face)));
    }

    @Inject(method = "isFaceCulled", at = @At("HEAD"), cancellable = true, require = 0)
    private void vantage$allFaces(Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (Xray.active() || vantage$besideHidden(face)) cir.setReturnValue(false);
    }

    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, require = 0) // not in Sodium 0.8.6
    private void vantage$drawSide(Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (vantage$besideHidden(face)) cir.setReturnValue(true);
    }
}
