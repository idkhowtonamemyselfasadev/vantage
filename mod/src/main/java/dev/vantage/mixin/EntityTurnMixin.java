package dev.vantage.mixin;

import dev.vantage.module.render.FreeLook;
import dev.vantage.module.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam and Free Look: mouse movement turns the free camera, your player keeps looking where it was. */
@Mixin(Entity.class)
public class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void vantage$turn(double dx, double dy, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        Freecam f = Freecam.active();
        if (f != null) {
            f.turn(dx, dy);
            ci.cancel();
            return;
        }
        FreeLook l = FreeLook.active();
        if (l != null) {
            l.turn(dx, dy);
            ci.cancel();
        }
    }
}
