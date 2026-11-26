package dev.vantage.mixin;

import dev.vantage.module.donut.FarmOptimizer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Farm optimizer: sound filter. */
@Mixin(SoundManager.class)
public class FarmSoundMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void vantage$spam(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (FarmOptimizer.muteSound(sound.getIdentifier())) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }
}
