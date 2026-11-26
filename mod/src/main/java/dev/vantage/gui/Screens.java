package dev.vantage.gui;

import dev.vantage.core.Modules;
import dev.vantage.module.client.ClickGuiModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

/** Which screen really opens when the game asks for a vanilla one. */
public final class Screens {
    public static boolean enabled() {
        ClickGuiModule m = Modules.get(ClickGuiModule.class);
        return !dev.vantage.core.Terms.off() && (m == null || m.screens.on()); // the terms gate: vanilla screens until the terms are confirmed
    }

    public static Screen swap(Screen wanted, Screen current) {
        if (wanted == null || !enabled()) return wanted;
        if (wanted instanceof TitleScreen && !(wanted instanceof VantageTitleScreen)) return new VantageTitleScreen();
        if (wanted instanceof SelectWorldScreen) return new VantageWorldsScreen(parentFor(current));
        if (wanted instanceof JoinMultiplayerScreen) return new VantageServersScreen(parentFor(current));
        if (wanted instanceof net.minecraft.client.gui.screens.options.OptionsScreen o && !(wanted instanceof VantageOptionsScreen))
            return new VantageOptionsScreen(((dev.vantage.mixin.OptionsScreenAccessor) o).vantage$lastScreen(), Minecraft.getInstance().options);
        // Esc in game; the "Game paused" strip from F3+Esc or losing focus stays vanilla
        if (wanted instanceof PauseScreen p && !(wanted instanceof VantagePauseScreen) && p.showsPauseMenu()) return new VantagePauseScreen();
        return wanted;
    }

    /**
     * Where Back goes: the screen that asked, when it is one a player can use (the title, one of ours, an options screen). After a
     * disconnect vanilla asks from a blank progress screen, and the multiplayer warning has done its job: those give null, so Back
     * lands on the title (no world) or in the game.
     */
    private static Screen parentFor(Screen current) {
        if (current == null || current instanceof SafetyScreen) return null;
        if (current instanceof VantageScreen || current instanceof TitleScreen) return current;
        if (current instanceof net.minecraft.client.gui.screens.ProgressScreen || current instanceof net.minecraft.client.gui.screens.GenericMessageScreen
                || current instanceof net.minecraft.client.gui.screens.DisconnectedScreen || current instanceof PauseScreen) return null;
        return current;
    }

    /** The parent, or the title when there is none and no world is open. */
    public static void back(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        if (parent != null) mc.setScreen(parent);
        else if (mc.level == null) mc.setScreen(new VantageTitleScreen());
        else mc.setScreen(null);
    }

    private Screens() {}
}
