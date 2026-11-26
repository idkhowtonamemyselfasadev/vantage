package dev.vantage.mixin;

import dev.vantage.module.client.FastXp;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fast XP: lets vanilla's own "use key held and delay is 0" test pass sooner. {@code rightClickDelay} is set to 4 by every use,
 * counted down once a tick, and tested in {@code handleKeybinds}; this lowers it there (only while FastXp says so). No click is made here.
 */
@Mixin(Minecraft.class)
public class FastXpMixin {
    @Shadow private int rightClickDelay;

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void vantage$fastXp(CallbackInfo ci) {
        rightClickDelay = FastXp.adjust((Minecraft) (Object) this, rightClickDelay);
    }
}
