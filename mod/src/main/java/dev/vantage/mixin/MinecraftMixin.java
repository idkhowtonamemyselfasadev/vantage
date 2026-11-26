package dev.vantage.mixin;

import dev.vantage.module.render.Freecam;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Freecam: the frozen player must not act on whatever it still points at, so no clicks, drops or hand swaps reach it. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void vantage$attack(CallbackInfoReturnable<Boolean> ci) {
        if (Freecam.active() != null) ci.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void vantage$mine(boolean down, CallbackInfo ci) {
        if (Freecam.active() != null) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void vantage$use(CallbackInfo ci) {
        if (Freecam.active() != null) ci.cancel();
    }

    @Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
    private void vantage$pick(CallbackInfo ci) {
        if (Freecam.active() != null) ci.cancel();
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void vantage$keys(CallbackInfo ci) {
        if (Freecam.active() == null) return;
        var o = ((Minecraft) (Object) this).options;
        while (o.keyDrop.consumeClick()) {}
        while (o.keySwapOffhand.consumeClick()) {}
    }
}
