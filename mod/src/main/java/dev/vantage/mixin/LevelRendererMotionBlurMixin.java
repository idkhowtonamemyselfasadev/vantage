package dev.vantage.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.vantage.module.cosmetic.MotionBlur;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Motion Blur: HEAD takes the camera matrices the world is drawn with (5th arg = rotation-only view, 6th = projection); TAIL blurs
 *  the finished world while its depth is intact and before the hands, overlays and HUD are drawn. HEAD/TAIL only, full descriptor. */
@Mixin(LevelRenderer.class)
public class LevelRendererMotionBlurMixin {
    @Inject(method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", at = @At("HEAD"))
    private void vantage$blurCapture(GraphicsResourceAllocator alloc, DeltaTracker delta, boolean blockOutline, Camera camera, Matrix4f view, Matrix4f projection, Matrix4f cullProjection, GpuBufferSlice fog, Vector4f fogColour, boolean sky, CallbackInfo ci) {
        MotionBlur.capture(camera, view, projection);
    }

    @Inject(method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", at = @At("TAIL"))
    private void vantage$blurWorld(GraphicsResourceAllocator alloc, DeltaTracker delta, boolean blockOutline, Camera camera, Matrix4f view, Matrix4f projection, Matrix4f cullProjection, GpuBufferSlice fog, Vector4f fogColour, boolean sky, CallbackInfo ci) {
        MotionBlur.afterWorld();
    }
}
