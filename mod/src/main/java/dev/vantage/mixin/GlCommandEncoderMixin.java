package dev.vantage.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.vantage.module.render.Freecam;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Freecam cave view: terrain draws without back-face culling, so from inside the ground you see every
 * surface (cave walls, the ground from below) instead of looking straight through to the sky.
 * Sodium draws terrain through the same state call, so this covers both.
 */
@Mixin(GlCommandEncoder.class)
public class GlCommandEncoderMixin {
    @ModifyExpressionValue(method = "applyPipelineState",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;isCull()Z"))
    private boolean vantage$caveView(boolean cull, RenderPipeline pipeline) {
        if (!cull || !Freecam.caveView()) return cull;
        for (ChunkSectionLayer l : ChunkSectionLayer.values()) if (l.pipeline() == pipeline) return false;
        return cull;
    }
}
