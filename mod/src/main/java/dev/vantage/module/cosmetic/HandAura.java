package dev.vantage.module.cosmetic;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.FilterMode;
import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Perf;
import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import dev.vantage.render.PostPass;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.ColorSetting;
import dev.vantage.setting.ModeSetting;
import dev.vantage.setting.NumberSetting;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A soft glow around your first-person hands and held item. Right after vanilla draws the hands (the depth buffer was cleared
 * just before, so the hand pixels are the only ones with depth), a mask of them is taken, blurred four times at half size, and added
 * back as a halo with an edge rim and a slow sheen that drifts across the hand; the whole thing breathes. Six small passes,
 * shaders in assets/vantage/shaders/post/aura_*.fsh. Steps aside when a shader pack is running, because the depth buffer is not
 * ours to read then.
 */
public class HandAura extends Module {
    public final ModeSetting colourMode = add(new ModeSetting("Colour", "Theme uses the menu accent, Manual your own", "Theme", "Theme", "Manual"));
    public final ColorSetting colour = add(new ColorSetting("Manual colour", "The glow colour in Manual mode", 0xFF7B6BFF)).visibleIf(() -> colourMode.is("Manual"));
    public final NumberSetting aura = add(new NumberSetting("Aura", "How strong the halo is", 65, 0, 100, 1));
    public final NumberSetting reach = add(new NumberSetting("Reach", "How far the halo spreads", 8, 1, 20, 1));
    public final NumberSetting rim = add(new NumberSetting("Rim", "Bright edge right along the hand", 40, 0, 100, 1));
    public final NumberSetting sheen = add(new NumberSetting("Sheen", "A light band that drifts across the hand", 25, 0, 100, 1));
    public final BoolSetting breathe = add(new BoolSetting("Breathe", "The glow swells and settles over about three seconds", true));
    /** Frames drawn and passes that threw, for the rig. */
    public static int frames, failures;
    private static TextureTarget mask, blurA, blurB;
    private static final PostPass MASK = new PostPass("aura_mask", "aura_mask", null, null, 0, "InSampler");
    private static final PostPass BLUR = new PostPass("aura_blur", "aura_blur", null, "BlurData", 16, "InSampler");
    private static final PostPass GLOW = new PostPass("aura_glow", "aura_glow", BlendFunction.ADDITIVE, "AuraData", 48, "InSampler", "GlowSampler");
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
    private static final long START = System.nanoTime();

    public HandAura() {
        super("Hand Aura", "A soft coloured glow around your hands and held item", Category.COSMETIC);
    }

    @Override
    protected void onDisable() { free(); }

    private static void free() {
        if (mask != null) { mask.destroyBuffers(); mask = null; }
        if (blurA != null) { blurA.destroyBuffers(); blurA = null; }
        if (blurB != null) { blurB.destroyBuffers(); blurB = null; }
    }

    /** True while an Iris shader pack is running: the depth buffer is theirs then. */
    private static boolean shaderPack() {
        if (!IRIS) return false;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isShaderPackInUse").invoke(inst);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Called right after vanilla has drawn the first-person hands. */
    public static void frame() {
        HandAura m = Modules.get(HandAura.class);
        if (m == null || !m.on()) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui || !mc.options.getCameraType().isFirstPerson() || shaderPack()) return;
        long t0 = System.nanoTime();
        try {
            RenderTarget main = mc.getMainRenderTarget();
            int w = main.width, h = main.height, hw = Math.max(1, w / 2), hh = Math.max(1, h / 2);
            if (mask == null || mask.width != w || mask.height != h) {
                free();
                mask = new TextureTarget("vantage aura mask", w, h, false);
                blurA = new TextureTarget("vantage aura blur a", hw, hh, false);
                blurB = new TextureTarget("vantage aura blur b", hw, hh, false);
            }
            MASK.run(mask.getColorTextureView(), FilterMode.NEAREST, null, main.getDepthTextureView());
            // two separable blurs, the second three times as wide, so the halo is dense at the hand and still reaches out
            float step = m.reach.f() * 0.6f;
            BLUR.run(blurA.getColorTextureView(), FilterMode.LINEAR, u -> u.putVec2(1, 0).putFloat(step).putFloat(0), mask.getColorTextureView());
            BLUR.run(blurB.getColorTextureView(), FilterMode.LINEAR, u -> u.putVec2(0, 1).putFloat(step).putFloat(0), blurA.getColorTextureView());
            BLUR.run(blurA.getColorTextureView(), FilterMode.LINEAR, u -> u.putVec2(1, 0).putFloat(step * 3).putFloat(0), blurB.getColorTextureView());
            BLUR.run(blurB.getColorTextureView(), FilterMode.LINEAR, u -> u.putVec2(0, 1).putFloat(step * 3).putFloat(0), blurA.getColorTextureView());
            int c = m.colourMode.is("Theme") ? Ui.ACCENT : m.colour.argb();
            float time = (System.nanoTime() - START) / 1e9f;
            GLOW.run(main.getColorTextureView(), FilterMode.LINEAR, u -> u
                    .putVec4((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, 1f)
                    .putFloat(m.aura.f() / 100f * 2.4f)
                    .putFloat(m.rim.f() / 100f)
                    .putFloat(m.sheen.f() / 100f)
                    .putFloat(time)
                    .putFloat(m.breathe.on() ? 1f : 0f)
                    .putFloat(Boolean.getBoolean("vantage.auraDebug") ? 1f : 0f).putFloat(0).putFloat(0), mask.getColorTextureView(), blurB.getColorTextureView());
            frames++;
            Perf.add("Hand aura passes", System.nanoTime() - t0);
        } catch (Throwable t) {
            failures++;
            Vantage.LOG.warn("Hand aura pass failed, switching it off", t);
            m.setEnabled(false);
        }
    }
}
