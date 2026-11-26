package dev.vantage.mixin;

import dev.vantage.module.render.NoRender;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * No Render "Chunk fade-in": vanilla fades every chunk section that loads or is rebuilt more than ~27 blocks away in from the
 * flat fog colour (terrain.fsh: mix(FogColor, color, ChunkVisibility)) over Video Settings' Chunk Fade time. With fog hidden,
 * or with Atmosphere's sky behind it, those sections show up as solid coloured blocks for most of a second, constantly on a
 * server full of block updates. A fade duration of 0 makes them simply appear, as before 1.21.9.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererChunkFadeMixin {
    @Redirect(method = "compileSections(Lnet/minecraft/client/Camera;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setFadeDuration(J)V"), require = 1)
    private void vantage$noChunkFade(SectionRenderDispatcher.RenderSection section, long millis) {
        section.setFadeDuration(NoRender.hide(NoRender.CHUNK_FADE) ? 0L : millis);
    }
}
