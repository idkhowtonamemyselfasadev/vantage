package dev.vantage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vantage.module.cosmetic.Atmosphere;
import dev.vantage.module.cosmetic.AtmosphereSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Atmosphere's hooks into the vanilla sky: the dome replaces the disc and the sunrise band, our stars replace vanilla's, the
 * moon can be bigger, and the halos, milky way, aurora and meteors are drawn once the sun and moon are placed. The sky render
 * state is kept on the way through so the extras know the angles and brightness.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererAtmosphereMixin {
    @Unique private SkyRenderState vantage$state;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void vantage$state(ClientLevel level, float partial, Camera camera, SkyRenderState state, CallbackInfo ci) {
        vantage$state = state;
        Atmosphere m = Atmosphere.active();
        if (m != null && m.keepInRain.on()) state.rainBrightness = 1f;
    }

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void vantage$dome(int colour, CallbackInfo ci) {
        Atmosphere m = Atmosphere.active();
        if (m == null || !m.gradient.on() || vantage$state == null) return;
        AtmosphereSky.dome(m, vantage$state);
        if (!AtmosphereSky.failed()) ci.cancel();
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void vantage$noBand(PoseStack pose, float sunAngle, int colour, CallbackInfo ci) {
        Atmosphere m = Atmosphere.active();
        if (m != null && m.gradient.on()) ci.cancel();
    }

    @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
    private void vantage$noVanillaStars(float brightness, PoseStack pose, CallbackInfo ci) {
        Atmosphere m = Atmosphere.active();
        if (m != null && m.stars.on()) ci.cancel();
    }

    @Inject(method = "renderMoon", at = @At("HEAD"))
    private void vantage$bigMoonIn(MoonPhase phase, float brightness, PoseStack pose, CallbackInfo ci) {
        pose.pushPose();
        Atmosphere m = Atmosphere.active();
        if (m != null && m.bigMoon.on()) pose.scale(1.4f, 1f, 1.4f);
    }

    @Inject(method = "renderMoon", at = @At("RETURN"))
    private void vantage$bigMoonOut(MoonPhase phase, float brightness, PoseStack pose, CallbackInfo ci) {
        pose.popPose();
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("TAIL"))
    private void vantage$extras(PoseStack pose, float sunAngle, float moonAngle, float starAngle, MoonPhase phase, float rain, float starBrightness, CallbackInfo ci) {
        Atmosphere m = Atmosphere.active();
        if (m == null || vantage$state == null) return;
        AtmosphereSky.extras(m, vantage$state, pose);
    }
}
