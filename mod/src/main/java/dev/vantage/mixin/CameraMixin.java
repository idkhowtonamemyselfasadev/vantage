package dev.vantage.mixin;

import dev.vantage.module.render.FreeLook;
import dev.vantage.module.render.FreeLookMath;
import dev.vantage.module.render.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam and Free Look: after vanilla places the camera, move it to the free camera and mark it detached (so you see yourself). */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;

    @Shadow private float eyeHeight;

    @Shadow private float eyeHeightOld;

    /** Vanilla's wall check: how far back the camera may go (at most the asked distance) before a block is in the way. */
    @Shadow protected abstract float getMaxZoom(float distance);

    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void vantage$freecam(Level level, Entity entity, boolean thirdPerson, boolean mirror, float partial, CallbackInfo ci) {
        FreeLook look = FreeLook.active();
        if (look != null) {
            // orbit the player's eyes: where vanilla put the camera before it moved back, then back along the free angles
            double px = Mth.lerp((double) partial, entity.xo, entity.getX());
            double py = Mth.lerp((double) partial, entity.yo, entity.getY()) + Mth.lerp(partial, eyeHeightOld, eyeHeight);
            double pz = Mth.lerp((double) partial, entity.zo, entity.getZ());
            float scale = entity instanceof LivingEntity le ? le.getScale() : 1f;
            float want = (float) look.distance() * scale;
            setRotation(look.yaw(), look.pitch());
            setPosition(px, py, pz); // getMaxZoom starts from the camera position and looks along its rotation
            double d = look.seeThrough() ? want : getMaxZoom(want);
            double[] at = FreeLookMath.orbit(px, py, pz, look.yaw(), look.pitch(), d);
            detached = true;
            setPosition(at[0], at[1], at[2]);
            return;
        }
        Freecam f = Freecam.active();
        if (f == null) return;
        f.frame();
        detached = true;
        setRotation(f.yaw(), f.pitch());
        setPosition(f.x(), f.y(), f.z());
    }
}
