package dev.vantage.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vantage.module.render.Xray;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla xray, decided before anyone gets to mesh the block: hidden blocks report an invisible shape
 * and fluids report empty. Works whether vanilla or Fabric's Indigo renderer draws the model.
 */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape vantage$hideBlock(BlockState state, Operation<RenderShape> original) {
        if ((Xray.active() && !Xray.shows(state)) || dev.vantage.module.donut.FarmOptimizer.hidesBlock(state)) return RenderShape.INVISIBLE;
        return original.call(state);
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z"))
    private boolean vantage$hideFluid(FluidState fluid, Operation<Boolean> original) {
        return Xray.active() || original.call(fluid);
    }
}
