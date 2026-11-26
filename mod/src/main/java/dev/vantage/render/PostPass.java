package dev.vantage.render;

import java.util.OptionalInt;
import java.util.function.Consumer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * One full-screen shader pass: vanilla's screen-quad vertex shader, a fragment shader from assets/vantage/shaders/post/, any
 * number of samplers and one optional std140 uniform block written fresh each run. The pipeline compiles lazily on first use
 * and a failure throws out of {@link #run}, so the module owning the pass can switch itself off and log once. SamplerInfo
 * (OutSize, InSize) is always provided, like vanilla's post shaders expect.
 */
public final class PostPass {
    private final String name;
    private final Identifier shader;
    private final BlendFunction blend;
    private final String[] samplers;
    private final String block;
    private final int blockSize;
    private RenderPipeline pipeline;
    private boolean validated;
    private MappableRingBuffer sizes, data;

    /** @param block name of the uniform block in the shader, or null; blockSize its std140 size in bytes (multiple of 16). */
    public PostPass(String name, String fragment, BlendFunction blend, String block, int blockSize, String... samplers) {
        this.name = name;
        this.shader = Identifier.fromNamespaceAndPath("vantage", "post/" + fragment);
        this.blend = blend;
        this.samplers = samplers;
        this.block = block;
        this.blockSize = blockSize;
    }

    /** Draws into {@code target}; {@code in} is the texture behind the first sampler (sets InSize), the rest follow the sampler order. */
    public void run(GpuTextureView target, FilterMode filter, Consumer<Std140Builder> uniforms, GpuTextureView... in) {
        if (pipeline == null) {
            RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("vantage", "pipeline/" + name))
                    .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                    .withFragmentShader(shader)
                    .withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
            for (String s : samplers) b.withSampler(s);
            if (block != null) b.withUniform(block, UniformType.UNIFORM_BUFFER);
            if (blend != null) b.withBlend(blend);
            pipeline = b.build();
            validated = false;
        }
        if (!validated) {
            // 1.21.11 skips draws of a program that failed to compile without a word outside the IDE (GlRenderPass.VALIDATION =
            // SharedConstants.IS_RUNNING_IN_IDE): check here and throw, so the owning module switches itself off and logs once
            if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
                pipeline = null;
                throw new IllegalStateException("vantage " + name + ": shader " + shader + " did not compile");
            }
            validated = true;
        }
        if (sizes == null) {
            int usage = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;
            sizes = new MappableRingBuffer(() -> "vantage " + name + " sizes", usage, 16);
            if (block != null) data = new MappableRingBuffer(() -> "vantage " + name + " data", usage, blockSize);
        }
        var enc = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView v = enc.mapBuffer(sizes.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(v.data()).putVec2(target.getWidth(0), target.getHeight(0)).putVec2(in[0].getWidth(0), in[0].getHeight(0));
        }
        if (data != null) {
            try (GpuBuffer.MappedView v = enc.mapBuffer(data.currentBuffer(), false, true)) {
                uniforms.accept(Std140Builder.intoBuffer(v.data()));
            }
        }
        try (RenderPass p = enc.createRenderPass(() -> "vantage " + name, target, OptionalInt.empty())) {
            p.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(p);
            p.setUniform("SamplerInfo", sizes.currentBuffer());
            if (data != null) p.setUniform(block, data.currentBuffer());
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(filter);
            for (int i = 0; i < samplers.length && i < in.length; i++) p.bindTexture(samplers[i], in[i], sampler);
            p.draw(0, 3);
        }
        sizes.rotate();
        if (data != null) data.rotate();
    }
}
