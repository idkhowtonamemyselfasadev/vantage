package dev.vantage.mixin.sodium;

import dev.vantage.module.render.Xray;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockRenderer.class, remap = false)
public class BlockRendererMixin {
    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, require = 0)
    private void vantage$skipBlock(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        if ((Xray.active() && !Xray.shows(state)) || dev.vantage.module.donut.FarmOptimizer.hidesBlock(state)) ci.cancel();
    }
}
