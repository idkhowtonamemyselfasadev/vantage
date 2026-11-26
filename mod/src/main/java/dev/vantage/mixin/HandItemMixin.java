package dev.vantage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vantage.module.render.HandView;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hand View: scales the held item in first person. Pushed and popped around the draw, so nothing else in the hand pass moves. */
@Mixin(ItemInHandRenderer.class)
public class HandItemMixin {
    @Inject(method = "renderItem", at = @At("HEAD"))
    private void vantage$grow(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose, SubmitNodeCollector out, int light, CallbackInfo ci) {
        pose.pushPose();
        if (context.firstPerson()) {
            float s = HandView.itemSize();
            pose.scale(s, s, s);
        }
    }

    @Inject(method = "renderItem", at = @At("RETURN"))
    private void vantage$restore(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose, SubmitNodeCollector out, int light, CallbackInfo ci) {
        pose.popPose();
    }
}
