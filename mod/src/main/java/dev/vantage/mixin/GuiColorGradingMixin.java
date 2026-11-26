package dev.vantage.mixin;

import dev.vantage.module.cosmetic.ColorGrading;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Colour Grading runs at the head of the HUD render. Motion Blur now runs earlier (end of LevelRenderer.renderLevel, or after
 * the hands in Blur too mode), so the grade sees the blurred frame.
 */
@Mixin(value = Gui.class, priority = 900)
public class GuiColorGradingMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void vantage$grade(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        ColorGrading.frame();
    }
}
