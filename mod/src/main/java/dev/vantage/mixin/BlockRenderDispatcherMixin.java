package dev.vantage.mixin;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vantage.module.render.Xray;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla xray: chunk meshes skip every block that isn't on the list, and all fluids. */
@Mixin(BlockRenderDispatcher.class)
public class BlockRenderDispatcherMixin {
    @Inject(method = "renderBatched", at = @At("HEAD"), cancellable = true)
    private void vantage$skipBlock(BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack pose,
                                   VertexConsumer out, boolean cull, List<BlockModelPart> parts, CallbackInfo ci) {
        if ((Xray.active() && !Xray.shows(state)) || dev.vantage.module.donut.FarmOptimizer.hidesBlock(state)) ci.cancel();
    }

    @Inject(method = "renderLiquid", at = @At("HEAD"), cancellable = true)
    private void vantage$skipFluid(BlockPos pos, BlockAndTintGetter level, VertexConsumer out, BlockState state,
                                   FluidState fluid, CallbackInfo ci) {
        if (Xray.active()) ci.cancel();
    }
}
