package dev.vantage.module.cosmetic;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.mixin.OverlayTextureAccessor;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.NumberSetting;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The flash a mob shows when it takes damage is a red tint baked into a 16x16 overlay texture. This rewrites that texture with
 * your colour and strength while on, and puts vanilla's red back when off. Purely what you see; the hit itself is unchanged.
 */
public class HitColor extends Module {
    /** Vanilla's rows 0-7: red at 70% alpha. */
    private static final int VANILLA = 0xB2FF0000;
    public final ColorSetting color = add(new ColorSetting("Colour", "Tint of a mob that was just hit", 0xFF7B6BFF));
    public final NumberSetting strength = add(new NumberSetting("Strength", "How strong the tint is", 0.7, 0.1, 1, 0.05));
    public final BoolSetting armour = add(new BoolSetting("Armour too", "Armour, trims and the cape flash with the body instead of keeping their own colour (vanilla leaves them untinted)", true));
    private int applied;
    /** Set by the armour and cape layer mixins while a wearer's pieces are submitted: that wearer's hurt overlay, or -1 for none. */
    public static int wearerOverlay = -1;
    private static HitColor self;

    public HitColor() {
        super("Hit Colour", "Mobs flash your colour instead of red when hit", Category.COSMETIC);
        self = this;
        color.onChange(() -> { if (on()) apply(); });
        strength.onChange(() -> { if (on()) apply(); });
    }

    /** Whether armour should flash: the module on with Armour too ticked. */
    public static boolean armourFlash() {
        return self != null && self.on() && self.armour.on();
    }

    /** The overlay an armour piece draws with: the wearer's while one is remembered, else vanilla's none. */
    public static int overlayFor(int none) {
        return wearerOverlay == -1 ? none : wearerOverlay;
    }

    public static int current() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        DynamicTexture t = ((OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).vantage$texture();
        NativeImage img = t.getPixels();
        return img == null ? 0 : img.getPixel(0, 0);
    }

    @Override
    protected void onEnable() { apply(); }

    @Override
    protected void onDisable() { write(VANILLA); }

    private boolean calmApplied;

    /** Calm (beta) softens the flash to 55% of its strength: re-applied when Calm is switched. */
    @Override
    public void onTick() {
        if (calmApplied != Cos.calm()) apply();
    }

    private void apply() {
        calmApplied = Cos.calm();
        int a = Math.round(strength.f() * 255 * Cos.calmAlpha());
        write((a << 24) | (color.argb() & 0xFFFFFF));
    }

    private void write(int argb) {
        if (mc.gameRenderer == null) return;
        DynamicTexture t = ((OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).vantage$texture();
        NativeImage img = t.getPixels();
        if (img == null) return;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) img.setPixel(x, y, argb);
        t.upload();
        applied = argb;
    }
}
