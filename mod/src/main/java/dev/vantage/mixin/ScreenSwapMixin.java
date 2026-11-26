package dev.vantage.mixin;

import dev.vantage.gui.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Vantage screens: the vanilla title, world and server screens are swapped for ours on the way in, when the setting is on. */
@Mixin(Minecraft.class)
public class ScreenSwapMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen vantage$swap(Screen screen) {
        return Screens.swap(screen, ((Minecraft) (Object) this).screen);
    }
}
