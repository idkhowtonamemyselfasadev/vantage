package dev.vantage.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vantage.core.Ui;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GlyphRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sharp menu text: GUI glyphs are always sampled NEAREST, which blocks up or drops texels as soon as a glyph raster is drawn at
 * any size but its own. Vantage's density-matched TTF pages (Ui.smoothGlyphs) are sampled linearly instead, so the small gap
 * between two density steps blends smoothly. Vanilla fonts and the bitmap fallback glyphs keep NEAREST.
 */
@Mixin(GlyphRenderState.class)
public abstract class GlyphRenderStateMixin {
    @Inject(method = "textureSetup()Lnet/minecraft/client/gui/render/TextureSetup;", at = @At("HEAD"), cancellable = true)
    private void vantage$smoothGlyphs(CallbackInfoReturnable<TextureSetup> cir) {
        GpuTextureView view = ((GlyphRenderState) (Object) this).renderable().textureView();
        if (Ui.smoothGlyphs(view)) {
            cir.setReturnValue(TextureSetup.singleTextureWithLightmap(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
        }
    }
}
