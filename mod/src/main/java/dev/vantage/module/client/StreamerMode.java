package dev.vantage.module.client;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.setting.BoolSetting;

/**
 * Streamer mode (beta): one switch that hides, on screen only, your own name, coordinates, the server address and other players'
 * names. See {@link dev.vantage.core.Streamer}; hooks sit in the chat, tab list, name tag and F3 mixins and in Vantage's own overlays.
 */
public class StreamerMode extends Module {
    public final BoolSetting self = add(new BoolSetting("Hide own name", "Your name becomes \"You\" in name tags, the tab list and chat lines", true));
    public final BoolSetting coords = add(new BoolSetting("Hide coordinates", "Vantage HUD, Vantage chat lines and cards, and the position lines of the F3 screen", true));
    public final BoolSetting server = add(new BoolSetting("Hide server address", "Menu sidebar, pause screen, tab list header and footer, chat lines that contain it", true));
    public final BoolSetting others = add(new BoolSetting("Hide other players' names", "Other players become Player 1, Player 2... in name tags, the tab list, chat and Vantage overlays", true));

    public StreamerMode() {
        super("Streamer Mode", "Hides your name, coordinates, the server address and other players' names on screen. Display only, nothing is sent", Category.CLIENT);
        beta();
    }

    @Override
    protected void onEnable() {
        dev.vantage.core.Streamer.reset();
    }
}
