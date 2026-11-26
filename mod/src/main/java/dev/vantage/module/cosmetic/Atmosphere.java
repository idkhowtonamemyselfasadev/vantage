package dev.vantage.module.cosmetic;

import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * The Overworld sky in a chosen palette: a gradient dome instead of the flat disc, more and twinkling stars, a milky way band,
 * an aurora curtain to the north at night, halos round the sun and moon, the odd meteor, and fog that takes the dome's horizon
 * colour. By day the palette only tints the vanilla sky a little; at night it takes over. Drawing is in {@link AtmosphereSky},
 * the hooks in SkyRendererAtmosphereMixin and FogRendererAtmosphereMixin. Nothing but what you see changes.
 */
public class Atmosphere extends Module {
    public final ModeSetting theme = add(new ModeSetting("Theme", "The palette of the night sky", "Borealis", "Borealis", "Nebula", "Frost", "Crimson", "Night", "Custom"));
    public final ColorSetting zenith = add(new ColorSetting("Custom zenith", "Straight up", 0xFF0B1A3A)).visibleIf(() -> theme.is("Custom"));
    public final ColorSetting mid = add(new ColorSetting("Custom mid sky", "Halfway down", 0xFF123C5C)).visibleIf(() -> theme.is("Custom"));
    public final ColorSetting horizon = add(new ColorSetting("Custom horizon", "At the horizon", 0xFF1F7A6E)).visibleIf(() -> theme.is("Custom"));
    public final ColorSetting glow = add(new ColorSetting("Custom aurora", "Aurora and star tint", 0xFF3FD9A0)).visibleIf(() -> theme.is("Custom"));
    public final NumberSetting dayTint = add(new NumberSetting("Day tint", "How much the palette shows by day; at night it always shows fully", 0.25, 0, 1, 0.05));
    public final BoolSetting gradient = add(new BoolSetting("Gradient sky", "The dome replaces the flat sky disc and the sunrise band", true));
    public final BoolSetting keepInRain = add(new BoolSetting("Keep in rain", "Stars and sky stay clear while it rains", true));
    public final BoolSetting halos = add(new BoolSetting("Sun & moon glow", "A soft halo round the sun and the moon", true));
    public final BoolSetting stars = add(new BoolSetting("Stars", "2600 stars that twinkle instead of vanilla's 1500", true));
    public final NumberSetting starBrightness = add(new NumberSetting("Star brightness", "How bright the stars get", 1.3, 0.2, 3, 0.1)).visibleIf(stars::on);
    public final BoolSetting milkyWay = add(new BoolSetting("Milky way", "A faint band of light across the night sky", true));
    public final BoolSetting meteors = add(new BoolSetting("Shooting stars", "A streak every so often at night", true));
    public final BoolSetting bigMoon = add(new BoolSetting("Bigger moon", "The moon at 1.4 times its size", true));
    public final BoolSetting fog = add(new BoolSetting("Fog colour", "The fog takes the colour of the sky where you look", true));
    public final BoolSetting netherColours = add(new BoolSetting("Nether colours", "Richer, slightly brighter fog in the Nether", true));
    public final ModeSetting aurora = add(new ModeSetting("Aurora", "The curtain of light to the north at night", "Always", "Always", "Cold biomes", "Off"));
    public final NumberSetting auroraBrightness = add(new NumberSetting("Aurora brightness", "How strong the curtain is", 1, 0.1, 3, 0.1)).visibleIf(() -> !aurora.is("Off"));
    public final NumberSetting auroraSpeed = add(new NumberSetting("Aurora speed", "How fast it waves", 1, 0.1, 4, 0.1)).visibleIf(() -> !aurora.is("Off"));
    /** Dome draws and passes that threw, for the rig. */
    public static int frames, failures;

    public Atmosphere() {
        super("Atmosphere", "A gradient sky with more stars, an aurora, halos and meteors", Category.COSMETIC);
    }

    @Override
    protected void onDisable() { AtmosphereSky.free(); }

    /** True while the module is on, the sky is the Overworld kind and the draw code has not failed. */
    public static Atmosphere active() {
        Atmosphere m = Modules.get(Atmosphere.class);
        if (m == null || !m.on() || AtmosphereSky.broken) return null;
        var level = Minecraft.getInstance().level;
        if (level == null || level.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD) return null;
        return m;
    }

    /** The palette: zenith, mid, horizon, aurora, all opaque ARGB. */
    public int[] palette() {
        return switch (theme.get()) {
            case "Nebula" -> new int[] {0xFF14092E, 0xFF3A1B5E, 0xFF7A3B7E, 0xFFB46CE0};
            case "Frost" -> new int[] {0xFF0A1830, 0xFF244B78, 0xFF9FC8E8, 0xFF7FE6FF};
            case "Crimson" -> new int[] {0xFF1A0710, 0xFF4A1020, 0xFFA02A3C, 0xFFFF5A5A};
            case "Night" -> new int[] {0xFF05070F, 0xFF0B1020, 0xFF1E2A44, 0xFF5FB8C8};
            case "Custom" -> new int[] {zenith.argb() | 0xFF000000, mid.argb() | 0xFF000000, horizon.argb() | 0xFF000000, glow.argb() | 0xFF000000};
            default -> new int[] {0xFF0B1A3A, 0xFF123C5C, 0xFF1F7A6E, 0xFF3FD9A0};
        };
    }
}
