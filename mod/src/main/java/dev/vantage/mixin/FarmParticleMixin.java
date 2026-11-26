package dev.vantage.mixin;

import dev.vantage.module.donut.FarmOptimizer;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Farm optimizer: explosion particles are never made. Knockback and sounds come from elsewhere and stay. */
@Mixin(ParticleEngine.class)
public class FarmParticleMixin {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void vantage$explosion(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        if (FarmOptimizer.hidesParticle(options)) { FarmOptimizer.particlesSkipped++; cir.setReturnValue(null); }
    }
}
