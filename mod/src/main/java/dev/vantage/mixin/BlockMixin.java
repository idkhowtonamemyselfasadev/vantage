package dev.vantage.mixin;

import dev.vantage.module.render.Xray;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Face culling used by Indigo: ore faces against stone still draw while xray is on. */
@Mixin(Block.class)
public class BlockMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void vantage$allFaces(BlockState state, BlockState neighbor, Direction dir, CallbackInfoReturnable<Boolean> cir) {
        if (Xray.active() || dev.vantage.module.donut.FarmOptimizer.hidesBlock(neighbor)) cir.setReturnValue(true);
    }
}
