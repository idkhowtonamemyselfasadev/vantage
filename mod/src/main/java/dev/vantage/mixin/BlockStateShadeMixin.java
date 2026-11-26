package dev.vantage.mixin;

import dev.vantage.module.render.Xray;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray and Freecam's See-through view: a block that is not drawn must not shade its neighbours. Smooth lighting darkens a face
 * to a fifth next to a solid block (vanilla and Sodium both ask the neighbour for this), so a base buried in hidden stone
 * showed up nearly black. Hidden blocks count as air for it.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
public class BlockStateShadeMixin {
    @Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true, require = 1)
    private void vantage$hiddenCastsNoShade(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (Xray.active() && !Xray.shows((BlockState) (Object) this)) cir.setReturnValue(1.0f);
    }
}
