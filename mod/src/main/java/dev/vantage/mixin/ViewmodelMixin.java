package dev.vantage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.vantage.module.cosmetic.Viewmodel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 3D Hand: each first-person arm gets its own push, move, scale and tilt around the whole draw, popped afterwards. */
@Mixin(ItemInHandRenderer.class)
public class ViewmodelMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void vantage$pose(AbstractClientPlayer player, float partial, float pitch, InteractionHand hand, float swing, ItemStack stack, float equip,
            PoseStack pose, SubmitNodeCollector out, int light, CallbackInfo ci) {
        Viewmodel v = Viewmodel.active();
        pose.pushPose();
        if (v == null) return;
        boolean main = hand == InteractionHand.MAIN_HAND;
        if (!main && !v.offHand.on()) { pose.popPose(); ci.cancel(); return; }
        boolean right = (player.getMainArm() == HumanoidArm.RIGHT) == main;
        float side = right ? 1 : -1;
        float sx = v.mirror.on() ? side : 1;
        pose.translate(v.x.f() * sx, v.y.f(), -v.z.f());
        float s = v.scale.f();
        pose.scale(s, s, s);
        if (v.tilt.f() != 0) pose.mulPose(Axis.ZP.rotationDegrees(-v.tilt.f() * sx));
        Viewmodel.applied++;
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"))
    private void vantage$unpose(AbstractClientPlayer player, float partial, float pitch, InteractionHand hand, float swing, ItemStack stack, float equip,
            PoseStack pose, SubmitNodeCollector out, int light, CallbackInfo ci) {
        pose.popPose();
    }
}
