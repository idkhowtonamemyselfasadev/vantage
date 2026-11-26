package dev.vantage.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.vantage.mixin.RenderTypeInvoker;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** Our own line and quad render types: one set that draws through walls, one that respects depth. */
public final class Pipelines {
    private static RenderType linesXray, linesDepth, quadsXray, quadsDepth;

    private static RenderType make(String name, RenderPipeline.Snippet snippet, boolean throughWalls) {
        RenderPipeline.Builder b = RenderPipeline.builder(snippet)
                .withLocation(Identifier.fromNamespaceAndPath("vantage", "pipeline/" + name))
                .withDepthWrite(false)
                .withCull(false);
        if (throughWalls) b.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
        return RenderTypeInvoker.vantage$create("vantage_" + name, RenderSetup.builder(b.build()).createRenderSetup());
    }

    public static RenderType lines(boolean throughWalls) {
        if (linesXray == null) {
            linesXray = make("lines_xray", RenderPipelines.LINES_SNIPPET, true);
            linesDepth = make("lines_depth", RenderPipelines.LINES_SNIPPET, false);
        }
        return throughWalls ? linesXray : linesDepth;
    }

    public static RenderType quads(boolean throughWalls) {
        if (quadsXray == null) {
            quadsXray = make("quads_xray", RenderPipelines.DEBUG_FILLED_SNIPPET, true);
            quadsDepth = make("quads_depth", RenderPipelines.DEBUG_FILLED_SNIPPET, false);
        }
        return throughWalls ? quadsXray : quadsDepth;
    }

    private Pipelines() {}
}
