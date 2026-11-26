package dev.vantage.mixin;

import dev.vantage.module.cosmetic.Pet;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pet: after the frame has collected the render states of the level's entities, the pet's state (never a level entity) is added to the same list. */
@Mixin(LevelRenderer.class)
public class LevelRendererPetMixin {
    @Inject(method = "extractVisibleEntities", at = @At("TAIL"))
    private void vantage$pet(Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState state, CallbackInfo ci) {
        Pet.extract(state, delta.getGameTimeDeltaPartialTick(true));
    }
}
