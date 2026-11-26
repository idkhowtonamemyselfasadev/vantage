package dev.vantage.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.vantage.module.render.Freecam;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sodium skips whole face groups that point away from the camera; freecam cave view needs them all. */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class DefaultChunkRendererMixin {
    @ModifyExpressionValue(method = "render", require = 0,
            at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useBlockFaceCulling:Z"))
    private boolean vantage$allFaces(boolean cull) {
        dev.vantage.core.hooks.OptionalHooks.ran("Sodium: DefaultChunkRenderer.allFaces"); // this kind of hook can only be judged by whether it runs
        return cull && !Freecam.caveView();
    }
}
