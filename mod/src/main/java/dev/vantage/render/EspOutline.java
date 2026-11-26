package dev.vantage.render;

import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vantage.core.Perf;
import dev.vantage.core.Vantage;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The Shader mode of Entity ESP. Vanilla already draws every entity with an outline colour as a flat silhouette into its
 * entity-outline target; normally a sobel + blur chain turns that into the thin glow line. In Shader mode that chain is
 * skipped and this pass runs instead of the final blit: one full-screen triangle over the main target with our fragment
 * shader (assets/vantage/shaders/post/esp_outline.fsh), which widens every silhouette by Width texels with a glow falloff
 * and fills the inside translucently. Pipeline and shader compile lazily on first use; if that fails the mode steps aside
 * and the vanilla glow line shows instead, so nothing is ever missing.
 */
public final class EspOutline {
    /** Frames drawn and passes that threw, for the rig and the module's info line. */
    public static int frames, failures;
    /** Frames in which the vanilla sobel + blur chain was skipped for us (the silhouettes stayed raw), for the rig. */
    public static int chainsSkipped;
    private static boolean broken;
    private static RenderPipeline pipeline;
    private static MappableRingBuffer sizes, data;
    /** Both blocks are four 4-byte values in std140. */
    private static final int UBO = 16;

    /**
     * What the pass needs from Entity ESP, handed in by the module ({@link #setSource}) so this layer does not know it. The values are
     * read live each frame, exactly as the module's settings stand.
     */
    public interface Source {
        /** The module is on in a Shader mode. */
        boolean shaderActive();

        /** 0 = Outline, 1 = Fill, 2 = Both. */
        int shapeIndex();

        /** Outline width in 1080p pixels. */
        int outlineWidth();

        /** Fill opacity of the shader fill. */
        float fillOpacity();

        /** How solid the outline is. */
        float glowStrength();
    }

    private static Source source;

    public static void setSource(Source s) {
        source = s;
    }

    private EspOutline() {}

    /** True while Entity ESP is on in a Shader mode and the pass still works. */
    public static boolean active() {
        if (broken) return false;
        Source m = source;
        return m != null && m.shaderActive();
    }

    /** Draws the silhouettes in {@code outline} as thick glowing outlines over {@code main}. False = fall back to vanilla. */
    public static boolean draw(RenderTarget outline, RenderTarget main) {
        Source m = source;
        if (m == null || broken) return false;
        long t0 = System.nanoTime();
        try {
            if (pipeline == null) pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("vantage", "pipeline/esp_outline"))
                    .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("vantage", "post/esp_outline"))
                    .withSampler("InSampler")
                    .withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
                    .withUniform("OutlineData", UniformType.UNIFORM_BUFFER)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .build();
            if (sizes == null) {
                int usage = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;
                sizes = new MappableRingBuffer(() -> "vantage esp outline sizes", usage, UBO);
                data = new MappableRingBuffer(() -> "vantage esp outline data", usage, UBO);
            }
            CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
            GpuTextureView tex = outline.getColorTextureView();
            try (GpuBuffer.MappedView v = enc.mapBuffer(sizes.currentBuffer(), false, true)) {
                Std140Builder.intoBuffer(v.data()).putVec2(main.width, main.height).putVec2(tex.getWidth(0), tex.getHeight(0));
            }
            int shape = m.shapeIndex();
            // the width setting is in 1080p pixels, so the line stays the same size on a 1440p or 4K screen
            int width = Math.max(1, Math.round(m.outlineWidth() * main.height / 1080f));
            try (GpuBuffer.MappedView v = enc.mapBuffer(data.currentBuffer(), false, true)) {
                Std140Builder.intoBuffer(v.data()).putInt(width).putFloat(m.fillOpacity()).putInt(shape).putFloat(m.glowStrength());
            }
            try (RenderPass p = enc.createRenderPass(() -> "vantage esp outline", main.getColorTextureView(), OptionalInt.empty())) {
                p.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(p);
                p.setUniform("SamplerInfo", sizes.currentBuffer());
                p.setUniform("OutlineData", data.currentBuffer());
                p.bindTexture("InSampler", tex, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                p.draw(0, 3);
            }
            sizes.rotate();
            data.rotate();
            frames++;
            Perf.add("Shader ESP pass", System.nanoTime() - t0);
            return true;
        } catch (Throwable t) {
            failures++;
            broken = true;
            Vantage.LOG.warn("Shader ESP pass failed, showing the vanilla glow line instead", t);
            return false;
        }
    }
}
