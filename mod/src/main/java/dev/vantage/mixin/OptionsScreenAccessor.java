package dev.vantage.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen a vanilla options screen would go back to, so ours can take its place and keep that. */
@Mixin(OptionsScreen.class)
public interface OptionsScreenAccessor {
    @Accessor("lastScreen")
    Screen vantage$lastScreen();
}
