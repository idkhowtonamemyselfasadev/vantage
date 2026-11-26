package dev.vantage.module.render;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.setting.BoolSetting;

/** Switches off screen clutter. Each option is read by a small mixin. */
public class NoRender extends Module {
    public static final int FIRE = 0, WATER = 1, IN_WALL = 2, TOTEM = 3, VIGNETTE = 4, PORTAL = 5, PUMPKIN = 6,
            SCOREBOARD = 7, BOSS_BAR = 8, HURT_CAM = 9, WEATHER = 10, FOG = 11, BLINDNESS = 12, CHUNK_FADE = 13;

    private final BoolSetting[] opts = new BoolSetting[14];
    private static NoRender instance;

    public NoRender() {
        super("No Render", "Hide fire, fog, overlays and other clutter", Category.RENDER);
        instance = this;
        opts[FIRE] = add(new BoolSetting("Fire overlay", "Flames on screen when burning", true));
        opts[WATER] = add(new BoolSetting("Water overlay", "Blue tint under water", true));
        opts[IN_WALL] = add(new BoolSetting("In-wall overlay", "Block texture when your head is inside a block", true));
        opts[TOTEM] = add(new BoolSetting("Totem pop", "Big totem animation", false));
        opts[VIGNETTE] = add(new BoolSetting("Vignette", "Dark screen edges", true));
        opts[PORTAL] = add(new BoolSetting("Portal / nausea", "Purple portal swirl and nausea wobble overlay", true));
        opts[PUMPKIN] = add(new BoolSetting("Pumpkin / snow", "Carved pumpkin and powder snow overlays", true));
        opts[SCOREBOARD] = add(new BoolSetting("Scoreboard", "Sidebar scoreboard", false));
        opts[BOSS_BAR] = add(new BoolSetting("Boss bar", "Boss health bars", false));
        opts[HURT_CAM] = add(new BoolSetting("Hurt cam", "Screen shake when hit", true));
        opts[WEATHER] = add(new BoolSetting("Weather", "Rain and snow", true));
        opts[FOG] = add(new BoolSetting("Fog", "Distance and environment fog", false));
        opts[BLINDNESS] = add(new BoolSetting("Blindness", "Blindness and darkness effect fog", true));
        opts[CHUNK_FADE] = add(new BoolSetting("Chunk fade-in", "New and rebuilt chunks pop in instead of fading in from a flat fog colour (without fog they flash as solid blocks)", true));
    }

    private static Boolean voxyFog;

    /** Voxy has its own fog switch, read when it builds its pipeline, so it follows the Fog option from the title screen on. */
    public static void syncVoxyFog() {
        boolean want = !hide(FOG);
        if (voxyFog != null && voxyFog == want) return;
        voxyFog = want;
        try {
            Class<?> c = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            Object config = c.getField("CONFIG").get(null);
            if (config != null) c.getField("useEnvironmentalFog").setBoolean(config, want);
        } catch (ReflectiveOperationException | LinkageError e) {
            // no Voxy, or a version without the option
        }
    }

    public static boolean hide(int what) {
        return instance != null && instance.on() && instance.opts[what].on();
    }
}
