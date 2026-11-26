package dev.vantage.mixin;

import dev.vantage.core.event.KeyPressEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires {@link KeyPressEvent}. Target: KeyboardHandler.keyPress, the game's own key callback (1.21.11). */
@Mixin(KeyboardHandler.class)
public class KeyPressMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void vantage$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        KeyPressEvent.fire(event.key(), event.scancode(), action, event.modifiers());
    }
}
