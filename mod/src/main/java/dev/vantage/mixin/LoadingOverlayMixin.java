package dev.vantage.mixin;

import dev.vantage.gui.VantageLoading;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes the Mojang loading screen's render to VantageLoading; the overlay's own tick and fade timing stay vanilla. */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow @Final private boolean fadeIn;
    @Shadow private long fadeOutStart;
    @Shadow private long fadeInStart;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vantage$render(GuiGraphics g, int mx, int my, float delta, CallbackInfo ci) {
        if (!VantageLoading.enabled()) return;
        if (fadeIn && fadeInStart == -1L) fadeInStart = Util.getMillis();
        boolean done;
        try {
            done = VantageLoading.render(g, minecraft, reload.getActualProgress(), fadeIn, fadeInStart, fadeOutStart, delta);
        } catch (Throwable t) {
            dev.vantage.core.log.Log.core("loading").once("render-failed", t, () -> "Loading screen failed, vanilla one instead (every later frame is only counted)");
            return;
        }
        ci.cancel();
        if (done) minecraft.setOverlay(null);
    }
}
