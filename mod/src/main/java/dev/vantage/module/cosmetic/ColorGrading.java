package dev.vantage.module.cosmetic;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.FilterMode;
import dev.vantage.core.Category;
import dev.vantage.core.Module;
import dev.vantage.core.Modules;
import dev.vantage.core.Perf;
import dev.vantage.core.Vantage;
import dev.vantage.render.PostPass;
import dev.vantage.setting.NumberSetting;

/**
 * A colour grade over the finished frame, before the HUD: exposure, warmth, vibrance that lifts dull colours more than
 * saturated ones, contrast and a soft vignette. Two passes: the frame is copied to a side texture, then graded back onto the
 * main target with assets/vantage/shaders/post/color_grade.fsh. Runs after Motion Blur so the trail is graded too.
 */
public class ColorGrading extends Module {
    public final NumberSetting vibrance = add(new NumberSetting("Vibrance", "Lifts dull colours, leaves saturated ones alone", 35, 0, 100, 1));
    public final NumberSetting contrast = add(new NumberSetting("Contrast", "Darks darker, lights lighter", 8, -20, 40, 1));
    public final NumberSetting brightness = add(new NumberSetting("Brightness", "Overall exposure", 3, -20, 30, 1));
    public final NumberSetting warmth = add(new NumberSetting("Warmth", "Negative is cooler and bluer, positive warmer and more orange", 15, -100, 100, 1));
    public final NumberSetting vignette = add(new NumberSetting("Vignette", "Darkens the corners", 30, 0, 100, 1));
    /** Frames graded and passes that threw, for the rig. */
    public static int frames, failures;
    private static TextureTarget copy;
    private static final PostPass COPY = new PostPass("grade_copy", "copy", null, null, 0, "InSampler");
    private static final PostPass GRADE = new PostPass("color_grade", "color_grade", null, "GradeData", 32, "InSampler");

    public ColorGrading() {
        super("Colour Grading", "Vibrance, contrast, warmth and a vignette over the whole picture", Category.COSMETIC);
    }

    @Override
    protected void onDisable() {
        if (copy != null) { copy.destroyBuffers(); copy = null; }
    }

    /** Called from the HUD hook each frame after Motion Blur, before anything 2D is drawn. */
    public static void frame() {
        ColorGrading m = Modules.get(ColorGrading.class);
        if (m == null || !m.on()) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        long t0 = System.nanoTime();
        try {
            RenderTarget main = mc.getMainRenderTarget();
            int w = main.width, h = main.height;
            if (copy == null || copy.width != w || copy.height != h) {
                if (copy != null) copy.destroyBuffers();
                copy = new TextureTarget("vantage grade copy", w, h, false);
            }
            COPY.run(copy.getColorTextureView(), FilterMode.NEAREST, null, main.getColorTextureView());
            GRADE.run(main.getColorTextureView(), FilterMode.NEAREST, u -> u
                    .putFloat(m.vibrance.f() / 100f)
                    .putFloat(1f + m.contrast.f() / 100f)
                    .putFloat(1f + m.brightness.f() / 100f)
                    .putFloat(m.warmth.f() / 100f)
                    .putFloat(m.vignette.f() / 100f)
                    .putFloat(0).putFloat(0).putFloat(0), copy.getColorTextureView());
            frames++;
            Perf.add("Colour grading pass", System.nanoTime() - t0);
        } catch (Throwable t) {
            failures++;
            Vantage.LOG.warn("Colour grading pass failed, switching it off", t);
            m.setEnabled(false);
        }
    }
}
