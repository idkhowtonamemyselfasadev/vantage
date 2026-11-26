package dev.vantage.mixin;

import java.util.Set;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.vantage.module.cosmetic.BlockOutline;
import dev.vantage.render.EspOutline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the vanilla black block outline while Block Outline draws its own, and in Entity ESP's Shader mode replaces the
 * vanilla glow post chain + blit with the Vantage silhouette outline pass.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Shadow private @Nullable RenderTarget entityOutlineTarget;
    private static final Identifier VANTAGE$OUTLINE_CHAIN = Identifier.withDefaultNamespace("entity_outline");

    @Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void vantage$outline(MultiBufferSource.BufferSource src, PoseStack ps, boolean translucent, LevelRenderState state, CallbackInfo ci) {
        if (BlockOutline.active()) ci.cancel();
    }

    /** Shader mode: keep the silhouettes raw, the sobel + blur chain would turn them into the thin line. */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderManager;getPostChain(Lnet/minecraft/resources/Identifier;Ljava/util/Set;)Lnet/minecraft/client/renderer/PostChain;"))
    private PostChain vantage$rawSilhouettes(ShaderManager shaders, Identifier id, Set<Identifier> targets, Operation<PostChain> original) {
        if (EspOutline.active() && VANTAGE$OUTLINE_CHAIN.equals(id)) { EspOutline.chainsSkipped++; return null; }
        return original.call(shaders, id, targets);
    }

    /** Shader mode: our pass composes the outline target onto the frame instead of the plain blit. */
    @Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
    private void vantage$shaderEsp(CallbackInfo ci) {
        if (!EspOutline.active() || entityOutlineTarget == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer.isPanoramicMode() || mc.player == null) return;
        if (EspOutline.draw(entityOutlineTarget, mc.getMainRenderTarget())) ci.cancel();
    }
}
