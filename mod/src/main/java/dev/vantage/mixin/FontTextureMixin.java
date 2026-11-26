package dev.vantage.mixin;

import java.util.function.Supplier;

import dev.vantage.core.Ui;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sharp menu text: remembers which glyph atlas pages belong to Vantage's density-matched TTF fonts. The page's name (the font id
 *  plus "/n") is only known here: GL drops texture labels unless debug labels are on. GlyphRenderStateMixin samples these LINEAR. */
@Mixin(FontTexture.class)
public abstract class FontTextureMixin {
    @Inject(method = "<init>(Ljava/util/function/Supplier;Lnet/minecraft/client/gui/font/GlyphRenderTypes;Z)V", at = @At("TAIL"))
    private void vantage$markPage(Supplier<String> name, GlyphRenderTypes types, boolean colored, CallbackInfo ci) {
        if (!colored) Ui.markGlyphPage(name.get(), ((AbstractTexture) (Object) this).getTexture());
    }
}
