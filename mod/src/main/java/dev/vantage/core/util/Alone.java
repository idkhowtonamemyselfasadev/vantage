package dev.vantage.core.util;

import net.minecraft.client.Minecraft;

/**
 * Is the user the only player this world can have? In a singleplayer world nobody else keeps chunks ticking, grows plants, harvests
 * geodes or travels: whatever the game seems to have "simulated by someone" is the user's own presence or world generation. The finders
 * that read other players off the world use this to stay quiet there. A world opened to LAN with a friend in it is not alone.
 * The test rig runs in a singleplayer world and plays other players with commands, so under it the answer is "not alone" unless a
 * check sets {@link #rigAlone} to test the real singleplayer behaviour.
 */
public final class Alone {
    /** Rig only: true makes the rig world count as a real singleplayer world. */
    public static volatile boolean rigAlone = false;
    private static final boolean RIG = Boolean.getBoolean("vantage.ui");

    public static boolean now() {
        if (RIG && !rigAlone) return false;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return mc.level != null && server != null && server.getPlayerCount() <= 1;
    }

    private Alone() {}
}
