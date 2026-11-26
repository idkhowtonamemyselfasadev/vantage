package dev.vantage.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vantage.module.cosmetic.AtmosphereSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Atmosphere: the fog takes the dome's horizon colour, and Nether fog gets a little richer. Wraps the colour computation so the buffer gets it too. */
@Mixin(FogRenderer.class)
public class FogRendererAtmosphereMixin {
    @WrapOperation(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;computeFogColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)Lorg/joml/Vector4f;"))
    private Vector4f vantage$fogColour(FogRenderer self, Camera camera, float partial, ClientLevel level, int distance, float darken, Operation<Vector4f> original) {
        Vector4f c = original.call(self, camera, partial, level, distance, darken);
        return AtmosphereSky.fog(c, camera, level);
    }
}
