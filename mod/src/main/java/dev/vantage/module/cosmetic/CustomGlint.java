package dev.vantage.module.cosmetic;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Theme;
import dev.vantage.core.Vantage;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The enchantment shimmer in your colour: the two glint textures are re-tinted and uploaded in place while on (vanilla's come back
 * when off), and the glint's scrolling matrix gets a speed and a pattern size. Only what you see; items are unchanged.
 */
public class CustomGlint extends Module {
    public final ModeSetting colorMode = add(new ModeSetting("Colour", "Your own colour or the menu accent", "Accent", "Accent", "Custom"));
    public final ColorSetting color = add(new ColorSetting("Custom colour", "Glint colour", 0xFF7B6BFF)).visibleIf(() -> colorMode.is("Custom"));
    public final NumberSetting intensity = add(new NumberSetting("Intensity", "How bright the shimmer is", 1, 0.2, 2, 0.1));
    public final NumberSetting speed = add(new NumberSetting("Speed", "How fast it scrolls", 1, 0.1, 4, 0.1));
    public final NumberSetting pattern = add(new NumberSetting("Pattern size", "Bigger numbers stretch the pattern", 1, 0.25, 4, 0.25));
    private static final Identifier[] IDS = {Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png"), Identifier.withDefaultNamespace("textures/misc/enchanted_glint_armor.png")};
    public static int applied;
    private int lastColor;

    public CustomGlint() {
        super("Custom Glint", "The enchantment shimmer in your colour, at your speed", Category.COSMETIC);
        colorMode.onChange(() -> { if (on()) retint(); });
        color.onChange(() -> { if (on()) retint(); });
        intensity.onChange(() -> { if (on()) retint(); });
    }

    public static CustomGlint active() {
        CustomGlint c = Modules.get(CustomGlint.class);
        return c != null && c.on() ? c : null;
    }

    private int tint() {
        return colorMode.is("Custom") ? color.argb() : Theme.accent();
    }

    @Override
    protected void onEnable() { retint(); }

    @Override
    protected void onDisable() {
        for (Identifier id : IDS) mc.getTextureManager().release(id); // the next use loads vanilla's file again
    }

    @Override
    public void onTick() {
        if (!colorMode.is("Custom") && Theme.accent() != lastColor) retint(); // the accent changed in the menu
    }

    /** Vanilla's glint images, each pixel's brightness turned into our colour. */
    private void retint() {
        int c = tint();
        lastColor = Theme.accent();
        float k = intensity.f();
        int cr = c >> 16 & 255, cg = c >> 8 & 255, cb = c & 255;
        for (Identifier id : IDS) {
            try (var in = mc.getResourceManager().getResource(id).orElseThrow().open()) {
                NativeImage img = NativeImage.read(in);
                for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                    int p = img.getPixel(x, y);
                    float lum = ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / (3f * 255) * k;
                    int a = p >>> 24;
                    img.setPixel(x, y, (a << 24) | (ch(cr * lum) << 16) | (ch(cg * lum) << 8) | ch(cb * lum));
                }
                mc.getTextureManager().register(id, new DynamicTexture(id::toString, img));
            } catch (Exception e) {
                Vantage.LOG.warn("Custom Glint: could not retint {}: {}", id, e.toString());
            }
        }
    }

    private static int ch(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
