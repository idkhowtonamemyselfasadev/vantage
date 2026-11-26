package dev.vantage.mixin;

import dev.vantage.core.Streamer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Streamer mode: the tab list's header and footer (servers like to print their address there) are scrubbed for the frame, then put back. */
@Mixin(PlayerTabOverlay.class)
public class StreamerTabMixin {
    @Shadow private Component header;
    @Shadow private Component footer;
    @Unique private Component vantage$header, vantage$footer;

    @Inject(method = "render", at = @At("HEAD"))
    private void vantage$scrubIn(GuiGraphics g, int w, Scoreboard sb, Objective o, CallbackInfo ci) {
        vantage$header = header;
        vantage$footer = footer;
        if (!Streamer.active()) return;
        if (header != null) header = Streamer.scrub(header);
        if (footer != null) footer = Streamer.scrub(footer);
        if (header != vantage$header || footer != vantage$footer) Streamer.tabScrubbed++;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void vantage$scrubOut(GuiGraphics g, int w, Scoreboard sb, Objective o, CallbackInfo ci) {
        header = vantage$header;
        footer = vantage$footer;
    }
}
