package dev.vantage.mixin;

import dev.vantage.module.render.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: the movement keys fly the camera, so the player itself gets no input. */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void vantage$freeze(CallbackInfo ci) {
        if (Freecam.active() == null) return;
        ((ClientInput) (Object) this).keyPresses = Input.EMPTY;
        ((ClientInputAccessor) this).vantage$setMoveVector(Vec2.ZERO);
    }
}
