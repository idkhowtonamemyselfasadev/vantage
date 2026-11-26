package dev.vantage.mixin;

import dev.vantage.module.render.Xray;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla xray: faces of shown blocks are drawn even when stone covers them. */
@Mixin(ModelBlockRenderer.class)
public class ModelBlockRendererMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void vantage$allFaces(BlockAndTintGetter level, BlockState state, boolean cull, Direction dir,
                                         BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (Xray.active()) cir.setReturnValue(true);
    }
}
